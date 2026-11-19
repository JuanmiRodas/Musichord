package com.music.bitchord.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.playback.smart.AudioCheck
import kotlinx.coroutines.flow.MutableStateFlow

/** BitChord Audio: the linked songs' listening check — where it is, and when it may run. */
object LinkCheck {
    internal val shown = MutableStateFlow(false)
    fun open() { shown.value = true }
}

@Composable
fun LinkCheckHost() {
    val shown by LinkCheck.shown.collectAsStateWithLifecycle()
    if (!shown) return
    Dialog(onDismissRequest = { LinkCheck.shown.value = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LinkCheckContent(onClose = { LinkCheck.shown.value = false })
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LinkCheckContent(onClose: () -> Unit) {
    val context = LocalContext.current
    val p by AudioCheck.progress.collectAsStateWithLifecycle()
    val sc by AudioCheck.schedule.collectAsStateWithLifecycle()
    var note by remember { mutableStateOf<String?>(null) }
    val noWifiName = stringResource(R.string.link_check_no_wifi_name)

    fun addCurrentWifi() {
        val name = AudioCheck.currentWifi(context)
        if (name == null) note = noWifiName
        else { note = null; AudioCheck.setSchedule(sc.copy(wifiNames = sc.wifiNames + name)) }
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { addCurrentWifi() }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.audio_check_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
        }
        Text(stringResource(R.string.audio_check_idle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

        // Status
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                val w = p.waiting
                val status = when {
                    p.paused -> stringResource(R.string.link_check_paused)
                    w is AudioCheck.Wait.Wifi -> stringResource(R.string.link_check_wait_wifi)
                    w is AudioCheck.Wait.Network -> stringResource(R.string.link_check_wait_network, w.names.joinToString(", "))
                    w is AudioCheck.Wait.Hours -> stringResource(R.string.link_check_wait_hours, "%02d:00".format(w.from), "%02d:00".format(w.to))
                    w is AudioCheck.Wait.Charging -> stringResource(R.string.link_check_wait_charging)
                    p.running && p.current != null -> stringResource(R.string.audio_check_running, p.done, p.total, p.current!!)
                    p.running -> stringResource(R.string.link_check_preparing)
                    p.lastRun > 0 -> stringResource(R.string.link_check_done_at, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(p.lastRun)))
                    else -> stringResource(R.string.link_check_not_yet)
                }
                Text(status, style = MaterialTheme.typography.titleMedium)
                if (p.total > 0 && p.running) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                    Text("${p.done} / ${p.total} · ${p.done * 100 / p.total} %", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.audio_check_counts, p.same, p.different, p.unsure), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { AudioCheck.runNow() }, enabled = !(p.running && p.waiting == null)) { Text(stringResource(R.string.link_check_run_now)) }
                    OutlinedButton(onClick = { AudioCheck.setPaused(!p.paused) }) {
                        Text(stringResource(if (p.paused) R.string.link_check_resume else R.string.link_check_pause))
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        // Schedule
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.link_check_schedule), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (sc.on) R.string.link_check_schedule_on else R.string.link_check_schedule_off),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = sc.on, onCheckedChange = { AudioCheck.setSchedule(sc.copy(on = it)) })
        }
        if (sc.on) {
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.link_check_hours), style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                HourStepper(stringResource(R.string.link_check_from), sc.fromHour) { AudioCheck.setSchedule(sc.copy(fromHour = it)) }
                Spacer(Modifier.width(16.dp))
                HourStepper(stringResource(R.string.link_check_to), sc.toHour) { AudioCheck.setSchedule(sc.copy(toHour = it)) }
            }
            if (sc.fromHour == sc.toHour) Text(stringResource(R.string.link_check_all_day), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.link_check_networks), style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(if (sc.wifiNames.isEmpty()) R.string.link_check_any_wifi else R.string.link_check_only_these),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sc.wifiNames.forEach { name ->
                InputChip(
                    selected = false,
                    onClick = { AudioCheck.setSchedule(sc.copy(wifiNames = sc.wifiNames - name)) },
                    label = { Text(name) },
                    leadingIcon = { Icon(Icons.Rounded.Wifi, contentDescription = null) },
                    trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = null) },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            FilledTonalButton(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    if (granted) addCurrentWifi()
                    else askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                },
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Icon(Icons.Rounded.Wifi, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.link_check_add_wifi))
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.link_check_charging), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = sc.chargingOnly, onCheckedChange = { AudioCheck.setSchedule(sc.copy(chargingOnly = it)) })
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.link_check_background_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // What has been heard, and what is left.
        Spacer(Modifier.height(20.dp))
        val history by AudioCheck.history.collectAsStateWithLifecycle()
        val pending by AudioCheck.pending.collectAsStateWithLifecycle()
        var tab by remember { mutableStateOf(0) }
        androidx.compose.material3.SecondaryTabRow(selectedTabIndex = tab) {
            androidx.compose.material3.Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.link_check_tab_done, history.size)) })
            androidx.compose.material3.Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.link_check_tab_left, pending.size)) })
        }
        Spacer(Modifier.height(8.dp))
        val time = remember { java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()) }
        if (tab == 0) {
            if (history.isEmpty()) Text(stringResource(R.string.link_check_nothing_yet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            history.take(300).forEach { h ->
                val (label, color) = when (h.verdict) {
                    AudioCheck.Verdict.SAME -> stringResource(R.string.link_check_same) to MaterialTheme.colorScheme.primary
                    AudioCheck.Verdict.DIFFERENT -> stringResource(R.string.link_check_different) to MaterialTheme.colorScheme.error
                    AudioCheck.Verdict.UNKNOWN -> stringResource(R.string.link_check_unsure) to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(h.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(if (h.copy == "download") stringResource(R.string.link_check_download) else h.copy, time.format(java.util.Date(h.at))).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
                        h.ber?.let { Text(stringResource(R.string.link_check_match, ((1 - it) * 100).toInt()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        } else {
            if (pending.isEmpty()) Text(stringResource(R.string.link_check_nothing_left), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            pending.take(300).forEachIndexed { i, t ->
                Text("${i + 1}. $t", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 3.dp))
            }
            if (pending.size > 300) Text(stringResource(R.string.link_check_more, pending.size - 300), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HourStepper(label: String, hour: Int, onChange: (Int) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange((hour + 23) % 24) }) { Icon(Icons.Rounded.Remove, contentDescription = null) }
            Text("%02d:00".format(hour), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onChange((hour + 1) % 24) }) { Icon(Icons.Rounded.Add, contentDescription = null) }
        }
    }
}

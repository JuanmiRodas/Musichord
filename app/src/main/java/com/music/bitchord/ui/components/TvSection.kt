package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.playback.tv.TvLink

/**
 * BitChord Audio: the LG TV app in the Cast popup — TVs found on the Wi-Fi,
 * pairing with the code on the TV, and while the music is on one, the video
 * switch and the way back.
 */
@Composable
fun TvSection(onConnected: () -> Unit) {
    val tv by TvLink.state.collectAsStateWithLifecycle()
    var pairing by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf<Int?>(null) }
    var addingAddress by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val stop = TvLink.discover()
        onDispose(stop)
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.tv_section),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.W600),
            color = Color.White.copy(alpha = 0.55f),
        )
        tv.saved.forEach { saved ->
            val connected = tv.connected?.id == saved.id
            TvRow(
                name = saved.name,
                detail = when {
                    connected -> tv.now?.let { "${it.title} · ${it.artist}" } ?: stringResource(R.string.tv_playing_on)
                    else -> null
                },
                connecting = tv.connectingId == saved.id,
                connected = connected,
                onClick = {
                    if (!connected) {
                        TvLink.connect(saved)
                        onConnected()
                    }
                },
                onForget = { TvLink.forget(saved) },
            )
        }
        tv.found.forEach { found ->
            TvRow(
                name = found.name,
                detail = stringResource(R.string.tv_new),
                connecting = false,
                connected = false,
                onClick = { pairing = found.id; code = ""; failed = null },
                onForget = null,
            )
            if (pairing == found.id) {
                EntryRow(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    hint = stringResource(R.string.tv_pair_hint),
                    action = stringResource(R.string.tv_pair),
                    number = true,
                    error = failed,
                    onAction = {
                        TvLink.pair(found, code) { ok ->
                            if (ok) {
                                pairing = null
                                TvLink.state.value.saved.firstOrNull { it.id == found.id }?.let(TvLink::connect)
                            } else {
                                failed = R.string.tv_pair_failed
                            }
                        }
                    },
                )
            }
        }
        if (tv.saved.isEmpty() && tv.found.isEmpty()) {
            Text(
                text = stringResource(R.string.tv_searching),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.sp),
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        tv.error?.let { error ->
            Text(
                text = stringResource(if (error == "unpaired") R.string.tv_unpaired else R.string.tv_unreachable),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                color = Color(0xFFFF6B63),
            )
        }
        if (tv.connected != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { TvLink.showVideo(!tv.videoShown) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.tv_show_video),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                    color = Color.White,
                )
                Switch(checked = tv.videoShown, onCheckedChange = { TvLink.showVideo(it) })
            }
            Text(
                text = stringResource(R.string.tv_stop),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { TvLink.disconnect(resumeHere = true) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.W600),
                color = Color(0xFFFF6B63),
            )
        } else if (addingAddress) {
            EntryRow(
                value = address,
                onValueChange = { address = it.filter { c -> c.isDigit() || c == '.' || c == ':' }.take(21) },
                hint = stringResource(R.string.tv_address_hint),
                action = stringResource(R.string.tv_add),
                number = false,
                error = failed,
                onAction = {
                    TvLink.addByAddress(address) { found ->
                        if (found == null) failed = R.string.tv_not_found
                        else { addingAddress = false; failed = null; pairing = found.id; code = "" }
                    }
                },
            )
        } else {
            Text(
                text = stringResource(R.string.tv_add_address),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { addingAddress = true; failed = null }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun TvRow(
    name: String,
    detail: String?,
    connecting: Boolean,
    connected: Boolean,
    onClick: () -> Unit,
    onForget: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Tv, contentDescription = null, tint = Color.White.copy(alpha = if (connected) 1f else 0.8f), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = if (connected) FontWeight.W600 else FontWeight.Normal),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(top = 1.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            connecting -> CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White.copy(alpha = 0.8f), strokeWidth = 1.75.dp)
            connected -> Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            onForget != null -> Box(
                Modifier.size(28.dp).clickable(onClick = onForget),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.tv_forget), tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun EntryRow(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    action: String,
    number: Boolean,
    error: Int?,
    onAction: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (value.isEmpty()) {
                    Text(hint, style = TextStyle(fontSize = 13.sp), color = Color.White.copy(alpha = 0.4f), maxLines = 2)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, color = Color.White, letterSpacing = if (number) 3.sp else 0.sp),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.NumberPassword else KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = action,
                modifier = Modifier.clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.W600),
                color = Color(0xFF64A8FF),
            )
        }
        error?.let {
            Text(stringResource(it), modifier = Modifier.padding(top = 4.dp), style = TextStyle(fontSize = 12.sp), color = Color(0xFFFF6B63))
        }
    }
}

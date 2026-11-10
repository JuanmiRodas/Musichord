package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.MixGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** BitChord Audio: whether the mix generator is open. */
object MixGeneratorUi {
    val open = MutableStateFlow(false)
}

/**
 * BitChord Audio: make a mix (LastWave's generator, on our own data).
 * [onPlay] plays the songs; [onSave] saves them as a playlist named as given.
 */
@Composable
fun MixGeneratorDialog(
    onPlay: (List<com.music.bitchord.data.model.Song>, shuffle: Boolean) -> Unit,
    onSave: (String, List<com.music.bitchord.data.model.Song>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val modes = listOf(
        Triple(MixGenerator.Mode.MY_MIX, R.string.mix_my, R.string.mix_my_desc),
        Triple(MixGenerator.Mode.NEVER_HEARD, R.string.mix_never, R.string.mix_never_desc),
        Triple(MixGenerator.Mode.REDISCOVER, R.string.mix_rediscover, R.string.mix_rediscover_desc),
        Triple(MixGenerator.Mode.ARTIST, R.string.mix_artist, R.string.mix_artist_desc),
    )
    var mode by remember { mutableStateOf(MixGenerator.Mode.MY_MIX) }
    var artist by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var mix by remember { mutableStateOf<MixGenerator.Mix?>(null) }
    val scope = rememberCoroutineScope()
    fun generate() {
        busy = true
        error = null
        val name = context.getString(modes.first { it.first == mode }.second) + " · " +
            java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date())
        scope.launch {
            MixGenerator.generate(mode, artist, name)
                .onSuccess { mix = it }
                .onFailure { error = it.message }
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(mix?.title ?: stringResource(R.string.mix_title)) },
        text = {
            Column {
                val current = mix
                if (current == null) {
                    modes.forEach { (m, label, desc) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { mode = m }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == m, onClick = { mode = m })
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Text(stringResource(label))
                                Text(stringResource(desc), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (mode == MixGenerator.Mode.ARTIST) {
                        OutlinedTextField(
                            value = artist,
                            onValueChange = { artist = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.mix_artist_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Text(stringResource(R.string.mix_count, current.songs.size), style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        itemsIndexed(current.songs, key = { i, s -> "$i-${s.videoId}" }) { i, s ->
                            Column(Modifier.fillMaxWidth().clickable { onPlay(current.songs.drop(i) + current.songs.take(i), false) }.padding(vertical = 6.dp)) {
                                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Row {
                        TextButton(onClick = { onPlay(current.songs, false) }) { Text(stringResource(R.string.mix_play)) }
                        TextButton(onClick = { onPlay(current.songs, true) }) { Text(stringResource(R.string.mix_shuffle)) }
                        TextButton(onClick = { onSave(current.title, current.songs) }) { Text(stringResource(R.string.mix_save)) }
                    }
                }
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (mix == null) {
                TextButton(enabled = !busy && (mode != MixGenerator.Mode.ARTIST || artist.isNotBlank()), onClick = ::generate) {
                    Text(stringResource(R.string.mix_generate))
                }
            } else {
                TextButton(enabled = !busy, onClick = ::generate) { Text(stringResource(R.string.mix_again)) }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (mix != null) mix = null else onDismiss() }) {
                Text(stringResource(if (mix != null) R.string.mix_other else R.string.mix_close))
            }
        },
    )
}

/** BitChord Audio: Home's card that opens the mix generator. */
@Composable
fun MakeMixCard(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable { MixGeneratorUi.open.value = true }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            com.music.bitchord.ui.icons.SfIcons.Shuffle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.home_make_mix), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.home_make_mix_sub), style = MaterialTheme.typography.bodySmall)
        }
    }
}

package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.MergedLibrary
import com.music.bitchord.data.model.Song

/**
 * BitChord Audio: "Play from…" — which copy of a song the merged library plays:
 * one of the places it is, or the default order from Settings.
 */
@Composable
fun SourceChoiceDialog(song: Song, onPicked: () -> Unit, onDismiss: () -> Unit) {
    val versions = remember(song.videoId) { MergedLibrary.versionsOf(song) }
    val current = remember(song.videoId) { MergedLibrary.choiceFor(song) }
    fun pick(origin: MergedLibrary.Origin?) {
        MergedLibrary.choose(song, origin)
        onPicked()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.play_from)) },
        text = {
            Column {
                Text(
                    "${song.title} · ${song.artist}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ChoiceRow(
                    label = stringResource(R.string.play_from_default),
                    detail = stringResource(R.string.play_from_default_detail),
                    selected = current == null,
                    onClick = { pick(null) },
                )
                versions.forEach { v ->
                    ChoiceRow(
                        label = v.origin.label,
                        detail = listOfNotNull(v.song.sourceQuality, v.song.albumName, v.song.durationText)
                            .joinToString(" · "),
                        selected = current == v.origin,
                        onClick = { pick(v.origin) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun ChoiceRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

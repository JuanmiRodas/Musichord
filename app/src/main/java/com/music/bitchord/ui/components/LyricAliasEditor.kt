package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.LyricAliases
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BitChord Audio: the song whose lyric names are being edited, raised by the
 * player's lyrics provider sheet and drawn by the activity.
 */
object LyricAliasEditor {
    private val _song = MutableStateFlow<Song?>(null)
    val song: StateFlow<Song?> = _song.asStateFlow()

    fun open(song: Song) {
        _song.value = song
    }

    fun close() {
        _song.value = null
    }
}

/**
 * Other titles and artists to look [song]'s lyrics up by — the original
 * Japanese title of a romanized one, a translated title, a soundtrack name.
 * One name per line; saving empty fields removes them.
 */
@Composable
fun LyricAliasDialog(song: Song, onDismiss: () -> Unit) {
    val current = remember(song.videoId) { LyricAliases.userNames(song.videoId, song.title, song.artist) }
    var titles by remember(song.videoId) { mutableStateOf(current.titles.joinToString("\n")) }
    var artists by remember(song.videoId) { mutableStateOf(current.artists.joinToString("\n")) }
    // Nothing saved yet: start from the names the song goes by — both copies,
    // romaji of Japanese, and the likely Japanese of a romaji title.
    androidx.compose.runtime.LaunchedEffect(song.videoId) {
        if (!current.isEmpty) return@LaunchedEffect
        val s = com.music.bitchord.data.lyrics.LyricNames.suggest(song)
        if (titles.isBlank()) titles = s.titles.joinToString("\n")
        if (artists.isBlank()) artists = s.artists.joinToString("\n")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lyric_aliases_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.lyric_aliases_message, song.title, song.artist),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = titles,
                    onValueChange = { titles = it },
                    label = { Text(stringResource(R.string.lyric_aliases_titles)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = artists,
                    onValueChange = { artists = it },
                    label = { Text(stringResource(R.string.lyric_aliases_artists)) },
                    minLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                LyricAliases.setUser(
                    song.videoId,
                    song.title,
                    song.artist,
                    LyricAliases.Names(titles.lines(), artists.lines()),
                )
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

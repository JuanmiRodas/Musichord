package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.EditedLyrics
import com.music.bitchord.data.lyrics.LyricAliases
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricNames
import com.music.bitchord.data.lyrics.LyricsRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * BitChord Audio: the song whose lyrics are being searched by hand, raised by
 * the player's lyrics provider sheet and drawn by the activity.
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

/** One answer from the search: which source, for which names, and the lyrics. */
private data class Found(
    val source: String,
    val title: String,
    val artist: String,
    val lines: List<LyricLine>,
) {
    val kind: Int = when {
        lines.any { it.words.isNotEmpty() } -> R.string.lyric_search_word_synced
        lines.any { it.timeMs > 0 } -> R.string.lyric_search_timed
        else -> R.string.lyric_search_untimed
    }
    val untimed: Boolean get() = kind == R.string.lyric_search_untimed
    val signature: String =
        lines.filter { it.text.isNotBlank() }.take(6).joinToString("|") { it.text.trim().lowercase() }
}

/**
 * Searching a song's lyrics by hand: the names to search by (filled in with
 * the song's names in romaji and Japanese), searched on every lyrics source
 * at once — on or off in Settings — and every answer listed: which source,
 * timed or not, how it starts. The one tapped is kept for the song; nothing
 * is picked automatically. The names can also just be saved, for the
 * automatic lookup.
 */
@Composable
fun LyricAliasDialog(song: Song, onDismiss: () -> Unit) {
    val current = remember(song.videoId) { LyricAliases.userNames(song.videoId, song.title, song.artist) }
    var titles by remember(song.videoId) { mutableStateOf(current.titles.joinToString("\n")) }
    var artists by remember(song.videoId) { mutableStateOf(current.artists.joinToString("\n")) }
    val results = remember(song.videoId) { mutableStateListOf<Found>() }
    var searching by remember { mutableStateOf<Job?>(null) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Nothing saved yet: start from the names the song goes by — both copies,
    // romaji of Japanese, and the likely Japanese of a romaji title.
    // Fields typed in (or cleared) by hand are left as they are.
    var titlesTouched by remember(song.videoId) { mutableStateOf(false) }
    var artistsTouched by remember(song.videoId) { mutableStateOf(false) }

    fun saveNames() = LyricAliases.setUser(
        song.videoId,
        song.title,
        song.artist,
        LyricAliases.Names(titles.lines(), artists.lines()),
    )

    fun search() {
        searching?.cancel()
        results.clear()
        searched = true
        // Exactly what is in the boxes: an artist box left empty searches by title alone.
        val ts = titles.lines().map(String::trim).filter(String::isNotEmpty).take(3)
        if (ts.isEmpty()) { searched = false; return }
        val ars = artists.lines().map(String::trim).filter(String::isNotEmpty).ifEmpty { listOf("") }.take(2)
        val duration = song.durationMillis()
        searching = scope.launch {
            val jobs = ts.flatMap { t -> ars.map { a -> t to a } }.map { (t, a) ->
                launch {
                    LyricsRepository.everyAnswer(song.videoId, t, a, duration, song.albumName) { r ->
                        val found = Found(r.source.label, t, a, r.lines)
                        scope.launch {
                            if (results.none { it.source == found.source && it.signature == found.signature }) {
                                // Timed answers above untimed ones.
                                val at = if (found.untimed) results.size else results.indexOfFirst { it.untimed }.let { if (it < 0) results.size else it }
                                results.add(at, found)
                            }
                        }
                    }
                }
            }
            jobs.forEach { it.join() }
            searching = null
        }
    }

    // BitChord Audio: and searched straight away — the answers are what
    // this was opened for; the boxes are there to search again differently.
    LaunchedEffect(song.videoId) {
        if (current.isEmpty) {
            val s = LyricNames.suggest(song)
            if (titles.isBlank() && !titlesTouched) titles = s.titles.joinToString("\n")
            if (artists.isBlank() && !artistsTouched) artists = s.artists.joinToString("\n")
        }
        search()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lyric_aliases_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.lyric_search_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = titles,
                    onValueChange = { titles = it; titlesTouched = true },
                    label = { Text(stringResource(R.string.lyric_aliases_titles)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = artists,
                    onValueChange = { artists = it; artistsTouched = true },
                    label = { Text(stringResource(R.string.lyric_aliases_artists)) },
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { search() }) { Text(stringResource(R.string.lyric_search_all)) }
                    if (searching != null) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                if (searched && searching == null && results.isEmpty()) {
                    Text(stringResource(R.string.lyric_search_none), style = MaterialTheme.typography.bodySmall)
                }
                if (results.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(results, key = { it.source + "\u0000" + it.signature }) { r ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                                    .clickable {
                                        searching?.cancel()
                                        saveNames()
                                        EditedLyrics.choose(song, r.lines)
                                        onDismiss()
                                    }
                                    .padding(10.dp),
                            ) {
                                Text(
                                    r.source + " · " + stringResource(r.kind),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    if (r.artist.isBlank()) r.title else "${r.title} — ${r.artist}",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    r.lines.filter { it.text.isNotBlank() }.take(3).joinToString("\n") { it.text },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                saveNames()
                onDismiss()
            }) { Text(stringResource(R.string.lyric_search_save_names)) }
        },
        dismissButton = {
            Row {
                if (remember(song.videoId) { EditedLyrics.has(song.videoId) }) {
                    TextButton(onClick = {
                        EditedLyrics.clear(song.videoId)
                        onDismiss()
                    }) { Text(stringResource(R.string.lyric_search_automatic)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

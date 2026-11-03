package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.covers.CoverSources
import com.music.bitchord.data.covers.CoverTags
import com.music.bitchord.data.covers.CoverWriter
import com.music.bitchord.data.edits.MetadataEdits
import com.music.bitchord.data.edits.TagWriter
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** BitChord Audio: raised from a song's menu, an album's menu or a selection. */
object MetadataEditor {
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()
    fun open(songs: List<Song>) { _songs.value = songs }
    fun close() { _songs.value = emptyList() }
}

/**
 * BitChord Audio: corrects a song's details — or the details several songs
 * share (an album: its name, album artist, artist, year, genre). What you
 * type is shown in place of the source's everywhere ([MetadataEdits]); the
 * song still plays from where it did. Optionally written into your pCloud
 * files and downloads as well ([TagWriter], MP3 and FLAC).
 */
@Composable
fun MetadataEditorDialog(songs: List<Song>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val single = songs.size == 1
    val first = songs.first()
    // What each field shows now: your correction, else the song's own; for
    // several songs, only what they all share (else empty, "leave as it is").
    fun shared(pick: (Song, MetadataEdits.Edit?) -> String?): String {
        val values = songs.map { s -> pick(s, MetadataEdits.get(s)).orEmpty() }.distinct()
        return if (values.size == 1) values.first() else ""
    }
    var title by remember { mutableStateOf(if (single) MetadataEdits.get(first)?.title ?: first.title else "") }
    var artist by remember { mutableStateOf(shared { s, e -> e?.artist ?: s.artist }) }
    var album by remember { mutableStateOf(shared { s, e -> e?.album ?: s.albumName }) }
    var albumArtist by remember { mutableStateOf(shared { _, e -> e?.albumArtist }) }
    var year by remember { mutableStateOf(shared { _, e -> e?.year }) }
    var track by remember { mutableStateOf(if (single) MetadataEdits.get(first)?.track ?: first.trackNumber?.toString().orEmpty() else "") }
    var genre by remember { mutableStateOf(shared { _, e -> e?.genre }) }
    var intoPcloud by remember { mutableStateOf(true) }
    var intoDownloads by remember { mutableStateOf(true) }

    fun save() {
        fun changed(new: String, original: String?): String? =
            new.trim().takeIf { it.isNotEmpty() && it != original.orEmpty() }
        val edit = if (single) {
            MetadataEdits.Edit(
                title = changed(title, first.title),
                artist = changed(artist, first.artist),
                album = changed(album, first.albumName),
                albumArtist = albumArtist.trim().ifEmpty { null },
                year = year.trim().ifEmpty { null },
                track = changed(track, first.trackNumber?.toString()),
                genre = genre.trim().ifEmpty { null },
            )
        } else {
            // Several songs: only the fields filled in are changed.
            MetadataEdits.Edit(
                artist = artist.trim().ifEmpty { null },
                album = album.trim().ifEmpty { null },
                albumArtist = albumArtist.trim().ifEmpty { null },
                year = year.trim().ifEmpty { null },
                genre = genre.trim().ifEmpty { null },
            )
        }
        MetadataEdits.set(songs, edit, replace = single)
        if (!intoPcloud && !intoDownloads) {
            onDismiss()
            return
        }
        CoroutineScope(Dispatchers.IO).launch {
            var saved = 0
            var tried = 0
            val notes = ArrayList<String>()
            for (s in songs) {
                val e = MetadataEdits.get(s) ?: continue
                val text = CoverTags.Text(
                    title = e.title ?: s.title,
                    artist = e.artist ?: s.artist,
                    album = e.album ?: s.albumName,
                    albumArtist = e.albumArtist,
                    year = e.year,
                    track = e.track ?: s.trackNumber?.toString(),
                    genre = e.genre,
                )
                val outcomes = ArrayList<CoverWriter.Outcome>()
                if (intoPcloud) {
                    CoverSources.filesOf(s).take(1).forEach { f ->
                        outcomes += runCatching { TagWriter.intoPcloud(app, f.videoId, text) }
                            .getOrElse { CoverWriter.Outcome.Failed(it.message ?: "error") }
                    }
                }
                if (intoDownloads) {
                    (s.localUri ?: Downloads.verifiedSavedUri(s.videoId))?.let { uri ->
                        outcomes += runCatching { TagWriter.intoDownload(app, uri, text) }
                            .getOrElse { CoverWriter.Outcome.Failed(it.message ?: "error") }
                    }
                }
                tried += outcomes.size
                outcomes.forEach { o ->
                    when (o) {
                        CoverWriter.Outcome.Saved -> saved++
                        is CoverWriter.Outcome.Skipped -> notes += "${s.title}: ${o.why}"
                        is CoverWriter.Outcome.Failed -> notes += "${s.title}: ${o.why}"
                    }
                }
            }
            withContext(Dispatchers.Main) {
                val text = if (tried == 0) {
                    app.getString(R.string.edit_info_no_files)
                } else {
                    app.getString(R.string.edit_info_saved_files, saved, tried) + notes.firstOrNull()?.let { "\n$it" }.orEmpty()
                }
                android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (single) stringResource(R.string.edit_info)
                else stringResource(R.string.edit_info_many, songs.size),
            )
        },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(if (single) R.string.edit_info_help else R.string.edit_info_help_many),
                    style = MaterialTheme.typography.bodySmall,
                )
                @Composable
                fun field(label: Int, value: String, onChange: (String) -> Unit, number: Boolean = false) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = onChange,
                        label = { Text(stringResource(label)) },
                        singleLine = true,
                        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (single) field(R.string.edit_info_title, title, { title = it })
                field(R.string.edit_info_artist, artist, { artist = it })
                field(R.string.edit_info_album, album, { album = it })
                field(R.string.edit_info_album_artist, albumArtist, { albumArtist = it })
                field(R.string.edit_info_year, year, { year = it }, number = true)
                if (single) field(R.string.edit_info_track, track, { track = it }, number = true)
                field(R.string.edit_info_genre, genre, { genre = it })
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { intoPcloud = !intoPcloud }) {
                    Checkbox(checked = intoPcloud, onCheckedChange = { intoPcloud = it })
                    Text(stringResource(R.string.edit_info_into_pcloud), style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { intoDownloads = !intoDownloads }) {
                    Checkbox(checked = intoDownloads, onCheckedChange = { intoDownloads = it })
                    Text(stringResource(R.string.edit_info_into_downloads), style = MaterialTheme.typography.bodyMedium)
                }
                if (songs.any { MetadataEdits.get(it) != null }) {
                    TextButton(onClick = { MetadataEdits.clear(songs); onDismiss() }) {
                        Text(stringResource(R.string.edit_info_restore))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = ::save) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

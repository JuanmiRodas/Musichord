package com.music.bitchord.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.covers.CoverOverrides
import com.music.bitchord.data.covers.CoverSources
import com.music.bitchord.data.covers.CoverWriter
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.AlbumSources
import com.music.bitchord.data.sources.ArtistLinks
import com.music.bitchord.data.sources.OwnFileMatcher
import com.music.bitchord.data.sources.PreferredSource
import com.music.bitchord.data.webdav.WebDavRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** BitChord Audio: raised from the player's cover button. */
object CoverPicker {
    private val _song = MutableStateFlow<Song?>(null)
    val song: StateFlow<Song?> = _song.asStateFlow()
    fun open(song: Song) { _song.value = song }
    fun close() { _song.value = null }
}

/**
 * BitChord Audio: every cover on offer for a song ([CoverSources]) to pick
 * from; then which songs of its album get it too (all ticked to start with),
 * and whether it is also written into your pCloud files (ticked).
 */
@Composable
fun CoverPickerDialog(song: Song, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    var covers by remember(song.videoId) { mutableStateOf<List<CoverSources.Cover>?>(null) }
    val broken = remember(song.videoId) { mutableStateListOf<String>() }
    val current = remember(song.videoId) { CoverOverrides.get(song) }
    var picked by remember(song.videoId) { mutableStateOf(current) }
    var albumSongs by remember(song.videoId) { mutableStateOf<List<Song>>(emptyList()) }
    val chosen = remember(song.videoId) { mutableStateListOf<String>() }
    var saveToFiles by remember(song.videoId) { mutableStateOf(true) }

    LaunchedEffect(song.videoId) {
        albumSongs = withContext(Dispatchers.Default) { albumOf(song) }
        chosen.clear()
        albumSongs.forEach { chosen += it.videoId }
    }
    LaunchedEffect(song.videoId) {
        covers = runCatching { CoverSources.find(app, song) }.getOrDefault(emptyList())
    }

    fun applyCover() {
        val url = picked ?: return
        val targets = listOf(song) + albumSongs.filter { it.videoId in chosen }
        CoverOverrides.set(targets, url)
        if (saveToFiles && com.music.bitchord.data.settings.AppSettings.meteredConnection.value != false) {
            // On mobile data (or offline): written into the files later, on Wi-Fi.
            CoroutineScope(Dispatchers.IO).launch {
                val files = targets.flatMap { CoverSources.filesOf(it).take(1) }.distinctBy { it.videoId }
                com.music.bitchord.data.covers.PendingCoverWrites.add(files.map { it.videoId }, url)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        app,
                        if (files.isEmpty()) app.getString(R.string.cover_no_files) else app.getString(R.string.cover_saved_later, files.size),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        } else if (saveToFiles) {
            CoroutineScope(Dispatchers.IO).launch {
                val image = CoverWriter.fetch(url)
                val files = targets.flatMap { CoverSources.filesOf(it).take(1) }.distinctBy { it.videoId }
                var saved = 0
                val notes = ArrayList<String>()
                if (image != null) {
                    for (f in files) {
                        when (val r = runCatching { CoverWriter.saveInto(app, f, image) }.getOrElse { CoverWriter.Outcome.Failed(it.message ?: "error") }) {
                            CoverWriter.Outcome.Saved -> saved++
                            is CoverWriter.Outcome.Skipped -> notes += "${f.title}: ${r.why}"
                            is CoverWriter.Outcome.Failed -> notes += "${f.title}: ${r.why}"
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    val text = when {
                        image == null -> app.getString(R.string.cover_download_failed)
                        files.isEmpty() -> app.getString(R.string.cover_no_files)
                        else -> app.getString(R.string.cover_saved_files, saved, files.size) +
                            notes.firstOrNull()?.let { "\n$it" }.orEmpty()
                    }
                    android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cover_title)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Text("${song.title} · ${song.artist}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.padding(4.dp))
                val list = covers?.filter { it.url !in broken }
                when {
                    list == null -> Text(stringResource(R.string.cover_searching), style = MaterialTheme.typography.labelMedium)
                    list.isEmpty() -> Text(stringResource(R.string.cover_none), style = MaterialTheme.typography.labelMedium)
                    else -> list.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { c ->
                                Column(Modifier.weight(1f).padding(bottom = 6.dp)) {
                                    val selected = picked == c.url
                                    AsyncImage(
                                        model = c.url,
                                        contentDescription = c.detail,
                                        contentScale = ContentScale.Crop,
                                        onError = { broken += c.url },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .then(
                                                if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                                                else Modifier,
                                            )
                                            .clickable { picked = c.url },
                                    )
                                    Text(c.source, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (c.detail.isNotBlank()) {
                                        Text(
                                            c.detail,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                if (albumSongs.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                        Text(
                            stringResource(R.string.cover_album_songs, albumSongs.size),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            if (chosen.size == albumSongs.size) chosen.clear()
                            else { chosen.clear(); albumSongs.forEach { chosen += it.videoId } }
                        }) {
                            Text(stringResource(if (chosen.size == albumSongs.size) R.string.cover_none_of_them else R.string.cover_all_of_them))
                        }
                    }
                    Text(
                        stringResource(R.string.cover_album_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    albumSongs.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (s.videoId in chosen) chosen -= s.videoId else chosen += s.videoId
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = s.videoId in chosen, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(s.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp).clickable { saveToFiles = !saveToFiles },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = saveToFiles, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cover_save_pcloud), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { applyCover() }, enabled = picked != null) {
                Text(stringResource(R.string.cover_apply))
            }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = {
                        CoverOverrides.clear(listOf(song) + albumSongs.filter { it.videoId in chosen })
                        onDismiss()
                    }) { Text(stringResource(R.string.cover_reset)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** The other songs of [song]'s album: in your likes and library, and among your pCloud files. */
private fun albumOf(song: Song): List<Song> {
    val album = song.albumName?.takeIf { it.isNotBlank() } ?: return emptyList()
    val ownKey = PreferredSource.keyOf(song.title, song.artist)
    val mates = runCatching { AlbumSources.albumMates(song) }.getOrDefault(emptyList())
    val files = WebDavRepository.library().filter { f ->
        !f.albumName.isNullOrBlank() && OwnFileMatcher.sameAlbum(album, f.albumName) &&
            (OwnFileMatcher.sameArtist(song.artist, f.artist) || ArtistLinks.linked(song.artist, f.artist))
    }
    return (mates + files)
        .filter { PreferredSource.keyOf(it.title, it.artist) != ownKey }
        .distinctBy { PreferredSource.keyOf(it.title, it.artist) ?: it.videoId }
}

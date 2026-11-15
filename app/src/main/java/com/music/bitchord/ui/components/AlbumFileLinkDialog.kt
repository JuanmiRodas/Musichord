package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.AlbumFileLink
import com.music.bitchord.data.sources.PreferredSource
import com.music.bitchord.data.webdav.WebDavRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** BitChord Audio: which release is being linked to your pCloud files. */
object AlbumFileLinker {
    data class Request(val title: String, val artist: String?, val songs: List<Song>)

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()
    fun open(title: String, artist: String?, songs: List<Song>) { _request.value = Request(title, artist, songs) }
    fun close() { _request.value = null }
}

/**
 * BitChord Audio: link a whole release to one of your pCloud folders. The
 * folders that hold most of its songs are offered first (or any, by name);
 * picking one pairs every song with its file — by name in either script,
 * sound, track number and length — and each pair can be changed by hand.
 */
@Composable
fun AlbumFileLinkDialog(request: AlbumFileLinker.Request, onDismiss: () -> Unit) {
    var folders by remember { mutableStateOf<List<AlbumFileLink.Folder>?>(null) }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<AlbumFileLink.Folder?>(null) }
    val pairs = remember { mutableStateListOf<AlbumFileLink.Match>() }

    LaunchedEffect(request, query) {
        if (query.isNotBlank()) delay(350)
        folders = null
        folders = withContext(Dispatchers.Default) {
            if (query.isBlank()) AlbumFileLink.folders(request.songs, request.title, request.artist)
            else AlbumFileLink.search(query, request.songs)
        }
    }
    LaunchedEffect(picked) {
        val folder = picked ?: return@LaunchedEffect
        val made = withContext(Dispatchers.Default) { AlbumFileLink.pair(request.songs, folder.files) }
        pairs.clear()
        pairs.addAll(made)
    }

    val folder = picked
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (folder == null) stringResource(R.string.album_link_files_title) else folder.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            if (folder == null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.album_link_search_folder)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val list = folders
                    when {
                        WebDavRepository.library().isEmpty() ->
                            Text(stringResource(R.string.album_link_no_library), style = MaterialTheme.typography.bodyMedium)
                        list == null -> Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                        }
                        list.isEmpty() -> Text(stringResource(R.string.album_link_no_folder), style = MaterialTheme.typography.bodyMedium)
                        else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(list, key = { it.url }) { f ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { picked = f }
                                        .padding(vertical = 10.dp),
                                ) {
                                    Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        stringResource(R.string.album_link_folder_counts, f.files.size, f.matching, request.songs.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    itemsIndexed(pairs, key = { _, p -> p.song.videoId }) { index, pair ->
                        var menu by remember { mutableStateOf(false) }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { menu = true }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(26.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(pair.song.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    pair.file?.let { "→ " + fileLabel(it) } ?: stringResource(R.string.album_link_no_file),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when {
                                        pair.file == null -> MaterialTheme.colorScheme.error
                                        pair.score < 100 -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.album_link_no_file)) },
                                    onClick = {
                                        pairs[index] = pair.copy(file = null, score = 0)
                                        menu = false
                                    },
                                )
                                folder.files.forEach { file ->
                                    DropdownMenuItem(
                                        text = { Text(fileLabel(file), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                        onClick = {
                                            // A file goes to one song: taken from any other.
                                            pairs.indices.forEach { i ->
                                                if (i != index && pairs[i].file?.videoId == file.videoId) pairs[i] = pairs[i].copy(file = null, score = 0)
                                            }
                                            pairs[index] = pair.copy(file = file, score = 1000)
                                            menu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (folder != null) {
                val count = pairs.count { it.file != null }
                TextButton(
                    enabled = count > 0,
                    onClick = {
                        AlbumFileLink.link(pairs.toList())
                        onDismiss()
                    },
                ) { Text(stringResource(R.string.album_link_confirm, count)) }
            }
        },
        dismissButton = {
            Row {
                if (folder != null) {
                    TextButton(onClick = { picked = null }) { Text(stringResource(R.string.back)) }
                } else if (request.songs.any { PreferredSource.get(it) is PreferredSource.File }) {
                    TextButton(onClick = {
                        AlbumFileLink.unlink(request.songs)
                        onDismiss()
                    }) { Text(stringResource(R.string.album_link_unlink)) }
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

private fun fileLabel(file: Song): String {
    val n = file.trackNumber?.let { "%02d. ".format(it) }.orEmpty()
    val length = file.durationText?.let { " · $it" }.orEmpty()
    return n + file.title + length
}

package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.session.MediaController
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.remote.RemoteTagStore
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.LibraryFolders
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.data.webdav.WebDavRepository
import com.music.bitchord.playback.playSongs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URLDecoder

/**
 * BitChord Audio: your pCloud music by folder — Artist, Album, files — read
 * live from the server. Opened from the Library to browse and play, or from
 * the player's source picker to choose the exact file a song plays from.
 */
object PcloudBrowser {
    /** What the browser is open for: [pickFor] set means "choose a file for this song". */
    data class Request(val pickFor: Song? = null)

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    fun browse() { _request.value = Request() }
    fun pickFor(song: Song) { _request.value = Request(pickFor = song) }
    fun close() { _request.value = null }

    const val LIBRARY_LINK = "app:pcloud-folders"
}

private data class Listing(val folders: List<WebDavClient.Entry>, val files: List<WebDavClient.Entry>)

@Composable
fun PcloudBrowserDialog(request: PcloudBrowser.Request, controller: MediaController?, onPicked: (Song) -> Unit, onDismiss: () -> Unit) {
    val roots = remember {
        LibraryFolders.musicFolders().map { it.url }.ifEmpty { listOf(AppSettings.webdavUrl.value) }
            .map { WebDavConfig.normalizeUrl(it).trimEnd('/') + "/" }
    }
    // Several music folders start at a list of them; one starts inside it.
    var path by remember { mutableStateOf(if (roots.size == 1) listOf(roots.first()) else emptyList()) }
    var listing by remember { mutableStateOf<Listing?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val dir = path.lastOrNull()

    LaunchedEffect(dir) {
        listing = null
        error = null
        if (dir == null) return@LaunchedEffect
        val u = AppSettings.webdavUsername.value
        val p = AppSettings.webdavPassword.value
        val folders = WebDavClient.listFolders(dir, u, p)
        val files = WebDavClient.listFiles(dir, u, p)
        if (folders.isFailure && files.isFailure) {
            error = (folders.exceptionOrNull() ?: files.exceptionOrNull())?.message
            return@LaunchedEffect
        }
        listing = Listing(
            folders = folders.getOrNull().orEmpty().sortedBy { nameOf(it).lowercase() },
            files = files.getOrNull().orEmpty()
                .filter { WebDavConfig.isAudioFile(nameOf(it)) }
                .sortedBy { nameOf(it).lowercase() },
        )
    }

    fun songOf(entry: WebDavClient.Entry): Song {
        val id = WebDavConfig.idFor(entry.url)
        return WebDavRepository.library().firstOrNull { it.videoId == id }
            ?: with(WebDavRepository) { entry.toSong() }
    }

    fun play(songs: List<Song>, start: Int) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        scope.launch { c.playSongs(songs, start) }
        onDismiss()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    IconButton(onClick = { if (path.size > (if (roots.size == 1) 1 else 0)) path = path.dropLast(1) else onDismiss() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.folder_up))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (request.pickFor != null) stringResource(R.string.source_browse_pcloud) else stringResource(R.string.pcloud_folders),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            request.pickFor?.let { "${it.title} · ${it.artist}" } ?: dir?.let(::decodedName).orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel)) }
                }
                if (dir == null) {
                    LazyColumn {
                        items(roots) { root ->
                            BrowserRow(Icons.Rounded.Folder, decodedName(root), null) { path = listOf(root) }
                        }
                    }
                    return@Column
                }
                val l = listing
                when {
                    error != null -> Text(error.orEmpty(), modifier = Modifier.padding(16.dp))
                    l == null -> Text(stringResource(R.string.browser_loading), modifier = Modifier.padding(16.dp))
                    else -> {
                        if (request.pickFor == null) {
                            Row(Modifier.padding(horizontal = 8.dp)) {
                                if (l.files.isNotEmpty()) {
                                    TextButton(onClick = { play(l.files.map(::songOf), 0) }) { Text(stringResource(R.string.play_folder)) }
                                }
                                TextButton(onClick = {
                                    // Every file under this folder, from the library as last listed.
                                    val under = WebDavRepository.library()
                                        .filter { WebDavConfig.fileUrlOf(it.videoId)?.startsWith(dir) == true }
                                        .sortedBy { WebDavConfig.fileUrlOf(it.videoId) }
                                    play(under, 0)
                                }) { Text(stringResource(R.string.play_folder_deep)) }
                            }
                        }
                        LazyColumn {
                            items(l.folders, key = { it.url }) { f ->
                                BrowserRow(Icons.Rounded.Folder, nameOf(f), null) { path = path + f.url }
                            }
                            items(l.files, key = { it.url }) { f ->
                                val tags = RemoteTagStore.get(f.url, RemoteTagStore.Stamp(f.size, f.modified))
                                BrowserRow(Icons.Rounded.MusicNote, nameOf(f), tags?.qualityLabel) {
                                    if (request.pickFor != null) {
                                        onPicked(songOf(f))
                                    } else {
                                        val songs = l.files.map(::songOf)
                                        play(songs, l.files.indexOf(f).coerceAtLeast(0))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String?, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrBlank()) {
                Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun nameOf(e: WebDavClient.Entry): String = e.displayName.ifBlank { decodedName(e.url) }

private fun decodedName(url: String): String =
    runCatching { URLDecoder.decode(url.trimEnd('/').substringAfterLast('/'), "UTF-8") }
        .getOrDefault(url.trimEnd('/').substringAfterLast('/'))

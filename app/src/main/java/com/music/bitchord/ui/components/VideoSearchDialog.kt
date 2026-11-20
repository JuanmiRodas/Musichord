package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.video.Bilibili
import com.music.bitchord.data.video.PcloudVideos
import androidx.compose.runtime.collectAsState
import com.music.bitchord.data.video.VideoMode
import com.music.bitchord.data.video.VideoRef
import kotlinx.coroutines.launch

/** One row of the video search: a YouTube or bilibili video. */
private data class VideoHit(
    val ref: VideoRef,
    val title: String,
    val by: String,
    val duration: String?,
    val thumbnail: String?,
)

/**
 * BitChord Audio: the video button held — which video a song shows in video
 * mode. Searches YouTube with the song's name (editable); bilibili only when
 * asked. The pick stays linked to the song wherever it plays from.
 */
@Composable
fun VideoSearchDialog(song: Song, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    // What you searched last time for this song, else its name and artist.
    var query by remember(song.videoId) {
        mutableStateOf(
            VideoMode.lastQuery(song)
                ?: "${song.title} ${song.artist.split(',').first().trim().takeUnless { com.music.bitchord.data.sources.TrackMatcher.isPlaceholderArtist(it) }.orEmpty()}".trim(),
        )
    }
    var asked by remember { mutableStateOf(query) }
    var youtube by remember { mutableStateOf<List<VideoHit>?>(null) }
    var bilibili by remember { mutableStateOf<List<VideoHit>?>(null) }
    var bilibiliAsked by remember { mutableStateOf(false) }
    // BitChord Audio: your pCloud videos — searched only when asked, in the folders you chose.
    var pcloudQuery by remember(song.videoId) { mutableStateOf(query) }
    var pcloudAsked by remember { mutableStateOf<String?>(null) }
    var pcloud by remember { mutableStateOf<List<PcloudVideos.Video>?>(null) }
    var choosingFolders by remember { mutableStateOf(false) }
    val folders by PcloudVideos.folders.collectAsState()
    val current = remember(song.videoId) { VideoMode.linked(song) ?: VideoMode.cached(song.videoId) }

    LaunchedEffect(asked) {
        VideoMode.rememberQuery(song, asked)
        youtube = null
        youtube = runCatching { VideoMode.youTube(asked, song) }.getOrDefault(emptyList()).map {
            VideoHit(VideoRef(VideoRef.Site.YOUTUBE, it.videoId), it.title, it.artist, it.durationText, it.thumbnailUrl)
        }
    }
    LaunchedEffect(asked, bilibiliAsked) {
        if (!bilibiliAsked) return@LaunchedEffect
        bilibili = null
        bilibili = runCatching { Bilibili.search(asked) }.getOrDefault(emptyList()).map {
            VideoHit(VideoRef(VideoRef.Site.BILIBILI, it.bvid), it.title, it.author, it.durationText, it.thumbnail)
        }
    }

    LaunchedEffect(pcloudAsked) {
        val q = pcloudAsked ?: return@LaunchedEffect
        pcloud = null
        pcloud = runCatching { PcloudVideos.search(q) }.getOrDefault(emptyList())
    }

    fun pick(hit: VideoHit) {
        VideoMode.link(song, hit.ref)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.video_mode_search_title)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.video_mode_search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { asked = query.trim() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { asked = query.trim() }) { Text(stringResource(R.string.video_mode_search_go)) }
                }
                SectionLabel("YouTube")
                Results(youtube, current, ::pick)
                if (!bilibiliAsked) {
                    TextButton(onClick = { bilibiliAsked = true }) {
                        Text(stringResource(R.string.video_mode_search_bilibili))
                    }
                } else {
                    SectionLabel("bilibili")
                    Results(bilibili, current, ::pick)
                }
                SectionLabel("pCloud")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (folders.isEmpty()) stringResource(R.string.video_pcloud_no_folders)
                        else folders.joinToString(" · ") { PcloudVideos.nameOf(it) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { choosingFolders = true }) { Text(stringResource(R.string.video_pcloud_folders)) }
                }
                val found = pcloud
                if (found != null) {
                    PcloudResults(found, current) { v ->
                        VideoMode.link(song, VideoRef(VideoRef.Site.PCLOUD, v.url))
                        onDismiss()
                    }
                } else if (pcloudAsked != null) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                OutlinedTextField(
                    value = pcloudQuery,
                    onValueChange = { pcloudQuery = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.video_pcloud_search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (folders.isNotEmpty()) pcloudAsked = pcloudQuery.trim() }),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        enabled = folders.isNotEmpty(),
                        onClick = { pcloudAsked = pcloudQuery.trim() },
                    ) { Text(stringResource(R.string.video_pcloud_search_go)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        // BitChord Audio: the video shown isn't this song's — unlinked and not offered again.
        dismissButton = if (current != null) {
            {
                TextButton(onClick = {
                    VideoMode.markWrong(song)
                    onDismiss()
                }) { Text(stringResource(R.string.video_mode_wrong)) }
            }
        } else {
            null
        },
    )
    if (choosingFolders) PcloudVideoFoldersDialog(onDismiss = { choosingFolders = false })
}

/** Your pCloud videos found, with a frame of each read off the file. */
@Composable
private fun PcloudResults(list: List<PcloudVideos.Video>, current: VideoRef?, onPick: (PcloudVideos.Video) -> Unit) {
    if (list.isEmpty()) {
        Text(
            stringResource(R.string.video_mode_search_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    list.take(20).forEach { v ->
        val chosen = current?.site == VideoRef.Site.PCLOUD && current.id == v.url
        var thumb by remember(v.url) { mutableStateOf<ByteArray?>(null) }
        LaunchedEffect(v.url) { thumb = PcloudVideos.thumbnail(v) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onPick(v) }
                .padding(vertical = 6.dp),
        ) {
            AsyncImage(
                model = thumb,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(96.dp)
                    .heightIn(min = 54.dp, max = 54.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    v.name.substringBeforeLast('.'),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(v.folder, v.size?.let { "${it / (1024 * 1024)} MB" }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * BitChord Audio: the folders of your pCloud looked through for videos — browsed from the
 * top of the drive, each added with one tap; only these are ever listed.
 */
@Composable
fun PcloudVideoFoldersDialog(onDismiss: () -> Unit) {
    val chosen by PcloudVideos.folders.collectAsState()
    var picked by remember { mutableStateOf(chosen) }
    val root = remember { PcloudVideos.serverRoot() }
    var at by remember { mutableStateOf(root) }
    var subfolders by remember { mutableStateOf<List<com.music.bitchord.data.webdav.WebDavClient.Entry>?>(null) }
    val trail = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    LaunchedEffect(at) {
        val dir = at ?: return@LaunchedEffect
        subfolders = null
        subfolders = com.music.bitchord.data.webdav.WebDavClient.listFolders(
            dir,
            com.music.bitchord.data.settings.AppSettings.webdavUsername.value,
            com.music.bitchord.data.settings.AppSettings.webdavPassword.value,
        ).getOrDefault(emptyList()).sortedBy { PcloudVideos.nameOf(it.url).lowercase() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.video_pcloud_folders_title)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                picked.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(PcloudVideos.nameOf(f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { picked = picked - f }) { Text(stringResource(R.string.video_pcloud_remove)) }
                    }
                }
                SectionLabel(at?.let { if (it == root) "pCloud" else PcloudVideos.nameOf(it) } ?: "pCloud")
                Row {
                    if (trail.isNotEmpty()) TextButton(onClick = { at = trail.removeAt(trail.lastIndex) }) { Text(stringResource(R.string.video_pcloud_up)) }
                    val here = at
                    if (here != null && here != root && here !in picked) {
                        TextButton(onClick = { picked = picked + here }) { Text(stringResource(R.string.video_pcloud_add_here)) }
                    }
                }
                when (val list = subfolders) {
                    null -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    else -> list.forEach { e ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    at?.let { trail.add(it) }
                                    at = e.url
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        ) {
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                PcloudVideos.nameOf(e.url),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                PcloudVideos.setFolders(picked)
                onDismiss()
            }) { Text(stringResource(R.string.video_pcloud_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun Results(hits: List<VideoHit>?, current: VideoRef?, onPick: (VideoHit) -> Unit) {
    when {
        hits == null -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        hits.isEmpty() -> Text(
            stringResource(R.string.video_mode_search_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> hits.take(12).forEach { hit ->
            val chosen = hit.ref == current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPick(hit) }
                    .padding(vertical = 6.dp),
            ) {
                AsyncImage(
                    model = hit.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(96.dp)
                        .heightIn(min = 54.dp, max = 54.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        hit.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(hit.by.ifBlank { null }, hit.duration).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

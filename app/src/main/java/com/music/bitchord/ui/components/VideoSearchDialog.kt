package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.video.AnimeThemes
import com.music.bitchord.data.video.Bilibili
import com.music.bitchord.data.video.PcloudVideos
import com.music.bitchord.data.video.VideoMode
import com.music.bitchord.data.video.VideoRef
import com.music.bitchord.data.webdav.WebDavClient

/** One row of the video picker, whatever it comes from. */
private data class VideoHit(
    val ref: VideoRef,
    val title: String,
    val by: String,
    val detail: String?,
    /** A URL, or (pCloud) the frame's bytes, read when the row shows. */
    val thumbnail: Any?,
    val pcloud: PcloudVideos.Video? = null,
)

private enum class Source(val label: String) { YOUTUBE("YouTube"), ANIMETHEMES("AnimeThemes"), BILIBILI("bilibili"), PCLOUD("pCloud") }

/**
 * BitChord Audio: the video button held — which video a song shows in video mode.
 *
 * One sheet for every place a video can come from: one search box (the song's name,
 * editable), and a chip per source — YouTube, AnimeThemes (anime openings and endings),
 * bilibili and your pCloud. A source is asked only when its chip is opened (pCloud only
 * when you press search, or browse it folder by folder). The pick stays linked to the
 * song wherever it plays from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSearchDialog(song: Song, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // What you searched last time for this song, else its name and artist.
    var query by remember(song.videoId) {
        mutableStateOf(
            VideoMode.lastQuery(song)
                ?: "${song.title} ${song.artist.split(',').first().trim().takeUnless { com.music.bitchord.data.sources.TrackMatcher.isPlaceholderArtist(it) }.orEmpty()}".trim(),
        )
    }
    var asked by remember { mutableStateOf(query) }
    // Anime songs open on AnimeThemes when a theme is already this song's video.
    val current = remember(song.videoId) { VideoMode.linked(song) ?: VideoMode.cached(song.videoId) }
    var source by remember {
        mutableStateOf(
            when (current?.site) {
                VideoRef.Site.ANIMETHEMES -> Source.ANIMETHEMES
                VideoRef.Site.PCLOUD -> Source.PCLOUD
                VideoRef.Site.BILIBILI -> Source.BILIBILI
                else -> Source.YOUTUBE
            },
        )
    }
    // Results by source and question, so moving between chips asks nothing twice.
    val results = remember { mutableStateMapOf<String, List<VideoHit>>() }
    var pcloudBrowsing by remember { mutableStateOf(false) }
    var pcloudAsked by remember { mutableStateOf<String?>(null) }

    fun pick(hit: VideoHit) {
        VideoMode.link(song, hit.ref)
        onDismiss()
    }

    val key = "${source.name}|$asked"
    val asks = source != Source.PCLOUD || pcloudAsked == asked
    LaunchedEffect(key, asks) {
        if (!asks || results.containsKey(key)) return@LaunchedEffect
        VideoMode.rememberQuery(song, asked)
        val found = runCatching {
            when (source) {
                Source.YOUTUBE -> VideoMode.youTube(asked, song).map {
                    VideoHit(VideoRef(VideoRef.Site.YOUTUBE, it.videoId), it.title, it.artist, it.durationText, it.thumbnailUrl)
                }
                Source.ANIMETHEMES -> AnimeThemes.ranked(song, AnimeThemes.search(asked)).map {
                    VideoHit(
                        VideoRef(VideoRef.Site.ANIMETHEMES, AnimeThemes.encode(it)),
                        it.title,
                        listOfNotNull(it.label, it.artists.joinToString(", ").ifBlank { null }).joinToString(" · "),
                        "${it.resolution}p",
                        it.cover,
                    )
                }
                Source.BILIBILI -> Bilibili.search(asked).map {
                    VideoHit(VideoRef(VideoRef.Site.BILIBILI, it.bvid), it.title, it.author, it.durationText, it.thumbnail)
                }
                Source.PCLOUD -> PcloudVideos.search(asked).map { it.toHit() }
            }
        }.getOrDefault(emptyList())
        results[key] = found
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.video_mode_search_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The video shown now, and the way to say it isn't this song's.
            if (current != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    Icon(Icons.Outlined.Movie, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        describe(current),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        VideoMode.markWrong(song)
                        onDismiss()
                    }) { Text(stringResource(R.string.video_mode_wrong)) }
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.video_mode_search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, null) } }
                } else null,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    asked = query.trim()
                    if (source == Source.PCLOUD) { pcloudBrowsing = false; pcloudAsked = asked }
                }),
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()),
            ) {
                Source.entries.forEach { s ->
                    FilterChip(
                        selected = source == s,
                        onClick = {
                            source = s
                            if (query.trim() != asked && s != Source.PCLOUD) asked = query.trim()
                        },
                        label = { Text(s.label) },
                    )
                }
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 520.dp).padding(top = 6.dp)) {
                if (source == Source.PCLOUD) {
                    PcloudPane(
                        browsing = pcloudBrowsing,
                        onBrowsing = { pcloudBrowsing = it },
                        searched = pcloudAsked?.let { results["${Source.PCLOUD.name}|$it"] },
                        searching = pcloudAsked != null && !results.containsKey("${Source.PCLOUD.name}|$pcloudAsked"),
                        onSearch = {
                            asked = query.trim()
                            pcloudAsked = asked
                        },
                        current = current,
                        onPick = ::pick,
                    )
                } else {
                    HitList(results[key], current, ::pick)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private fun PcloudVideos.Video.toHit() = VideoHit(
    VideoRef(VideoRef.Site.PCLOUD, url),
    name.substringBeforeLast('.'),
    folder,
    size?.let { "${it / (1024 * 1024)} MB" },
    null,
    pcloud = this,
)

private fun describe(ref: VideoRef): String = when (ref.site) {
    VideoRef.Site.YOUTUBE -> "YouTube · ${ref.id}"
    VideoRef.Site.BILIBILI -> "bilibili · ${ref.id}"
    VideoRef.Site.PCLOUD -> "pCloud · " + PcloudVideos.nameOf(ref.id).substringBeforeLast('.')
    VideoRef.Site.ANIMETHEMES -> "AnimeThemes · " + AnimeThemes.videoOf(ref.id).substringAfterLast('/').substringBeforeLast('.')
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp)
    }
}

@Composable
private fun Empty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 20.dp, horizontal = 4.dp),
    )
}

@Composable
private fun HitList(hits: List<VideoHit>?, current: VideoRef?, onPick: (VideoHit) -> Unit) {
    when {
        hits == null -> Loading()
        hits.isEmpty() -> Empty(stringResource(R.string.video_mode_search_empty))
        else -> LazyColumn(Modifier.fillMaxWidth()) {
            items(hits.take(30), key = { it.ref.encode() }) { HitRow(it, it.ref == current, onPick) }
        }
    }
}

@Composable
private fun HitRow(hit: VideoHit, chosen: Boolean, onPick: (VideoHit) -> Unit) {
    // A pCloud video's frame is read (or made, and saved beside it) when the row shows.
    var frame by remember(hit.ref) { mutableStateOf(hit.thumbnail) }
    if (hit.pcloud != null) LaunchedEffect(hit.ref) { frame = PcloudVideos.thumbnail(hit.pcloud) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onPick(hit) }
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Box(
            Modifier
                .width(112.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (frame == null) Icon(Icons.Outlined.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            AsyncImage(model = frame, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            hit.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                hit.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (hit.by.isNotBlank()) {
                Text(
                    hit.by,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (chosen) Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp).size(20.dp))
    }
}

/**
 * BitChord Audio: your pCloud — searched in your video folders when you ask, or browsed
 * folder by folder to the very file; any folder can be added to (or taken off) the ones
 * searched.
 */
@Composable
private fun PcloudPane(
    browsing: Boolean,
    onBrowsing: (Boolean) -> Unit,
    searched: List<VideoHit>?,
    searching: Boolean,
    onSearch: () -> Unit,
    current: VideoRef?,
    onPick: (VideoHit) -> Unit,
) {
    val folders by PcloudVideos.folders.collectAsState()
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(!browsing, { onBrowsing(false) }, { Text(stringResource(R.string.video_pcloud_search_tab)) }, leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) })
            FilterChip(browsing, { onBrowsing(true) }, { Text(stringResource(R.string.video_pcloud_browse_tab)) }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)) })
        }
        if (browsing) {
            PcloudBrowser(folders, current, onPick)
            return@Column
        }
        // The folders searched, each removable; more are added while browsing.
        if (folders.isEmpty()) {
            Empty(stringResource(R.string.video_pcloud_no_folders))
            OutlinedButton(onClick = { onBrowsing(true) }) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.video_pcloud_browse_tab))
            }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState())) {
            folders.forEach { f ->
                InputChip(
                    selected = false,
                    onClick = { PcloudVideos.setFolders(folders - f) },
                    label = { Text(PcloudVideos.nameOf(f), maxLines = 1) },
                    leadingIcon = { Icon(Icons.Outlined.Folder, null, Modifier.size(16.dp)) },
                    trailingIcon = { Icon(Icons.Outlined.Close, null, Modifier.size(16.dp)) },
                )
            }
        }
        when {
            searching -> Loading()
            searched == null -> OutlinedButton(onClick = onSearch, modifier = Modifier.padding(top = 8.dp)) {
                Icon(Icons.Outlined.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.video_pcloud_search_go))
            }
            else -> HitList(searched, current, onPick)
        }
    }
}

/** Folder by folder from the top of your pCloud: folders first, then the video files to pick. */
@Composable
private fun PcloudBrowser(folders: List<String>, current: VideoRef?, onPick: (VideoHit) -> Unit) {
    val root = remember { PcloudVideos.serverRoot() }
    val trail = remember { mutableStateListOf<String>() }
    // Opens on your first video folder, when there is one.
    var at by remember { mutableStateOf(folders.firstOrNull() ?: root) }
    var listing by remember { mutableStateOf<Pair<List<WebDavClient.Entry>, List<PcloudVideos.Video>>?>(null) }
    LaunchedEffect(at) {
        val dir = at ?: return@LaunchedEffect
        listing = null
        listing = PcloudVideos.list(dir)
    }
    val here = at
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            IconButton(
                enabled = here != null && here != root,
                onClick = {
                    at = trail.removeLastOrNull() ?: here?.trimEnd('/')?.substringBeforeLast('/')?.plus("/")?.takeIf { root == null || it.length >= root.length } ?: root
                },
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null) }
            Text(
                here?.takeIf { it != root }?.let(PcloudVideos::nameOf) ?: "pCloud",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (here != null && here != root) {
                val inList = folders.any { it.trimEnd('/') == here.trimEnd('/') }
                TextButton(onClick = {
                    PcloudVideos.setFolders(if (inList) folders.filterNot { it.trimEnd('/') == here.trimEnd('/') } else folders + here)
                }) {
                    Icon(if (inList) Icons.Outlined.CheckCircle else Icons.Outlined.CreateNewFolder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (inList) R.string.video_pcloud_is_video_folder else R.string.video_pcloud_add_here))
                }
            }
        }
        val l = listing
        when {
            l == null -> Loading()
            l.first.isEmpty() && l.second.isEmpty() -> Empty(stringResource(R.string.video_pcloud_empty_folder))
            else -> LazyColumn(Modifier.fillMaxWidth()) {
                items(l.first, key = { it.url }) { e ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                here?.let { trail.add(it) }
                                at = e.url
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                    ) {
                        Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(PcloudVideos.nameOf(e.url), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                items(l.second, key = { it.url }) { v ->
                    val hit = v.toHit()
                    HitRow(hit, hit.ref == current, onPick)
                }
            }
        }
    }
}

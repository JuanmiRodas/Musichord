package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import com.music.bitchord.R
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.PreferredSource
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.playback.revertToOriginal
import com.music.bitchord.playback.swapToVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

/** BitChord Audio: raised from the player's source line. */
object NowPlayingSource {
    private val _song = MutableStateFlow<Song?>(null)
    val song: StateFlow<Song?> = _song.asStateFlow()
    fun open(song: Song) { _song.value = song }
    fun close() { _song.value = null }
}

/**
 * BitChord Audio: where the playing song comes from, and the other copies it
 * could come from — your matching files on pCloud, YouTube, the rule in
 * Settings — with a way to pick any file by browsing pCloud. The pick is
 * switched to straight away (aligned and cross-faded) and remembered for that
 * song wherever it is played from ([PreferredSource]).
 */
@Composable
fun NowPlayingSourceDialog(song: Song, controller: MediaController?, onBrowse: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var files by remember(song.videoId) { mutableStateOf<List<Song>?>(null) }
    LaunchedEffect(song.videoId) {
        files = withContext(Dispatchers.Default) { PreferredSource.filesFor(song) }
    }
    val choice = remember(song.videoId) { PreferredSource.get(song) }
    val playingFrom = NerdStats.sourceFor(song.videoId)
        ?: if (WebDavConfig.isWebDavId(song.videoId)) "pCloud" else "YouTube"
    val isFile = WebDavConfig.isWebDavId(song.videoId) || song.localUri != null

    // BitChord Audio: the rest of the album from the same source, when ticked.
    var wholeAlbum by remember(song.videoId) { mutableStateOf(true) }
    var albumMates by remember(song.videoId) { mutableStateOf<List<Song>>(emptyList()) }
    LaunchedEffect(song.videoId) {
        albumMates = withContext(Dispatchers.Default) {
            runCatching { com.music.bitchord.data.sources.AlbumSources.albumMates(song) }.getOrDefault(emptyList())
        }
    }
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    fun alsoAlbum(picked: Song) {
        if (!wholeAlbum) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val mates = com.music.bitchord.data.sources.AlbumSources.albumMates(song, picked)
            if (mates.isEmpty()) return@launch
            val n = runCatching { com.music.bitchord.data.sources.AlbumSources.apply(song, picked, mates) }.getOrDefault(0)
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    appContext,
                    appContext.getString(R.string.source_album_done, n, mates.size),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    fun switchTo(file: Song) {
        PreferredSource.chooseFile(song, file)
        alsoAlbum(file)
        if (file.videoId != song.videoId) controller?.swapToVersion(file.carryingQueueOf(song))
        onDismiss()
    }

    // Search box: starts with the song's own name, and searches again as you type.
    var query by remember(song.videoId) { mutableStateOf(searchName(song)) }
    var searched by remember(song.videoId) { mutableStateOf<List<Pair<String, Song>>?>(null) }
    LaunchedEffect(query) {
        searched = null
        kotlinx.coroutines.delay(500)
        searched = withContext(Dispatchers.IO) { searchEverywhere(query, song) }
    }

    fun pick(hit: Song) {
        PreferredSource.chooseSong(song, hit)
        alsoAlbum(hit)
        if (hit.videoId != song.videoId) controller?.swapToVersion(hit.carryingQueueOf(song))
        onDismiss()
    }

    fun switchToYouTube() {
        if (!isFile) {
            PreferredSource.chooseYouTube(song, song.videoId)
            alsoAlbum(song)
            controller?.revertToOriginal()
            onDismiss()
            return
        }
        // Playing a file: YouTube's copy is found by name first.
        scope.launch {
            val yt = withContext(Dispatchers.IO) { youTubeCopyOf(song) }
            PreferredSource.chooseYouTube(song, yt?.videoId)
            if (yt != null) alsoAlbum(yt)
            if (yt != null) controller?.swapToVersion(yt.carryingQueueOf(song))
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.source_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "${song.title} · ${song.artist}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.source_playing_now, playingFrom),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                // Why a song you might own still plays from YouTube.
                if (!isFile) {
                    val why = remember(song.videoId, files) {
                        val seconds = song.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
                            ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }
                        when {
                            com.music.bitchord.data.settings.AppSettings.meteredConnection.value == null -> "offline"
                            !com.music.bitchord.data.settings.AppSettings.ownFilesAllowedNow ->
                                "mobile data — your files are set to play on Wi-Fi only (Settings)"
                            else -> when (val v = com.music.bitchord.data.sources.OwnFileMatcher.decide(song.title, song.artist, seconds, song.albumName, song.videoId)) {
                                is com.music.bitchord.data.sources.OwnFileMatcher.Keep -> v.reason
                                is com.music.bitchord.data.sources.OwnFileMatcher.Play -> "your file will play: ${v.file.title}"
                            }
                        }
                    }
                    Text(
                        "pCloud: $why",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (albumMates.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().clickable { wholeAlbum = !wholeAlbum }.padding(vertical = 2.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(checked = wholeAlbum, onCheckedChange = null)
                        Text(
                            stringResource(R.string.source_album_too, albumMates.size),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                SourceRow(
                    label = stringResource(R.string.source_auto),
                    detail = stringResource(R.string.source_auto_detail),
                    selected = choice == null,
                ) {
                    PreferredSource.clear(song)
                    onDismiss()
                }
                when (val list = files) {
                    null -> Text(
                        stringResource(R.string.browser_loading),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    else -> {
                        if (list.isEmpty()) {
                            Text(
                                stringResource(R.string.source_no_files),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        list.forEach { f ->
                            SourceRow(
                                label = "pCloud · " + fileLabel(f),
                                detail = listOfNotNull(qualityText(f, song.durationText?.let(::secondsIn)), f.durationText, folderOf(f)).joinToString(" · "),
                                selected = (choice as? PreferredSource.File)?.id == f.videoId ||
                                    (choice == null && f.videoId == song.videoId),
                            ) { switchTo(f) }
                        }
                    }
                }
                SourceRow(
                    label = "YouTube Music",
                    detail = "",
                    selected = choice == PreferredSource.YouTube,
                ) { switchToYouTube() }
                TextButton(onClick = onBrowse, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.source_browse_pcloud))
                }
                // Every source, searched with the song's name — or your own words.
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.source_search)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                when (val found = searched) {
                    null -> Text(
                        stringResource(R.string.browser_loading),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    else -> {
                        if (found.isEmpty()) {
                            Text(
                                stringResource(R.string.source_search_none),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        found.forEach { (place, hit) ->
                            SourceRow(
                                label = "$place · ${hit.title} · ${hit.artist}",
                                detail = listOfNotNull(
                                    if (WebDavConfig.isWebDavId(hit.videoId)) qualityText(hit, hit.durationText?.let(::secondsIn)) else hit.sourceQuality,
                                    hit.albumName,
                                    hit.durationText,
                                ).joinToString(" · "),
                                selected = (choice as? PreferredSource.Chosen)?.song?.videoId == hit.videoId,
                            ) { pick(hit) }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** [this] in [other]'s place in the queue. */
internal fun Song.carryingQueueOf(other: Song): Song = also {
    com.music.bitchord.data.LocalLikes.link(it, other)
}.copy(
    queueTier = other.queueTier,
    queueEntryId = other.queueEntryId,
    radioName = other.radioName,
    playbackSource = other.playbackSource,
    playbackSourceType = other.playbackSourceType,
    playbackSourceId = other.playbackSourceId,
)

private val BRACKETED = Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]""")

private fun searchName(song: Song): String {
    val title = song.title.replace(BRACKETED, " ").trim()
    return "$title ${song.artist}".trim()
}

/**
 * [query] on every place a song can play from: your pCloud files (every word
 * in title, artist, album or file name, or how a Japanese title sounds),
 * each other source set up — JioSaavn, addons — whether or not it is switched
 * on for playback, and YouTube Music. Best matches for [song] first within
 * each place.
 */
private suspend fun searchEverywhere(query: String, song: Song): List<Pair<String, Song>> = kotlinx.coroutines.coroutineScope {
    val q = query.trim()
    if (q.isEmpty()) return@coroutineScope emptyList()
    val target = TrackMatcher.Target(
        title = song.title,
        artist = song.artist,
        album = song.albumName,
        durationSec = song.durationText?.let(::secondsIn),
    )
    fun ranked(list: List<Song>): List<Song> {
        val best = TrackMatcher.ranked(list, target)
        return (best + list).distinctBy { it.videoId }
    }
    val pcloud = async {
        val words = norm(q).split(' ').filter { it.isNotBlank() }
        val sound = com.music.bitchord.data.sources.JapaneseReading.romaji(q)
            ?.let(com.music.bitchord.data.sources.JapaneseReading::soundKey)?.takeIf { it.length >= 4 }
        com.music.bitchord.data.webdav.WebDavRepository.library().filter { f ->
            val hay = norm("${f.title} ${f.artist} ${f.albumName.orEmpty()} ${fileLabel(f)}")
            words.all { it in hay } ||
                (sound != null && com.music.bitchord.data.sources.OwnFileMatcher.soundKeys(f.title).any { it == "~$sound" })
        }.take(40).let(::ranked).take(12).map { "pCloud" to it }
    }
    val others = com.music.bitchord.data.sources.SourceRegistry.configs.value
        .mapNotNull { com.music.bitchord.data.sources.SourceRegistry.instance(it.id) }
        .filter { it.kind != com.music.bitchord.data.sources.SourceKind.MY_LIBRARY && it.kind != com.music.bitchord.data.sources.SourceKind.YOUTUBE }
        .map { source ->
            async {
                kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    runCatching { source.search(q, limit = 8, waitForAll = false, request = null) }.getOrNull()
                }.orEmpty().let(::ranked).take(6).map { source.displayName to it }
            }
        }
    val youtube = async {
        kotlinx.coroutines.withTimeoutOrNull(10_000) {
            YtMusicRepository.search(q, SearchFilter.SONGS).getOrNull()
        }.orEmpty().mapNotNull {
            when (it) {
                is SearchResult.Track -> it.song
                is SearchResult.TopTrack -> it.song
                else -> null
            }
        }.let(::ranked).take(6).map { "YouTube" to it }
    }
    pcloud.await() + others.flatMap { it.await() } + youtube.await()
}

private fun norm(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

private suspend fun youTubeCopyOf(song: Song): Song? {
    val results = YtMusicRepository.search("${song.title} ${song.artist}", SearchFilter.SONGS).getOrNull().orEmpty()
        .mapNotNull {
            when (it) {
                is SearchResult.Track -> it.song
                is SearchResult.TopTrack -> it.song
                else -> null
            }
        }
        .filterNot { it.isVideo }
    val target = TrackMatcher.Target(title = song.title, artist = song.artist, album = song.albumName)
    return TrackMatcher.ranked(results, target).firstOrNull() ?: results.firstOrNull()
}

/** "MP3 320 kbps", "FLAC 16/44.1" — from the tags as last read. */
private fun secondsIn(text: String): Int? =
    text.split(':').mapNotNull { it.trim().toIntOrNull() }.fold(0) { acc, p -> acc * 60 + p }.takeIf { it > 0 }

private fun qualityText(f: Song, forSeconds: Int?): String? {
    val rank = com.music.bitchord.data.sources.OwnFileMatcher.qualityRank(f, f.durationText?.let(::secondsIn) ?: forSeconds)
    val codec = f.sourceQuality?.substringBefore(' ')?.takeIf { it.isNotBlank() }
    return when {
        rank >= 9_000 -> f.sourceQuality ?: "Lossless"
        rank > 0 -> listOfNotNull(codec, "$rank kbps").joinToString(" ")
        else -> f.sourceQuality
    }
}

private fun fileLabel(f: Song): String =
    WebDavConfig.fileUrlOf(f.videoId)?.let { url ->
        runCatching { URLDecoder.decode(url.substringAfterLast('/'), "UTF-8") }.getOrNull()
    } ?: f.title

private fun folderOf(f: Song): String? =
    WebDavConfig.fileUrlOf(f.videoId)?.let { url ->
        runCatching {
            URLDecoder.decode(url.substringBeforeLast('/').substringAfterLast('/'), "UTF-8")
        }.getOrNull()
    }

@Composable
private fun SourceRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

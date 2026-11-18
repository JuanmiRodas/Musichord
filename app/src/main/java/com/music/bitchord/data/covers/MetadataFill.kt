package com.music.bitchord.data.covers

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.music.bitchord.data.LikedMusicCache
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.edits.TagWriter
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceMap
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.data.webdav.WebDavRepository
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * BitChord Audio: files with their details filled in — title, artist, album and cover —
 * from the YouTube Music song they are: your downloads saved without tags (a JioSaavn
 * copy, a copy of a pCloud file), and your pCloud files that carry no album or artist,
 * when one of your liked songs is matched to them. Only what is missing; on Wi-Fi; a
 * few at a time while the app runs.
 */
object MetadataFill {

    private const val TAG = "BitChord"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    private var prefs: android.content.SharedPreferences? = null

    private fun wifi() = AppSettings.meteredConnection.value == false

    fun start(context: Context) {
        val app = context.applicationContext
        if (prefs == null) prefs = app.getSharedPreferences("bitchord_metadata_fill", Context.MODE_PRIVATE)
        if (job?.isActive == true) return
        job = scope.launch {
            delay(60_000)
            val liked = LikedMusicCache.likedSongs.value.associateBy { it.videoId }
            var downloads = 0
            for (m in Downloads.savedMetadataSongs()) {
                if (!wifi()) break
                if (prefs?.getBoolean("d:" + m.videoId, false) == true) continue
                val uri = m.localUri ?: continue
                // Only a download heard to be this song (see AudioCheck); the rest wait for it.
                if (!com.music.bitchord.playback.smart.AudioCheck.confirmed(m.videoId, "download:$uri")) continue
                val info = liked[m.videoId] ?: m
                if (runCatching { fillDownload(app, uri, info) }.getOrDefault(false)) downloads++
                prefs?.edit()?.putBoolean("d:" + m.videoId, true)?.apply()
            }
            var files = 0
            val library = WebDavRepository.library().associateBy { it.videoId }
            for ((songId, fileId) in SourceMap.fileLinks()) {
                if (!wifi() || files >= 25) break
                if (prefs?.getBoolean("p:$fileId", false) == true) continue
                val file = library[fileId] ?: continue
                val song = liked[songId] ?: continue
                if (!com.music.bitchord.playback.smart.AudioCheck.confirmed(songId, fileId)) continue
                val missing = file.albumName.isNullOrBlank() || file.artist.isBlank() ||
                    file.artist.equals("unknown", true) || file.artist.contains("unknown artist", true)
                if (missing && !song.albumName.isNullOrBlank()) {
                    val outcome = runCatching {
                        TagWriter.intoPcloud(app, fileId, TagWriter.Change(text = text(song, file), cover = cover(song)))
                    }.getOrNull()
                    TrackLog.d(TAG, "metadata: '${song.title}' into your pCloud file — $outcome")
                    if (outcome !is CoverWriter.Outcome.Failed) files++
                }
                prefs?.edit()?.putBoolean("p:$fileId", true)?.apply()
                delay(2_000)
            }
            if (downloads + files > 0) TrackLog.d(TAG, "metadata: filled $downloads downloads and $files pCloud files")
            fixUnnamed(app)
        }
    }

    private val _fixed = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Files of yours whose missing artist or album was written in this run. */
    val fixed: kotlinx.coroutines.flow.StateFlow<Int> = _fixed

    private val GENERIC = Regex("""(?i)^\s*(various( artists)?|varios( artistas)?|va|soundtracks?|ost|bandas? sonoras?|music|música|musica|compilations?|recopilatorios?|singles?|misc\.?|otros|others|downloads?|descargas)\s*$""")
    private fun usable(name: String?) = !name.isNullOrBlank() && WebDavRepository.named(name) && !GENERIC.matches(name) && !WebDavRepository.discFolder(name)

    /**
     * Your pCloud files whose tags say "Unknown artist" (or nothing): the artist worked
     * out from context and written into the file — the liked song it was heard to be,
     * else the artist the other files of its folder name, else its file name or artist
     * folder; and the album from its folder when that is missing too.
     */
    private suspend fun fixUnnamed(app: Context) {
        val library = WebDavRepository.library()
        if (library.isEmpty()) return
        val byId = library.associateBy { it.videoId }
        val liked = LikedMusicCache.likedSongs.value.associateBy { it.videoId }
        val heardAs = HashMap<String, Song>()
        SourceMap.fileLinks().forEach { (songId, fileId) ->
            if (com.music.bitchord.playback.smart.AudioCheck.confirmed(songId, fileId)) liked[songId]?.let { heardAs[fileId] = it }
        }
        fun dirOf(id: String) = WebDavConfig.fileUrlOf(id)?.substringBeforeLast('/')
        val byDir = library.groupBy { dirOf(it.videoId) }
        var done = 0
        for ((id, missing) in WebDavRepository.unnamedTags.entries.toList()) {
            if (!wifi() || done >= 40) break
            val file = byId[id] ?: continue
            val stamp = "u:$id:" + missing.sorted().joinToString()
            if (prefs?.getBoolean(stamp, false) == true) continue
            val heard = heardAs[id]
            val artist = if ("artist" in missing) {
                heard?.artist
                    ?: byDir[dirOf(id)].orEmpty()
                        .filter { it.videoId != id && WebDavRepository.unnamedTags[it.videoId]?.contains("artist") != true }
                        .groupingBy { it.artist }.eachCount().maxByOrNull { it.value }?.key
                    ?: file.artist
            } else null
            val album = if ("album" in missing) heard?.albumName ?: file.albumName else null
            val text = CoverTags.Text(
                artist = artist?.takeIf(::usable),
                albumArtist = artist?.takeIf(::usable)?.split(",", "&", " feat").orEmpty().firstOrNull()?.trim(),
                album = album?.takeIf(::usable),
            )
            prefs?.edit()?.putBoolean(stamp, true)?.apply()
            if (text.isEmpty) continue
            val outcome = runCatching {
                TagWriter.intoPcloud(app, id, TagWriter.Change(text = text, cover = heard?.let { cover(it) }))
            }.getOrNull()
            TrackLog.d(TAG, "metadata: '${file.title}' had no ${missing.joinToString(" or ")} — wrote ${text.artist ?: "-"} / ${text.album ?: "-"}: $outcome")
            if (outcome is CoverWriter.Outcome.Saved) {
                done++
                _fixed.value = _fixed.value + 1
                WebDavRepository.unnamedTags.remove(id)
            }
            delay(2_000)
        }
        if (done > 0) TrackLog.d(TAG, "metadata: $done files that named nobody now say who")
    }

    private fun text(song: Song, file: Song? = null) = CoverTags.Text(
        title = song.title.takeIf { file == null || file.title.isBlank() } ?: file?.title,
        artist = song.artist,
        album = song.albumName,
        albumArtist = song.artist.split(",", "&", " feat").first().trim(),
    )

    private suspend fun cover(song: Song): CoverWriter.Image? {
        val url = song.thumbnailUrl?.let { OfflineCovers.localFor(it)?.let { f -> Uri.fromFile(f).toString() } }
            ?: Downloads.savedArtwork(song.videoId)
            ?: song.thumbnailUrl?.replace(Regex("=w\\d+-h\\d+.*$"), "=w1200-h1200")
            ?: return null
        return runCatching { CoverWriter.fetch(url) }.getOrNull()
    }

    /** True when the download had no album (or title) and was filled. */
    private suspend fun fillDownload(context: Context, uri: String, song: Song): Boolean {
        val parsed = Uri.parse(uri)
        if (parsed.scheme != "file" && parsed.scheme != "content") return false
        val (album, title, picture) = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, parsed)
                Triple(
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    r.embeddedPicture != null,
                )
            } finally {
                r.release()
            }
        }.getOrNull() ?: return false
        if (!album.isNullOrBlank() && !title.isNullOrBlank() && picture) return false
        val outcome = TagWriter.intoDownload(
            context,
            uri,
            TagWriter.Change(text = text(song), cover = if (picture) null else cover(song)),
        )
        TrackLog.d(TAG, "metadata: '${song.title}' into its download — $outcome")
        return outcome is CoverWriter.Outcome.Saved
    }
}

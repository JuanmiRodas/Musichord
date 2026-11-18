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
            delay(180_000)
            val liked = LikedMusicCache.likedSongs.value.associateBy { it.videoId }
            var downloads = 0
            for (m in Downloads.savedMetadataSongs()) {
                if (!wifi()) break
                if (prefs?.getBoolean("d:" + m.videoId, false) == true) continue
                val uri = m.localUri ?: continue
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
        }
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

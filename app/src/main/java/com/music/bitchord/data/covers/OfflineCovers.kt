package com.music.bitchord.data.covers

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import com.music.bitchord.data.LikedMusicCache
import com.music.bitchord.data.PlaylistPageCache
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.edits.TagWriter
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: covers of downloaded songs, kept on the phone and used in
 * every list, so they show offline. Lists ask for a cover by its web address
 * (a different size per screen); [key] reduces any of those to the picture
 * itself, and [Interceptor] answers with the copy kept here when there is one —
 * online too, which is quicker and saves data.
 *
 * [sweep] fills them in for every downloaded song (from the cover kept beside
 * the download when there is one, else from the web), and writes the cover into
 * the downloaded file itself when the file has none.
 */
object OfflineCovers {

    private const val TAG = "BitChord"
    private var dir: File? = null
    private val have = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        val d = File(context.filesDir, "offline-art").apply { mkdirs() }
        dir = d
        prefs = context.getSharedPreferences("bitchord_offline_covers", Context.MODE_PRIVATE)
        scope.launch { d.list()?.forEach { have += it.removeSuffix(".jpg") } }
    }

    /** The picture behind [url], whatever size it was asked at. */
    fun key(url: String): String {
        val u = url.trim()
        Regex("""ytimg\.com/vi(?:_webp)?/([A-Za-z0-9_-]{6,})/""").find(u)?.let { return "yt:" + it.groupValues[1] }
        if ("googleusercontent.com" in u || "ggpht.com" in u) return u.substringBefore('=').substringBefore('?')
        return u
    }

    private fun name(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(key(url).toByteArray()).joinToString("") { "%02x".format(it) }.take(40)

    fun localFor(url: String): File? {
        if (!url.startsWith("http")) return null
        val n = name(url)
        if (n !in have) return null
        return dir?.let { File(it, "$n.jpg") }?.takeIf { it.isFile }
    }

    class CoilInterceptor : Interceptor {
        override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
            val url = chain.request.data.toString()
            val local = if (url.startsWith("http")) localFor(url) else null
            return if (local != null) {
                chain.withRequest(chain.request.newBuilder().data(local).build()).proceed()
            } else {
                chain.proceed()
            }
        }
    }

    /** Covers (and embedded covers) for every downloaded song; run again whenever the lists change. */
    fun sweep(context: Context, afterMs: Long = 15_000) {
        val app = context.applicationContext
        if (job?.isActive == true) return
        job = scope.launch {
            delay(afterMs)
            val saved = Downloads.saved.value
            if (saved.isEmpty()) return@launch
            val songs = (LikedMusicCache.likedSongs.value + runCatching { PlaylistPageCache.all() }.getOrDefault(emptyList()))
                .filter { it.videoId in saved }
                .distinctBy { it.videoId }
            var kept = 0
            var embedded = 0
            for (s in songs) {
                val url = s.artworkAt(544) ?: continue
                val cover = localFor(url) ?: runCatching { keep(s, url) }.getOrNull()?.also { kept++ } ?: continue
                if (runCatching { embed(app, s, saved[s.videoId], cover) }.getOrDefault(false)) embedded++
            }
            if (kept + embedded > 0) TrackLog.d(TAG, "covers: $kept kept for offline, $embedded written into downloads (of ${songs.size} downloaded songs)")
        }
    }

    private suspend fun keep(song: Song, url: String): File? {
        val d = dir ?: return null
        // The cover kept beside the download first: no network needed.
        val source = Downloads.savedArtwork(song.videoId)
            ?: if (AppSettings.meteredConnection.value == null) return null else (song.artworkAt(1200) ?: url)
        val image = CoverWriter.fetch(source) ?: return null
        val n = name(url)
        val f = File(d, "$n.jpg")
        f.writeBytes(image.bytes)
        have += n
        return f
    }

    /** The cover written into the downloaded file when it carries none. True when written. */
    private suspend fun embed(context: Context, song: Song, uri: String?, cover: File): Boolean {
        if (uri == null || song.isVideo) return false
        val p = prefs ?: return false
        if (p.getBoolean(song.videoId, false)) return false
        val parsed = Uri.parse(uri)
        if (parsed.scheme != "file" && parsed.scheme != "content") return false
        val hasOne = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, parsed)
                r.embeddedPicture != null
            } finally {
                r.release()
            }
        }.getOrDefault(true)
        if (hasOne) {
            p.edit().putBoolean(song.videoId, true).apply()
            return false
        }
        val image = CoverWriter.fetch(Uri.fromFile(cover).toString()) ?: return false
        val outcome = TagWriter.intoDownload(context, uri, TagWriter.Change(cover = image))
        if (outcome !is CoverWriter.Outcome.Failed) p.edit().putBoolean(song.videoId, true).apply()
        return outcome is CoverWriter.Outcome.Saved
    }
}

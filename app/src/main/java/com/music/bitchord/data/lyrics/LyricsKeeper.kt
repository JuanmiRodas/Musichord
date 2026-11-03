package com.music.bitchord.data.lyrics

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.LikedMusicCache
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.LibraryFolders
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BitChord Audio: lyrics found once are kept — so a song's lyrics are never
 * looked for (or a different source chosen) again.
 *
 *  - Timed lyrics shown for a song, found automatically or picked by you,
 *    are saved on the phone and in pCloud ([EditedLyrics], which the player
 *    reads before any lookup), and as a readable `Artist - Title.lrc` in your
 *    lyrics folder on pCloud — your first lyrics folder, else a `Lyrics`
 *    folder at the top of your pCloud (and added to your lyrics folders).
 *  - Every Liked Music song's lyrics are looked up in the background, on
 *    Wi-Fi, a song every few seconds, and kept the same way. Songs no source
 *    has are tried again after a month.
 */
object LyricsKeeper {

    private const val TAG = "BitChord"
    private const val PREFS = "bitchord_lyrics_keeper"
    private const val RETRY_AFTER_MS = 30L * 24 * 60 * 60 * 1000

    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /**
     * Keeps [lines] as [song]'s lyrics. [picked]: you chose them, so they
     * replace what was kept; otherwise lyrics already kept stay.
     */
    fun keep(song: Song, lines: List<LyricLine>, picked: Boolean = false) {
        if (lines.none { it.timeMs > 0 } || lines.count { it.text.isNotBlank() } < 2) return
        if (!picked && EditedLyrics.has(song.videoId)) return
        EditedLyrics.save(song, lines, besideFile = false, quiet = !picked)
        prefs?.edit()?.remove(song.videoId)?.apply()
        scope.launch { runCatching { writeToFolder(song, lines) } }
    }

    /** The lyrics folder on pCloud the readable copies go to; made the first time. */
    private suspend fun folder(): String? {
        LibraryFolders.lyricsFolders().firstOrNull { it.kind == LibraryFolders.Kind.LYRICS }?.let {
            return WebDavConfig.normalizeUrl(it.url).trimEnd('/') + "/"
        }
        val base = AppSettings.webdavUrl.value
        if (!WebDavConfig.isConfigured(base)) return null
        val url = WebDavConfig.normalizeUrl(base).trimEnd('/') + "/Lyrics/"
        WebDavClient.ensureCollection(url, AppSettings.webdavUsername.value, AppSettings.webdavPassword.value).getOrNull() ?: return null
        LibraryFolders.add("Lyrics", url, LibraryFolders.Kind.LYRICS)
        return url
    }

    private val UNSAFE = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    private suspend fun writeToFolder(song: Song, lines: List<LyricLine>) {
        val dir = folder() ?: return
        val artist = song.artist.split(',').first().trim()
        val name = "$artist - ${song.title}".replace(UNSAFE, " ").replace(Regex("\\s+"), " ").trim().take(150) + ".lrc"
        val text = SidecarLyrics.toLrc(lines, song.title, song.artist, 0).toByteArray(Charsets.UTF_8)
        val url = dir + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        WebDavClient.putFile(url, text.inputStream(), text.size.toLong(), "text/plain",
            AppSettings.webdavUsername.value, AppSettings.webdavPassword.value, overwrite = true)
    }

    @Volatile
    private var running = false

    /** Looks up the lyrics of every Liked Music song not kept yet, while on Wi-Fi. */
    suspend fun prefetchLiked() {
        if (running) return
        running = true
        try {
            val songs = LikedMusicCache.load(LikedMusicCache.lastProfile)?.songs.orEmpty()
            val now = System.currentTimeMillis()
            val todo = songs.filter { s ->
                !s.isVideo && !EditedLyrics.has(s.videoId) &&
                    (prefs?.getLong(s.videoId, 0L) ?: 0L).let { tried -> now - tried > RETRY_AFTER_MS }
            }
            if (todo.isEmpty()) return
            TrackLog.d(TAG, "lyrics: looking up ${todo.size} liked songs in the background")
            var found = 0
            for (s in todo) {
                if (AppSettings.meteredConnection.value != false) break
                val lines = withTimeoutOrNull(45_000) { runCatching { lookUp(s) }.getOrNull() }
                if (lines != null) {
                    keep(s, lines)
                    found++
                } else {
                    prefs?.edit()?.putLong(s.videoId, System.currentTimeMillis())?.apply()
                }
                delay(2_500)
            }
            TrackLog.d(TAG, "lyrics: kept $found of ${todo.size} looked up")
        } finally {
            running = false
        }
    }

    private suspend fun lookUp(s: Song): List<LyricLine>? {
        val seconds = s.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
            ?.fold(0) { acc, p -> acc * 60 + p } ?: return null
        if (seconds <= 0) return null
        val flagged = BadLyrics.flagged(s.videoId)
        val sources = AppSettings.lyricsSources.value.filterTo(LinkedHashSet()) { it.name !in flagged && !it.untimed }
        if (sources.isEmpty()) return null
        val spellings = runCatching { LyricNames.variants(s.title, s.artist) }.getOrDefault(emptyList())
        for (q in LyricAliases.queries(s.videoId, s.title, s.artist, spellings).take(3)) {
            val r = LyricsRepository.lyrics(
                s.videoId, q.title, q.artist, seconds * 1000L, s.albumName, sources,
                AppSettings.lyricsSourceOrder.value, false,
            ) ?: continue
            if (r.lines.any { it.timeMs > 0 }) return r.lines
        }
        return null
    }
}

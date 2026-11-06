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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.music.bitchord.data.covers.CoverSources
import com.music.bitchord.data.covers.CoverWriter
import com.music.bitchord.data.edits.TagWriter
import com.music.bitchord.download.Downloads

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
 *  - The lyrics are also written inside the song's own files (the LYRICS
 *    tag, any format, through TagLib): a download at once, its pCloud file
 *    on Wi-Fi — so any player, on any device, shows them. Kept lyrics from
 *    before are written the same way, in the background.
 */
object LyricsKeeper {

    private const val TAG = "BitChord"
    private const val PREFS = "bitchord_lyrics_keeper"
    private const val RETRY_AFTER_MS = 30L * 24 * 60 * 60 * 1000

    private var prefs: SharedPreferences? = null
    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val embedLock = Mutex()

    private val _embed = MutableStateFlow(true)

    /** Whether lyrics are written inside the song files too. */
    val embed: StateFlow<Boolean> = _embed.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        app = context.applicationContext
        _embed.value = p.getBoolean(KEY_EMBED, true)
    }

    fun setEmbed(on: Boolean) {
        _embed.value = on
        prefs?.edit()?.putBoolean(KEY_EMBED, on)?.apply()
    }

    private const val KEY_EMBED = "embed_in_files"
    private const val EMBEDDED = "embedded:"

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
        if (picked) forgetEmbedded(song)
        scope.launch { runCatching { embedInFiles(song, lines) } }
    }

    private fun forgetEmbedded(song: Song) {
        val e = prefs?.edit() ?: return
        e.remove(EMBEDDED + "d:" + song.videoId)
        CoverSources.filesOf(song).forEach { e.remove(EMBEDDED + "p:" + it.videoId) }
        e.apply()
    }

    /**
     * [lines] written into [song]'s download (at once) and its pCloud file
     * (on Wi-Fi only: the whole file is fetched and sent back). Each file
     * once; choosing other lyrics writes them again.
     */
    private suspend fun embedInFiles(song: Song, lines: List<LyricLine>, wifiOnlyAll: Boolean = false) {
        val ctx = app ?: return
        if (!_embed.value || song.isVideo) return
        val lrc = SidecarLyrics.toLrc(lines, null, null, 0).trim()
        if (lrc.isBlank()) return
        embedLock.withLock {
            val p = prefs ?: return
            val change = TagWriter.Change(lyrics = lrc)
            val download = song.localUri ?: Downloads.verifiedSavedUri(song.videoId)
            if (download != null && !p.contains(EMBEDDED + "d:" + song.videoId) &&
                (!wifiOnlyAll || AppSettings.meteredConnection.value == false)
            ) {
                val o = TagWriter.intoDownload(ctx, download, change)
                if (o !is CoverWriter.Outcome.Failed) p.edit().putBoolean(EMBEDDED + "d:" + song.videoId, true).apply()
                TrackLog.d(TAG, "lyrics into the download of ${song.title}: $o")
            }
            if (AppSettings.meteredConnection.value != false) return
            CoverSources.filesOf(song).take(1).forEach { f ->
                if (p.contains(EMBEDDED + "p:" + f.videoId)) return@forEach
                val o = TagWriter.intoPcloud(ctx, f.videoId, change)
                // Skipped (a format that holds no tags, not a pCloud file): don't try again either.
                if (o !is CoverWriter.Outcome.Failed) p.edit().putBoolean(EMBEDDED + "p:" + f.videoId, true).apply()
                TrackLog.d(TAG, "lyrics into the pCloud file of ${song.title}: $o")
            }
        }
    }

    /** Lyrics kept before (or while on mobile data) written into the liked songs' files. */
    private suspend fun embedKept(songs: List<Song>) {
        if (!_embed.value) return
        val p = prefs ?: return
        var done = 0
        for (s in songs) {
            if (AppSettings.meteredConnection.value != false || !_embed.value) break
            if (s.isVideo || !EditedLyrics.has(s.videoId)) continue
            val hasDownload = (s.localUri ?: Downloads.verifiedSavedUri(s.videoId)) != null
            val files = CoverSources.filesOf(s).take(1)
            val pending = (hasDownload && !p.contains(EMBEDDED + "d:" + s.videoId)) ||
                files.any { !p.contains(EMBEDDED + "p:" + it.videoId) }
            if (!pending) continue
            val lines = EditedLyrics.get(s.videoId) ?: continue
            if (lines.none { it.timeMs > 0 }) continue
            runCatching { embedInFiles(s, lines, wifiOnlyAll = true) }
            done++
            delay(1_500)
        }
        if (done > 0) TrackLog.d(TAG, "lyrics: written into the files of $done songs")
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
            if (todo.isEmpty()) {
                embedKept(songs)
                return
            }
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
            embedKept(songs)
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

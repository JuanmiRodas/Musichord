package com.music.bitchord.data.lyrics

import android.content.Context
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceMap
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * BitChord Audio: lyrics you timed in the player's editor.
 *
 * Kept on the phone and in pCloud (`.bitchord-audio/lyrics/`), and — for a
 * song that is one of your files — written as the .lrc beside the file, so
 * LyricsX and the next install read the same timing. They come before every
 * other source for that song.
 */
object EditedLyrics {
    private const val TAG = "BitChord"
    private const val REMOTE_DIR = ".bitchord-audio/lyrics/"

    private var dir: File? = null
    private val WORD_STAMP = Regex("""<\d{1,2}:\d{2}""")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _version = MutableStateFlow(0)

    /** Bumped on a save, so the playing song's lyrics are read again. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(context: Context) {
        dir = File(context.filesDir, "edited_lyrics").apply { mkdirs() }
    }

    private fun name(videoId: String) = videoId.hashCode().toUInt().toString(16) + "_" +
        videoId.filter { it.isLetterOrDigit() }.takeLast(24) + ".lrc"

    private fun remoteDir(): String? {
        val base = AppSettings.webdavUrl.value
        if (!WebDavConfig.isConfigured(base)) return null
        return WebDavConfig.normalizeUrl(base).trimEnd('/') + "/" + REMOTE_DIR
    }

    /** The timed lyrics saved for [videoId]: on the phone, else from pCloud. */
    private val misses: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    suspend fun get(videoId: String): List<LyricLine>? {
        val f = dir?.let { File(it, name(videoId)) }
        val text = f?.takeIf { it.isFile }?.readText()
            ?: if (videoId in misses) null else remoteDir()?.let { d ->
                WebDavClient.readText(d + name(videoId), AppSettings.webdavUsername.value, AppSettings.webdavPassword.value, 512 * 1024)
                    .getOrNull()?.takeIf { it.isNotBlank() }?.also { runCatching { f?.writeText(it) } }
            }
        if (text == null) {
            misses += videoId
            return null
        }
        val lines = if (WORD_STAMP.containsMatchIn(text)) EnhancedLrc.parse(text) else LrcLib.parseLrc(text)
        return lines.takeIf { it.isNotEmpty() }
    }

    /** Whether lyrics were timed or picked for [videoId] on this phone. */
    fun has(videoId: String): Boolean = dir?.let { File(it, name(videoId)).isFile } == true

    /** BitChord Audio: when the lyrics for [videoId] were last saved on this phone (0: unknown). */
    fun savedAt(videoId: String): Long = dir?.let { File(it, name(videoId)) }?.takeIf { it.isFile }?.lastModified() ?: 0L

    /** Back to the automatic lookup for [videoId] (here and in pCloud). */
    fun clear(videoId: String) {
        dir?.let { File(it, name(videoId)).delete() }
        misses += videoId
        _version.value += 1
        scope.launch {
            remoteDir()?.let { d ->
                // Emptied rather than deleted: an empty file reads as none.
                runCatching {
                    WebDavClient.putFile(d + name(videoId), ByteArray(0).inputStream(), 0L, "text/plain",
                        AppSettings.webdavUsername.value, AppSettings.webdavPassword.value, overwrite = true)
                }
            }
        }
    }

    /** Lyrics picked from the search: kept for the song, the .lrc beside your file left alone. */
    fun choose(song: Song, lines: List<LyricLine>) = save(song, lines, besideFile = false)

    /**
     * [quiet]: kept without disturbing anything — the song's lyric shift is
     * left as it is and the player isn't asked to read them again (lyrics
     * fetched once and kept, see [LyricsKeeper]).
     */
    fun save(song: Song, lines: List<LyricLine>, besideFile: Boolean = true, quiet: Boolean = false) {
        // Word timings kept (enhanced LRC) in the app's copy; the .lrc beside
        // your file stays plain LRC, which every player reads.
        val plain = SidecarLyrics.toLrc(lines, song.title, song.artist, 0)
        val text = if (lines.any { it.words.isNotEmpty() }) lines.toEnhancedLrc() else plain
        runCatching { dir?.let { File(it, name(song.videoId)).writeText(text) } }
        misses -= song.videoId
        if (!quiet) {
            LyricOffsets.reset(song.videoId)
            _version.value += 1
        }
        scope.launch {
            val u = AppSettings.webdavUsername.value
            val p = AppSettings.webdavPassword.value
            val bytes = text.toByteArray(Charsets.UTF_8)
            val plainBytes = plain.toByteArray(Charsets.UTF_8)
            runCatching {
                remoteDir()?.let { d ->
                    WebDavClient.ensureCollection(d, u, p)
                    WebDavClient.putFile(d + name(song.videoId), bytes.inputStream(), bytes.size.toLong(), "text/plain", u, p, overwrite = true)
                }
                // Beside your file too, as its .lrc.
                if (!besideFile) return@runCatching
                val file = if (WebDavConfig.isWebDavId(song.videoId)) song.videoId else SourceMap.lookup(song.videoId)?.file
                file?.let { SidecarLyrics.besideUrl(it) }?.let { url ->
                    WebDavClient.putFile(url, plainBytes.inputStream(), plainBytes.size.toLong(), "text/plain", u, p, overwrite = true).getOrThrow()
                    SidecarLyrics.wroteBeside(file, url)
                    TrackLog.d(TAG, "timed lyrics written to ${url.substringAfterLast('/')}")
                }
            }.onFailure { TrackLog.w(TAG, "timed lyrics: couldn't save to pCloud: ${it.message}") }
        }
    }
}

package com.music.bitchord.data.lyrics

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: your own lyrics files, used before any lyrics service.
 *
 * Two ways a file belongs to a track:
 *
 *  1. **Beside it, same name** — `Song.flac` and `Song.lrc` in one folder, the
 *     way desktop players pair them. Phone files by path, pCloud/WebDAV files
 *     by URL ([registerRemote]).
 *  2. **By name, from a lyrics folder** ([index]) — any enabled folder marked
 *     for lyrics, searched by the song's title and artist. Collections named
 *     loosely are the norm here: `Artist - Title`, `Title - Artist`, extras
 *     like "(Official Video)", the artist in another script. So every pairing
 *     of a file name's parts is indexed both ways round, and a title alone
 *     answers when exactly one file carries it. The song's other names
 *     ([LyricAliases]: romaji, the original title) are tried too. The same
 *     song in two folders is one entry; the first folder, then a synced file
 *     over a plain one, wins.
 *
 * Read: `.lrc`, LyricsX's `.lrcx` (its translation and word-timing lines are
 * dropped — the app has its own translation), and plain `.txt` lyrics, shown
 * unsynced. UTF-8 and UTF-16 (with a byte-order mark), the encodings these
 * tools write.
 */
object SidecarLyrics {

    private const val MAX_BYTES = 1024 * 1024

    val EXTENSIONS = setOf("lrc", "lrcx", "txt")

    /** Remote track id → the `.lrc` beside it, from the latest library listing. */
    private val remote = ConcurrentHashMap<String, String>()

    /** Remote track ids known to have no file beside them, so they aren't asked again. */
    private val remoteMisses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Name key → lyrics file URL, from the lyrics folders. */
    @Volatile
    private var byPair: Map<String, String> = emptyMap()

    /** Title key → lyrics file URLs carrying that title. */
    @Volatile
    private var byTitle: Map<String, List<String>> = emptyMap()

    fun registerRemote(songId: String, lrcUrl: String) {
        remote[songId] = lrcUrl
        remoteMisses -= songId
    }

    /** Whether the lyrics folders have been indexed — Settings shows the count. */
    val indexedCount: Int get() = byTitle.values.sumOf { it.size }

    /**
     * Rebuilds the name index from these lyrics files, in folder order. Synced
     * formats are taken before plain text for the same name.
     */
    fun index(files: List<String>) {
        val pairs = HashMap<String, String>()
        val titles = HashMap<String, MutableList<String>>()
        val ordered = files.sortedBy { url -> if (url.endsWith(".txt", ignoreCase = true)) 1 else 0 }
        for (url in ordered) {
            val parts = nameParts(url)
            if (parts.isEmpty()) continue
            if (parts.size == 1) {
                titles.getOrPut(parts[0]) { mutableListOf() }.let { if (url !in it) it += url }
                continue
            }
            for (i in parts.indices) for (j in parts.indices) {
                if (i != j) pairs.putIfAbsent(parts[i] + "|" + parts[j], url)
            }
            parts.forEach { p -> titles.getOrPut(p) { mutableListOf() }.let { if (url !in it) it += url } }
        }
        byPair = pairs
        byTitle = titles
    }

    /**
     * Lyrics for a track: the file beside it, else a lyrics folder's file for
     * one of [titles] with one of [artists] (the first of each being the
     * names shown).
     */
    suspend fun forSong(
        context: Context,
        videoId: String,
        localUri: String?,
        localPath: String?,
        titles: List<String> = emptyList(),
        artists: List<String> = emptyList(),
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val beside = when {
            WebDavConfig.isWebDavId(videoId) -> remoteBeside(videoId)
            else -> localText(context, localUri, localPath)
        }
        val raw = beside ?: fromFolders(titles, artists) ?: return@withContext null
        parse(raw)
    }

    private suspend fun fromFolders(titles: List<String>, artists: List<String>): String? {
        if (byTitle.isEmpty()) return null
        // Each title as written and without its version note — "(Live)",
        // "[Remix]" — since lyrics are usually filed under the plain title.
        val ts = titles.flatMap { listOf(key(it), key(it.replace(QUALIFIER, " "))) }
            .filter { it.isNotEmpty() }.distinct()
        val ars = artists.flatMap(::artistKeys).distinct()
        ts.firstNotNullOfOrNull { t -> ars.firstNotNullOfOrNull { a -> byPair["$t|$a"] } }
            ?.let { url -> readRemote(url)?.let { return it } }
        // A title alone, where only one file carries it. The file's name can't
        // say which part is the title, so its own [ti:] header has to agree
        // when it has one: "Barairo no Sekai - Pierrot" isn't a song "Pierrot".
        for (t in ts) {
            val url = byTitle[t]?.singleOrNull() ?: continue
            val text = readRemote(url) ?: continue
            val declared = TITLE_HEADER.find(text)?.groupValues?.get(1)
            if (declared != null) {
                val d = setOf(key(declared), key(declared.replace(QUALIFIER, " ")))
                if (t !in d) continue
            }
            return text
        }
        return null
    }

    private val QUALIFIER = Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]\s*""")
    private val TITLE_HEADER = Regex("""\[ti:([^\]]*)]""", RegexOption.IGNORE_CASE)

    // ── Reading ──────────────────────────────────────────────────────────

    private suspend fun remoteBeside(videoId: String): String? {
        if (videoId in remoteMisses) return null
        val fileUrl = WebDavConfig.fileUrlOf(videoId) ?: return null
        val lrcUrl = remote[videoId] ?: (fileUrl.substringBeforeLast('.') + ".lrc")
        val text = readRemote(lrcUrl)
        if (text == null) remoteMisses += videoId
        return text
    }

    private suspend fun readRemote(url: String): String? =
        WebDavClient.readHead(url, AppSettings.webdavUsername.value, AppSettings.webdavPassword.value, MAX_BYTES)
            .getOrNull()?.let(::decode)?.takeIf { it.isNotBlank() }

    private fun localText(context: Context, localUri: String?, localPath: String?): String? {
        val audio = localPath?.let(::File)?.takeIf { it.isFile }
            ?: localUri?.let { pathOf(context, it) }?.let(::File)?.takeIf { it.isFile }
            ?: return null
        val base = audio.name.substringBeforeLast('.')
        val dir = audio.parentFile ?: return null
        val file = listOf("lrc", "lrcx").firstNotNullOfOrNull { ext ->
            File(dir, "$base.$ext").takeIf { it.isFile }
                ?: dir.listFiles { f -> f.isFile && f.name.equals("$base.$ext", ignoreCase = true) }?.firstOrNull()
        } ?: return null
        if (file.length() !in 1..MAX_BYTES.toLong()) return null
        return runCatching { decode(file.readBytes()) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** A `file://` path, or the file behind a MediaStore `content://` id. */
    private fun pathOf(context: Context, uriString: String): String? = runCatching {
        val uri = Uri.parse(uriString)
        when (uri.scheme) {
            "file" -> uri.path
            "content" -> context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            else -> null
        }
    }.getOrNull()

    /** Text by its byte-order mark: UTF-16 LE/BE or UTF-8; UTF-8 without one. */
    internal fun decode(bytes: ByteArray): String {
        fun b(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xff)
        return when {
            b(0) == 0xFF && b(1) == 0xFE -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            b(0) == 0xFE && b(1) == 0xFF -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            // UTF-16 without a mark still shows itself: every other byte NUL.
            bytes.size > 8 && b(1) == 0 && b(3) == 0 && b(0) != 0 -> String(bytes, Charsets.UTF_16LE)
            else -> String(bytes, Charset.forName("UTF-8"))
        }
    }

    /**
     * Lines out of an LRC, LRCX or plain-text file. LRCX translation and
     * word-timing lines are left out; a file with no timestamps is plain
     * lyrics, shown unsynced, minus its `Artist:`/`Title:` header.
     */
    internal fun parse(raw: String): List<LyricLine>? {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val cleaned = text.lineSequence()
            .filterNot { LRCX_EXTRA.containsMatchIn(it) }
            .joinToString("\n")
        val synced = LrcLib.parseLrc(cleaned)
        if (synced.any { it.text.isNotBlank() }) return synced.withBackgroundVocals()
        val plain = text.lineSequence()
            .map { it.trim() }
            .dropWhile { it.isEmpty() || HEADER.matches(it) || TAG_LINE.matches(it) }
            .filterNot { TAG_LINE.matches(it) }
            .toList()
            .dropLastWhile { it.isEmpty() }
        if (plain.none { it.isNotEmpty() }) return null
        return plain.map { LyricLine(timeMs = 0L, text = it) }
    }

    private val LRCX_EXTRA = Regex("""^\s*\[\d+:\d+(?:[.:]\d+)?]\s*\[(?:tr|tt)(?::[^\]]*)?]""")
    private val HEADER = Regex("""^(artist|title|album|by)\s*:.*$""", RegexOption.IGNORE_CASE)
    private val TAG_LINE = Regex("""^\[[a-zA-Z]+:[^\]]*]$""")

    // ── Names ────────────────────────────────────────────────────────────

    /** A lyrics file's name, split into its keyed parts, with video and track noise taken off. */
    private fun nameParts(url: String): List<String> {
        val name = runCatching { URLDecoder.decode(url.substringAfterLast('/'), "UTF-8") }
            .getOrDefault(url.substringAfterLast('/'))
            .substringBeforeLast('.')
        val stripped = name.replace(NOISE, " ").replace(TRACK_PREFIX, "")
        return stripped.split(" - ")
            .flatMap { listOf(key(it), key(it.replace(QUALIFIER, " "))) }
            .filter { it.isNotEmpty() }.distinct()
    }

    private val NOISE = Regex(
        """[(\[]\s*(official|music video|lyric|lyrics|audio|video|mv|pv|hd|hq|4k)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val TRACK_PREFIX = Regex("""^\s*\d{1,3}\s*[.\-_)]\s*""")

    /** Letters and digits only, any script, compatibility-folded: "E.G.O.（LIVE#7）" → "egolive7". */
    internal fun key(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC)
            .replace(NOISE, " ")
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }

    /** The artist as written, and each name in a credit like "A feat. B" or "A & B". */
    private fun artistKeys(artist: String): List<String> {
        val whole = key(artist)
        val split = artist.split(ARTIST_SPLIT).map(::key).filter { it.isNotEmpty() }
        return (listOf(whole) + split).filter { it.isNotEmpty() }.distinct()
    }

    private val ARTIST_SPLIT = Regex("""\s*(?:,|&|/|;|\bfeat\.?|\bft\.?|\bx\b|、)\s*""", RegexOption.IGNORE_CASE)
}

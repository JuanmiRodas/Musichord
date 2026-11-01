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
        // BitChord Audio: a file's [offset:] is the song's starting shift (see
        // LyricOffsets), applied once on screen rather than baked in here.
        offsetOf(raw)?.let { fileOffsets[videoId] = it } ?: fileOffsets.remove(videoId)
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
            else -> utf8OrLegacy(bytes)
        }
    }

    /**
     * BitChord Audio: UTF-8 when the bytes are UTF-8; otherwise the older
     * encoding they make sense in. About one lyrics file in twelve is in one
     * (GBK from Chinese lyric sites — Japanese songs included — Shift-JIS,
     * Big5, Windows-1252), and read as UTF-8 they came out garbled.
     *
     * Western text has its accented bytes one at a time between letters; the
     * Asian encodings use them in pairs. So a file whose high bytes stand
     * alone is Windows-1252 ("won’t" saved there would otherwise decode as
     * Shift-JIS "won稚"); otherwise each Asian encoding is tried and the one
     * whose text looks most like text — kana, common kanji, Hangul, no
     * half-width katakana or replacement marks — is kept.
     */
    private fun utf8OrLegacy(bytes: ByteArray): String {
        runCatching {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
        var high = 0
        var paired = 0
        var i = 0
        while (i < bytes.size) {
            if (bytes[i].toInt() and 0x80 != 0) {
                high++
                if (i + 1 < bytes.size && bytes[i + 1].toInt() and 0x80 != 0) {
                    paired++
                    i++
                }
            }
            i++
        }
        val western = high > 0 && paired * 3 < high
        if (western) return String(bytes, Charset.forName("windows-1252"))
        fun score(text: String): Int {
            var s = 0
            for (c in text) {
                s += when (c) {
                    in '\u3040'..'\u30ff' -> 3 // kana
                    in '\u4e00'..'\u9fff' -> 2 // common CJK
                    in '\uac00'..'\ud7af' -> 2 // Hangul
                    in '\uff61'..'\uff9f' -> -4 // half-width katakana: a wrong guess
                    '\ufffd' -> -6
                    in '\ue000'..'\uf8ff' -> -6
                    else -> 0
                }
            }
            return s
        }
        return listOf("GB18030", "Shift_JIS", "Big5", "EUC-KR")
            .mapNotNull { name -> runCatching { String(bytes, Charset.forName(name)) }.getOrNull() }
            .maxByOrNull(::score)
            ?: String(bytes, Charsets.UTF_8)
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
        val synced = withoutCredits(LrcLib.parseLrc(cleaned), text)
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

    /** The `[offset:]` of the lyrics file last read for each song. */
    private val fileOffsets = ConcurrentHashMap<String, Long>()

    fun fileOffset(videoId: String): Long? = fileOffsets[videoId]

    fun offsetOf(raw: String): Long? = OFFSET.find(raw)?.groupValues?.get(1)?.toLongOrNull()

    /** [raw] with its `[offset:]` set to [ms] (added after the header tags if it had none). */
    fun withOffset(raw: String, ms: Long): String {
        val tag = "[offset:$ms]"
        if (OFFSET.containsMatchIn(raw)) return raw.replace(OFFSET, tag)
        val lines = raw.replace("\r\n", "\n").split('\n')
        val headerEnd = lines.indexOfFirst { !TAG_LINE.matches(it.trim()) || it.isBlank() }.let { if (it < 0) lines.size else it }
        return (lines.take(headerEnd) + tag + lines.drop(headerEnd)).joinToString("\n")
    }

    /** Lines as an .lrc file, with [offset] when not 0. */
    fun toLrc(lines: List<LyricLine>, title: String?, artist: String?, offset: Long): String = buildString {
        artist?.let { append("[ar:").append(it).append("]\n") }
        title?.let { append("[ti:").append(it).append("]\n") }
        if (offset != 0L) append("[offset:").append(offset).append("]\n")
        lines.forEach { l ->
            val ms = l.timeMs
            append("[%02d:%02d.%02d]".format(java.util.Locale.ROOT, ms / 60_000, ms / 1000 % 60, ms / 10 % 100))
            append(l.text).append('\n')
        }
    }

    /** Where the lyrics file beside [videoId] is (or would go). */
    fun besideUrl(videoId: String): String? {
        val fileUrl = WebDavConfig.fileUrlOf(videoId) ?: return null
        return remote[videoId] ?: (fileUrl.substringBeforeLast('.') + ".lrc")
    }

    /** Whether a lyrics file was found beside [videoId]. */
    fun hasBeside(videoId: String): Boolean = videoId !in remoteMisses && WebDavConfig.isWebDavId(videoId)

    /** A lyrics file was just written beside [videoId]. */
    fun wroteBeside(videoId: String, url: String) = registerRemote(videoId, url)

    suspend fun readBeside(videoId: String): String? = besideUrl(videoId)?.let { readRemote(it) }

    /** `[offset:500]`: the lyrics are 500 ms early (positive) or late (negative). */
    private val OFFSET = Regex("""(?im)^\s*\[offset:\s*([+-]?\d+)\s*]""")

    private fun applyOffset(lines: List<LyricLine>, raw: String): List<LyricLine> {
        val ms = OFFSET.find(raw)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it != 0L } ?: return lines
        return lines.map { line ->
            line.copy(
                timeMs = (line.timeMs - ms).coerceAtLeast(0),
                words = line.words.map { w -> w.copy(startMs = (w.startMs - ms).coerceAtLeast(0), endMs = (w.endMs - ms).coerceAtLeast(0)) },
            )
        }
    }

    /**
     * Timed lines that are the file's credits, not the song: "Artist: Ke$ha",
     * "Title: …", "By: m07 (…)", "Lrc by …", 作词：…, 歌手：…, 所属专辑：…, and a
     * line that is just the song's title or artist (from its [ti:]/[ar:] tags)
     * in the first half minute.
     */
    private val CREDIT = Regex(
        """^\s*(artist|title|album|by|lrc\s*by|lyrics?(\s*by)?|music(\s*by)?|composer|arranger|written\s*by|""" +
            """作词|作曲|编曲|作詞|編曲|歌手|演唱|专辑|所属专辑|制作|製作|翻译)\s*[:：]""",
        RegexOption.IGNORE_CASE,
    )
    /** "by (alexcar)", "Lrc by Syazrul": only as a short line in the first half minute. */
    private val BY_LINE = Regex("""^\s*(lrc\s+by|by)\s+\S""", RegexOption.IGNORE_CASE)
    private val TAG_VALUE = Regex("""(?im)^\s*\[(ti|ar):([^\]]*)]""")

    private fun withoutCredits(lines: List<LyricLine>, raw: String): List<LyricLine> {
        val names = TAG_VALUE.findAll(raw).map { key(it.groupValues[2]) }.filter { it.isNotEmpty() }.toSet()
        fun isCredit(line: LyricLine): Boolean {
            if (line.text.isBlank()) return false
            if (CREDIT.containsMatchIn(line.text)) return true
            if (line.timeMs > 30_000) return false
            if (line.text.length <= 40 && BY_LINE.containsMatchIn(line.text)) return true
            val k = key(line.text)
            return k.isNotEmpty() && (k in names || line.text.split(" - ").map(::key).all { it in names })
        }
        return lines.filterNot(::isCredit)
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

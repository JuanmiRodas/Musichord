package bitchord.library

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/**
 * Text from tags, file names and folders: repaired (accents in one form, Japanese and
 * Chinese read with the wrong code page), told apart from words that name nothing
 * ("Anime", "Varios", "pista 3", a file name), and read out of file names when the tags
 * are empty ("07.Miguel Bose Ft. Paulina Rubio - Nena.mp3").
 */
object Words {
    fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    /** Accents and case aside, letters and digits only: "Pokémon" = "pokemon" = "POKEMON". */
    fun plain(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("\\p{M}"), "").lowercase().filter { it.isLetterOrDigit() }

    private val EAST = Regex("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}\\u3000-\\u303F\\uFF01-\\uFF5E]")
    private val HALF_KANA = Regex("[\\uFF61-\\uFF9F]")
    private val CJK_ONLY = Regex("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]")
    private val decoders = listOf("windows-31j", "GBK", "EUC-JP", "Big5").mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }
    private val cp1252 = Charset.forName("windows-1252")

    /** The bytes a string was before it was misread as Latin-1 / Windows-1252 (null if it wasn't). */
    private fun bytesOf(s: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        for (c in s) {
            when {
                c.code <= 0xFF -> out.write(c.code)
                else -> {
                    val b = c.toString().toByteArray(cp1252)
                    if (b.size != 1 || b[0] == '?'.code.toByte()) return null
                    out.write(b[0].toInt())
                }
            }
        }
        return out.toByteArray()
    }

    private fun isHighCp1252(c: Char): Boolean {
        if (c.code in 0x80..0xFF) return true
        if (c.code < 0x80) return false
        val b = c.toString().toByteArray(cp1252)
        return b.size == 1 && b[0] != '?'.code.toByte() && (b[0].toInt() and 0xFF) >= 0x80
    }

    /**
     * "°®ÄÚÀï²Ë" → "爱内里菜", "Get Along¡«SelfTag Version¡«" → "Get Along～SelfTag Version～";
     * "Fantôme", "Malmö" or "¡Viva!" stay: a repair must keep every plain letter where it was
     * and give East Asian text.
     */
    private fun sameLetters(decoded: String, s: String): Boolean {
        val all = s.filter { it.code < 0x80 && it.isLetterOrDigit() }
        if (decoded == all) return true
        // Only for text that is mostly garbled ("Canción María" read as Big5 would lose letters too).
        if (s.count(::isHighCp1252) < 4) return false
        var j = 0
        for ((i, c) in s.withIndex()) {
            if (c.code >= 0x80 || !c.isLetterOrDigit()) continue
            if (j < decoded.length && decoded[j] == c) { j++; continue }
            if (i == 0 || !isHighCp1252(s[i - 1])) return false
        }
        return j == decoded.length
    }

    fun fix(s0: String): String {
        val s = nfc(s0).replace('\u0000', ' ').trim()
        val bytes = bytesOf(s) ?: return s
        if (bytes.count { (it.toInt() and 0xFF) >= 0x80 } < 2) return s
        // UTF-8 read as Latin-1 ("CatatÃ³nica", "Hey, TÃº"): the bytes are UTF-8.
        runCatching {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()?.let { u -> if (u != s && u.none { it.code in 0x80..0x9F }) return nfc(u) }
        // East Asian text read as Latin-1: every decoding that keeps the plain letters, the most plausible one.
        var best: String? = null; var bestScore = 0
        for (cs in decoders) {
            val d = runCatching {
                cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            }.getOrNull() ?: continue
            // Plain letters and digits stay where they were; in Big5, GBK or Shift-JIS a letter right after an
            // accented one may be the second half of a character ("¤é¥»ªZ¹DÀ]" is 日本武道館).
            if (!sameLetters(d.filter { it.code < 0x80 && it.isLetterOrDigit() }, s)) continue
            if (d.count { CJK_ONLY.containsMatchIn(it.toString()) } < 2 && !Regex("[～〜・「」『』]").containsMatchIn(d)) continue
            if (HALF_KANA.containsMatchIn(d) || !EAST.containsMatchIn(d)) continue
            val score = plausibility(d)
            if (score > bestScore) { best = d; bestScore = score }
        }
        return best?.let { nfc(it) } ?: s
    }

    /** How much a decoding looks like real text: common kana, kanji and CJK punctuation count; odd leftovers count against. */
    private fun plausibility(d: String): Int {
        var n = 0
        for (c in d) {
            val k = c.code
            n += when {
                k in 0x3041..0x30FF -> 3                       // kana
                k in 0x4E00..0x9FFF -> 2                       // common kanji
                c in "「」『』“”・～〜、。！？（）" -> 3
                k < 0x80 -> 0
                k in 0x80..0x24F || c in "‘’¨〃ńŃ" -> -4         // Latin-1 or odd marks left over
                else -> -1
            }
        }
        return n
    }

    // ── Words that name nothing ──

    private val NOTHING = Regex(
        "(?i)^(anime[s]?|anime y japonadas|m[uú]sica( normal o anime| variada| japonesa)?|musica|music|musique|varios?( artistas)?|varias|various( artists)?|va|v\\.a\\.?|" +
            "unknown( artist| album)?|desconocid[oa]s?|artista desconocido|álbum desconocido|soundtracks?|ost|o\\.s\\.t\\.?|bso|b\\.s\\.o\\.?|" +
            "videojuegos?|games?|juegos?|sueltas?|singles?|otros( artistas)?|other( artists)?|misc.*|compilation|recopilatorio|" +
            "track\\s*\\d*|pista\\s*\\d*|audio\\s*track\\s*\\d*|untitled|sin t[ií]tulo|new folder|nueva carpeta|[-_.?¿!¡\\s]*)$"
    )
    private val FILE_LIKE = Regex("(?i)\\.(mp3|m4a|flac|wav|wma|ogg|opus|aac)\\b|^(www\\.|https?:)|\\.(com|net|org|info|ru|de|es|fr|tk|to|cc)\\b|downloads?\\b|descargas?\\b")
    private val VARIOUS_YEAR = Regex("(?i)^vari[oa]s\\b.*|^various\\b.*")

    /** An artist tag that names no one: a category, a grab-bag, a file name, a bare number. */
    fun noArtist(s: String?): Boolean = s == null || s.isBlank() || NOTHING.matches(s.trim()) || FILE_LIKE.containsMatchIn(s) ||
        s.contains('�') || Regex("^\\d{1,4}$").matches(s.trim()) || plain(s).isEmpty() || VARIOUS_YEAR.matches(s.trim()) ||
        s.count { it == '?' } >= 3 || s.none { it.isLetter() } || Regex("^\\d{1,3}\\s+\\S.*\\s[-–]\\s").containsMatchIn(s)

    /** An album tag that names no album ("Anime", "Desconocido", "Pista 3"…); "1989" is an album. */
    fun noAlbum(s: String?): Boolean = s == null || s.isBlank() || NOTHING.matches(s.trim()) || FILE_LIKE.containsMatchIn(s) || s.contains('�') || plain(s).isEmpty() || VARIOUS_YEAR.matches(s.trim())

    fun noTitle(s: String?): Boolean = s == null || s.isBlank() || FILE_LIKE.containsMatchIn(s) || s.contains('�') || Regex("(?i)^(track|pista|audio ?track)\\s*\\d+$").matches(s.trim()) || plain(s).isEmpty()

    /** "05. ONE" → "ONE" (a track number left in a title). */
    fun dropNumber(title: String): String = title.replace(Regex("^\\s*\\d{1,3}\\s*[.\\-–_)]+\\s*(?=\\S)"), "").trim().ifBlank { title.trim() }

    /** A folder's name as an album: "[170920] SawanoHiroyuki[nZk] 2ndアルバム「2V-ALK」[320K+BK]" → "2V-ALK". */
    fun albumFromFolder(name: String): Pair<String?, String> {
        var n = nfc(name)
        var year: String? = null
        Regex("\\[(\\d{2})(\\d{2})(\\d{2})]").find(n)?.let { m -> year = (if (m.groupValues[1].toInt() > 40) "19" else "20") + m.groupValues[1] }
        Regex("「([^」]+)」").find(n)?.let { return year to it.groupValues[1].trim() }
        Regex("^(\\d{4})\\s*[-–.]\\s*(.+)$").find(n)?.let { m -> year = m.groupValues[1]; n = m.groupValues[2] }
        Regex("[(\\[](\\d{4})[)\\]]").find(n)?.let { m -> if (year == null) year = m.groupValues[1] }
        Regex("[(\\[]((?:19|20)\\d{2})[.\\-/]\\d{1,2}[.\\-/]\\d{1,2}[)\\]]").find(n)?.let { m -> if (year == null) year = m.groupValues[1] }
        n = n.replace(Regex("\\[[^\\]]*]"), " ")
            .replace(Regex("(?i)\\((\\d{4}|mp3|flac|320k?|v0|web|cd|cdm|cds)\\)"), " ")
            .replace(Regex("(?i)\\b(320\\s?kbps|320k|mp3|flac|v0)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim().trim('-', '–', '.', ' ')
        return year to n
    }

    data class FromName(val disc: Int?, val no: Int?, val parts: List<String>)

    /** "1-05. Title - Artist" → disc 1, track 5, ["Title", "Artist"]. */
    fun fromName(fileName: String): FromName {
        var n = nfc(fileName.substringBeforeLast('.'))
        if (!n.contains(' ') && n.count { it == '_' } >= 2) n = n.replace('_', ' ')
        var disc: Int? = null
        var no: Int? = null
        Regex("^\\s*(?:(\\d)\\s*-\\s*(\\d{1,3})|(\\d{1,3}))(?:\\s*[.)\\-–_]+\\s*|\\s+)(?=\\S)").find(n)?.let { m ->
            if (m.groupValues[1].isNotEmpty()) { disc = m.groupValues[1].toInt(); no = m.groupValues[2].toInt() } else no = m.groupValues[3].toInt()
            n = n.substring(m.range.last + 1)
        }
        val parts = n.split(Regex("\\s+[-–]\\s+|\\s*[-–]\\s+(?=\\S)|(?<=\\S)\\s+[-–](?=\\s)")).map { it.trim() }.filter { it.isNotEmpty() }
        return FromName(disc, no, parts.ifEmpty { listOf(n.trim()) })
    }
}

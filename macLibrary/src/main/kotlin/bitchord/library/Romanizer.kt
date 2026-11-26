package bitchord.library

import org.json.JSONObject
import java.io.File

/**
 * Bilingual "Romaji (日本語)" romanization engine for album folders and track titles:
 *  1. Normalizes inverted "日本語 - Romaji" titles (common in Music/BitChord downloads) to "Romaji (日本語)".
 *  2. Uses official romanizations from `romanizations.json` (MusicBrainz / VGMdb / Wikipedia) and source
 *     Discografías filenames/folders first.
 *  3. Falls back to clean Hepburn romanization (from `romanizations.json` pykakasi table + built-in kana table)
 *     only when no official romanization exists.
 */
object Romanizer {
    private val CJK = Regex("""[\p{IsHiragana}\p{IsKatakana}\p{IsHan}]""")
    private val LATIN_WORD = Regex("[A-Za-z]{2,}")
    private val PAREN_VER = Regex("(?i)[(\\[（【]\\s*(?:(?:\\d{4}\\s+)?(?:ver(?:sion)?|edit|mix|remix|rmx|live|acoustic|instrumental|inst\\.?|tv\\s*size|movie|full|album|single|piano|orchestra|demo|radio|club|extended|ballad|bonus|track|remaster(?:ed)?|original|short|long|special|type|side|ep|mini|the\\s+late\\s+spring|end\\s+of\\s+spring)[\\s;.,/\\d-]*)+[)\\]）】]")
    private val ALREADY_BILINGUAL = Regex("[(（][^)）]*[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}][^)）]*[)）]")

    val cacheFile: File get() = File(Store.dir, "romanizations.json")

    private class Db(
        val albumsExact: Map<String, String>,
        val albumsPlain: Map<String, String>,
        val tracksExact: Map<String, String>,
        val tracksPlain: Map<String, String>,
    )

    @Volatile private var dbCache: Pair<Long, Db>? = null

    private fun loadDb(): Db {
        val m = if (cacheFile.isFile) cacheFile.lastModified() else -1L
        dbCache?.takeIf { it.first == m }?.let { return it.second }
        val ae = HashMap<String, String>(); val ap = HashMap<String, String>()
        val te = HashMap<String, String>(); val tp = HashMap<String, String>()
        runCatching {
            if (cacheFile.isFile) {
                val o = JSONObject(cacheFile.readText())
                o.optJSONObject("albums")?.let { a ->
                    for (k in a.keySet()) {
                        val v = a.optString(k).trim()
                        if (v.isNotEmpty()) { ae[Words.nfc(k)] = v; ap.putIfAbsent(Words.plain(k), v) }
                    }
                }
                o.optJSONObject("tracks")?.let { t ->
                    for (k in t.keySet()) {
                        val v = t.optString(k).trim()
                        if (v.isNotEmpty()) { te[Words.nfc(k)] = v; tp.putIfAbsent(Words.plain(k), v) }
                    }
                }
            }
        }
        return Db(ae, ap, te, tp).also { dbCache = m to it }
    }

    fun hasRealLatin(s: String): Boolean = LATIN_WORD.containsMatchIn(PAREN_VER.replace(s, " ").replace(CJK, " "))

    fun isAlreadyBilingual(s: String): Boolean =
        ALREADY_BILINGUAL.containsMatchIn(s) && LATIN_WORD.containsMatchIn(s.substringBefore("(").substringBefore("（"))

    /** Fixes inverted "日本語 - Romaji" titles (e.g. "カタオモイ - Kataomoi" -> "Kataomoi (カタオモイ)"). */
    fun normalizeInverted(title: String, artist: String = ""): String {
        val t = Words.nfc(title).trim()
        if (!CJK.containsMatchIn(t)) return t
        val m = Regex("^(.+?)\\s+-\\s+(.+)$").find(t) ?: return t
        val left = m.groupValues[1].trim()
        val right = m.groupValues[2].trim()
        if (left.isBlank() || right.isBlank()) return t
        // Left must have Japanese (and no real Latin words besides version tags), right must be Latin/Romaji and not the artist name.
        if (!CJK.containsMatchIn(left) || hasRealLatin(left)) return t
        if (CJK.containsMatchIn(right) || !LATIN_WORD.containsMatchIn(right)) return t
        val artPlain = Words.plain(artist.substringBefore(" ("))
        if (artPlain.length >= 3 && (Words.plain(right) == artPlain || Words.plain(right).startsWith(artPlain))) return t
        // Avoid duplicating trailing version suffix like "(2026 Remaster)" inside both halves.
        val verSuffix = Regex("\\s*[(（\\[]([^)）\\]]+)[)）\\]]\\s*$").find(left)
        val cleanLeft = if (verSuffix != null && !CJK.containsMatchIn(verSuffix.groupValues[1]) &&
            Words.plain(right).contains(Words.plain(verSuffix.groupValues[1]))) {
            left.substring(0, verSuffix.range.first).trim()
        } else left
        return "$right (${cleanLeft.ifBlank { left }})"
    }

    /** Produces "Romaji (日本語)" for a Japanese-only track title using: inverted fix -> filename Romaji -> romanizations.json -> kana fallback. */
    fun bilingualTrack(tagTitle: String, rawFilename: String, artist: String): String {
        val norm = normalizeInverted(tagTitle, artist)
        if (!CJK.containsMatchIn(norm) || isAlreadyBilingual(norm) || hasRealLatin(norm)) return norm
        // 1. Check filename: handles both pure-Romaji filenames ("05. Haru wa Yuku the late spring ver..flac")
        //    and bilingual filenames ("2. Yurayura (ゆらゆら) - Every Little Thing - Grip!.m4a").
        val stem = Words.dropNumber(Words.nfc(Words.fix(File(rawFilename).nameWithoutExtension))).trim()
        val fnHead = stem.split(Regex("\\s+-\\s+")).first().trim().trimEnd('.')
        val fnRomajiRaw = Regex("^(.+?)\\s*[(（][^)]*[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}][^)]*[)）]\\s*$")
            .find(fnHead)?.groupValues?.get(1)?.trim()
            ?: fnHead.takeIf { !CJK.containsMatchIn(it) }
        val fnRomaji = fnRomajiRaw?.replace('_', ' ')?.replace(Regex("\\s{2,}"), " ")?.trim()
        if (!fnRomaji.isNullOrBlank() && fnRomaji.length >= 2 && hasRealLatin(fnRomaji) && !Words.noTitle(fnRomaji)) {
            val cjkCore = stripDuplicateSuffix(norm, fnRomaji)
            return "$fnRomaji ($cjkCore)"
        }
        // 2. Official / cached lookup in romanizations.json (exact or stripped of trailing version parens).
        val db = loadDb()
        val exact = (db.tracksExact[norm] ?: db.tracksPlain[Words.plain(norm)])?.replace('_', ' ')?.trim()
        if (!exact.isNullOrBlank() && !CJK.containsMatchIn(exact)) {
            val cjkCore = stripDuplicateSuffix(norm, exact)
            return "$exact ($cjkCore)"
        }
        val baseM = Regex("^(.+?)\\s*([(（\\[].*)$").find(norm)
        if (baseM != null) {
            val baseJp = baseM.groupValues[1].trim()
            val tail = baseM.groupValues[2].trim()
            val hit = (db.tracksExact[baseJp] ?: db.tracksPlain[Words.plain(baseJp)])?.replace('_', ' ')?.trim()
            if (!hit.isNullOrBlank() && !CJK.containsMatchIn(hit)) {
                val tailClean = if (Words.plain(hit).contains(Words.plain(tail))) "" else " $tail"
                return "$hit$tailClean ($baseJp)"
            }
        }
        // 3. Built-in kana-only Hepburn fallback for pure hiragana/katakana titles.
        val kana = kanaToRomaji(norm)
        if (kana != null) return "$kana ($norm)"
        return norm
    }

    /** Produces "Romaji (日本語)" for a Japanese-only album name using: romanizations.json -> folder Romaji -> VGMdb -> kana fallback. */
    fun bilingualAlbum(tagAlbum: String, folderAlbum: String, artist: String): String {
        val a = Words.nfc(tagAlbum).trim()
        if (!CJK.containsMatchIn(a)) return a
        // Already bilingual "Romaji (日本語)" -> leave untouched.
        if (Regex("[(（][^)）]*[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}][^)）]*[)）]").containsMatchIn(a) &&
            LATIN_WORD.containsMatchIn(a.substringBefore("(").substringBefore("（"))) return a
        // 1. Official / cached lookup in romanizations.json.
        val db = loadDb()
        val dbHit = db.albumsExact[a] ?: db.albumsPlain[Words.plain(a)]
        if (!dbHit.isNullOrBlank() && !CJK.containsMatchIn(dbHit)) return "$dbHit ($a)"
        // 2. Source folder name (when it supplies Romaji words not already in tagAlbum).
        if (folderAlbum.isNotBlank()) {
            val stripped = folderAlbum
                .replace(Regex("^(?:19|20)\\d{2}\\s*[-–]?\\s*"), "")
                .replace(Regex("(?i)\\s*[-–]\\s*(digital\\s+)?(single|mini|ep|album)\\s*$"), "")
                .replace(Regex("(?i)\\s*\\((?:320\\s*kbps|flac|mp3|single|ep)\\)\\s*$"), "").trim()
            if (stripped.isNotBlank() && !CJK.containsMatchIn(stripped) && !Words.noAlbum(stripped) && Words.plain(stripped) != Words.plain(artist)) {
                val tagLat = LATIN_WORD.findAll(a).map { it.value.lowercase() }.toSet()
                val dirLat = LATIN_WORD.findAll(stripped).map { it.value.lowercase() }.toSet()
                if (dirLat.size > tagLat.size) return "$stripped ($a)"
            }
        }
        // 3. Existing VGMdb AlbumNames cache.
        AlbumNames.of(a)?.takeIf { it.isNotBlank() && !CJK.containsMatchIn(it) }?.let { return "$it ($a)" }
        // 4. Built-in kana fallback if pure hiragana/katakana.
        if (!hasRealLatin(a)) kanaToRomaji(a)?.let { return "$it ($a)" }
        return a
    }

    private fun stripDuplicateSuffix(jp: String, romaji: String): String {
        val m = Regex("\\s*[(（\\[]([^)）\\]]+)[)）\\]]\\s*$").find(jp) ?: return jp
        val inside = m.groupValues[1]
        if (!CJK.containsMatchIn(inside) && Words.plain(romaji).contains(Words.plain(inside))) {
            return jp.substring(0, m.range.first).trim().ifBlank { jp }
        }
        return jp
    }

    /** Pure-Kotlin Hepburn converter for pure Hiragana/Katakana strings (returns null if Kanji present). */
    private fun kanaToRomaji(s: String): String? {
        if (Regex("[\\p{IsHan}]").containsMatchIn(s)) return null
        val hira = buildString {
            for (ch in s) {
                append(if (ch in '\u30A1'..'\u30F6') (ch.code - 0x60).toChar() else ch)
            }
        }
        val sb = StringBuilder()
        var i = 0
        var geminate = false
        while (i < hira.length) {
            val c = hira[i]
            if (c == 'っ') { geminate = true; i++; continue }
            if (c == 'ー') {
                val last = sb.lastOrNull()
                if (last != null && last in "aiueo") sb.append(last)
                i++; continue
            }
            val two = if (i + 1 < hira.length) hira.substring(i, i + 2) else ""
            val rom = DIGRAPHS[two] ?: MONOGRAPHS[c]
            if (rom != null) {
                if (geminate && rom.isNotEmpty() && rom[0].isLetter()) sb.append(if (rom.startsWith("ch")) 't' else rom[0])
                geminate = false
                sb.append(rom)
                i += if (DIGRAPHS.containsKey(two)) 2 else 1
            } else {
                geminate = false
                sb.append(c)
                i++
            }
        }
        val res = sb.toString().replace(Regex("\\s+"), " ").trim()
        return res.takeIf { LATIN_WORD.containsMatchIn(it) }?.replaceFirstChar { it.uppercase() }
    }

    private val DIGRAPHS = mapOf(
        "きゃ" to "kya", "きゅ" to "kyu", "きょ" to "kyo", "しゃ" to "sha", "しゅ" to "shu", "しょ" to "sho",
        "ちゃ" to "cha", "ちゅ" to "chu", "ちょ" to "cho", "にゃ" to "nya", "にゅ" to "nyu", "にょ" to "nyo",
        "ひゃ" to "hya", "ひゅ" to "hyu", "ひょ" to "hyo", "みゃ" to "mya", "みゅ" to "myu", "みょ" to "myo",
        "りゃ" to "rya", "りゅ" to "ryu", "りょ" to "ryo", "ぎゃ" to "gya", "ぎゅ" to "gyu", "ぎょ" to "gyo",
        "じゃ" to "ja", "じゅ" to "ju", "じょ" to "jo", "びゃ" to "bya", "びゅ" to "byu", "びょ" to "byo",
        "ぴゃ" to "pya", "ぴゅ" to "pyu", "ぴょ" to "pyo", "ふぁ" to "fa", "ふぃ" to "fi", "ふぇ" to "fe", "ふぉ" to "fo",
        "てぃ" to "ti", "でぃ" to "di", "ゔぁ" to "va", "ゔぃ" to "vi", "ゔぇ" to "ve", "ゔぉ" to "vo",
    )
    private val MONOGRAPHS = mapOf(
        'あ' to "a", 'い' to "i", 'う' to "u", 'え' to "e", 'お' to "o",
        'か' to "ka", 'き' to "ki", 'く' to "ku", 'け' to "ke", 'こ' to "ko",
        'さ' to "sa", 'し' to "shi", 'す' to "su", 'せ' to "se", 'そ' to "so",
        'た' to "ta", 'ち' to "chi", 'つ' to "tsu", 'て' to "te", 'と' to "to",
        'な' to "na", 'に' to "ni", 'ぬ' to "nu", 'ね' to "ne", 'の' to "no",
        'は' to "ha", 'ひ' to "hi", 'ふ' to "fu", 'へ' to "he", 'ほ' to "ho",
        'ま' to "ma", 'み' to "mi", 'む' to "mu", 'め' to "me", 'も' to "mo",
        'や' to "ya", 'ゆ' to "yu", 'よ' to "yo",
        'ら' to "ra", 'り' to "ri", 'る' to "ru", 'れ' to "re", 'ろ' to "ro",
        'わ' to "wa", 'を' to "wo", 'ん' to "n",
        'が' to "ga", 'ぎ' to "gi", 'ぐ' to "gu", 'げ' to "ge", 'ご' to "go",
        'ざ' to "za", 'じ' to "ji", 'ず' to "zu", 'ぜ' to "ze", 'ぞ' to "zo",
        'だ' to "da", 'ぢ' to "ji", 'づ' to "zu", 'で' to "de", 'ど' to "do",
        'ば' to "ba", 'び' to "bi", 'ぶ' to "bu", 'べ' to "be", 'ぼ' to "bo",
        'ぱ' to "pa", 'ぴ' to "pi", 'ぷ' to "pu", 'ぺ' to "pe", 'ぽ' to "po",
        'ぁ' to "a", 'ぃ' to "i", 'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o", 'ゔ' to "v",
    )
}

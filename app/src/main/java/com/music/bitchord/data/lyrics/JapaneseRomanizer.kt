package com.music.bitchord.data.lyrics

import com.music.bitchord.data.sources.JapaneseReading
import com.music.bitchord.data.sources.TrackMatcher

/**
 * BitChord Audio: Japanese lyrics in romaji, read as words.
 *
 * Google's romanizer, which the app used before, is asked line by line with
 * the language guessed per batch: a batch of kanji-only lines is taken for
 * Chinese and comes back in pinyin ("shě"), and even as Japanese it reads
 * kanji one by one ("Fu Yasu Jō" for 不安定). Here a dictionary splits the line
 * into words and gives each its reading (Kuromoji / IPADIC), so 不安定な栄光の音
 * reads "fuantei na eikou no oto"; the kana then go through the app's
 * Hepburn ([TrackMatcher.romajiOf]).
 *
 * The rules lyric romaji follows on top of that:
 *  - 君 is "kimi" — "kun" only as a name suffix (Naruto-kun);
 *  - the particles は, へ, を are read "wa", "e", "o";
 *  - っ / ッ doubles the next consonant, across a word break too (待っ|て →
 *    "matte"), and is dropped at the end of a line ("あっ" → "a");
 *  - endings stay on their word: verb endings and auxiliaries (消え|た →
 *    "kieta", 捨てれ|ば → "sutereba"), while particles stand apart ("no", "ni").
 * Latin words in the line are kept as written.
 */
object JapaneseRomanizer {

    private val KANA = Regex("[\\u3040-\\u30ff]")

    /** Lyrics that are Japanese: some kana, not just kanji (which Chinese shares). */
    fun isJapanese(lines: List<String>): Boolean = lines.any { KANA.containsMatchIn(it) }

    /** Spaces between two Japanese characters — how syllable-timed lyrics often spell a line ("甘 い 恋"). */
    private val INNER_SPACE = Regex("""(?<=[\p{IsHan}\p{IsHiragana}\p{IsKatakana}ー々])[\s\u00A0\u2000-\u200B\u3000\uFEFF]+(?=[\p{IsHan}\p{IsHiragana}\p{IsKatakana}ー々])""")

    /** Kanji with their reading in brackets after them — "運命(さだめ)", "未来（あした）". */
    private val RUBY = Regex("""[\p{IsHan}々〆ヶ]+[ \u3000]*[(（]([\p{IsHiragana}\p{IsKatakana}ー ]+)[)）]""")

    /**
     * BitChord Audio: the line as it should be read. A syllable-timed line
     * spaced character by character is closed up, so the dictionary sees
     * words (予期, not 予 and 期 read on their own — or not at all); and a
     * reading the lyrics give in brackets is the reading, in place of the
     * kanji's dictionary one, rather than read as well.
     */
    fun prepare(line: String): String =
        RUBY.replace(line.replace(INNER_SPACE, "")) { it.groupValues[1].replace(" ", "") }

    /** One romanized word: its text, whether a space goes before it, and the span of [raw] it reads. */
    private class Piece(val text: String, val spaced: Boolean, val from: Int, val to: Int)

    /**
     * [prepare], keeping for every character of the result the span of the
     * original it came from — so a romaji word can be traced back to the
     * Japanese it reads, and take its timing.
     */
    private fun prepareMapped(raw: String): Triple<String, IntArray, IntArray> {
        val sb = StringBuilder()
        val from = ArrayList<Int>()
        val to = ArrayList<Int>()
        val ruby = RUBY.findAll(raw).associateBy { it.range.first }
        var i = 0
        while (i < raw.length) {
            val r = ruby[i]
            if (r != null) {
                r.groupValues[1].replace(" ", "").forEach { c ->
                    sb.append(c); from += r.range.first; to += r.range.last + 1
                }
                i = r.range.last + 1
                continue
            }
            val c = raw[i]
            val isSpace = c.isWhitespace() || c == '\u00A0' || c == '\u200B' || c == '\u3000' || c == '\uFEFF'
            if (isSpace && i > 0 && JAPANESE_CHAR.matches(raw[i - 1].toString())) {
                var j = i
                while (j < raw.length && (raw[j].isWhitespace() || raw[j] == '\u00A0' || raw[j] == '\u200B' || raw[j] == '\u3000' || raw[j] == '\uFEFF')) j++
                if (j < raw.length && JAPANESE_CHAR.matches(raw[j].toString())) {
                    i = j
                    continue
                }
            }
            sb.append(c); from += i; to += i + 1
            i++
        }
        return Triple(sb.toString(), from.toIntArray(), to.toIntArray())
    }

    private val JAPANESE_CHAR = Regex("""[\p{IsHan}\p{IsHiragana}\p{IsKatakana}ー々]""")

    fun romanize(raw: String): String? {
        if (!JapaneseReading.isJapanese(raw)) return raw
        val pieces = pieces(raw) ?: return null
        return buildString {
            pieces.forEach { p ->
                if (p.spaced && isNotEmpty()) append(' ')
                append(p.text)
            }
        }.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * BitChord Audio: a word-timed line's romaji with timing of its own —
     * each romaji word timed by the Japanese words it reads (first start to
     * last end). Swept by these, the romaji fills as it is sung, rather than
     * by the original's characters stretched over a text of another length.
     * Null when the line isn't word-timed or its words can't be placed.
     */
    fun timedWords(line: LyricLine): List<LyricWord>? {
        if (line.words.isEmpty() || !JapaneseReading.isJapanese(line.text)) return null
        val pieces = pieces(line.text) ?: return null
        val spans = ArrayList<IntRange>()
        var cursor = 0
        for (w in line.words) {
            val at = line.text.indexOf(w.text, cursor)
            if (at < 0 || w.text.isEmpty()) return null
            spans += at until at + w.text.length
            cursor = at + w.text.length
        }
        var lastEnd = line.words.first().startMs
        return pieces.mapNotNull { p ->
            if (p.text.isBlank()) return@mapNotNull null
            val hit = line.words.indices.filter { k -> spans[k].first < p.to && spans[k].last + 1 > p.from }
            val start = hit.minOfOrNull { line.words[it].startMs } ?: lastEnd
            val end = hit.maxOfOrNull { line.words[it].endMs } ?: start
            lastEnd = end
            LyricWord(start, end.coerceAtLeast(start), p.text)
        }.takeIf { it.isNotEmpty() }
    }

    private fun pieces(raw: String): List<Piece>? {
        val (line, fromAt, toAt) = prepareMapped(raw)
        // The dictionary loads in the background at start-up. Asked before it
        // is ready, this used to give up — and the lyrics went to the web
        // romanizer instead, which leaves kanji it can't place, and whose
        // answer is cached for good. Waited for instead, up to 20 s.
        val tokenizer = JapaneseReading.tokenizerNow() ?: run {
            JapaneseReading.warmUp()
            var waited = 0
            while (JapaneseReading.tokenizerNow() == null && waited < 20_000) {
                Thread.sleep(100)
                waited += 100
            }
            JapaneseReading.tokenizerNow()
        } ?: return null
        val tokens = runCatching { tokenizer.tokenize(line) }.getOrNull() ?: return null
        val out = ArrayList<Piece>()
        var gap = false
        var carry = ""
        var previousPos: String? = null
        var previousNamed = false
        fun spanOf(tok: com.atilika.kuromoji.ipadic.Token): Pair<Int, Int> {
            val a = tok.position.coerceIn(0, (fromAt.size - 1).coerceAtLeast(0))
            val b = (tok.position + tok.surface.length - 1).coerceIn(0, (toAt.size - 1).coerceAtLeast(0))
            return if (fromAt.isEmpty()) 0 to 0 else fromAt[a] to toAt[b]
        }
        tokens.forEachIndexed { index, tok ->
            val named = previousNamed
            previousNamed = tok.partOfSpeechLevel3 == "人名" ||
                (tok.surface.isNotEmpty() && tok.surface.all { it in 'ァ'..'ヺ' || it == 'ー' } && tok.partOfSpeechLevel2 == "固有名詞")
            val surface = tok.surface
            val pos1 = tok.partOfSpeechLevel1
            val pos2 = tok.partOfSpeechLevel2
            if (surface.isBlank()) {
                gap = true
                previousPos = null
                return@forEachIndexed
            }
            val (from, to) = spanOf(tok)
            if (!JapaneseReading.isJapanese(surface)) {
                // Latin, digits, punctuation: as written.
                val punct = surface.all { !it.isLetterOrDigit() }
                out += Piece(surface, spaced = !punct || gap, from = from, to = to)
                gap = false
                previousPos = if (punct) null else "latin"
                return@forEachIndexed
            }
            var reading = when {
                // The dictionary tags 君 after almost any noun as the suffix
                // ("あの日君が" → 日 + -kun); in lyrics it is "you" unless it
                // follows a person's name.
                surface == "君" -> if (pos2 == "接尾" && named) "クン" else "キミ"
                pos1 == "助詞" && surface == "は" -> "ワ"
                pos1 == "助詞" && surface == "へ" -> "エ"
                pos1 == "助詞" && surface == "を" -> "オ"
                else -> tok.reading?.takeIf { it.isNotBlank() && it != "*" }
                    // A word the dictionary doesn't know: read kanji by kanji
                    // rather than leave them in the romaji.
                    ?: surface.map { ch ->
                        if (Character.UnicodeScript.of(ch.code) != Character.UnicodeScript.HAN) ch.toString()
                        else runCatching { tokenizer.tokenize(ch.toString()).firstOrNull()?.reading }.getOrNull()
                            ?.takeIf { it.isNotBlank() && it != "*" } ?: ch.toString()
                    }.joinToString("")
            }
            reading = carry + reading
            carry = ""
            // A small tsu at the end of a word belongs to the next one.
            if ((reading.endsWith("ッ") || reading.endsWith("っ")) && index < tokens.lastIndex) {
                carry = "ッ"
                reading = reading.dropLast(1)
            }
            val roma = TrackMatcher.romajiOf(reading).lowercase()
            if (roma.isEmpty()) return@forEachIndexed
            // Endings join the word they end; な/だ after a noun stand apart
            // ("fuantei na"), as lyric romaji writes them.
            val inflecting = previousPos in setOf("動詞", "助動詞", "形容詞")
            val attaches = when {
                pos1 == "助動詞" -> inflecting
                pos1 == "助詞" && pos2 == "接続助詞" -> inflecting
                pos1 == "動詞" && (pos2 == "接尾" || pos2 == "非自立") -> inflecting
                pos1 == "形容詞" && pos2 == "非自立" -> inflecting
                pos1 == "名詞" && pos2 == "接尾" && surface != "君" -> previousPos == "名詞"
                else -> false
            }
            val last = out.lastOrNull()
            if (attaches && last != null && !gap) {
                // Joined to the word it ends: one romaji word, spanning both.
                out[out.lastIndex] = Piece(last.text + roma, last.spaced, last.from, to)
            } else {
                out += Piece(roma, spaced = true, from = from, to = to)
            }
            gap = false
            previousPos = pos1
        }
        return out
    }
}

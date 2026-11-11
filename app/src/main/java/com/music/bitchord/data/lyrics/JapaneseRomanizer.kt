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
    private val INNER_SPACE = Regex("""(?<=[\p{IsHan}\p{IsHiragana}\p{IsKatakana}ー々])[ \u3000]+(?=[\p{IsHan}\p{IsHiragana}\p{IsKatakana}ー々])""")

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

    fun romanize(raw: String): String? {
        if (!JapaneseReading.isJapanese(raw)) return raw
        val line = prepare(raw)
        val tokenizer = JapaneseReading.tokenizerNow() ?: return null
        val tokens = runCatching { tokenizer.tokenize(line) }.getOrNull() ?: return null
        val out = StringBuilder()
        var carry = ""
        var previousPos: String? = null
        var previousNamed = false
        tokens.forEachIndexed { index, tok ->
            val named = previousNamed
            previousNamed = tok.partOfSpeechLevel3 == "人名" ||
                (tok.surface.isNotEmpty() && tok.surface.all { it in 'ァ'..'ヺ' || it == 'ー' } && tok.partOfSpeechLevel2 == "固有名詞")
            val surface = tok.surface
            val pos1 = tok.partOfSpeechLevel1
            val pos2 = tok.partOfSpeechLevel2
            if (surface.isBlank()) {
                if (out.isNotEmpty() && !out.endsWith(" ")) out.append(' ')
                previousPos = null
                return@forEachIndexed
            }
            if (!JapaneseReading.isJapanese(surface)) {
                // Latin, digits, punctuation: as written.
                val punct = surface.all { !it.isLetterOrDigit() }
                if (!punct && out.isNotEmpty() && !out.endsWith(" ")) out.append(' ')
                out.append(surface)
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
                else -> tok.reading?.takeIf { it.isNotBlank() && it != "*" } ?: surface
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
            if (!attaches && out.isNotEmpty() && !out.endsWith(" ")) out.append(' ')
            out.append(roma)
            previousPos = pos1
        }
        return out.toString().replace(Regex("\\s+"), " ").trim()
    }
}

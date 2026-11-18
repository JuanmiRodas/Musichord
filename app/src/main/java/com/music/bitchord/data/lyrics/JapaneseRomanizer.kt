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

    /** Simplified (and a few traditional) forms whose Japanese form differs from a plain Hans→Hant swap. */
    private val TO_JAPANESE = mapOf(
        '谁' to '誰', '仆' to '僕', '们' to '達', '这' to '這', '说' to '説', '时' to '時', '爱' to '愛',
        '见' to '見', '梦' to '夢', '现' to '現', '让' to '譲', '还' to '還', '问' to '問', '间' to '間',
        '门' to '門', '会' to '会', '过' to '過', '对' to '対', '为' to '為', '么' to '麼', '远' to '遠',
        '边' to '辺', '泪' to '涙', '声' to '声', '恋' to '恋', '风' to '風', '飞' to '飛', '乐' to '楽',
        '车' to '車', '东' to '東', '长' to '長', '开' to '開', '关' to '関', '无' to '無', '语' to '語',
        '话' to '話', '记' to '記', '忆' to '憶', '续' to '続', '实' to '実', '变' to '変', '样' to '様',
        '还' to '還', '岁' to '歳', '处' to '処', '轻' to '軽', '颜' to '顔', '笔' to '筆', '红' to '紅',
        '绿' to '緑', '蓝' to '藍', '鸟' to '鳥', '鱼' to '魚', '马' to '馬', '买' to '買', '卖' to '売',
        '读' to '読', '写' to '写', '听' to '聴', '亲' to '親', '恶' to '悪', '错' to '錯', '经' to '経',
        '结' to '結', '给' to '給', '终' to '終', '线' to '線', '纸' to '紙', '约' to '約', '级' to '級',
        '热' to '熱', '烧' to '焼', '灯' to '灯', '饭' to '飯', '钟' to '鐘', '铁' to '鉄', '银' to '銀',
        '钱' to '銭', '树' to '樹', '楼' to '楼', '桥' to '橋', '强' to '強', '胜' to '勝', '战' to '戦',
        '单' to '単', '义' to '義', '学' to '学', '觉' to '覚', '运' to '運', '远' to '遠', '进' to '進',
        '选' to '選', '连' to '連', '违' to '違', '择' to '択', '护' to '護', '认' to '認', '识' to '識',
        '谢' to '謝', '请' to '請', '论' to '論', '证' to '証', '诗' to '詩', '谈' to '談', '调' to '調',
        '负' to '負', '贵' to '貴', '费' to '費', '贴' to '貼', '弃' to '棄', '虽' to '雖', '气' to '気',
        '归' to '帰', '发' to '発', '广' to '広', '欢' to '歓', '难' to '難', '杂' to '雑', '离' to '離',
    )

    private val formCache = java.util.concurrent.ConcurrentHashMap<Char, Char>()

    private fun knows(tokenizer: com.atilika.kuromoji.ipadic.Tokenizer, ch: Char): Boolean =
        runCatching { tokenizer.tokenize(ch.toString()).firstOrNull()?.reading }.getOrNull()
            ?.let { it.isNotBlank() && it != "*" } == true

    /** [line] with every Han character the dictionary can't read replaced by its Japanese form, when there is one. */
    private fun japaneseForms(line: String, tokenizer: com.atilika.kuromoji.ipadic.Tokenizer): String {
        if (line.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return line
        return buildString(line.length) {
            line.forEach { ch ->
                if (Character.UnicodeScript.of(ch.code) != Character.UnicodeScript.HAN) {
                    append(ch); return@forEach
                }
                append(formCache.getOrPut(ch) {
                    if (knows(tokenizer, ch)) return@getOrPut ch
                    TO_JAPANESE[ch]?.let { return@getOrPut it }
                    // Otherwise its traditional form, which Japanese mostly shares.
                    val hant = if (android.os.Build.VERSION.SDK_INT >= 29) runCatching {
                        android.icu.text.Transliterator.getInstance("Hans-Hant").transliterate(ch.toString())
                    }.getOrNull() else null
                    hant?.singleOrNull()?.takeIf { it != ch && knows(tokenizer, it) } ?: ch
                })
            }
        }
    }

    /** A token as read here: the dictionary's, or two of its joined under a reading of their own. */
    private class Tok(
        val surface: String,
        val reading: String?,
        val partOfSpeechLevel1: String,
        val partOfSpeechLevel2: String,
        val partOfSpeechLevel3: String,
        val position: Int,
    )

    /**
     * BitChord Audio: the reading a word most probably has in a song. The dictionary splits
     * a number and its counter (二人 → 二 + 人, "ni nin") and picks rarer readings for some
     * everyday words; these are read as they are sung — futari, hitori, otona, kyou…
     */
    private val USUAL = mapOf(
        "一人" to "ヒトリ", "二人" to "フタリ", "大人" to "オトナ", "今日" to "キョウ", "昨日" to "キノウ",
        "明日" to "アシタ", "一つ" to "ヒトツ", "二つ" to "フタツ", "三つ" to "ミッツ", "一緒" to "イッショ",
        "一番" to "イチバン", "何故" to "ナゼ", "何処" to "ドコ", "貴方" to "アナタ", "貴女" to "アナタ",
        "一瞬" to "イッシュン", "永遠" to "エイエン", "刹那" to "セツナ", "日々" to "ヒビ", "人々" to "ヒトビト",
        "上手" to "ジョウズ", "下手" to "ヘタ", "大切" to "タイセツ", "一日" to "イチニチ", "今年" to "コトシ",
        "眼差し" to "マナザシ", "微笑み" to "ホホエミ", "微笑ん" to "ホホエン", "微笑む" to "ホホエム",
        "時計" to "トケイ", "景色" to "ケシキ", "宇宙" to "ウチュウ", "未来" to "ミライ", "世界" to "セカイ",
        "一人きり" to "ヒトリキリ", "二人きり" to "フタリキリ", "誰か" to "ダレカ", "何度" to "ナンド",
        "何時" to "イツ", "何もかも" to "ナニモカモ", "今夜" to "コンヤ", "今宵" to "コヨイ", "故郷" to "フルサト",
    )
    private val USUAL_SINGLE = mapOf("空" to "ソラ", "時" to "トキ", "心" to "ココロ", "瞳" to "ヒトミ", "涙" to "ナミダ", "誰" to "ダレ")

    private fun readings(tokens: List<com.atilika.kuromoji.ipadic.Token>): List<Tok> {
        val out = ArrayList<Tok>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            // Up to three tokens joined when together they are one of [USUAL].
            val joined = (3 downTo 2).firstNotNullOfOrNull { n ->
                if (i + n > tokens.size) return@firstNotNullOfOrNull null
                val surface = tokens.subList(i, i + n).joinToString("") { it.surface }
                USUAL[surface]?.let { n to Tok(surface, it, "名詞", "一般", "*", t.position) }
            }
            if (joined != null) {
                out += joined.second
                i += joined.first
                continue
            }
            val reading = USUAL[t.surface] ?: USUAL_SINGLE[t.surface]?.takeIf { t.partOfSpeechLevel1 == "名詞" } ?: t.reading
            out += Tok(t.surface, reading, t.partOfSpeechLevel1, t.partOfSpeechLevel2, t.partOfSpeechLevel3, t.position)
            i++
        }
        return out
    }

    private fun pieces(raw: String): List<Piece>? {
        val (prepared, fromAt, toAt) = prepareMapped(raw)
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
        // BitChord Audio: lyrics from Chinese services (KuGou above all) write
        // Japanese with Chinese simplified characters — 谁 for 誰, 仆 for 僕 —
        // which the Japanese dictionary can't read, so they stayed as kanji in
        // the romaji. Each one is swapped for its Japanese form first, one
        // character for one (so the timing map holds).
        val line = japaneseForms(prepared, tokenizer)
        val tokens = runCatching { tokenizer.tokenize(line) }.getOrNull()?.let(::readings) ?: return null
        val out = ArrayList<Piece>()
        var gap = false
        var carry = ""
        var previousPos: String? = null
        var previousNamed = false
        fun spanOf(tok: Tok): Pair<Int, Int> {
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

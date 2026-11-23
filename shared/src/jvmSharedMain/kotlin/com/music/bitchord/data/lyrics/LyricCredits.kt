package com.music.bitchord.data.lyrics

/**
 * BitChord Audio: the credits many lyrics open with — "作詞：…", "Composed by …",
 * "Title - Artist" — taken off the top, so the lyrics start with the first sung line
 * and the screen stays clear until then. Only lines before the first real lyric go;
 * anything that might be sung stays.
 */
object LyricCredits {
    private val CREDIT = Regex(
        """^\s*[\[(【「]?\s*(作詞|作曲|編曲|作詞・作曲|作詞作曲|歌|唄|ボーカル|词|曲|作词|编曲|演唱|制作人|監修|lyrics?(\s+by)?|lyricist|written(\s+by)?|writers?|composers?|composed(\s+by)?|music(\s+by)?|arranged?(\s+by)?|arranger|producers?|produced(\s+by)?|vocals?|mix(ed)?(\s+by)?|letra|música|title|artist|album|original|op|ed|singers?|song|performers?|performed(\s+by)?|featuring|feat\.?|guitars?|bass|drums?|piano|keyboards?|strings|synth(esizer)?s?|programming|recording|recorded(\s+by)?|engineers?|engineered(\s+by)?|mastering|mastered(\s+by)?|mixing|publishers?|published(\s+by)?|label|copyright|©|source|from|anime|tv\s*anime|opening|ending|insert\s*song|theme\s*song|主題歌|挿入歌|オープニング|エンディング|出品|监制|監製|混音|母带|吉他|贝斯|鼓|键盘|和声|合声|录音|錄音|弦乐|弦樂|发行|發行|出版|统筹|策划|企划|版权|版權|原唱|翻唱|コーラス|ギター|ベース|ドラム|ピアノ|ストリングス|プログラミング|レコーディング|ミックス|マスタリング)\s*[\]）)】」]?\s*([:：／/|｜\-–—]|by\b)""",
        RegexOption.IGNORE_CASE,
    )

    fun strip(raw: List<LyricLine>?): List<LyricLine>? {
        if (raw.isNullOrEmpty()) return raw
        val lines = raw.map(::tidy)
        var drop = 0
        for ((i, line) in lines.withIndex()) {
            if (i >= 16) break
            val t = line.text.trim()
            if (t.isEmpty()) { drop = i + 1; continue }
            if (!isCredit(t, i)) break
            drop = i + 1
        }
        if (drop == 0) return lines
        // Nothing sung left: keep them all rather than show nothing.
        if (lines.drop(drop).none { it.text.isNotBlank() }) return lines
        return lines.drop(drop)
    }

    private fun isCredit(t: String, index: Int): Boolean {
        if (CREDIT.containsMatchIn(t)) return true
        // Rights notices the Chinese services put on top ("TME享有本翻译作品的著作权", "未经许可…").
        if (Regex("著作权|著作權|未经|未經|版权所有|版權所有|享有本|All rights reserved", RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
        // "Song - Artist" / "Artist「Song」" as the very first lines, short and with no sentence in it.
        if (index <= 1 && t.length <= 60 && (Regex("""\S\s[-–—]\s\S""").containsMatchIn(t) || Regex("""\S「[^」]+」\s*$""").containsMatchIn(t)) &&
            !t.contains(',') && !t.endsWith('?') && !t.endsWith('!')
        ) return true
        // A label and a name: "Key：Value" kept short.
        if (t.length <= 40 && Regex("""^[^:：]{1,14}[:：]\s*\S""").containsMatchIn(t) && t.count { it == ' ' } <= 5) return true
        return false
    }

    // ---- BitChord Audio: words kept whole ----
    //
    // Found in the saved lyrics (the KuGou/QQ ones): every letter outside plain ASCII is
    // set apart with spaces, as those services do with Chinese characters — "qui é n eres",
    // "El ú ltimo soplo de mi coraz ó n", "I ' m". Where the space was really a word break
    // is lost, so it is worked out from the language: a lone accented vowel joins the piece
    // before it unless that is a word of its own ("El", "que") and the piece after can't
    // start a word ("ltimo", "ste"); it joins the piece after when that can't stand alone
    // ("n", "s", "r") or carries on a vowel ("d í a" → "día"). An apostrophe between two
    // letters closes up ("I ' m" → "I'm").

    private val LONE_MARK = Regex("\\s+(\\p{M})")
    private val LONE = Regex("(\\p{L}+) ([áéíóúàèìòùâêîôûñüÁÉÍÓÚÀÈÌÒÙÑÜ]) (\\p{L}+)")
    private val LONE_END = Regex("(\\p{L}+) ([áéíóúñÁÉÍÓÚÑ])(?=[\\s,.!?;:)\\]\"»]|$)")
    private val APOSTROPHE = Regex("(\\p{L}) ?(['’]) ?(s|t|m|re|ve|ll|d|n|S|T|M|RE|VE|LL|D)\\b")
    private val APOSTROPHE_N = Regex("(\\p{L}n) ?(['’]) ?(t)\\b")
    private val WORDS = setOf(
        "a", "ahora", "al", "algo", "alguien", "alma", "amor", "antes", "aquel", "aquella", "aunque", "bien", "boca", "cada", "casa", "cielo", "como", "con", "corazon", "cosa", "cosas", "cual", "cuando", "da", "de", "decir", "dejar", "del", "desde", "despues", "di", "dia", "dice", "dime", "donde", "dos", "doy", "e", "el", "ella", "ellas", "ellos", "en", "entre", "eres", "es", "esa", "esas", "ese", "eso", "esos", "esta", "estamos", "estas", "este", "estos", "estoy", "fin", "fue", "fui", "gente", "ha", "haber", "hace", "hacer", "hacia", "han", "has", "hasta", "hay", "he", "hora", "hoy", "jamas", "la", "las", "le", "les", "llegar", "lo", "los", "luego", "luna", "mal", "mano", "manos", "mar", "mas", "me", "mejor", "menos", "mi", "mia", "mientras", "mil", "mio", "mirar", "mis", "misma", "mismo", "mucha", "mucho", "muchos", "mundo", "muy", "nada", "nadie", "ni", "no", "noche", "nos", "nosotros", "nunca", "o", "ojos", "os", "otra", "otras", "otro", "otros", "para", "pasar", "paso", "pensar", "peor", "pero", "piel", "poca", "poco", "por", "porque", "puede", "pueden", "puedo", "que", "quedar", "quien", "quieres", "quiero", "saber", "sabes", "se", "seguir", "sentir", "ser", "sera", "seras", "si", "siempre", "sin", "sobre", "sol", "sola", "solo", "somos", "son", "soy", "su", "sus", "suya", "suyo", "tambien", "tan", "tanto", "te", "tener", "tengo", "tiempo", "tiene", "tienes", "toda", "todas", "todo", "todos", "tres", "tu", "tus", "tuya", "tuyo", "u", "un", "una", "unas", "unos", "usted", "ustedes", "va", "vamos", "van", "vas", "veces", "ver", "ves", "vez", "vida", "volver", "vosotros", "voy", "voz", "y", "ya", "yo",
    )
    private val START_PAIRS = setOf("bl", "br", "cl", "cr", "dr", "fl", "fr", "gl", "gr", "pl", "pr", "tr", "ch", "ll", "qu", "gu", "ps", "tl")
    private fun isVowel(c: Char) = c.lowercaseChar() in "aeiouáéíóúü"
    private val ENCLITIC = Regex("(me|te|se|lo|la|le|nos|los|las|les)$", RegexOption.IGNORE_CASE)

    /** Whether [w] could be a whole Spanish word as far as its ending goes. */
    private fun canEndWord(w: String): Boolean {
        val l = w.lowercase()
        if (isVowel(l.last())) return true
        return l.last() in "dlnrszyjx" && l.length >= 2 && isVowel(l[l.length - 2])
    }

    private fun endsInTwoConsonants(w: String): Boolean =
        w.length >= 2 && !isVowel(w.last()) && !isVowel(w[w.length - 2]) && w.last().isLetter() && w[w.length - 2].isLetter()

    /** Whether [w] could be where a Spanish word begins. */
    private fun canStartWord(w: String): Boolean {
        val l = w.lowercase()
        if (l.length < 2) return l == "y" || l == "a" || l == "o" || l == "e" || l == "u"
        if (isVowel(l[0]) || isVowel(l[1])) return true
        return l.substring(0, 2) in START_PAIRS
    }

    private fun fixSpacing(text: String): String {
        var t = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC).replace(LONE_MARK, "$1")
        repeat(3) {
            t = LONE.replace(t) { m ->
                val (l, v, r) = m.destructured
                val lw = l.lowercase() in WORDS
                val rw = r.lowercase() in WORDS
                val wordable = lw || (l.length >= 3 && canEndWord(l))
                // "s é d ó nde": a piece followed by another lone accent begins the next word.
                val next = t.substring(m.range.last + 1).take(2)
                val beforeAccent = next.length == 2 && next[0] == ' ' && next[1] in "áéíóúÁÉÍÓÚ"
                val (joinLeft, joinRight) = when {
                    beforeAccent && r.length <= 2 -> true to false
                    // "coraz ó n", "m á s", "C ó mo": a tail that is no word ends this one.
                    r.length <= 2 && !rw -> true to true
                    // "d í a", "hab í a", "fr í o": a vowel after the accent, the same word.
                    isVowel(r[0]) && r.length <= 3 && !lw &&
                        (!rw || (v.lowercase() in setOf("í", "ú") && r[0].lowercaseChar() in "ao")) -> true to true
                    // "ú ltimo", "á ngel", "é ste": the accent begins the next word — after
                    // a word ("El", "del", "que"), else it is the middle of one ("Cu á nto").
                    !canStartWord(r) -> !wordable to true
                    // "m í rame", "s í gueme": a pronoun on the end of a verb.
                    l.length <= 2 && !lw && !rw && r.length <= 6 && ENCLITIC.containsMatchIn(r) -> true to true
                    // "t ú nel", "p é talo": a single consonant before can't be a word.
                    !rw && r.length >= 3 && !lw && l.length == 1 && l.lowercase() != "y" && !isVowel(r[0]) -> true to true
                    // "Ser á demasiado", "lleg ó nuestra", "As í estamos": the accent ends a word.
                    else -> true to false
                }
                (if (joinLeft) l + v else "$l $v") + (if (joinRight) r else " $r")
            }
        }
        t = LONE_END.replace(t) { m -> if (m.groupValues[1].lowercase() in WORDS && m.groupValues[2].lowercase() !in setOf("á", "é", "í", "ó", "ú")) m.value else m.groupValues[1] + m.groupValues[2] }
        t = APOSTROPHE_N.replace(t) { m -> m.groupValues[1] + m.groupValues[2] + m.groupValues[3] }
        t = APOSTROPHE.replace(t) { m -> m.groupValues[1] + m.groupValues[2] + m.groupValues[3] }
        return t
    }

    /** [words] regrouped to the words of [text] (each word of the text made of whole timed words). */
    private fun regroup(words: List<LyricWord>, text: String): List<LyricWord>? {
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val out = ArrayList<LyricWord>()
        var i = 0
        for (tok in tokens) {
            if (i >= words.size) return null
            var built = ""
            val first = words[i]
            var last = first
            while (i < words.size && built.length < tok.length) {
                val w = java.text.Normalizer.normalize(words[i].text, java.text.Normalizer.Form.NFC).replace(" ", "")
                built += w
                last = words[i]
                i++
            }
            if (built != tok) return null
            out += if (first === last) first.copy(text = tok, syllables = if (first.text == tok) first.syllables else emptyList())
            else LyricWord(first.startMs, last.endMs, tok)
        }
        return out.takeIf { i == words.size }
    }

    private fun tidy(line: LyricLine): LyricLine {
        val text = fixSpacing(line.text)
        val words = when {
            line.words.isEmpty() -> line.words
            text == line.text -> line.words
            else -> regroup(line.words, text) ?: emptyList()
        }
        val background = line.background?.let { tidy(it) }
        return if (text == line.text && words == line.words && background == line.background) line
        else line.copy(text = text, words = words, background = background)
    }
}

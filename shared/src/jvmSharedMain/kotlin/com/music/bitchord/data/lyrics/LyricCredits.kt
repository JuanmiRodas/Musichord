package com.music.bitchord.data.lyrics

/**
 * BitChord Audio: the credits many lyrics open with — "作詞：…", "Composed by …",
 * "Title - Artist" — taken off the top, so the lyrics start with the first sung line
 * and the screen stays clear until then. Only lines before the first real lyric go;
 * anything that might be sung stays.
 */
object LyricCredits {
    private val CREDIT = Regex(
        """^\s*[\[(【「]?\s*(作詞|作曲|編曲|作詞・作曲|作詞作曲|歌|唄|ボーカル|词|曲|作词|编曲|演唱|制作人|監修|lyrics?(\s+by)?|lyricist|written(\s+by)?|writers?|composers?|composed(\s+by)?|music(\s+by)?|arranged?(\s+by)?|arranger|producers?|produced(\s+by)?|vocals?|mix(ed)?(\s+by)?|letra|música|title|artist|album|original|op|ed)\s*[\]）)】」]?\s*([:：／/|｜\-–—]|by\b)""",
        RegexOption.IGNORE_CASE,
    )

    fun strip(lines: List<LyricLine>?): List<LyricLine>? {
        if (lines.isNullOrEmpty()) return lines
        var drop = 0
        for ((i, line) in lines.withIndex()) {
            if (i >= 8) break
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
        // "Song - Artist" / "Artist「Song」" as the very first lines, short and with no sentence in it.
        if (index <= 1 && t.length <= 60 && (Regex("""\S\s[-–—]\s\S""").containsMatchIn(t) || Regex("""\S「[^」]+」\s*$""").containsMatchIn(t)) &&
            !t.contains(',') && !t.endsWith('?') && !t.endsWith('!')
        ) return true
        // A label and a name: "Key：Value" kept short.
        if (t.length <= 40 && Regex("""^[^:：]{1,14}[:：]\s*\S""").containsMatchIn(t) && t.count { it == ' ' } <= 5) return true
        return false
    }
}

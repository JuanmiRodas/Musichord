package com.music.bitchord.data.lyrics

import java.text.Normalizer
import java.util.Locale

/**
 * BitChord Audio: is a romaji name ("Mezameta Venus" · "Miho Morikawa") the
 * same name as a Japanese one (目覚めたヴィーナス · 森川美穂)?
 *
 * The Japanese side is read into romaji ([romanize], the app's kanji
 * dictionary), then both are compared by their *consonant skeleton*: vowels,
 * long marks and the letters romaji systems disagree on are dropped
 * (shi/si, tsu/tu, chi/ti, ji/zi, fu/hu, ō/ou/oo), and v/b, l/r are one
 * sound — so a katakana loanword (ヴィーナス, read "biinasu") still matches
 * the English word it stands for ("Venus"). Both "mzmtbns".
 *
 * A skeleton is a loose key, so it is only one of the checks: callers use it
 * where the length (and artist) already agree.
 */
object CrossScript {

    /** Japanese → romaji, set by the app (its dictionary lives there). */
    @Volatile
    var romanize: ((String) -> String?)? = null

    private val JAPANESE = Regex("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]")
    private val LATIN = Regex("[A-Za-z]")
    private val NOTE = Regex("""[(\[（【].*?[)\]）】]""")
    private val GUEST = Regex("""\s+(feat\.?|ft\.?|featuring|with)\s.*$""", RegexOption.IGNORE_CASE)

    fun hasJapanese(s: String) = JAPANESE.containsMatchIn(s)

    /** The consonant skeleton of a Latin-letter name. */
    fun skeleton(latin: String): String {
        var s = Normalizer.normalize(latin.replace(NOTE, " ").replace(GUEST, ""), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .filter { it in 'a'..'z' }
        s = s.replace("sh", "s").replace("ch", "t").replace("ts", "t").replace("dz", "z")
            .replace('j', 'z').replace('f', 'h').replace('v', 'b').replace('l', 'r')
            .replace('c', 'k').replace('q', 'k').replace('x', 'k')
        val out = StringBuilder()
        for (ch in s) {
            if (ch in "aeiouyhw") continue
            if (out.isNotEmpty() && out.last() == ch) continue
            out.append(ch)
        }
        return out.toString()
    }

    /** The skeleton of [name], whatever its script; null when it can't be read. */
    fun skeletonOf(name: String): String? {
        if (!hasJapanese(name)) return skeleton(name).takeIf { it.isNotEmpty() }
        val read = runCatching { romanize?.invoke(name.replace(NOTE, " ")) }.getOrNull() ?: return null
        // Latin words kept inside the Japanese ("Feeling" in 恋のFeeling) are part of it.
        return skeleton(read).takeIf { it.isNotEmpty() }
    }

    /** [a] and [b] (one romaji, one Japanese — or both either) sound the same. */
    fun sameTitle(a: String, b: String): Boolean {
        if (hasJapanese(a) == hasJapanese(b)) return false
        if (!LATIN.containsMatchIn(if (hasJapanese(a)) b else a)) return false
        val x = skeletonOf(a) ?: return false
        val y = skeletonOf(b) ?: return false
        return x.length >= 3 && x == y
    }

    /**
     * The same person or group: every name's skeleton, in any order
     * ("Miho Morikawa" · 森川美穂 read "morikawa miho").
     */
    fun sameArtist(a: String, b: String): Boolean {
        if (hasJapanese(a) == hasJapanese(b)) return false
        val latin = if (hasJapanese(a)) b else a
        val japanese = if (hasJapanese(a)) a else b
        val read = runCatching { romanize?.invoke(japanese) }.getOrNull() ?: return false
        val want = latin.split(Regex("[\\s,&、・]+")).map(::skeleton).filter { it.isNotEmpty() }
        if (want.isEmpty()) return false
        val joinedWant = want.sorted().joinToString("")
        val gotWords = read.split(Regex("[\\s,&、・]+")).map(::skeleton).filter { it.isNotEmpty() }
        // The reading may not split the name where romaji does (森川美穂 → "morikawamiho").
        val gotJoined = skeleton(read)
        return gotWords.sorted().joinToString("") == joinedWant ||
            gotJoined == want.joinToString("") || gotJoined == want.reversed().joinToString("")
    }
}

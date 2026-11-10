package com.music.bitchord.data.lyrics

import kotlin.math.min

/**
 * BitChord Audio: how good a set of lyrics is, so the best one any source
 * has, under any spelling of the song, is the one shown — not merely the
 * first to answer.
 *
 * In order of weight:
 *  1. **Timing**: word by word (karaoke fill) > line by line > none.
 *  2. For a Japanese song: lyrics that come with their own romaji (the
 *     Japanese and the source's romaji, paired line by line) > romaji only >
 *     Japanese only (left to the automatic romanisation).
 *  3. Plausibility: lines timed past the end of the track are another version;
 *     a handful of lines is a fragment.
 *  4. Completeness, as a tie-breaker.
 * The source order chosen in Settings breaks the ties that are left.
 */
object LyricsQuality {

    const val WORD = 3_000
    const val LINE = 2_000
    const val PLAIN = 1_000

    fun score(lines: List<LyricLine>, japaneseSong: Boolean, durationMs: Long): Int {
        val text = lines.filter { it.text.isNotBlank() }
        if (text.isEmpty()) return 0
        val wordSynced = text.count { it.isWordSynced } * 2 >= text.size
        val timed = text.count { it.timeMs > 0 } * 2 >= text.size
        var s = when {
            wordSynced -> WORD
            timed -> LINE
            else -> PLAIN
        }
        if (japaneseSong) s += romajiBonus(text)
        if (timed && durationMs > 0 && text.maxOf { it.timeMs } > durationMs + 15_000) s -= 1_500
        if (text.size < 6) s -= 500
        s += min(text.size, 60)
        return s
    }

    /** Whether lyrics sets in hand say this is a Japanese song. */
    fun looksJapanese(lines: List<LyricLine>): Boolean {
        val text = lines.filter { it.text.isNotBlank() }
        return text.isNotEmpty() && text.count { CrossScript.hasJapanese(it.text) } * 10 >= text.size * 3
    }

    private fun romajiBonus(text: List<LyricLine>): Int {
        val japanese = text.count { CrossScript.hasJapanese(it.text) }
        return when {
            // Japanese with the source's own romaji for (nearly) every line.
            japanese > 0 && ProvidedRomaji.forLines(text.map { it.text }) != null -> 400
            japanese == 0 && isRomaji(text) -> 300
            else -> 0
        }
    }

    private val SYLLABLES = Regex("""^(?:(?:[kgsztdnhbpmyrwfjvc]|sh|ch|ts|ky|gy|ny|hy|by|py|my|ry|j)?[aiueoāīūēō]+|n|m(?=[bpm])|[kstpc](?=[kstpc]))+$""")

    /** Latin lines that read as romaji rather than, say, an English translation. */
    private fun isRomaji(text: List<LyricLine>): Boolean {
        val words = text.flatMap { it.text.lowercase().split(Regex("[^a-zāīūēō']+")) }
            .map { it.replace("'", "") }.filter { it.length >= 2 }
        if (words.size < 8) return false
        return words.count { SYLLABLES.matches(it) } * 10 >= words.size * 7
    }
}

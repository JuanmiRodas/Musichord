package com.music.bitchord.data.lyrics

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BitChord Audio: the manual lyrics search ("Search all", in the lyrics menu),
 * run without the dialog — the same names (yours, else the song's names in
 * romaji and Japanese), the same sources, the same requests — so what the
 * automatic choice sees is exactly what you would see searching by hand.
 * The best of it is then picked by [LyricsQuality]: word by word first.
 */
object LyricsSearchAll {

    /** Every answer for [song], as the manual search gets them. */
    suspend fun answers(song: Song, maxTitles: Int = 3, maxArtists: Int = 2, timeoutMs: Long = 30_000): List<LyricsRepository.Result> {
        val mine = LyricAliases.userNames(song.videoId, song.title, song.artist)
        val (titles, artists) = if (!mine.isEmpty) {
            mine.titles to mine.artists
        } else {
            val s = runCatching { LyricNames.suggest(song) }.getOrNull()
            (s?.titles ?: listOf(song.title)) to (s?.artists ?: listOf(song.artist))
        }
        val ts = titles.map(String::trim).filter(String::isNotEmpty).distinct().take(maxTitles)
        if (ts.isEmpty()) return emptyList()
        val ars = artists.map(String::trim).filter(String::isNotEmpty).distinct().take(maxArtists).ifEmpty { listOf("") }
        val duration = song.durationMillis()
        val flagged = BadLyrics.flagged(song.videoId)
        val sources = LyricsSource.offered.filter { it.name !in flagged }
        val out = java.util.Collections.synchronizedList(ArrayList<LyricsRepository.Result>())
        withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                for (t in ts) for (a in ars) launch {
                    LyricsRepository.everyAnswer(song.videoId, t, a, duration, song.albumName, sources) { r -> out += r }
                }
            }
        }
        return synchronized(out) { out.toList() }
    }

    /** The best of [answers] for a song of [durationMs]: word by word, then line by line, then the rest. */
    fun best(answers: List<LyricsRepository.Result>, durationMs: Long, japaneseSong: Boolean = false): LyricsRepository.Result? {
        if (answers.isEmpty()) return null
        val japanese = japaneseSong || answers.any { LyricsQuality.looksJapanese(it.lines) }
        return answers.maxByOrNull { LyricsQuality.score(it.lines, japanese, durationMs) }
    }

    /**
     * One scale for every comparison of two lyrics of a song — on screen, kept,
     * in the background — so two places never disagree and swap them back and
     * forth. [japaneseSong]: the song is Japanese (its names, or any lyrics
     * found for it, say so) — whatever these [lines] are written in.
     */
    fun score(lines: List<LyricLine>, durationMs: Long, japaneseSong: Boolean = false): Int =
        LyricsQuality.score(lines, japaneseSong || LyricsQuality.looksJapanese(lines), durationMs)

    fun namesJapanese(song: Song): Boolean =
        CrossScript.hasJapanese(song.title) || CrossScript.hasJapanese(song.artist) || CrossScript.hasJapanese(song.albumName.orEmpty())

    /**
     * Whether two sets of lyrics are the same song — compared in traditional
     * kanji (a Chinese source writes some simplified) and regardless of where
     * the lines break.
     */
    fun sameSong(a: List<LyricLine>, b: List<LyricLine>): Boolean {
        val toTraditional = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            runCatching { android.icu.text.Transliterator.getInstance("Hans-Hant") }.getOrNull()
        } else {
            null
        }
        fun pairs(lines: List<LyricLine>): Set<String> {
            val raw = lines.joinToString("") { it.text }
            val t = (toTraditional?.transliterate(raw) ?: raw).lowercase().filter { it.isLetterOrDigit() }
            return (0 until (t.length - 1).coerceAtLeast(0)).map { t.substring(it, it + 2) }.toSet()
        }
        val x = pairs(a)
        val y = pairs(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x.count { it in y }.toDouble() / minOf(x.size, y.size) >= 0.6
    }
}

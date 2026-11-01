package com.music.bitchord.data.lyrics

import com.music.bitchord.data.LocalLikes
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.JapaneseReading
import com.music.bitchord.data.sources.SourceMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * BitChord Audio: the names a song's lyrics could be filed under, to start the
 * "search by other names" fields with — so they are rarely typed by hand.
 *
 * The song's own title and artist; the other copy's names (the YouTube song a
 * file of yours stands in for, or the file a YouTube song plays from); the
 * romaji of a Japanese title; and for a title already in romaji, the Japanese
 * it most likely is — kana always, and kanji from Google's Japanese input
 * (the same conversion a phone keyboard does) when online.
 */
object LyricNames {

    data class Suggested(val titles: List<String>, val artists: List<String>)

    suspend fun suggest(song: Song): Suggested = withContext(Dispatchers.IO) {
        val titles = LinkedHashSet<String>()
        val artists = LinkedHashSet<String>()
        fun add(title: String?, artist: String?) {
            title?.trim()?.takeIf { it.isNotEmpty() }?.let(titles::add)
            artist?.trim()?.takeIf { it.isNotEmpty() }?.let(artists::add)
        }
        add(song.title, song.artist)
        others(song).forEach { add(it.title, it.artist) }

        for (t in titles.toList()) {
            if (JapaneseReading.isJapanese(t)) {
                runCatching { JapaneseReading.romaji(t) }.getOrNull()?.let { titles += it }
            } else {
                kana(t)?.let { k ->
                    runCatching { japanese(t) }.getOrNull().orEmpty().take(2).forEach { titles += it }
                    titles += k
                }
            }
        }
        for (a in artists.toList()) {
            if (JapaneseReading.isJapanese(a)) {
                runCatching { JapaneseReading.romaji(a) }.getOrNull()?.let { artists += it }
            } else if (kana(a) != null) {
                runCatching { japanese(a) }.getOrNull()?.firstOrNull()?.let { artists += it }
            }
        }
        Suggested(titles.toList().take(6), artists.toList().take(4))
    }

    private val variantCache = java.util.concurrent.ConcurrentHashMap<String, List<LyricAliases.Query>>()

    /**
     * Other spellings to look the lyrics up by, made automatically: the
     * Japanese title and artist in romaji, and a romaji title or artist in
     * the Japanese it most likely is. Japanese catalogues file songs under
     * the Japanese names, the English ones often under romaji — so each
     * lookup gets both. Cached per song; the kanji guess waits at most a few
     * seconds for the network and is skipped offline.
     */
    suspend fun variants(title: String, artist: String): List<LyricAliases.Query> {
        val key = "$title\u0000$artist"
        variantCache[key]?.let { return it }
        val out = withContext(Dispatchers.IO) {
            val titles = otherSpellings(title)
            val artists = otherSpellings(artist)
            buildList {
                for (t in titles) for (a in artists) add(LyricAliases.Query(t, a))
                for (t in titles) add(LyricAliases.Query(t, artist))
                for (a in artists) add(LyricAliases.Query(title, a))
            }.distinct().filterNot { it.title == title && it.artist == artist }
        }
        variantCache[key] = out
        return out
    }

    private suspend fun otherSpellings(name: String): List<String> {
        if (name.isBlank()) return emptyList()
        if (JapaneseReading.isJapanese(name)) {
            return listOfNotNull(runCatching { JapaneseReading.romaji(name) }.getOrNull())
        }
        if (kana(name) == null) return emptyList()
        return kotlinx.coroutines.withTimeoutOrNull(3_500) {
            runCatching { japanese(name) }.getOrNull()?.take(1)
        }.orEmpty()
    }

    /** The other copies of [song]: the YouTube song it stands in for, the file it plays from. */
    private fun others(song: Song): List<Song> {
        val out = ArrayList<Song>()
        SourceMap.entry(song.videoId)?.file?.let { SourceMap.fileSong(it) }?.let(out::add)
        LocalLikes.youTubeOf(song.videoId)?.let { yt ->
            com.music.bitchord.data.LikedMusicCache.load(com.music.bitchord.data.LikedMusicCache.lastProfile)
                ?.songs?.firstOrNull { it.videoId == yt }?.let(out::add)
        }
        return out
    }

    /** Google's Japanese conversion of romaji [text]: the likeliest spellings, kanji and all. */
    private fun japanese(text: String): List<String> {
        val q = URLEncoder.encode(text.lowercase(), "UTF-8")
        val url = URL("https://inputtools.google.com/request?text=$q&itc=ja-t-i0-und&num=3&cp=0&cs=1&ie=utf-8&oe=utf-8")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 4_000
        }
        return try {
            val body = conn.inputStream.bufferedReader().readText()
            val root = JSONArray(body)
            if (root.optString(0) != "SUCCESS") return emptyList()
            val segments = root.getJSONArray(1)
            // One segment per word group: join the best of each, then the other whole candidates.
            if (segments.length() == 1) {
                val c = segments.getJSONArray(0).getJSONArray(1)
                (0 until c.length()).map { c.getString(it) }
            } else {
                listOf((0 until segments.length()).joinToString("") {
                    segments.getJSONArray(it).getJSONArray(1).optString(0)
                })
            }.filter { JapaneseReading.isJapanese(it) }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * [text] in hiragana when it reads as romaji throughout ("Kimi no Na wa" →
     * きみのなは), else null — so English titles are left alone.
     */
    fun kana(text: String): String? {
        val words = text.lowercase().replace(Regex("[^a-zâêîôûāēīōū' -]"), " ")
            .split(' ', '-').filter { it.isNotEmpty() }
        if (words.isEmpty() || text.any { it.isDigit() }) return null
        val sb = StringBuilder()
        for (w in words) sb.append(word(w) ?: return null)
        return sb.toString()
    }

    private fun word(raw: String): String? {
        val w = raw.replace("'", "")
            .replace('ā', 'a').replace('â', 'a').replace('ū', 'u').replace('û', 'u')
            .replace('ē', 'e').replace('ê', 'e').replace("ō", "ou").replace("ô", "ou")
            .replace("ī", "ii").replace("î", "ii")
        val sb = StringBuilder()
        var i = 0
        while (i < w.length) {
            // っ: a doubled consonant
            if (i + 1 < w.length && w[i] == w[i + 1] && w[i] !in "aeioun") {
                sb.append('っ'); i++; continue
            }
            if (w.startsWith("tch", i)) { sb.append('っ'); i++; continue }
            var hit: String? = null
            for (len in 3 downTo 1) {
                if (i + len > w.length) continue
                SYLLABLES[w.substring(i, i + len)]?.let { hit = it; i += len }
                if (hit != null) break
            }
            if (hit == null) {
                // ん: n before a consonant, an apostrophe, or the end
                if (w[i] == 'n' || (w[i] == 'm' && i + 1 < w.length && w[i + 1] in "bmp")) { sb.append('ん'); i++; continue }
                return null
            }
            sb.append(hit)
        }
        return sb.toString()
    }

    private val SYLLABLES: Map<String, String> = buildMap {
        val rows = listOf(
            "" to "あいうえお", "k" to "かきくけこ", "s" to "さしすせそ", "t" to "たちつてと",
            "n" to "なにぬねの", "h" to "はひふへほ", "m" to "まみむめも", "r" to "らりるれろ",
            "g" to "がぎぐげご", "z" to "ざじずぜぞ", "d" to "だぢづでど", "b" to "ばびぶべぼ",
            "p" to "ぱぴぷぺぽ",
        )
        for ((c, kana) in rows) "aiueo".forEachIndexed { i, v -> put(c + v, kana[i].toString()) }
        putAll(mapOf(
            "ya" to "や", "yu" to "ゆ", "yo" to "よ", "wa" to "わ", "wo" to "を",
            "shi" to "し", "chi" to "ち", "tsu" to "つ", "fu" to "ふ", "ji" to "じ",
            "si" to "し", "ti" to "ち", "tu" to "つ", "hu" to "ふ", "zi" to "じ", "di" to "ぢ", "du" to "づ",
            "fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ", "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ",
            "je" to "じぇ", "she" to "しぇ", "che" to "ちぇ", "ti" to "ち", "vu" to "ゔ",
        ))
        for ((c, i) in listOf("k" to "き", "g" to "ぎ", "n" to "に", "h" to "ひ", "b" to "び", "p" to "ぴ", "m" to "み", "r" to "り")) {
            put(c + "ya", i + "ゃ"); put(c + "yu", i + "ゅ"); put(c + "yo", i + "ょ")
        }
        for ((c, i) in listOf("sh" to "し", "ch" to "ち", "j" to "じ")) {
            put(c + "a", i + "ゃ"); put(c + "u", i + "ゅ"); put(c + "o", i + "ょ")
        }
    }
}

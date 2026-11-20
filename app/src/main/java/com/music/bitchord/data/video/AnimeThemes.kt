package com.music.bitchord.data.video

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.JapaneseReading
import com.music.bitchord.data.sources.OwnFileMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: AnimeThemes (animethemes.moe) — anime openings and endings, clean
 * encodes, usually better than an upload on YouTube — as video mode's picture for a song
 * that is one of them.
 *
 * Its catalogue names the song and who sings it, which is checked against yours loosely:
 * romaji spellings differ ("Rica"/"Rika Matsumoto", "Tsu"/"Tu"), a title may be in kanji
 * on one side and romaji on the other, and a credit may name the character or the singer.
 */
object AnimeThemes {
    private const val TAG = "VideoMode"
    private const val API = "https://api.animethemes.moe"

    /** One theme's video: the anime, which theme (OP2, ED1…), the song, and the file. */
    data class Theme(
        val anime: String,
        val slug: String,
        val title: String,
        val titleNative: String?,
        val artists: List<String>,
        /** The WebM's address on v.animethemes.moe. */
        val video: String,
        /** Its sound alone (an Ogg on a.animethemes.moe) — small, for the sync. */
        val audio: String,
        val resolution: Int,
        val cover: String?,
    ) {
        val label: String get() = "$anime $slug"
    }

    private val searched = ConcurrentHashMap<String, List<Theme>>()

    /** Themes AnimeThemes finds for [query] (any of song, anime or artist). */
    suspend fun search(query: String): List<Theme> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        searched[q]?.let { return@withContext it }
        val url = "$API/search?q=" + URLEncoder.encode(q, "UTF-8") +
            "&fields%5Bsearch%5D=animethemes" +
            "&include%5Banimetheme%5D=" + URLEncoder.encode("anime.images,song.artists,animethemeentries.videos", "UTF-8") +
            "&page%5Blimit%5D=15"
        val body = runCatching {
            Http.client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use {
                if (it.isSuccessful) it.body?.string() else {
                    TrackLog.w(TAG, "AnimeThemes: search answered ${it.code}")
                    null
                }
            }
        }.getOrNull() ?: return@withContext emptyList()
        val themes = runCatching { parse(JSONObject(body)) }.getOrElse {
            TrackLog.w(TAG, "AnimeThemes: unreadable answer: ${it.message}")
            emptyList()
        }
        if (searched.size > 200) searched.clear()
        searched[q] = themes
        themes
    }

    private fun parse(root: JSONObject): List<Theme> {
        val list = root.optJSONObject("search")?.optJSONArray("animethemes") ?: root.optJSONArray("animethemes") ?: return emptyList()
        val out = ArrayList<Theme>()
        for (i in 0 until list.length()) {
            val t = list.optJSONObject(i) ?: continue
            val anime = t.optJSONObject("anime")
            val song = t.optJSONObject("song") ?: continue
            val title = song.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val artists = (song.optJSONArray("artists") ?: JSONArray()).let { a ->
                (0 until a.length()).mapNotNull { k ->
                    val o = a.optJSONObject(k) ?: return@mapNotNull null
                    // The credit as the theme gives it ("as" a character), and the singer's own name.
                    listOfNotNull(
                        o.optString("name").takeIf { it.isNotBlank() },
                        o.optJSONObject("artistsong")?.optString("as")?.takeIf { it.isNotBlank() && it != "null" },
                    )
                }.flatten()
            }
            val cover = anime?.optJSONArray("images")?.let { imgs ->
                (0 until imgs.length()).mapNotNull { imgs.optJSONObject(it) }
                    .sortedBy { if (it.optString("facet").contains("Small", true)) 0 else 1 }
                    .firstNotNullOfOrNull { it.optString("link").takeIf { l -> l.startsWith("http") } }
            }
            // The best video of its first version: the highest resolution, a creditless one
            // (no text over the picture, under the lyrics) when there are two alike.
            val entries = t.optJSONArray("animethemeentries") ?: continue
            val videos = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }
                .sortedBy { it.optInt("version", 1).coerceAtLeast(1) }
                .flatMap { e -> e.optJSONArray("videos")?.let { v -> (0 until v.length()).mapNotNull { v.optJSONObject(it) } }.orEmpty() }
            val best = videos.maxWithOrNull(compareBy<JSONObject>({ it.optInt("resolution") }, { if (it.optBoolean("nc")) 1 else 0 }))
                ?: continue
            val link = best.optString("link").takeIf { it.startsWith("http") }
                ?: best.optString("basename").takeIf { it.isNotBlank() }?.let { "https://v.animethemes.moe/$it" }
                ?: continue
            val base = link.substringAfterLast('/').substringBeforeLast('.')
            out += Theme(
                anime = anime?.optString("name").orEmpty(),
                slug = t.optString("slug").ifBlank { t.optString("type") + t.optInt("sequence", 1) },
                title = title,
                titleNative = song.optString("title_native").takeIf { it.isNotBlank() && it != "null" },
                artists = artists.distinct(),
                video = link,
                audio = "https://a.animethemes.moe/$base.ogg",
                resolution = best.optInt("resolution"),
                cover = cover,
            )
        }
        return out
    }

    // ── Loose matching: romaji spellings, kanji and romaji ──

    /** Spelling folded: Hepburn and Kunrei and English habits alike — "Rica" is "Rika". */
    private fun fold(s: String): String {
        val romaji = JapaneseReading.romaji(s) ?: s
        var k = JapaneseReading.soundKey(romaji)
        for ((a, b) in listOf("ck" to "k", "c" to "k", "q" to "k", "l" to "r", "v" to "b", "ph" to "f", "x" to "ks", "y" to "i", "h" to "")) {
            k = k.replace(a, b)
        }
        return k.replace(Regex("(.)\\1+"), "$1")
    }

    private fun titleKeys(s: String): Set<String> =
        (runCatching { OwnFileMatcher.allKeys(s) }.getOrDefault(emptySet()).map { it.removePrefix("~") } + fold(s))
            .filter { it.length >= 2 }.toSet()

    private fun sameTitle(song: Song, t: Theme): Boolean {
        val mine = titleKeys(song.title)
        val theirs = titleKeys(t.title) + (t.titleNative?.let(::titleKeys) ?: emptySet())
        val foldedMine = mine.map(::fold).toSet()
        val foldedTheirs = theirs.map(::fold).toSet()
        return mine.any { it in theirs } || foldedMine.any { it.length >= 3 && it in foldedTheirs }
    }

    private fun nameWords(s: String): Set<String> =
        s.split(Regex("[\\s,&/、・()（）]+|\\bfeat\\.?|\\bcv[.:]?", RegexOption.IGNORE_CASE))
            .map { fold(it) }.filter { it.length >= 2 }.toSet()

    /** Whether someone in [t]'s credits is someone in [song]'s, any order, loosely spelt. */
    private fun sameArtist(song: Song, t: Theme): Boolean {
        val mine = song.artist.split(',', '&', '、').map { it.trim() }.filter { it.isNotEmpty() }
        if (mine.isEmpty() || mine.all { com.music.bitchord.data.sources.TrackMatcher.isPlaceholderArtist(it) }) return true
        val theirs = t.artists
        if (theirs.isEmpty()) return true
        return mine.any { m ->
            val mw = nameWords(m)
            val mk = fold(m)
            theirs.any { a ->
                val aw = nameWords(a)
                fold(a) == mk || (mw.isNotEmpty() && aw.isNotEmpty() && (mw.containsAll(aw) || aw.containsAll(mw))) ||
                    // A kanji name against its romaji, read in the dictionary.
                    JapaneseReading.romajiWords(m)?.map(::fold)?.toSet()?.let { it.isNotEmpty() && it == aw } == true ||
                    JapaneseReading.romajiWords(a)?.map(::fold)?.toSet()?.let { it.isNotEmpty() && it == mw } == true
            }
        }
    }

    /**
     * [song]'s theme on AnimeThemes, when it is one: the same title and the same singer
     * (both loosely), asked under its title and under its title with the singer.
     */
    suspend fun bestFor(song: Song): Theme? {
        val artist = song.artist.split(',', '&').firstOrNull()?.trim().orEmpty()
        val base = song.title.replace(Regex("\\s*[(\\[（【].*?[)\\]）】]\\s*"), " ").trim().ifBlank { song.title }
        val queries = listOf(base, "$base $artist").map { it.trim() }.distinct()
        for (q in queries) {
            val hit = search(q).firstOrNull { sameTitle(song, it) && sameArtist(song, it) }
            if (hit != null) {
                TrackLog.d(TAG, "AnimeThemes: '${song.title}' is ${hit.label} ('${hit.title}' by ${hit.artists.joinToString()}, ${hit.resolution}p)")
                return hit
            }
        }
        return null
    }

    /** A saved theme as a video-mode reference: the WebM and its Ogg, together. */
    fun encode(t: Theme): String = t.video + "|" + t.audio

    fun videoOf(id: String): String = id.substringBefore('|')
    fun audioOf(id: String): String? = id.substringAfter('|', "").ifBlank { null }
}

package com.music.bitchord.data.video

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.JapaneseReading
import com.music.bitchord.data.sources.OwnFileMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
        // Its GraphQL service (what animethemes.moe itself asks); the older REST search after.
        var themes = graphql(q) ?: rest(q) ?: return@withContext emptyList()
        // Nothing for the whole of it ("OK! Rica Matsumoto": their spelling is "Rika"): the
        // last words dropped, one at a time, down to the title.
        var words = q.split(Regex("\\s+"))
        while (themes.isEmpty() && words.size > 1) {
            words = words.dropLast(1)
            themes = graphql(words.joinToString(" ")).orEmpty()
        }
        if (searched.size > 200) searched.clear()
        searched[q] = themes
        themes
    }

    private const val GRAPHQL = "https://graphql.animethemes.moe/"
    private val QUERY = """
        query(${'$'}q: String!) {
          search(search: ${'$'}q, first: 15) {
            animethemes {
              type
              sequence
              slug
              anime { slug title { romaji } images { nodes { link facet } } }
              song { title { romaji native } performances { as alias artist { name { main native } } } }
              animethemeentries { version videos { nodes { basename link resolution nc audio { basename link } } } }
            }
          }
        }
    """.trimIndent()

    private fun graphql(q: String): List<Theme>? {
        val body = JSONObject().put("query", QUERY).put("variables", JSONObject().put("q", q)).toString()
            .toRequestBody("application/json".toMediaType())
        val text = runCatching {
            Http.client.newCall(
                Request.Builder().url(GRAPHQL).post(body)
                    .header("Accept", "application/json")
                    .header("User-Agent", "BitChordAudio/1.0 (Android music player)")
                    .build(),
            ).execute().use {
                if (it.isSuccessful) it.body?.string() else {
                    TrackLog.w(TAG, "AnimeThemes: GraphQL answered ${it.code}")
                    null
                }
            }
        }.onFailure { TrackLog.w(TAG, "AnimeThemes: GraphQL failed: ${it.message}") }.getOrNull() ?: return null
        return runCatching {
            val root = JSONObject(text)
            root.optJSONArray("errors")?.let { e -> TrackLog.w(TAG, "AnimeThemes: GraphQL: ${e.optJSONObject(0)?.optString("message")}") }
            val data = root.optJSONObject("data")?.optJSONObject("search")?.optJSONArray("animethemes")
                ?: return@runCatching null
            parseGraph(data)
        }.getOrNull()
    }

    private fun JSONObject.nodes(name: String): List<JSONObject> {
        val v = opt(name)
        val arr = when (v) {
            is JSONArray -> v
            is JSONObject -> v.optJSONArray("nodes") ?: v.optJSONArray("data")
            else -> null
        } ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun parseGraph(list: JSONArray): List<Theme> {
        val out = ArrayList<Theme>()
        for (i in 0 until list.length()) {
            val t = list.optJSONObject(i) ?: continue
            val anime = t.optJSONObject("anime")
            val song = t.optJSONObject("song") ?: continue
            val title = song.optJSONObject("title")?.optString("romaji")?.takeIf { it.isNotBlank() && it != "null" }
                ?: song.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val artists = (song.nodes("performances") + song.nodes("staff")).flatMap { st ->
                val name = st.optJSONObject("artist")?.optJSONObject("name")
                listOfNotNull(
                    name?.optString("main")?.takeIf { it.isNotBlank() && it != "null" },
                    name?.optString("native")?.takeIf { it.isNotBlank() && it != "null" },
                    st.optString("as").takeIf { it.isNotBlank() && it != "null" },
                    st.optString("alias").takeIf { it.isNotBlank() && it != "null" },
                )
            }.distinct()
            val cover = anime?.nodes("images")
                ?.sortedBy { if (it.optString("facet").contains("Small", true)) 0 else 1 }
                ?.firstNotNullOfOrNull { it.optString("link").takeIf { l -> l.startsWith("http") } }
            val videos = (t.nodes("animethemeentries") + t.nodes("entries")).sortedBy { it.optInt("version", 1).coerceAtLeast(1) }
                .flatMap { it.nodes("videos") }
            val best = videos.maxWithOrNull(compareBy<JSONObject>({ it.optInt("resolution") }, { if (it.optBoolean("nc")) 1 else 0 })) ?: continue
            val basename = best.optString("basename").takeIf { it.isNotBlank() && it != "null" } ?: continue
            // The addresses it gives, else the usual ones built from the file names.
            val videoLink = best.optString("link").takeIf { it.startsWith("http") } ?: "https://v.animethemes.moe/$basename"
            val audioJson = best.optJSONObject("audio")
            val audioLink = audioJson?.optString("link")?.takeIf { it.startsWith("http") }
                ?: "https://a.animethemes.moe/" + (audioJson?.optString("basename")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: (basename.substringBeforeLast('.') + ".ogg"))
            out += Theme(
                anime = anime?.optJSONObject("title")?.optString("romaji")?.takeIf { it.isNotBlank() } ?: anime?.optString("slug").orEmpty(),
                slug = t.optString("slug").takeIf { it.isNotBlank() && it != "null" }
                    ?: (t.optString("type") + (t.optInt("sequence", 0).takeIf { it > 0 } ?: "")),
                title = title,
                titleNative = song.optJSONObject("title")?.optString("native")?.takeIf { it.isNotBlank() && it != "null" },
                artists = artists,
                video = videoLink,
                audio = audioLink,
                resolution = best.optInt("resolution"),
                cover = cover,
            )
        }
        return out
    }

    private fun rest(q: String): List<Theme>? {
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
        }.getOrNull() ?: return null
        return runCatching { parse(JSONObject(body)) }.getOrElse {
            TrackLog.w(TAG, "AnimeThemes: unreadable answer: ${it.message}")
            null
        }
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

    internal fun sameTitle(song: Song, t: Theme): Boolean {
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
    internal fun sameArtist(song: Song, t: Theme): Boolean {
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

    /** [themes] with [song]'s own first: same title and singer, then same title, then the rest. */
    fun ranked(song: Song, themes: List<Theme>): List<Theme> =
        themes.withIndex().sortedWith(compareByDescending<IndexedValue<Theme>> { (_, t) ->
            val title = sameTitle(song, t)
            (if (title) 2 else 0) + (if (title && sameArtist(song, t)) 1 else 0)
        }.thenBy { it.index }).map { it.value }

    /** A saved theme as a video-mode reference: the WebM and its Ogg, together. */
    fun encode(t: Theme): String = t.video + "|" + t.audio

    fun videoOf(id: String): String = id.substringBefore('|')
    fun audioOf(id: String): String? = id.substringAfter('|', "").ifBlank { null }
}

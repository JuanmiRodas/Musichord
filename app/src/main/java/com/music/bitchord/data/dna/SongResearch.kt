package com.music.bitchord.data.dna

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.sources.OwnFileMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * BitChord Audio: when no source has anything to say about a song, a look round the web
 * for it — no AI, only what pages say: Genius's note on the song (its "About"), and the
 * sentences of the artist's and album's Wikipedia articles (Spanish, English, Japanese)
 * that name it. Kept with the song's DNA, so it is asked once.
 */
object SongResearch {
    private const val TAG = "SongDna"
    private val http by lazy { Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }
    private val json = Json { ignoreUnknownKeys = true }
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json").build())
            .execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun key(s: String) = OwnFileMatcher.key(s).ifEmpty { s.lowercase().trim() }

    suspend fun find(title: String, artist: String): List<SongDna.Fact> = withContext(Dispatchers.IO) {
        val bare = SongDna.bareTitle(title)
        val who = SongDna.firstArtist(artist)
        coroutineScope {
            val genius = async { runCatching { genius(bare, who) }.getOrNull() }
            val wiki = async { runCatching { wikipediaMentions(bare, who) }.getOrDefault(emptyList()) }
            listOfNotNull(genius.await()) + wiki.await()
        }.also { TrackLog.d(TAG, "research for '$bare' · '$who': ${it.size} piece(s) (${it.joinToString { f -> f.source }})") }
    }

    /** Genius's "About" for the song, matched by title and artist. */
    private fun genius(title: String, artist: String): SongDna.Fact? {
        val body = get("https://genius.com/api/search/song?per_page=10&q=" + enc("$title $artist")) ?: return null
        val hits = json.parseToJsonElement(body).jsonObject["response"]?.jsonObject?.get("sections")?.jsonArray
            ?.flatMap { (it.jsonObject["hits"] as? JsonArray).orEmpty() }.orEmpty()
        val song = hits.mapNotNull { it.jsonObject["result"]?.jsonObject }.firstOrNull { r ->
            val t = r["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val a = r["primary_artist"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
            key(SongDna.bareTitle(t)) == key(title) && (key(a).contains(key(artist)) || key(artist).contains(key(a)))
        } ?: return null
        val id = song["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val detail = get("https://genius.com/api/songs/$id?text_format=plain") ?: return null
        val s = json.parseToJsonElement(detail).jsonObject["response"]?.jsonObject?.get("song")?.jsonObject ?: return null
        val about = s["description"]?.jsonObject?.get("plain")?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.length > 20 && it != "?" } ?: return null
        val url = s["url"]?.jsonPrimitive?.contentOrNull
        return SongDna.Fact(about, "Genius", url)
    }

    private val SENTENCE = Regex("""(?<=[.!?。！？])\s+""")

    /** The sentences of the artist's (or album's) article that name the song. */
    private fun wikipediaMentions(title: String, artist: String): List<SongDna.Fact> {
        if (title.length < 3) return emptyList()
        val out = ArrayList<SongDna.Fact>()
        for (lang in listOf("es", "en", "ja")) {
            val search = get(
                "https://$lang.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=5&srsearch=" +
                    enc("\"$title\" $artist"),
            ) ?: continue
            val pages = json.parseToJsonElement(search).jsonObject["query"]?.jsonObject?.get("search")?.jsonArray
                ?.mapNotNull { it.jsonObject["title"]?.jsonPrimitive?.contentOrNull }.orEmpty()
            for (page in pages.take(3)) {
                val ex = get(
                    "https://$lang.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1&format=json&redirects=1&titles=" + enc(page),
                ) ?: continue
                val text = (json.parseToJsonElement(ex).jsonObject["query"]?.jsonObject?.get("pages") as? JsonObject)
                    ?.values?.firstOrNull()?.jsonObject?.get("extract")?.jsonPrimitive?.contentOrNull ?: continue
                // The article has to be about the artist's work: it names them.
                if (!text.contains(artist, ignoreCase = true)) continue
                val sentences = text.split(SENTENCE).map { it.trim().replace(Regex("\\s+"), " ") }
                    .filter { it.contains(title, ignoreCase = true) && it.length in 40..600 && !it.startsWith("==") }
                    .distinct().take(4)
                if (sentences.isEmpty()) continue
                out += SongDna.Fact(
                    sentences.joinToString(" "),
                    "Wikipedia",
                    "https://$lang.wikipedia.org/wiki/" + enc(page.replace(' ', '_')).replace("+", "%20"),
                )
                if (out.size >= 2) return out
            }
            if (out.isNotEmpty()) return out
        }
        return out
    }
}

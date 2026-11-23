package com.music.bitchord.data.names

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * BitChord Audio: where [OfficialNames] finds official Latin names — the
 * open music databases, asked politely (MusicBrainz allows one request a
 * second, and is told who is asking).
 */
object NameSources {

    private const val TAG = "BitChord"
    private const val UA = "BitChordAudio/1.9 ( https://github.com/JuanmiRodas/Musichord )"

    private val mbGate = Mutex()
    private var mbLast = 0L

    private fun latin(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() && !DisplayNames.needsLatin(it) }

    private fun same(a: String?, b: String?): Boolean =
        a != null && b != null && OfficialNames.norm(a) == OfficialNames.norm(b)

    internal fun lucene(s: String) = s.replace(Regex("""([+\-!(){}\[\]^"~*?:\\/]|&&|\|\|)"""), "\\\\$1")

    internal suspend fun getJson(url: String, musicBrainz: Boolean = false): JSONObject? = withContext(Dispatchers.IO) {
        // BitChord Audio: vgmdb.info is gone; the same answers read from vgmdb.net.
        if (url.contains("://vgmdb.info/")) {
            return@withContext com.music.bitchord.data.sources.VgmdbNet.text(url)?.let { runCatching { JSONObject(it) }.getOrNull() }
        }
        if (musicBrainz) {
            mbGate.withLock {
                val wait = 1_100 - (System.currentTimeMillis() - mbLast)
                if (wait > 0) delay(wait)
                mbLast = System.currentTimeMillis()
            }
        }
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json").build()
            Http.client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                JSONObject(r.body?.string() ?: return@use null)
            }
        }.onFailure { TrackLog.d(TAG, "names: ${url.toHttpUrl().host} failed: ${it.message}") }.getOrNull()
    }

    internal fun mb(path: String, vararg params: Pair<String, String>): String =
        "https://musicbrainz.org/ws/2/$path".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("fmt", "json")
        }.build().toString()

    internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    // ---- artists ------------------------------------------------------------------------

    suspend fun artist(name: String): Pair<String, OfficialNames.Source>? =
        mbArtist(name)?.let { it to OfficialNames.Source.MUSICBRAINZ }
            ?: vgmdbArtist(name)?.let { it to OfficialNames.Source.VGMDB }
            ?: wikidataArtist(name)?.let { it to OfficialNames.Source.WIKIDATA }

    private suspend fun mbArtist(name: String): String? {
        val q = "artist:\"${lucene(name)}\" OR alias:\"${lucene(name)}\""
        val root = getJson(mb("artist/", "query" to q, "limit" to "5"), musicBrainz = true) ?: return null
        val artist = root.optJSONArray("artists").objects().firstOrNull { a ->
            a.optInt("score") >= 85 && (same(a.optString("name"), name) || a.optJSONArray("aliases").objects().any { same(it.optString("name"), name) })
        } ?: return null
        latin(artist.optString("name"))?.let { return it }
        val aliases = artist.optJSONArray("aliases").objects().filter { latin(it.optString("name")) != null }
        fun pick(test: (JSONObject) -> Boolean) = aliases.firstOrNull(test)?.optString("name")
        return pick { it.optString("locale").startsWith("en") && it.optBoolean("primary") }
            ?: pick { it.optString("locale").contains("Latn") && it.optBoolean("primary") }
            ?: pick { it.optBoolean("primary") }
            ?: pick { it.optString("locale").startsWith("en") }
            ?: pick { it.optString("locale").contains("Latn") }
            ?: pick { it.optString("type") == "Artist name" }
            ?: latin(artist.optString("sort-name"))?.let { sort ->
                // "Kajiura, Yuki" → "Yuki Kajiura".
                val parts = sort.split(", ")
                if (parts.size == 2) parts[1] + " " + parts[0] else sort
            }
    }

    private suspend fun wikidataArtist(name: String): String? {
        val search = "https://www.wikidata.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "wbsearchentities").addQueryParameter("search", name)
            .addQueryParameter("language", "ja").addQueryParameter("uselang", "ja")
            .addQueryParameter("type", "item").addQueryParameter("limit", "5").addQueryParameter("format", "json").build().toString()
        val ids = getJson(search)?.optJSONArray("search").objects()
            .filter { same(it.optString("label"), name) }.map { it.optString("id") }.take(3)
        if (ids.isEmpty()) return null
        val get = "https://www.wikidata.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "wbgetentities").addQueryParameter("ids", ids.joinToString("|"))
            .addQueryParameter("props", "labels").addQueryParameter("languages", "en|ja")
            .addQueryParameter("format", "json").build().toString()
        val entities = getJson(get)?.optJSONObject("entities") ?: return null
        for (id in ids) {
            val labels = entities.optJSONObject(id)?.optJSONObject("labels") ?: continue
            if (!same(labels.optJSONObject("ja")?.optString("value"), name)) continue
            latin(labels.optJSONObject("en")?.optString("value"))?.let { return it }
        }
        return null
    }

    private suspend fun vgmdbArtist(name: String): String? {
        val root = getJson("https://vgmdb.info/search/artists/".toHttpUrl().newBuilder().addPathSegment(name).addQueryParameter("format", "json").build().toString())
            ?: return null
        val hits = root.optJSONObject("results")?.optJSONArray("artists").objects()
        for (a in hits) {
            val names = a.optJSONObject("names") ?: continue
            val ja = names.optString("ja").takeIf { it.isNotBlank() }
            if (!same(ja, name)) continue
            latin(names.optString("en"))?.let { return it }
        }
        return null
    }

    // ---- titles ---------------------------------------------------------------------------

    suspend fun title(title: String, artist: String, artistLatin: String?, album: String?): Pair<String, OfficialNames.Source>? =
        mbTitle(title, artist)?.let { it to OfficialNames.Source.MUSICBRAINZ }
            ?: album?.let { vgmdbTitle(title, it) }?.let { it to OfficialNames.Source.VGMDB }

    private suspend fun mbTitle(title: String, artist: String): String? {
        val q = "recording:\"${lucene(title)}\"" + if (artist.isNotBlank()) " AND artist:\"${lucene(artist)}\"" else ""
        val root = getJson(mb("recording/", "query" to q, "limit" to "10"), musicBrainz = true) ?: return null
        val recordings = root.optJSONArray("recordings").objects()
            .filter { it.optInt("score") >= 80 && same(it.optString("title"), title) }
            .take(3)
        for (rec in recordings) {
            val id = rec.optString("id")
            // Releases holding this recording; a Latin-script one (a transliterated or
            // international issue) gives the track's Latin title.
            val releases = getJson(mb("release", "recording" to id, "inc" to "recordings", "limit" to "100"), musicBrainz = true)
                ?.optJSONArray("releases").objects()
            for (release in releases) {
                val script = release.optJSONObject("text-representation")?.optString("script")
                if (script != "Latn") continue
                for (medium in release.optJSONArray("media").objects()) {
                    for (track in medium.optJSONArray("tracks").objects()) {
                        if (track.optJSONObject("recording")?.optString("id") != id) continue
                        latin(track.optString("title"))?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private suspend fun vgmdbAlbum(album: String): JSONObject? {
        val root = getJson("https://vgmdb.info/search/albums/".toHttpUrl().newBuilder().addPathSegment(album).addQueryParameter("format", "json").build().toString())
            ?: return null
        return root.optJSONObject("results")?.optJSONArray("albums").objects().firstOrNull { a ->
            val titles = a.optJSONObject("titles") ?: return@firstOrNull false
            titles.keys().asSequence().any { same(titles.optString(it), album) }
        }
    }

    private suspend fun vgmdbTitle(title: String, album: String): String? {
        val link = vgmdbAlbum(album)?.optString("link")?.takeIf { it.isNotBlank() } ?: return null
        val page = getJson("https://vgmdb.info/$link?format=json") ?: return null
        for (disc in page.optJSONArray("discs").objects()) {
            for (track in disc.optJSONArray("tracks").objects()) {
                val names = track.optJSONObject("names") ?: continue
                if (names.keys().asSequence().none { same(names.optString(it), title) }) continue
                // Romaji is the title's own Latin spelling; English is a translation, second.
                return latin(names.optString("Romaji")) ?: latin(names.optString("English"))
            }
        }
        return null
    }

    // ---- albums ---------------------------------------------------------------------------

    suspend fun album(album: String, artist: String, artistLatin: String?): Pair<String, OfficialNames.Source>? =
        mbAlbum(album, artist)?.let { it to OfficialNames.Source.MUSICBRAINZ }
            ?: vgmdbAlbum(album)?.optJSONObject("titles")?.let { t -> latin(t.optString("en")) ?: latin(t.optString("ja-latn")) }
                ?.let { it to OfficialNames.Source.VGMDB }

    private suspend fun mbAlbum(album: String, artist: String): String? {
        val q = "release:\"${lucene(album)}\"" + if (artist.isNotBlank()) " AND artist:\"${lucene(artist)}\"" else ""
        val root = getJson(mb("release/", "query" to q, "limit" to "10"), musicBrainz = true) ?: return null
        val group = root.optJSONArray("releases").objects()
            .firstOrNull { it.optInt("score") >= 80 && same(it.optString("title"), album) }
            ?.optJSONObject("release-group")?.optString("id")?.takeIf { it.isNotBlank() } ?: return null
        val releases = getJson(mb("release", "release-group" to group, "limit" to "100"), musicBrainz = true)
            ?.optJSONArray("releases").objects()
        return releases.firstOrNull { it.optJSONObject("text-representation")?.optString("script") == "Latn" && latin(it.optString("title")) != null }
            ?.optString("title")
    }
}

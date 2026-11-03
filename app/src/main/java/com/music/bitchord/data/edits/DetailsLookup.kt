package com.music.bitchord.data.edits

import com.music.bitchord.data.Http
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * BitChord Audio: what other catalogues say about a song or an album — Apple
 * Music, Deezer, MusicBrainz and Wikidata (Wikipedia's facts) — for the
 * details editor to suggest and fill in: title, artist, album, album artist,
 * year, genre and track numbers.
 *
 * Every place is asked at once and given a few seconds; the ones that answer
 * for the same release are what the editor fills empty fields from (see
 * [consensus]), the rest are offered to pick from.
 */
object DetailsLookup {

    data class Suggestion(
        val source: String,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val year: String? = null,
        val genre: String? = null,
        val track: String? = null,
        /** The release's running order: a song's normalised title → its track number. */
        val tracks: Map<String, Int> = emptyMap(),
    )

    private val http by lazy { Http.client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build() }
    private const val AGENT = "BitChordAudio/1.9 (https://github.com/JuanmiRodas/Musichord)"

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", AGENT).header("Accept", "application/json").build())
            .execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun norm(s: String) = s.lowercase()
        .replace(Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]"""), " ")
        .filter { it.isLetterOrDigit() }

    private fun firstArtist(a: String) =
        a.split(Regex("""\s*(?:,|&|/|;|\bfeat\.?|\bft\.?|、)\s*""", RegexOption.IGNORE_CASE)).first().trim()

    private fun same(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        val x = norm(a)
        val y = norm(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x == y || x.contains(y) || y.contains(x))
    }

    /**
     * For [songs]: one song (its own details), or several (their album's).
     * Only answers that are about this album / song (by name and artist) are kept.
     */
    suspend fun find(songs: List<Song>): List<Suggestion> = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext emptyList()
        val single = songs.size == 1
        val shown = songs.map { MetadataEdits.applied(it) ?: it }
        val artist = firstArtist(shown.first().artist)
        val album = shown.mapNotNull { it.albumName?.takeIf(String::isNotBlank) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val title = shown.first().title
        coroutineScope {
            fun place(block: () -> List<Suggestion>) = async { withTimeoutOrNull(10_000) { runCatching(block).getOrNull() }.orEmpty() }
            listOf(
                place { itunes(if (single) title else null, album, artist) },
                place { deezer(if (single) title else null, album, artist) },
                place { musicBrainz(if (single) title else null, album, artist) },
                place { wikidata(album ?: title, artist, isAlbum = album != null && !single) },
            ).awaitAll().flatten()
        }.filter { s ->
            // About this one, not a namesake.
            val who = s.artist ?: s.albumArtist
            who == null || artist.isBlank() || same(who, artist)
        }
    }

    /** What most places agree on, field by field (two or more saying it, else the first). */
    fun consensus(found: List<Suggestion>): Suggestion {
        fun pick(f: (Suggestion) -> String?): String? {
            val values = found.mapNotNull(f).filter { it.isNotBlank() }
            if (values.isEmpty()) return null
            val counted = values.groupBy { norm(it).ifEmpty { it } }.maxByOrNull { it.value.size } ?: return null
            return counted.value.first()
        }
        return Suggestion(
            source = "",
            title = pick { it.title },
            artist = pick { it.artist },
            album = pick { it.album },
            albumArtist = pick { it.albumArtist },
            year = pick { it.year },
            genre = pick { it.genre },
            track = pick { it.track },
            tracks = found.firstOrNull { it.tracks.isNotEmpty() }?.tracks.orEmpty(),
        )
    }

    // ---- Apple Music (the iTunes catalogue) ---------------------------------

    private fun itunes(title: String?, album: String?, artist: String): List<Suggestion> {
        val out = ArrayList<Suggestion>()
        for (country in listOf(java.util.Locale.getDefault().country.ifBlank { "US" }, "US", "JP").distinct()) {
            if (title != null) {
                val body = get("https://itunes.apple.com/search?term=${enc("$title $artist")}&country=$country&media=music&entity=song&limit=8") ?: continue
                val rows = JSONObject(body).optJSONArray("results") ?: continue
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i)
                    if (!same(r.optString("trackName"), title)) continue
                    out += Suggestion(
                        source = "Apple Music",
                        title = r.optString("trackName").ifEmpty { null },
                        artist = r.optString("artistName").ifEmpty { null },
                        album = r.optString("collectionName").ifEmpty { null }?.removeSuffix(" - Single")?.removeSuffix(" - EP"),
                        albumArtist = r.optString("collectionArtistName").ifEmpty { null } ?: r.optString("artistName").ifEmpty { null },
                        year = r.optString("releaseDate").take(4).ifEmpty { null },
                        genre = r.optString("primaryGenreName").ifEmpty { null },
                        track = r.optInt("trackNumber").takeIf { it > 0 }?.toString(),
                    )
                    break
                }
            }
            if (album != null) {
                val body = get("https://itunes.apple.com/search?term=${enc("$album $artist")}&country=$country&media=music&entity=album&limit=8") ?: continue
                val rows = JSONObject(body).optJSONArray("results") ?: continue
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i)
                    if (!same(r.optString("collectionName"), album)) continue
                    // Its track list, for the track numbers.
                    val tracks = HashMap<String, Int>()
                    r.optLong("collectionId").takeIf { it > 0 }?.let { id ->
                        get("https://itunes.apple.com/lookup?id=$id&entity=song&country=$country")?.let { t ->
                            val arr = JSONObject(t).optJSONArray("results")
                            if (arr != null) for (j in 0 until arr.length()) {
                                val s = arr.getJSONObject(j)
                                if (s.optString("wrapperType") == "track") {
                                    tracks[norm(s.optString("trackName"))] = s.optInt("trackNumber")
                                }
                            }
                        }
                    }
                    out += Suggestion(
                        source = "Apple Music",
                        album = r.optString("collectionName").ifEmpty { null }?.removeSuffix(" - Single")?.removeSuffix(" - EP"),
                        artist = r.optString("artistName").ifEmpty { null },
                        albumArtist = r.optString("artistName").ifEmpty { null },
                        year = r.optString("releaseDate").take(4).ifEmpty { null },
                        genre = r.optString("primaryGenreName").ifEmpty { null },
                        tracks = tracks,
                    )
                    break
                }
            }
            if (out.isNotEmpty()) break
        }
        return out
    }

    // ---- Deezer -------------------------------------------------------------

    private fun deezer(title: String?, album: String?, artist: String): List<Suggestion> {
        val out = ArrayList<Suggestion>()
        fun albumDetails(id: Long): JSONObject? = get("https://api.deezer.com/album/$id")?.let { JSONObject(it) }
        fun fromAlbum(a: JSONObject, base: Suggestion): Suggestion {
            val tracks = HashMap<String, Int>()
            a.optJSONObject("tracks")?.optJSONArray("data")?.let { arr ->
                for (j in 0 until arr.length()) tracks[norm(arr.getJSONObject(j).optString("title"))] = j + 1
            }
            val genre = a.optJSONObject("genres")?.optJSONArray("data")?.optJSONObject(0)?.optString("name")?.ifEmpty { null }
            return base.copy(
                album = base.album ?: a.optString("title").ifEmpty { null },
                albumArtist = a.optJSONObject("artist")?.optString("name")?.ifEmpty { null },
                year = a.optString("release_date").take(4).ifEmpty { null },
                genre = genre,
                tracks = tracks,
            )
        }
        if (title != null) {
            val rows = get("https://api.deezer.com/search?q=${enc("artist:\"$artist\" track:\"$title\"")}&limit=8")
                ?.let { JSONObject(it).optJSONArray("data") }
            if (rows != null) for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                if (!same(r.optString("title"), title)) continue
                val a = r.optJSONObject("album")
                val base = Suggestion(
                    source = "Deezer",
                    title = r.optString("title").ifEmpty { null },
                    artist = r.optJSONObject("artist")?.optString("name")?.ifEmpty { null },
                    album = a?.optString("title")?.ifEmpty { null },
                )
                val full = a?.optLong("id")?.takeIf { it > 0 }?.let(::albumDetails)
                val s = if (full != null) fromAlbum(full, base) else base
                out += s.copy(track = s.tracks[norm(title)]?.toString())
                break
            }
        }
        if (album != null) {
            val rows = get("https://api.deezer.com/search/album?q=${enc("artist:\"$artist\" album:\"$album\"")}&limit=8")
                ?.let { JSONObject(it).optJSONArray("data") }
            if (rows != null) for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                if (!same(r.optString("title"), album)) continue
                val full = albumDetails(r.optLong("id")) ?: continue
                out += fromAlbum(full, Suggestion(source = "Deezer", artist = r.optJSONObject("artist")?.optString("name")))
                break
            }
        }
        return out
    }

    // ---- MusicBrainz --------------------------------------------------------

    private fun musicBrainz(title: String?, album: String?, artist: String): List<Suggestion> {
        val out = ArrayList<Suggestion>()
        fun credit(o: JSONObject): String? {
            val arr = o.optJSONArray("artist-credit") ?: return null
            return buildString {
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    append(c.optString("name").ifEmpty { c.optJSONObject("artist")?.optString("name").orEmpty() })
                    append(c.optString("joinphrase"))
                }
            }.ifBlank { null }
        }
        fun tag(o: JSONObject): String? {
            val tags = o.optJSONArray("tags") ?: return null
            var best: JSONObject? = null
            for (i in 0 until tags.length()) {
                val t = tags.getJSONObject(i)
                if (best == null || t.optInt("count") > best.optInt("count")) best = t
            }
            return best?.optString("name")?.ifEmpty { null }?.replaceFirstChar { it.uppercase() }
        }
        if (album != null) {
            val q = "releasegroup:\"$album\" AND artist:\"$artist\""
            val body = get("https://musicbrainz.org/ws/2/release-group?query=${enc(q)}&fmt=json&limit=5")
            val arr = body?.let { JSONObject(it).optJSONArray("release-groups") }
            if (arr != null) for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                if (!same(g.optString("title"), album)) continue
                out += Suggestion(
                    source = "MusicBrainz",
                    album = g.optString("title").ifEmpty { null },
                    artist = credit(g),
                    albumArtist = credit(g),
                    year = g.optString("first-release-date").take(4).ifEmpty { null },
                    genre = tag(g),
                )
                break
            }
        }
        if (title != null) {
            val q = "recording:\"$title\" AND artist:\"$artist\""
            val body = get("https://musicbrainz.org/ws/2/recording?query=${enc(q)}&fmt=json&limit=5")
            val arr = body?.let { JSONObject(it).optJSONArray("recordings") }
            if (arr != null) for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                if (!same(r.optString("title"), title)) continue
                val release = r.optJSONArray("releases")?.optJSONObject(0)
                out += Suggestion(
                    source = "MusicBrainz",
                    title = r.optString("title").ifEmpty { null },
                    artist = credit(r),
                    album = release?.optString("title")?.ifEmpty { null },
                    year = (r.optString("first-release-date").ifEmpty { release?.optString("date").orEmpty() }).take(4).ifEmpty { null },
                    genre = tag(r),
                    track = release?.optJSONArray("media")?.optJSONObject(0)?.optJSONArray("track")?.optJSONObject(0)
                        ?.optString("number")?.ifEmpty { null },
                )
                break
            }
        }
        return out
    }

    // ---- Wikidata (Wikipedia's facts) ---------------------------------------

    private fun wikidata(name: String, artist: String, isAlbum: Boolean): List<Suggestion> {
        val search = get("https://www.wikidata.org/w/api.php?action=wbsearchentities&search=${enc(name)}&language=en&uselang=en&type=item&limit=6&format=json")
            ?: return emptyList()
        val hits = JSONObject(search).optJSONArray("search") ?: return emptyList()
        val ids = (0 until hits.length()).map { hits.getJSONObject(it) }
            .filter { h -> same(h.optString("label"), name) }
            .map { it.optString("id") }
            .filter { it.isNotBlank() }
        if (ids.isEmpty()) return emptyList()
        val body = get("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${ids.joinToString("|")}&props=claims|labels&languages=en&format=json")
            ?: return emptyList()
        val entities = JSONObject(body).optJSONObject("entities") ?: return emptyList()
        fun claimIds(e: JSONObject, p: String): List<String> {
            val arr = e.optJSONObject("claims")?.optJSONArray(p) ?: return emptyList()
            return (0 until arr.length()).mapNotNull {
                arr.getJSONObject(it).optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("id")?.ifEmpty { null }
            }
        }
        fun claimTime(e: JSONObject, p: String): String? {
            val arr = e.optJSONObject("claims")?.optJSONArray(p) ?: return null
            val t = arr.optJSONObject(0)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("time")
            return t?.removePrefix("+")?.take(4)?.ifEmpty { null }
        }
        // Albums (Q482994) and songs/singles (Q7366, Q134556), by "instance of".
        val wanted = if (isAlbum) setOf("Q482994", "Q208569", "Q169930") else setOf("Q7366", "Q134556", "Q105543609")
        for (id in ids) {
            val e = entities.optJSONObject(id) ?: continue
            if (claimIds(e, "P31").none { it in wanted }) continue
            val performers = claimIds(e, "P175")
            val genres = claimIds(e, "P136").take(1)
            val partOf = claimIds(e, "P361").take(1)
            val needed = (performers.take(1) + genres + partOf).distinct()
            val labels = if (needed.isEmpty()) emptyMap() else get(
                "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${needed.joinToString("|")}&props=labels&languages=en&format=json",
            )?.let { JSONObject(it).optJSONObject("entities") }?.let { les ->
                needed.associateWith { les.optJSONObject(it)?.optJSONObject("labels")?.optJSONObject("en")?.optString("value") }
            }.orEmpty()
            val performer = performers.firstOrNull()?.let { labels[it] }
            if (performer != null && !same(performer, artist)) continue
            val label = e.optJSONObject("labels")?.optJSONObject("en")?.optString("value")
            return listOf(
                Suggestion(
                    source = "Wikipedia",
                    title = label.takeIf { !isAlbum },
                    album = if (isAlbum) label else partOf.firstOrNull()?.let { labels[it] },
                    artist = performer,
                    albumArtist = performer.takeIf { isAlbum },
                    year = claimTime(e, "P577"),
                    genre = genres.firstOrNull()?.let { labels[it] }?.replaceFirstChar { it.uppercase() },
                ),
            )
        }
        return emptyList()
    }
}

package com.music.bitchord.data

import android.content.Context
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.OwnFileMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * BitChord Audio: an album's whole track list from the reference catalogues — Apple Music
 * (iTunes, Japan's store first), MusicBrainz, VGMdb (anime, games, old Japanese releases) and
 * Discogs (with your token) — for an album of yours YouTube Music doesn't have. Your songs
 * are put in their place on it, discs included, and the ones you are missing show greyed in
 * theirs: a tap looks for one to play.
 *
 * Only asked when such an album is opened, and kept (a month) for the next time. A list is
 * only taken when most of your songs of the album are on it — never another album's.
 */
object AlbumReference {
    private const val TAG = "BitChord"
    private const val UA = "BitChordAudio/1.0 (https://github.com/JuanmiRodas/Musichord)"
    private const val TTL_MS = 30L * 24 * 3600 * 1000
    const val MISSING = "missing:"

    data class Track(val disc: Int, val number: Int, val title: String, val seconds: Int?)
    data class Reference(val source: String, val tracks: List<Track>)

    @Volatile private var file: File? = null
    private val cache: JSONObject by lazy {
        runCatching { file?.takeIf { it.isFile }?.readText()?.let(::JSONObject) }.getOrNull() ?: JSONObject()
    }
    private val http by lazy { Http.client.newBuilder().callTimeout(12, TimeUnit.SECONDS).build() }
    private val brainz = Mutex()
    @Volatile private var lastBrainz = 0L

    fun init(context: Context) {
        file = File(context.applicationContext.filesDir, "album_references.json")
    }

    private fun save() = synchronized(cache) { runCatching { file?.writeText(cache.toString()) } }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): String? = runCatching {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun key(s: String) = OwnFileMatcher.key(s)

    fun isMissing(song: Song) = song.videoId.startsWith(MISSING)

    // ── Your album, completed ──

    /**
     * [songs] (your songs of album [album]) laid out on the album's full track list, with
     * greyed rows for the ones you lack; [songs] unchanged when no reference fits (or none
     * answers within [waitMs] — the look-up carries on and is kept for next time).
     */
    suspend fun complete(album: String, songs: List<Song>, waitMs: Long = 6_000): List<Song> {
        if (songs.isEmpty() || album.isBlank()) return songs
        val artist = songs.groupingBy { it.artist.split(',', '&', '、').first().trim() }.eachCount().maxByOrNull { it.value }?.key
        val ref = withTimeoutOrNull(waitMs) { find(album, artist, songs) } ?: cached(album)?.takeIf { fits(it, songs) } ?: return songs
        return layOut(ref, album, artist, songs)
    }

    private fun layOut(ref: Reference, album: String, artist: String?, songs: List<Song>): List<Song> {
        val left = songs.toMutableList()
        val cover = songs.firstNotNullOfOrNull { it.thumbnailUrl }
        val out = ArrayList<Song>()
        for (t in ref.tracks) {
            val mine = left.firstOrNull { matches(t, it) }
            if (mine != null) {
                left.remove(mine)
                out += mine.copy(trackNumber = t.number)
            } else {
                out += Song(
                    videoId = MISSING + key(album) + ":" + t.disc + ":" + t.number,
                    title = t.title,
                    artist = artist ?: "",
                    thumbnailUrl = cover,
                    albumName = album,
                    durationText = t.seconds?.let { "%d:%02d".format(it / 60, it % 60) },
                    trackNumber = t.number,
                )
            }
        }
        // Yours not on the list (a bonus track, another edition's) after it.
        out += left
        TrackLog.d(TAG, "album '$album': ${ref.source} lists ${ref.tracks.size} — you have ${songs.size - left.size} of them" +
            (if (left.isNotEmpty()) ", ${left.size} more of yours" else ""))
        return out
    }

    private fun secondsOf(s: Song) = s.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }?.fold(0) { a, p -> a * 60 + p }

    private fun words(t: String) = t.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 3 }.toSet()

    /** One of yours is this listed track: the title in any script, or a shared word and the same length (±3 s). */
    private fun matches(t: Track, s: Song): Boolean {
        val kt = OwnFileMatcher.allKeys(t.title) + key(t.title)
        val ks = OwnFileMatcher.allKeys(s.title) + key(s.title)
        if (kt.any { it.isNotEmpty() && it in ks }) return true
        val a = t.seconds ?: return false
        val b = secondsOf(s) ?: return false
        if (kotlin.math.abs(a - b) > 3) return false
        return (words(t.title) + kt).any { it in (words(s.title) + ks) }
    }

    /** Whether [ref] is this album: most of your songs (and at least one) are on it. */
    private fun fits(ref: Reference, songs: List<Song>): Boolean {
        if (ref.tracks.isEmpty()) return false
        val hit = songs.count { s -> ref.tracks.any { matches(it, s) } }
        return hit >= 1 && hit * 10 >= songs.size * 6
    }

    // ── The catalogues ──

    private fun cached(album: String): Reference? {
        val o = synchronized(cache) { cache.optJSONObject(key(album)) } ?: return null
        if (System.currentTimeMillis() - o.optLong("at") > TTL_MS) return null
        val a = o.optJSONArray("t") ?: return null
        return Reference(o.optString("s"), (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Track(it.optInt("d", 1), it.optInt("n"), it.optString("t"), it.optInt("l").takeIf { l -> l > 0 })
        })
    }

    private fun keep(album: String, ref: Reference?) {
        val o = JSONObject().put("at", System.currentTimeMillis()).put("s", ref?.source.orEmpty())
        o.put("t", JSONArray().apply {
            ref?.tracks?.forEach { put(JSONObject().put("d", it.disc).put("n", it.number).put("t", it.title).put("l", it.seconds ?: 0)) }
        })
        synchronized(cache) { cache.put(key(album), o) }
        save()
    }

    /** The first catalogue whose list fits your songs (kept, a miss too). */
    suspend fun find(album: String, artist: String?, songs: List<Song>): Reference? = withContext(Dispatchers.IO) {
        synchronized(cache) { cache.optJSONObject(key(album)) }?.takeIf { System.currentTimeMillis() - it.optLong("at") < TTL_MS }?.let {
            return@withContext cached(album)?.takeIf { r -> r.tracks.isNotEmpty() && fits(r, songs) }
        }
        val tries = listOf<suspend () -> List<Reference>>(
            { itunes(album, artist) },
            { vgmdb(album) },
            { musicBrainz(album, artist) },
            { discogs(album, artist) },
        )
        for (t in tries) {
            val found = runCatching { t() }.getOrDefault(emptyList()).firstOrNull { fits(it, songs) }
            if (found != null) { keep(album, found); return@withContext found }
        }
        TrackLog.d(TAG, "album '$album': no catalogue lists it")
        keep(album, null)
        null
    }

    private fun sameTitle(a: String, b: String) = key(a) == key(b) || OwnFileMatcher.sameAlbum(a, b)

    /** Apple Music's catalogue (Japan's store first, then the US one). */
    private fun itunes(album: String, artist: String?): List<Reference> {
        val out = ArrayList<Reference>()
        for (country in listOf("JP", "US")) {
            val q = listOfNotNull(album, artist).joinToString(" ")
            val body = get("https://itunes.apple.com/search?term=${enc(q)}&entity=album&limit=10&country=$country") ?: continue
            val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: continue
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                if (!sameTitle(album, r.optString("collectionName"))) continue
                val id = r.optLong("collectionId").takeIf { it > 0 } ?: continue
                val list = get("https://itunes.apple.com/lookup?id=$id&entity=song&country=$country") ?: continue
                val items = runCatching { JSONObject(list).optJSONArray("results") }.getOrNull() ?: continue
                val tracks = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                    .filter { it.optString("wrapperType") == "track" }
                    .map { Track(it.optInt("discNumber", 1), it.optInt("trackNumber"), it.optString("trackName"), (it.optLong("trackTimeMillis") / 1000).toInt().takeIf { s -> s > 0 }) }
                    .sortedWith(compareBy({ it.disc }, { it.number }))
                if (tracks.isNotEmpty()) out += Reference("Apple Music", tracks)
            }
            if (out.isNotEmpty()) break
        }
        return out
    }

    /** VGMdb (through vgmdb.info), the reference for anime, game and older Japanese releases. */
    private fun vgmdb(album: String): List<Reference> {
        val body = get("https://vgmdb.info/search/albums/${enc(album)}?format=json") ?: return emptyList()
        val albums = runCatching { JSONObject(body).optJSONObject("results")?.optJSONArray("albums") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Reference>()
        for (i in 0 until minOf(albums.length(), 5)) {
            val a = albums.optJSONObject(i) ?: continue
            val titles = a.optJSONObject("titles")
            val names = listOfNotNull(titles?.optString("en"), titles?.optString("ja"), titles?.optString("ja-latn")).filter { it.isNotBlank() }
            if (names.none { sameTitle(album, it) }) continue
            val link = a.optString("link").takeIf { it.isNotBlank() } ?: continue
            val page = get("https://vgmdb.info/$link?format=json") ?: continue
            val discs = runCatching { JSONObject(page).optJSONArray("discs") }.getOrNull() ?: continue
            val tracks = ArrayList<Track>()
            for (d in 0 until discs.length()) {
                val disc = discs.optJSONObject(d) ?: continue
                val list = disc.optJSONArray("tracks") ?: continue
                for (t in 0 until list.length()) {
                    val tr = list.optJSONObject(t) ?: continue
                    val n = tr.optJSONObject("names")
                    val title = n?.let { listOf("English", "Romaji", "Japanese").firstNotNullOfOrNull { k -> it.optString(k).takeIf { v -> v.isNotBlank() } } ?: it.keys().asSequence().firstOrNull()?.let { k -> it.optString(k) } }
                        ?: continue
                    val len = tr.optString("track_length").split(':').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 2 }?.let { it[0] * 60 + it[1] }
                    tracks += Track(d + 1, t + 1, title, len)
                }
            }
            if (tracks.isNotEmpty()) out += Reference("VGMdb", tracks)
        }
        return out
    }

    /** MusicBrainz releases of that title (once a second, as it asks). */
    private suspend fun musicBrainz(album: String, artist: String?): List<Reference> {
        suspend fun ask(url: String): String? = brainz.withLock {
            val wait = 1100 - (System.currentTimeMillis() - lastBrainz)
            if (wait > 0) delay(wait)
            lastBrainz = System.currentTimeMillis()
            get(url)
        }
        val q = "release:\"${album.replace("\"", "")}\"" + (artist?.let { " AND artist:\"${it.replace("\"", "")}\"" } ?: "")
        val body = ask("https://musicbrainz.org/ws/2/release/?query=${enc(q)}&fmt=json&limit=5")
            ?: return emptyList()
        val releases = runCatching { JSONObject(body).optJSONArray("releases") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Reference>()
        for (i in 0 until minOf(releases.length(), 3)) {
            val r = releases.optJSONObject(i) ?: continue
            if (!sameTitle(album, r.optString("title"))) continue
            val page = ask("https://musicbrainz.org/ws/2/release/${r.optString("id")}?inc=recordings&fmt=json") ?: continue
            val media = runCatching { JSONObject(page).optJSONArray("media") }.getOrNull() ?: continue
            val tracks = ArrayList<Track>()
            for (m in 0 until media.length()) {
                val disc = media.optJSONObject(m) ?: continue
                val list = disc.optJSONArray("tracks") ?: continue
                for (t in 0 until list.length()) {
                    val tr = list.optJSONObject(t) ?: continue
                    tracks += Track(disc.optInt("position", m + 1), tr.optInt("position", t + 1), tr.optString("title"), (tr.optLong("length") / 1000).toInt().takeIf { it > 0 })
                }
            }
            if (tracks.isNotEmpty()) out += Reference("MusicBrainz", tracks)
        }
        return out
    }

    /** Discogs (only with your token, from Settings › Account and integrations). */
    private fun discogs(album: String, artist: String?): List<Reference> {
        val token = AppSettings.discogsToken.value.trim().ifBlank { return emptyList() }
        val q = "release_title=${enc(album)}" + (artist?.let { "&artist=${enc(it)}" } ?: "")
        val body = get("https://api.discogs.com/database/search?$q&type=release&per_page=5&token=${enc(token)}") ?: return emptyList()
        val results = runCatching { JSONObject(body).optJSONArray("results") }.getOrNull() ?: return emptyList()
        val out = ArrayList<Reference>()
        for (i in 0 until minOf(results.length(), 3)) {
            val r = results.optJSONObject(i) ?: continue
            val title = r.optString("title").substringAfter(" - ")
            if (!sameTitle(album, title)) continue
            val page = get("https://api.discogs.com/releases/${r.optLong("id")}?token=${enc(token)}") ?: continue
            val list = runCatching { JSONObject(page).optJSONArray("tracklist") }.getOrNull() ?: continue
            var lastDisc = 1
            var n = 0
            val tracks = ArrayList<Track>()
            for (t in 0 until list.length()) {
                val tr = list.optJSONObject(t) ?: continue
                if (tr.optString("type_") != "track") continue
                val pos = tr.optString("position")
                val disc = Regex("""^(\d+)[-.]""").find(pos)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                if (disc != lastDisc) { lastDisc = disc; n = 0 }
                n++
                val len = tr.optString("duration").split(':').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }?.let { it[0] * 60 + it[1] }
                tracks += Track(disc, n, tr.optString("title"), len)
            }
            if (tracks.isNotEmpty()) out += Reference("Discogs", tracks)
        }
        return out
    }

    // ── A missing track, found to play ──

    /** Somewhere [missing] plays (YouTube Music: same title in any script, length within 6 s when known). */
    suspend fun findPlayable(missing: Song): Song? = withContext(Dispatchers.IO) {
        val q = "${missing.title} ${missing.artist}".trim()
        val want = secondsOf(missing)
        val keys = OwnFileMatcher.allKeys(missing.title) + key(missing.title)
        val hits = YtMusicRepository.search(q, SearchFilter.SONGS).getOrNull().orEmpty()
            .mapNotNull { (it as? SearchResult.Track)?.song }
        hits.firstOrNull { s ->
            val k = OwnFileMatcher.allKeys(s.title) + key(s.title)
            k.any { it.isNotEmpty() && it in keys } && (want == null || secondsOf(s)?.let { kotlin.math.abs(it - want) <= 6 } != false)
        }.also { TrackLog.d(TAG, "missing '${missing.title}': " + (it?.let { s -> "plays from YouTube Music (${s.videoId})" } ?: "nowhere found")) }
    }
}

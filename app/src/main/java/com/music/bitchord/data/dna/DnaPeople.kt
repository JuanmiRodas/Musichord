package com.music.bitchord.data.dna

import android.content.Context
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.names.NameSources
import com.music.bitchord.data.names.NameSources.objects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * BitChord Audio: the people on a song's credits — a page for each, as Spotify's
 * SongDNA has: their picture, who they are, what else they wrote, produced, arranged
 * or played, who they worked with in your library, and their artist page.
 *
 * Found by the ids the song's sources gave (MusicBrainz, Genius, Discogs, YouTube
 * Music), so a common name never brings someone else's work; by name only when no
 * source gave an id, and then only on an exact match. Kept in [FILE] (copied to
 * pCloud), filled at night for the people of your library, and on opening otherwise.
 */
object DnaPeople {

    private const val TAG = "BitChord"
    const val FILE = "song_dna_people.json"
    private const val FRESH_MS = 90L * 24 * 60 * 60 * 1000
    private const val FRESH_FAILED_MS = 2L * 24 * 60 * 60 * 1000
    private const val AGENT = "BitChordAudio/1.9 ( https://github.com/JuanmiRodas/Musichord )"

    data class Work(
        val title: String,
        val artist: String,
        val role: String,
        val year: String? = null,
        val art: String? = null,
        val url: String? = null,
        val source: String,
    )

    data class Person(
        val key: String,
        val name: String,
        val fetchedAt: Long,
        val image: String? = null,
        val about: String? = null,
        val aboutSource: String? = null,
        /** Short facts: "Person · Japan · since 1985". */
        val facts: List<String> = emptyList(),
        val ytBrowseId: String? = null,
        val works: List<Work> = emptyList(),
        val links: List<SongDna.Link> = emptyList(),
        val failed: List<String> = emptyList(),
        /** BitChord Audio: how many times in a row those sources were asked again and still didn't answer. */
        val tries: Int = 0,
    )

    /** A song of your library the person is credited on, with their roles there. */
    data class LibrarySong(val dna: SongDna.Dna, val roles: List<String>)

    private val cache = ConcurrentHashMap<String, Person>()
    private val inFlight = ConcurrentHashMap<String, Deferred<Person>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var file: File? = null
    @Volatile private var saveJob: Job? = null

    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, FILE)
        scope.launch { load() }
    }

    fun load() {
        val text = runCatching { file?.takeIf { it.isFile }?.readText() }.getOrNull() ?: return
        runCatching {
            val people = JSONObject(text).optJSONObject("people") ?: return
            people.keys().forEach { k -> runCatching { cache[k] = fromJson(people.getJSONObject(k)) } }
            _changes.value++
        }
    }

    private fun scheduleSave() {
        if (saveJob?.isActive == true) return
        saveJob = scope.launch {
            delay(15_000)
            val people = JSONObject()
            cache.forEach { (k, v) -> people.put(k, toJson(v)) }
            runCatching {
                val f = file ?: return@runCatching
                val tmp = File(f.parentFile, "$FILE.tmp")
                tmp.writeText(JSONObject().put("people", people).toString())
                tmp.renameTo(f)
            }
        }
    }

    fun cached(name: String): Person? = cache[SongDna.personKey(name)]

    private fun fresh(p: Person) =
        System.currentTimeMillis() - p.fetchedAt < if (p.failed.isNotEmpty()) retryAfter(p.tries) else FRESH_MS

    /** BitChord Audio: a source that didn't answer is asked again after 2 days, a week, a month — then with the ordinary refresh. */
    private fun retryAfter(tries: Int): Long = when (tries) {
        0 -> FRESH_FAILED_MS
        1 -> 7L * 24 * 60 * 60 * 1000
        2 -> 30L * 24 * 60 * 60 * 1000
        else -> FRESH_MS
    }

    // ── In your library ────────────────────────────────────────────────────

    /** Every song of your library (as far as its DNA is known) [name] is credited on. */
    fun inLibrary(name: String): List<LibrarySong> {
        val k = SongDna.personKey(name)
        return SongDna.all().mapNotNull { d ->
            val roles = d.credits.filter { SongDna.personKey(it.name) == k }.map { it.role }
            if (roles.isEmpty()) null else LibrarySong(d, roles.distinct())
        }.sortedBy { it.dna.title.lowercase() }
    }

    /** Who [name] worked with in your library, the most often first: name → songs together. */
    fun collaborators(name: String): List<Pair<SongDna.Credit, Int>> {
        val k = SongDna.personKey(name)
        val count = HashMap<String, Pair<SongDna.Credit, Int>>()
        for (d in SongDna.all()) {
            if (d.credits.none { SongDna.personKey(it.name) == k }) continue
            d.credits.filter { SongDna.personKey(it.name) != k }.distinctBy { SongDna.personKey(it.name) }.forEach { c ->
                val pk = SongDna.personKey(c.name)
                val old = count[pk]
                count[pk] = (old?.first ?: c) to ((old?.second ?: 0) + 1)
            }
        }
        return count.values.sortedByDescending { it.second }.take(30)
    }

    // ── Looking a person up ────────────────────────────────────────────────

    /** [credit]'s page: what is kept, or looked up now. [ids]: every credit of theirs on the song. */
    suspend fun get(credit: SongDna.Credit, force: Boolean = false): Person {
        val key = SongDna.personKey(credit.name)
        if (!force) cache[key]?.takeIf(::fresh)?.let { return it }
        inFlight[key]?.let { if (!force) return it.await() }
        // BitChord Audio: known, but some sources didn't answer: only they are asked again.
        val gaps = cache[key]?.takeIf { !force && it.failed.isNotEmpty() }
        val job = scope.async { lookUp(credit, key, only = gaps?.failed?.toSet()) }
        inFlight[key] = job
        return try {
            job.await().also { p ->
                val old = cache[key]
                cache[key] = when {
                    gaps != null && old != null -> filledIn(old, p)
                    old != null && p.failed.isNotEmpty() -> merged(old, p)
                    else -> p
                }
                _changes.value++
                scheduleSave()
            }
        } finally {
            inFlight.remove(key)
        }
    }

    /** BitChord Audio: what the sources asked again found, added to what was known; what still didn't answer, later each time. */
    private fun filledIn(old: Person, new: Person) = old.copy(
        fetchedAt = new.fetchedAt,
        image = old.image ?: new.image,
        about = old.about ?: new.about,
        aboutSource = if (old.about != null) old.aboutSource else new.aboutSource,
        facts = old.facts.ifEmpty { new.facts },
        ytBrowseId = old.ytBrowseId ?: new.ytBrowseId,
        works = (old.works + new.works).distinctBy { norm(it.title) + "|" + it.role }.take(200),
        links = (old.links + new.links).distinctBy { it.url },
        failed = new.failed,
        tries = when {
            new.failed.isEmpty() -> 0
            com.music.bitchord.data.YouTubeLimit.active() -> old.tries
            else -> old.tries + 1
        },
    )

    private fun merged(old: Person, new: Person) = new.copy(
        image = new.image ?: old.image,
        about = new.about ?: old.about,
        aboutSource = if (new.about != null) new.aboutSource else old.aboutSource,
        facts = new.facts.ifEmpty { old.facts },
        ytBrowseId = new.ytBrowseId ?: old.ytBrowseId,
        works = (new.works + old.works).distinctBy { norm(it.title) + "|" + it.role }.take(200),
        links = (new.links + old.links).distinctBy { it.url },
    )

    /**
     * The people of your library's songs, at night: those with a writing, arranging or
     * producing credit, and the performers. Called from [SongDna.fillAll].
     */
    internal suspend fun fillSome(limit: Int, stillNight: () -> Boolean): Int {
        val people = SongDna.all().flatMap { it.credits }
            .filter { DnaRoles.group(it.role) != DnaRoles.Group.OTHER }
            .groupBy { SongDna.personKey(it.name) }
            .filter { (k, _) -> cache[k]?.let(::fresh) != true }
            .values.sortedByDescending { it.size }
            .map { list ->
                list.reduce { a, b ->
                    a.copy(
                        mbid = a.mbid ?: b.mbid, geniusId = a.geniusId ?: b.geniusId, discogsId = a.discogsId ?: b.discogsId,
                        ytId = a.ytId ?: b.ytId, image = a.image ?: b.image,
                    )
                }
            }
        var n = 0
        for (c in people.take(limit)) {
            if (!stillNight()) break
            runCatching { withTimeoutOrNull(60_000) { get(c) } }
            n++
            delay(1_500)
        }
        return n
    }

    private suspend fun lookUp(c: SongDna.Credit, key: String, only: Set<String>? = null): Person = coroutineScope {
        val failed = java.util.Collections.synchronizedList(ArrayList<String>())
        suspend fun <T> ask(name: String, limit: Long, block: suspend () -> T?): T? {
            // Asking again only those that didn't answer: the rest is already known.
            if (only != null && name !in only) return null
            var answered = false
            val r = withTimeoutOrNull(limit) {
                runCatching { block() }.onSuccess { answered = true }
                    .onFailure { TrackLog.d(TAG, "song dna people: $name failed for '${c.name}': ${it.message}") }.getOrNull()
            }
            if (!answered) failed += name
            return r
        }
        val yt = async { ask("YouTube Music", 12_000) { youTube(c) } }
        val genius = async { ask("Genius", 20_000) { genius(c) } }
        val discogs = async { ask("Discogs", 20_000) { discogs(c) } }
        val mb = async { ask("MusicBrainz", 40_000) { musicBrainz(c) } }
        val parts = listOfNotNull(genius.await(), discogs.await(), mb.await())
        val y = yt.await()
        val works = parts.flatMap { it.works }
            .groupBy { norm(it.title) + "|" + norm(it.artist) }
            .map { (_, same) ->
                same.first().copy(
                    role = same.map { it.role }.filter { it.isNotBlank() }.distinct().joinToString(", "),
                    year = same.firstNotNullOfOrNull { it.year },
                    art = same.firstNotNullOfOrNull { it.art },
                    url = same.firstNotNullOfOrNull { it.url },
                )
            }
            .filter { it.title.isNotBlank() }
            .take(200)
        val p = Person(
            key = key,
            name = c.name,
            fetchedAt = System.currentTimeMillis(),
            image = c.image ?: y?.second ?: parts.firstNotNullOfOrNull { it.image },
            about = parts.firstNotNullOfOrNull { it.about },
            aboutSource = parts.firstOrNull { it.about != null }?.source,
            facts = parts.flatMap { it.facts }.distinct().take(4),
            ytBrowseId = c.ytId ?: y?.first,
            works = works,
            links = parts.flatMap { it.links }.distinctBy { it.url },
            failed = failed.toList(),
        )
        TrackLog.d(TAG, "song dna people: '${c.name}' — ${p.works.size} works (${parts.joinToString { it.source }})")
        p
    }

    private class Part(val source: String) {
        var image: String? = null
        var about: String? = null
        val facts = ArrayList<String>()
        val works = ArrayList<Work>()
        val links = ArrayList<SongDna.Link>()
    }

    // ── HTTP (as SongDna's: a network failure is thrown, a 404 is nothing) ──

    private val http by lazy { Http.client.newBuilder().callTimeout(12, TimeUnit.SECONDS).build() }

    private fun json(url: String, headers: Map<String, String> = emptyMap()): JSONObject? {
        val b = Request.Builder().url(url).header("User-Agent", AGENT).header("Accept", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        val body = http.newCall(b.build()).execute().use { r ->
            when {
                r.isSuccessful -> r.body?.string()
                r.code == 429 || r.code >= 500 -> throw java.io.IOException("HTTP ${r.code} from ${r.request.url.host}")
                else -> null
            }
        } ?: return null
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun samePerson(a: String, b: String) = SongDna.personKey(a) == SongDna.personKey(b) || norm(a) == norm(b)

    // ── YouTube Music: their artist page and photo ─────────────────────────

    private suspend fun youTube(c: SongDna.Credit): Pair<String, String?>? {
        if (c.ytId != null && c.image != null) return c.ytId to c.image
        val hit = YtMusicRepository.search(c.name, SearchFilter.ARTISTS).getOrNull()
            ?.filterIsInstance<SearchResult.Browse>()
            ?.map { it.item }
            ?.firstOrNull { samePerson(it.title, c.name) }
            ?: return null
        return hit.browseId to hit.thumbnailUrl
    }

    // ── Genius ─────────────────────────────────────────────────────────────

    private fun genius(c: SongDna.Credit): Part? {
        val ua = mapOf("User-Agent" to "BitChord")
        val id = c.geniusId ?: run {
            val body = json("https://genius.com/api/search/multi?q=${enc(c.name)}", ua) ?: return null
            body.optJSONObject("response")?.optJSONArray("sections").objects()
                .firstOrNull { it.optString("type") == "artist" }?.optJSONArray("hits").objects()
                .mapNotNull { it.optJSONObject("result") }
                .firstOrNull { samePerson(it.optString("name"), c.name) }
                ?.optLong("id")?.takeIf { it > 0 }
        } ?: return null
        val p = Part("Genius")
        json("https://genius.com/api/artists/$id?text_format=plain", ua)?.optJSONObject("response")?.optJSONObject("artist")?.let { a ->
            p.image = a.optString("image_url").takeIf { it.startsWith("http") && !it.contains("default_avatar") }
            p.about = a.optJSONObject("description")?.optString("plain")?.trim()?.takeIf { it.length > 30 && it != "?" }?.take(2000)
            a.optString("url").ifBlank { null }?.let { p.links += SongDna.Link("Genius", it) }
        }
        for (page in 1..2) {
            val songs = json("https://genius.com/api/artists/$id/songs?sort=popularity&per_page=50&page=$page", ua)
                ?.optJSONObject("response")?.optJSONArray("songs").objects()
            if (songs.isEmpty()) break
            for (s in songs) {
                val primary = s.optJSONObject("primary_artist")?.optLong("id") == id
                p.works += Work(
                    title = s.optString("title"),
                    artist = s.optString("artist_names"),
                    role = if (primary) "performer" else "",
                    year = s.optJSONObject("release_date_components")?.optInt("year")?.takeIf { it > 0 }?.toString(),
                    art = s.optString("song_art_image_thumbnail_url").ifBlank { null },
                    url = s.optString("url").ifBlank { null },
                    source = "Genius",
                )
            }
            if (songs.size < 50) break
        }
        return p
    }

    // ── MusicBrainz: what they composed, wrote, arranged, produced, played ──

    private suspend fun musicBrainz(c: SongDna.Credit): Part? {
        val mbid = c.mbid ?: run {
            val q = "artist:\"${NameSources.lucene(c.name)}\""
            NameSources.getJson(NameSources.mb("artist", "query" to q, "limit" to "5"), musicBrainz = true)
                ?.optJSONArray("artists").objects()
                .firstOrNull { it.optInt("score") >= 95 && (samePerson(it.optString("name"), c.name) || samePerson(it.optString("sort-name"), c.name)) }
                ?.optString("id")?.takeIf { it.isNotBlank() }
        } ?: return null
        val a = NameSources.getJson(
            NameSources.mb("artist/$mbid", "inc" to "work-rels+recording-rels+url-rels+aliases"),
            musicBrainz = true,
        ) ?: return null
        val p = Part("MusicBrainz")
        p.links += SongDna.Link("MusicBrainz", "https://musicbrainz.org/artist/$mbid")
        val type = a.optString("type").ifBlank { null }
        val country = a.optJSONObject("area")?.optString("name")?.ifBlank { null }
        val begin = a.optJSONObject("life-span")?.optString("begin")?.take(4)?.ifBlank { null }
        val end = a.optJSONObject("life-span")?.optString("end")?.take(4)?.ifBlank { null }
        listOfNotNull(type, country, begin?.let { b -> if (end != null) "$b–$end" else b }).joinToString(" · ").ifBlank { null }
            ?.let { p.facts += it }
        a.optString("disambiguation").ifBlank { null }?.let { p.facts += it }
        for (r in a.optJSONArray("relations").objects()) {
            val role = r.optString("type").ifBlank { continue }
            when (r.optString("target-type")) {
                "work" -> r.optJSONObject("work")?.let { w ->
                    p.works += Work(w.optString("title"), "", role, source = "MusicBrainz", url = "https://musicbrainz.org/work/" + w.optString("id"))
                }
                "recording" -> r.optJSONObject("recording")?.let { rec ->
                    val attrs = r.optJSONArray("attributes")?.let { x -> (0 until x.length()).map { x.optString(it) } }.orEmpty()
                    p.works += Work(
                        rec.optString("title"), "", if (attrs.isNotEmpty() && role == "instrument") attrs.joinToString(", ") else role,
                        year = rec.optString("first-release-date").take(4).ifBlank { null },
                        source = "MusicBrainz", url = "https://musicbrainz.org/recording/" + rec.optString("id"),
                    )
                }
                "url" -> {
                    val u = r.optJSONObject("url")?.optString("resource") ?: continue
                    when (r.optString("type")) {
                        "official homepage" -> p.links += SongDna.Link("Web", u)
                        "wikipedia" -> p.links += SongDna.Link("Wikipedia", u)
                        "wikidata" -> p.links += SongDna.Link("Wikidata", u)
                    }
                }
            }
        }
        return p
    }

    // ── Discogs (with your token) ──────────────────────────────────────────

    private fun discogs(c: SongDna.Credit): Part? {
        val token = DnaKeys.discogs.value.ifBlank { return null }
        val auth = mapOf("Authorization" to "Discogs token=$token")
        val id = c.discogsId ?: json("https://api.discogs.com/database/search?type=artist&per_page=5&q=${enc(c.name)}", auth)
            ?.optJSONArray("results").objects()
            .firstOrNull { samePerson(it.optString("title").replace(Regex("""\s*\(\d+\)$"""), ""), c.name) }
            ?.optLong("id")?.takeIf { it > 0 }
            ?: return null
        val p = Part("Discogs")
        json("https://api.discogs.com/artists/$id", auth)?.let { a ->
            p.about = a.optString("profile").replace(Regex("""\[[a-z]=?([^\]]*)\]"""), "$1").trim().takeIf { it.length > 30 }?.take(2000)
            p.image = a.optJSONArray("images").objects().firstOrNull()?.optString("uri")?.ifBlank { null }
            a.optString("uri").ifBlank { null }?.let { p.links += SongDna.Link("Discogs", it) }
        }
        json("https://api.discogs.com/artists/$id/releases?sort=year&sort_order=desc&per_page=100", auth)
            ?.optJSONArray("releases").objects().forEach { r ->
                val role = r.optString("role").let { if (it == "Main") "performer" else it }
                if (role == "UnofficialRelease") return@forEach
                p.works += Work(
                    title = r.optString("title"),
                    artist = r.optString("artist").replace(Regex("""\s*\(\d+\)"""), ""),
                    role = (r.optString("trackinfo").takeIf { it.isNotBlank() }?.let { "$role ($it)" } ?: role).replace("TrackAppearance", "track"),
                    year = r.optInt("year").takeIf { it > 0 }?.toString(),
                    art = r.optString("thumb").ifBlank { null },
                    url = r.optString("resource_url").ifBlank { null }?.let { "https://www.discogs.com/" + (if (r.optString("type") == "master") "master/" else "release/") + r.optLong("id") },
                    source = "Discogs",
                )
            }
        return p
    }

    // ── JSON ───────────────────────────────────────────────────────────────

    private fun toJson(p: Person) = JSONObject().apply {
        put("k", p.key); put("n", p.name); put("at", p.fetchedAt)
        p.image?.let { put("im", it) }; p.about?.let { put("ab", it) }; p.aboutSource?.let { put("as", it) }
        p.ytBrowseId?.let { put("yt", it) }
        put("fa", JSONArray(p.facts))
        put("w", JSONArray(p.works.map { w ->
            JSONObject().put("t", w.title).put("a", w.artist).put("r", w.role).put("s", w.source)
                .apply { w.year?.let { put("y", it) }; w.art?.let { put("i", it) }; w.url?.let { put("u", it) } }
        }))
        put("ln", JSONArray(p.links.map { JSONObject().put("l", it.label).put("u", it.url) }))
        put("fail", JSONArray(p.failed))
        if (p.tries > 0) put("tr", p.tries)
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

    private fun fromJson(o: JSONObject) = Person(
        key = o.optString("k"),
        name = o.optString("n"),
        fetchedAt = o.optLong("at"),
        image = o.optString("im").ifBlank { null },
        about = o.optString("ab").ifBlank { null },
        aboutSource = o.optString("as").ifBlank { null },
        facts = o.optJSONArray("fa").strings(),
        ytBrowseId = o.optString("yt").ifBlank { null },
        works = o.optJSONArray("w").objects().map {
            Work(
                it.optString("t"), it.optString("a"), it.optString("r"),
                it.optString("y").ifBlank { null }, it.optString("i").ifBlank { null }, it.optString("u").ifBlank { null },
                it.optString("s"),
            )
        },
        links = o.optJSONArray("ln").objects().map { SongDna.Link(it.optString("l"), it.optString("u")) },
        failed = o.optJSONArray("fail").strings(),
        tries = o.optInt("tr"),
    )

    @Suppress("unused")
    private val lang get() = Locale.getDefault().language
}

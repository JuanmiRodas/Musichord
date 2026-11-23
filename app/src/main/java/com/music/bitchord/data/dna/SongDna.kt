package com.music.bitchord.data.dna

import android.content.Context
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.BackgroundHours
import com.music.bitchord.data.Http
import com.music.bitchord.data.LikedMusicCache
import com.music.bitchord.data.LocalLikes
import com.music.bitchord.data.OwnLibrary
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.names.NameSources
import com.music.bitchord.data.names.NameSources.objects
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.OwnFileMatcher
import com.music.bitchord.data.webdav.WebDavConfig
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
import kotlinx.coroutines.withContext
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
 * BitChord Audio: a song's "DNA", the way Spotify's SongDNA shows it — who wrote,
 * produced and played it, its story, its alternative covers, and the songs it is
 * connected to (samples, interpolations, covers, remixes, live versions).
 *
 * Gathered from free sources only, all at once, each given a few seconds:
 *  - YouTube Music's own song credits (the "Song credits" page),
 *  - MusicBrainz (credits of the recording and of its work — composer, lyricist,
 *    arranger — release, ISRC, genres) and the Cover Art Archive (other covers),
 *  - Wikidata and Wikipedia (the song's article, in Spanish, English or Japanese),
 *  - Genius (writers, producers, every credit, the story, samples and covers),
 *  - Last.fm (its wiki, tags, listeners), iTunes and Deezer (genre, date, BPM, covers),
 *  - Discogs, with your own free token (detailed credits, label, catalogue number),
 *  - AnimeThemes (the anime openings and endings it is),
 *  - for Japanese music, anime and games ([DnaJapan]): VGMdb, pocketmonsters.net,
 *    canta-per-me.net, niconico 大百科, Generasia and the series' own wikis.
 *
 * Kept on the phone in [FILE] (copied to pCloud by CloudBackup) and filled in for your
 * whole library at night ([fillAll]); a song not yet looked up is looked up when its page
 * is opened.
 */
object SongDna {

    private const val TAG = "BitChord"
    const val FILE = "song_dna.json"
    private const val VERSION = 3
    private const val FRESH_MS = 120L * 24 * 60 * 60 * 1000
    private const val FRESH_EMPTY_MS = 21L * 24 * 60 * 60 * 1000
    private const val FRESH_FAILED_MS = 2L * 24 * 60 * 60 * 1000
    private const val AGENT = "BitChordAudio/1.9 ( https://github.com/JuanmiRodas/Musichord )"

    /** A person on the song, with whatever ids the sources gave (so their page finds the right person). */
    data class Credit(
        val role: String,
        val name: String,
        val source: String,
        val mbid: String? = null,
        val geniusId: Long? = null,
        val discogsId: Long? = null,
        val ytId: String? = null,
        val image: String? = null,
        /** VGMdb's artist page ("artist/77"). */
        val vgmdbId: String? = null,
    )
    data class Fact(val text: String, val source: String, val url: String?)
    data class Cover(val url: String, val label: String)
    data class Link(val label: String, val url: String)
    data class Relation(
        val kind: String,
        val title: String,
        val artist: String,
        val year: String? = null,
        val art: String? = null,
        val url: String? = null,
    )

    /** What is known about a song. */
    data class Dna(
        val key: String,
        val title: String,
        val artist: String,
        val fetchedAt: Long,
        val version: Int = VERSION,
        val album: String? = null,
        val releaseDate: String? = null,
        val label: String? = null,
        /** Catalogue number of the release it is on ("VVCL-2985"). */
        val catalog: String? = null,
        val isrc: String? = null,
        val bpm: String? = null,
        val language: String? = null,
        val genres: List<String> = emptyList(),
        val credits: List<Credit> = emptyList(),
        val facts: List<Fact> = emptyList(),
        val covers: List<Cover> = emptyList(),
        val relations: List<Relation> = emptyList(),
        val uses: List<String> = emptyList(),
        val stats: Map<String, String> = emptyMap(),
        val links: List<Link> = emptyList(),
        val sources: List<String> = emptyList(),
        /** Sources that didn't answer (network, timeout): asked again sooner, and nothing of theirs is lost. */
        val failed: List<String> = emptyList(),
    ) {
        val empty: Boolean
            get() = credits.isEmpty() && facts.isEmpty() && relations.isEmpty() && covers.isEmpty() && genres.isEmpty()
    }

    // Relation kinds.
    const val SAMPLES = "samples"
    const val SAMPLED_IN = "sampled_in"
    const val INTERPOLATES = "interpolates"
    const val INTERPOLATED_BY = "interpolated_by"
    const val COVER_OF = "cover_of"
    const val COVERED_BY = "covered_by"
    const val REMIX_OF = "remix_of"
    const val REMIXED_BY = "remixed_by"
    const val LIVE_VERSION_OF = "live_version_of"
    const val PERFORMED_LIVE_AS = "performed_live_as"
    const val TRANSLATION_OF = "translation_of"
    const val TRANSLATIONS = "translations"

    // ── Keys and storage ───────────────────────────────────────────────────

    private val BRACKETS = Regex("""\s*[(\[（【「『][^)\]）】」』]*[)\]）】」』]\s*""")
    private val INNER = Regex("""[(\[（【「『]([^)\]）】」』]+)[)\]）】」』]""")
    private val NOISE = Regex(
        """\s*[-–—]\s*(?:\d{4}\s+)?(?:remaster(?:ed)?|single version|album version|radio edit|mono|stereo).*$""",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT = Regex("""\s*(?:\(|\[)?\s*(?:feat\.?|ft\.?|featuring|with)\s+[^)\]]*[)\]]?""", RegexOption.IGNORE_CASE)

    fun firstArtist(a: String): String =
        a.split(Regex("""\s*(?:,|&|/|;|、|\bfeat\.?|\bft\.?|\bx\b|×)\s*""", RegexOption.IGNORE_CASE)).first().trim().ifBlank { a.trim() }

    /** The title without its brackets, edition notes and featured artists. */
    fun bareTitle(t: String): String =
        t.replace(NOISE, "").replace(FEAT, " ").replace(BRACKETS, " ").replace(Regex("\\s+"), " ").trim().ifBlank { t.trim() }

    /** The titles to ask with: bare, and what was in its brackets ("Wasure Saki (忘れ咲き)" → both). */
    fun titles(t: String): List<String> {
        val out = LinkedHashSet<String>()
        out += bareTitle(t)
        INNER.findAll(t).map { it.groupValues[1].trim() }
            .filter { it.length >= 2 && !Regex("""(?i)feat|ft\.|remaster|version|edit|live|mix|from|op|ed|ost""").containsMatchIn(it) }
            .forEach { out += it }
        return out.filter { it.isNotBlank() }
    }

    /** One person under any spelling a source used ("Mika Nakashima", "MIKA NAKASHIMA"). */
    fun personKey(name: String): String = OwnFileMatcher.key(name).ifEmpty { name.trim().lowercase() }

    /** Everything known, for the people pages (who worked on what in your library). */
    fun all(): Collection<Dna> = cache.values

    fun keyOf(song: Song): String = OwnFileMatcher.key(bareTitle(song.title)) + "|" + OwnFileMatcher.key(firstArtist(song.artist))

    private val cache = ConcurrentHashMap<String, Dna>()
    private val inFlight = ConcurrentHashMap<String, Deferred<Dna>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var file: File? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var saveJob: Job? = null
    @Volatile private var filling: Job? = null

    private val _changes = MutableStateFlow(0)
    /** Bumped whenever something is learnt. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    /** The night's fill: done of total, while it runs. */
    val progress: StateFlow<Pair<Int, Int>?> = _progress.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        file = File(context.filesDir, FILE)
        DnaKeys.init(context)
        DnaPeople.init(context)
        DnaJapan.init(context)
        scope.launch { load() }
    }

    /** Read again (after a restore from pCloud). */
    fun load() {
        val text = runCatching { file?.takeIf { it.isFile }?.readText() }.getOrNull() ?: return
        runCatching {
            val root = JSONObject(text)
            val songs = root.optJSONObject("songs") ?: return
            songs.keys().forEach { k -> runCatching { cache[k] = fromJson(songs.getJSONObject(k)) } }
            _changes.value++
            TrackLog.d(TAG, "song dna: ${cache.size} songs known")
        }
    }

    private fun scheduleSave() {
        if (saveJob?.isActive == true) return
        saveJob = scope.launch {
            delay(15_000)
            val songs = JSONObject()
            cache.forEach { (k, v) -> songs.put(k, toJson(v)) }
            val root = JSONObject().put("version", VERSION).put("songs", songs)
            runCatching {
                val f = file ?: return@runCatching
                val tmp = File(f.parentFile, "$FILE.tmp")
                tmp.writeText(root.toString())
                tmp.renameTo(f)
            }
        }
    }

    /**
     * BitChord Audio: a song nobody had anything to say about, looked up on the web
     * ([SongResearch]) and its findings kept with its DNA. Asked once per song (an empty
     * answer is kept too: no facts and the "researched" source).
     */
    suspend fun research(song: Song): Dna? {
        val key = keyOf(song)
        val d = cache[key] ?: return null
        if (d.facts.isNotEmpty() || RESEARCHED in d.sources) return d
        val found = runCatching { SongResearch.find(song.title, song.artist) }.getOrDefault(emptyList())
        val updated = d.copy(facts = d.facts + found, sources = d.sources + RESEARCHED)
        cache[key] = updated
        _changes.value++
        scheduleSave()
        return updated
    }

    private const val RESEARCHED = "web research"

    /** What is already known for [song], without asking anyone. */
    fun cached(song: Song): Dna? = cache[keyOf(song)]

    private fun fresh(d: Dna): Boolean =
        d.version == VERSION && timeFresh(d)

    private fun timeFresh(d: Dna): Boolean = System.currentTimeMillis() - d.fetchedAt < when {
            d.failed.isNotEmpty() -> FRESH_FAILED_MS
            d.empty -> FRESH_EMPTY_MS
            else -> FRESH_MS
        }

    /** [song]'s DNA: what is kept, or looked up now ([force]: looked up again anyway). */
    suspend fun get(song: Song, force: Boolean = false): Dna {
        val key = keyOf(song)
        if (!force) cache[key]?.takeIf(::fresh)?.let { return it }
        val running = inFlight[key]
        if (running != null && !force) return running.await()
        // Looked up before the Japanese / anime / game sources existed: only they are asked now.
        val older = cache[key]?.takeIf { !force && it.version == 2 && timeFresh(it) }
        val job = scope.async {
            if (older != null) withJapan(song, older)
            else lookUp(song, key).let { new -> cache[key]?.let { old -> merged(old, new) } ?: new }
        }
        inFlight[key] = job
        return try {
            job.await().also { d ->
                cache[key] = d
                _changes.value++
                scheduleSave()
            }
        } finally {
            inFlight.remove(key)
        }
    }

    // ── The night's fill ───────────────────────────────────────────────────

    /**
     * Every liked song and every song of your library, looked up at night only
     * ([BackgroundHours]), on Wi-Fi, one at a time. Starts again the next night where
     * it stopped (songs already known are skipped).
     */
    fun fillAll() {
        if (filling?.isActive == true) return
        filling = scope.launch {
            delay(120_000)
            while (true) {
                BackgroundHours.await()
                val pool = (
                    LikedMusicCache.likedSongs.value +
                        LocalLikes.liked.value.values +
                        runCatching { com.music.bitchord.data.webdav.WebDavRepository.library() }.getOrDefault(emptyList()) +
                        OwnLibrary.uploads.value
                    )
                    .filter { !it.isVideo && it.title.isNotBlank() }
                    .distinctBy { keyOf(it) }
                val todo = pool.filter { s -> cache[keyOf(s)]?.let(::fresh) != true }
                if (todo.isEmpty()) {
                    if (AppSettings.meteredConnection.value == false) {
                        val people = DnaPeople.fillSome(300) { BackgroundHours.now() && AppSettings.meteredConnection.value == false }
                        if (people > 0) TrackLog.d(TAG, "song dna: $people people looked up")
                    }
                    delay(6 * 60 * 60_000L)
                    continue
                }
                TrackLog.d(TAG, "song dna: ${todo.size} of ${pool.size} songs to look up tonight")
                var done = 0
                _progress.value = 0 to todo.size
                for (s in todo) {
                    if (!BackgroundHours.now()) break
                    while (AppSettings.meteredConnection.value != false) {
                        delay(60_000)
                        if (!BackgroundHours.now()) break
                    }
                    if (!BackgroundHours.now()) break
                    runCatching { withTimeoutOrNull(180_000) { get(s) } }
                    done++
                    _progress.value = done to todo.size
                    delay(1_500)
                }
                _progress.value = null
                TrackLog.d(TAG, "song dna: $done songs looked up")
                // Then the people on their credits (their other work), a few hundred a night.
                if (BackgroundHours.now()) {
                    val people = DnaPeople.fillSome(300) { BackgroundHours.now() && AppSettings.meteredConnection.value == false }
                    TrackLog.d(TAG, "song dna: $people people looked up")
                }
                delay(30 * 60_000L)
            }
        }
    }

    // ── Looking a song up ──────────────────────────────────────────────────

    internal class Found {
        val credits = ArrayList<Credit>()
        val facts = ArrayList<Fact>()
        val covers = ArrayList<Cover>()
        val relations = ArrayList<Relation>()
        val genres = ArrayList<String>()
        val uses = ArrayList<String>()
        val stats = LinkedHashMap<String, String>()
        val links = ArrayList<Link>()
        val sources = ArrayList<String>()
        var album: String? = null
        var releaseDate: String? = null
        var label: String? = null
        var isrc: String? = null
        var bpm: String? = null
        var language: String? = null
        var wikidata: String? = null
        var catalog: String? = null
    }

    private suspend fun lookUp(song: Song, key: String): Dna = coroutineScope {
        val titles = titles(song.title)
        val artist = firstArtist(song.artist)
        val seconds = secondsOf(song.durationText)
        val failed = java.util.Collections.synchronizedList(ArrayList<String>())
        suspend fun ask(name: String, limit: Long, block: suspend () -> Found?): Found? {
            var answered = false
            val r = withTimeoutOrNull(limit) {
                runCatching { block() }
                    .onSuccess { answered = true }
                    .onFailure { TrackLog.d(TAG, "song dna: $name failed: ${it.message}") }
                    .getOrNull()
            }
            if (!answered) failed += name
            return r
        }
        // 1. Everyone who can be asked by name, at once. Deezer's answer carries the ISRC —
        //    the recording's own code — which phase 2 and 3 find it by exactly.
        val first = listOf(
            "YouTube Music" to suspend { youTubeMusic(song) },
            "Genius" to suspend { genius(titles, artist) },
            "Last.fm" to suspend { lastFm(titles.first(), artist) },
            "iTunes" to suspend { iTunes(titles.first(), artist, seconds) },
            "Deezer" to suspend { deezer(titles, artist, seconds) },
            "Discogs" to suspend { discogs(titles.first(), artist) },
            "TheAudioDB" to suspend { audioDb(titles, artist) },
            "AnimeThemes" to suspend { animeThemes(song) },
        ).map { (name, block) -> async(Dispatchers.IO) { ask(name, 15_000, block) } }.mapNotNull { it.await() }
        val isrc = first.firstNotNullOfOrNull { it.isrc }
        // 2. MusicBrainz: by the ISRC when there is one (no guessing), else by name and length.
        val mb = ask("MusicBrainz", 45_000) { musicBrainz(titles, artist, isrc, seconds) }
        val found = first + listOfNotNull(mb)
        // 3. Wikipedia: through Wikidata — MusicBrainz's link, else the ISRC — else searched.
        val wiki = ask("Wikipedia", 20_000) {
            val wd = mb?.wikidata ?: isrc?.let { wikidataByIsrc(it) }
            wikipedia(wd, titles, artist)
        }
        val general = found + listOfNotNull(wiki)
        // 4. The Japanese, anime and game sources (VGMdb, the fan sites, the series' wikis).
        val japan = runCatching {
            val ctx = DnaJapan.contextOf(
                song, general.firstNotNullOfOrNull { it.album }, general.flatMap { it.uses },
                general.flatMap { it.genres } + listOfNotNull(general.firstNotNullOfOrNull { it.language }?.let { "lang $it" }),
            )
            val credits = general.flatMap { it.credits }
            val noWriters = credits.none { DnaRoles.group(it.role) == DnaRoles.Group.WRITING }
            if (DnaJapan.relevant(song, ctx, general.flatMap { it.uses }, credits)) {
                DnaJapan.lookUp(song, ctx, general.firstNotNullOfOrNull { it.album }, credits, noWriters) { n, l, b -> ask(n, l, b) }
            } else emptyList()
        }.getOrDefault(emptyList())
        val all = general + japan
        fun <T> firstOf(f: (Found) -> T?): T? = all.firstNotNullOfOrNull(f)
        val dna = Dna(
            key = key,
            title = song.title,
            artist = song.artist,
            fetchedAt = System.currentTimeMillis(),
            album = firstOf { it.album },
            releaseDate = earliestDate(all.mapNotNull { it.releaseDate }),
            label = firstOf { it.label },
            catalog = firstOf { it.catalog },
            isrc = firstOf { it.isrc },
            bpm = firstOf { it.bpm },
            language = firstOf { it.language },
            genres = all.flatMap { it.genres }.map { it.trim().replaceFirstChar { c -> c.uppercase() } }
                .filter { it.isNotBlank() && it.length < 40 }.distinctBy { it.lowercase() }.take(8),
            credits = mergeCredits(all.flatMap { it.credits }),
            facts = all.flatMap { it.facts }.filter { it.text.length > 20 }.distinctBy { it.text.take(60) }.take(9),
            covers = all.flatMap { it.covers }.distinctBy { it.url }.take(24),
            relations = all.flatMap { it.relations }
                .distinctBy { it.kind + "|" + OwnFileMatcher.key(it.title) + "|" + OwnFileMatcher.key(firstArtist(it.artist)) }.take(60),
            uses = all.flatMap { it.uses }.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(16),
            stats = LinkedHashMap<String, String>().also { m -> all.forEach { f -> f.stats.forEach { (k, v) -> m.putIfAbsent(k, v) } } },
            links = all.flatMap { it.links }.distinctBy { it.url }.take(20),
            sources = all.flatMap { it.sources }.distinct(),
            failed = failed.toList(),
        )
        TrackLog.d(
            TAG,
            "song dna: '${song.title}' — ${dna.credits.size} credits, ${dna.facts.size} texts, ${dna.relations.size} connections, " +
                "${dna.covers.size} covers (${dna.sources.joinToString()})",
        )
        dna
    }

    /** The earliest year said, written as fully as any source has it ("2001-05-16" over "2001"). */
    private fun earliestDate(dates: List<String>): String? {
        val ok = dates.map { it.trim() }.filter { it.length >= 4 && it.take(4).all(Char::isDigit) && !it.startsWith("0000") }
        val year = ok.minOfOrNull { it.take(4) } ?: return null
        return ok.filter { it.startsWith(year) }.maxByOrNull { it.length }
    }

    /**
     * A song looked up before the Japanese / anime / game sources: they alone are asked,
     * and what they find is added to what was known.
     */
    private suspend fun withJapan(song: Song, old: Dna): Dna {
        val failed = java.util.Collections.synchronizedList(ArrayList<String>())
        val ctx = DnaJapan.contextOf(song, old.album, old.uses, old.genres + listOfNotNull(old.language?.let { "lang $it" }))
        val noWriters = old.credits.none { DnaRoles.group(it.role) == DnaRoles.Group.WRITING }
        val japan = if (DnaJapan.relevant(song, ctx, old.uses, old.credits)) {
            DnaJapan.lookUp(song, ctx, old.album, old.credits, noWriters) { name, limit, block ->
                var answered = false
                val r = withTimeoutOrNull(limit) {
                    runCatching { block() }.onSuccess { answered = true }
                        .onFailure { TrackLog.d(TAG, "song dna: $name failed: ${it.message}") }.getOrNull()
                }
                if (!answered) failed += name
                r
            }
        } else emptyList()
        val d = old.copy(
            version = VERSION,
            album = old.album ?: japan.firstNotNullOfOrNull { it.album },
            releaseDate = earliestDate(listOfNotNull(old.releaseDate) + japan.mapNotNull { it.releaseDate }),
            label = old.label ?: japan.firstNotNullOfOrNull { it.label },
            catalog = old.catalog ?: japan.firstNotNullOfOrNull { it.catalog },
            genres = (old.genres + japan.flatMap { it.genres }).filter { it.isNotBlank() && it.length < 40 }.distinctBy { it.lowercase() }.take(10),
            credits = mergeCredits(old.credits + japan.flatMap { it.credits }),
            facts = (old.facts + japan.flatMap { it.facts }).filter { it.text.length > 20 }.distinctBy { it.text.take(60) }.take(9),
            covers = (old.covers + japan.flatMap { it.covers }).distinctBy { it.url }.take(24),
            uses = (old.uses + japan.flatMap { it.uses }).map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.take(16),
            links = (old.links + japan.flatMap { it.links }).distinctBy { it.url }.take(20),
            sources = (old.sources + japan.flatMap { it.sources }).distinct(),
            failed = (old.failed + failed).distinct(),
        )
        if (japan.isNotEmpty()) TrackLog.d(TAG, "song dna: '${song.title}' — added from ${japan.flatMap { it.sources }.joinToString()}")
        return d
    }

    /**
     * A new look-up on top of the last one: what a source said before is kept when it
     * didn't answer this time, so a bad night never makes a song's page poorer.
     */
    private fun merged(old: Dna, new: Dna): Dna {
        if (new.failed.isEmpty()) return new
        val lost = old.sources.filter { it in new.failed }
        if (lost.isEmpty()) return new
        fun <T> keep(a: List<T>, b: List<T>, n: Int, by: (T) -> Any) = (a + b).distinctBy(by).take(n)
        return new.copy(
            album = new.album ?: old.album,
            releaseDate = new.releaseDate ?: old.releaseDate,
            label = new.label ?: old.label,
            catalog = new.catalog ?: old.catalog,
            isrc = new.isrc ?: old.isrc,
            bpm = new.bpm ?: old.bpm,
            language = new.language ?: old.language,
            genres = keep(new.genres, old.genres, 8) { it.lowercase() },
            credits = mergeCredits(new.credits + old.credits.filter { it.source in lost }),
            facts = keep(new.facts, old.facts.filter { it.source in lost }, 6) { it.text.take(60) },
            covers = keep(new.covers, old.covers, 24) { it.url },
            relations = keep(new.relations, old.relations, 60) { it.kind + it.title + it.artist },
            uses = keep(new.uses, old.uses, 12) { it },
            stats = old.stats + new.stats,
            links = keep(new.links, old.links, 14) { it.url },
            sources = (new.sources + lost).distinct(),
        )
    }

    /** One credit per person and role, the most specific role kept. */
    private fun mergeCredits(list: List<Credit>): List<Credit> {
        val seen = LinkedHashMap<String, Credit>()
        for (c in list) {
            val name = c.name.trim()
            if (name.isBlank() || name.length > 80) continue
            val k = DnaRoles.group(c.role).name + "|" + OwnFileMatcher.key(name).ifEmpty { name.lowercase() }
            val old = seen[k]
            if (old == null || (old.role.length < c.role.length && c.role.length < 40)) seen[k] = c.copy(name = name)
        }
        // Every id any source gave a person, on each of their credits.
        val ids = HashMap<String, Credit>()
        for (c in list) {
            val pk = personKey(c.name)
            val o = ids[pk] ?: c
            ids[pk] = o.copy(
                mbid = o.mbid ?: c.mbid, geniusId = o.geniusId ?: c.geniusId, discogsId = o.discogsId ?: c.discogsId,
                ytId = o.ytId ?: c.ytId, image = o.image ?: c.image, vgmdbId = o.vgmdbId ?: c.vgmdbId,
            )
        }
        return seen.values.map { c ->
            val i = ids[personKey(c.name)] ?: return@map c
            c.copy(mbid = c.mbid ?: i.mbid, geniusId = c.geniusId ?: i.geniusId, discogsId = c.discogsId ?: i.discogsId, ytId = c.ytId ?: i.ytId, image = c.image ?: i.image, vgmdbId = c.vgmdbId ?: i.vgmdbId)
        }.sortedBy { DnaRoles.group(it.role).ordinal }.take(80)
    }

    // ── HTTP ───────────────────────────────────────────────────────────────

    private val http by lazy { Http.client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build() }

    /**
     * The body, or null when the site has nothing ("not found"). A network failure, a
     * rate limit or a server error is thrown instead — the source is then marked as not
     * having answered, and asked again soon rather than taken as having nothing.
     */
    private fun get(url: String, headers: Map<String, String> = emptyMap()): String? {
        val b = Request.Builder().url(url).header("User-Agent", AGENT).header("Accept", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        return http.newCall(b.build()).execute().use { r ->
            when {
                r.isSuccessful -> r.body?.string()
                r.code == 429 || r.code >= 500 -> throw java.io.IOException("HTTP ${r.code} from ${r.request.url.host}")
                else -> null
            }
        }
    }

    /** "4:59" → 299. */
    internal fun secondsOf(t: String?): Int? = t?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
        ?.takeIf { it.isNotEmpty() }?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }

    /** Lengths that can be the same recording (both known and within 8 s, or one unknown). */
    private fun sameLength(a: Int?, b: Int?): Boolean = a == null || b == null || b <= 0 || kotlin.math.abs(a - b) <= 8

    private fun json(url: String, headers: Map<String, String> = emptyMap()): JSONObject? =
        get(url, headers)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun norm(s: String) = s.lowercase().replace(BRACKETS, " ").filter { it.isLetterOrDigit() }

    internal fun sameTitle(want: List<String>, got: String): Boolean {
        val g = norm(got)
        if (g.isEmpty()) return false
        return want.any { w -> val n = norm(w); n.isNotEmpty() && (n == g || (n.length > 3 && (g.startsWith(n) || n.startsWith(g)))) } ||
            // Romaji against Japanese ("Wasure Saki" · 忘れ咲き).
            want.any { w -> runCatching { com.music.bitchord.data.lyrics.CrossScript.sameTitle(w, got) }.getOrDefault(false) }
    }

    internal fun sameArtist(want: String, got: String): Boolean =
        runCatching { OwnFileMatcher.sameArtist(want, got) }.getOrDefault(false) ||
            norm(got).let { g -> val w = norm(want); w.isNotEmpty() && g.isNotEmpty() && (g.contains(w) || w.contains(g)) } ||
            runCatching { com.music.bitchord.data.lyrics.CrossScript.sameArtist(want, got) }.getOrDefault(false)

    internal fun clip(s: String, max: Int = 1600): String {
        val t = s.replace(Regex("\\s+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        if (t.length <= max) return t
        val cut = t.take(max)
        val end = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("。"))
        return (if (end > max / 2) cut.take(end + 1) else "$cut…").trim()
    }

    // ── YouTube Music ──────────────────────────────────────────────────────

    /** A YouTube id for [song]: its own, its link, or found by title and artist. */
    private suspend fun youTubeIdOf(song: Song): String? {
        if (LocalLikes.isYouTubeId(song.videoId) && Regex("^[A-Za-z0-9_-]{11}$").matches(song.videoId)) return song.videoId
        runCatching { LocalLikes.sameAs(song.videoId) }.getOrNull()
            ?.firstOrNull { LocalLikes.isYouTubeId(it) && Regex("^[A-Za-z0-9_-]{11}$").matches(it) }?.let { return it }
        val titles = titles(song.title)
        val artist = firstArtist(song.artist)
        val found = withTimeoutOrNull(8_000) {
            YtMusicRepository.search("${titles.first()} $artist", SearchFilter.SONGS).getOrNull()
        }.orEmpty().mapNotNull { r ->
            when (r) {
                is SearchResult.Track -> r.song
                is SearchResult.TopTrack -> r.song
                else -> null
            }
        }
        return found.firstOrNull { sameTitle(titles + song.title, it.title) && sameArtist(artist, it.artist) }?.videoId
    }

    private suspend fun youTubeMusic(song: Song): Found? {
        val id = youTubeIdOf(song) ?: return null
        val response = runCatching { Innertube.browse("MPTC$id") }.getOrNull() ?: return null
        val root = runCatching { JSONObject(response.toString()) }.getOrNull() ?: return null
        val f = Found()
        // The credits dialog: sections of a role (its title) and the people (its subtitle).
        walk(root) { o ->
            val section = o.optJSONObject("dismissableDialogContentSectionRenderer") ?: return@walk
            val role = runs(section.optJSONObject("title")) ?: return@walk
            val subtitle = section.optJSONObject("subtitle")
            val people = runs(subtitle) ?: return@walk
            // A name that is a link is an artist page on YouTube Music.
            val pages = HashMap<String, String>()
            subtitle?.optJSONArray("runs").objects().forEach { r ->
                val id = r.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                if (!id.isNullOrBlank()) pages[r.optString("text").trim()] = id
            }
            for (name in people.split('\n', '•').flatMap { it.split(Regex("""\s*,\s*|\s+&\s+""")) }) {
                val n = name.trim()
                if (n.isBlank()) continue
                if (DnaRoles.group(role) == DnaRoles.Group.LABEL) f.label = f.label ?: n
                else f.credits += Credit(role, n, "YouTube Music", ytId = pages[n])
            }
        }
        if (f.credits.isEmpty() && f.label == null) return null
        f.sources += "YouTube Music"
        f.links += Link("YouTube Music", "https://music.youtube.com/watch?v=$id")
        return f
    }

    private fun runs(o: JSONObject?): String? {
        if (o == null) return null
        o.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val r = o.optJSONArray("runs") ?: return null
        return (0 until r.length()).joinToString("") { r.optJSONObject(it)?.optString("text").orEmpty() }.ifBlank { null }
    }

    private fun walk(node: Any?, visit: (JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> {
                visit(node)
                node.keys().forEach { walk(node.opt(it), visit) }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), visit)
        }
    }

    // ── MusicBrainz and the Cover Art Archive ──────────────────────────────

    private fun credit(o: JSONObject): String =
        o.optJSONArray("artist-credit").objects().joinToString("") { c ->
            c.optString("name").ifEmpty { c.optJSONObject("artist")?.optString("name").orEmpty() } + c.optString("joinphrase")
        }.trim()

    private fun mbRelCredits(rels: JSONArray?, into: Found) {
        for (r in rels.objects()) {
            if (r.optString("target-type") != "artist") continue
            val name = r.optJSONObject("artist")?.optString("name")?.takeIf { it.isNotBlank() } ?: continue
            val mbid = r.optJSONObject("artist")?.optString("id")?.takeIf { it.isNotBlank() }
            val type = r.optString("type")
            val attrs = r.optJSONArray("attributes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val role = when {
                type == "instrument" && attrs.isNotEmpty() -> attrs.joinToString(", ")
                type == "vocal" -> attrs.firstOrNull()?.let { "$it vocals" } ?: "vocals"
                else -> type + if (attrs.isNotEmpty() && type != "instrument") " (" + attrs.joinToString(", ") + ")" else ""
            }
            into.credits += Credit(role, name, "MusicBrainz", mbid = mbid)
        }
    }

    private suspend fun musicBrainz(titles: List<String>, artist: String, isrc: String?, seconds: Int?): Found? {
        var recId: String? = null
        if (isrc != null) {
            val byCode = NameSources.getJson(NameSources.mb("isrc/$isrc", "inc" to "artist-credits"), musicBrainz = true)
                ?.optJSONArray("recordings").objects()
            recId = (byCode.firstOrNull { sameArtist(artist, credit(it)) } ?: byCode.firstOrNull())
                ?.optString("id")?.takeIf { it.isNotBlank() }
        }
        if (recId == null) for (t in titles.take(2)) {
            val q = "recording:\"${NameSources.lucene(t)}\" AND artist:\"${NameSources.lucene(artist)}\""
            val found = NameSources.getJson(NameSources.mb("recording", "query" to q, "limit" to "8"), musicBrainz = true) ?: continue
            val hit = found.optJSONArray("recordings").objects()
                .filter { it.optInt("score") >= 80 && sameTitle(titles, it.optString("title")) && sameArtist(artist, credit(it)) }
                .filter { sameLength(seconds, it.optInt("length").takeIf { l -> l > 0 }?.let { l -> l / 1000 }) }
                // The earliest release is the original, not a compilation's copy.
                .minByOrNull { it.optString("first-release-date").ifBlank { "9999" } }
            recId = hit?.optString("id")?.takeIf { it.isNotBlank() }
            if (recId != null) break
        }
        val id = recId ?: return null
        val rec = NameSources.getJson(
            NameSources.mb(
                "recording/$id",
                "inc" to "artist-credits+releases+release-groups+isrcs+artist-rels+work-rels+url-rels+genres+tags",
            ),
            musicBrainz = true,
        ) ?: return null
        val f = Found()
        f.sources += "MusicBrainz"
        f.links += Link("MusicBrainz", "https://musicbrainz.org/recording/$id")
        f.releaseDate = rec.optString("first-release-date").ifBlank { null }
        f.isrc = isrc ?: rec.optJSONArray("isrcs")?.optString(0)?.ifBlank { null }
        (rec.optJSONArray("genres").objects() + rec.optJSONArray("tags").objects())
            .sortedByDescending { it.optInt("count") }.take(5).forEach { f.genres += it.optString("name") }
        mbRelCredits(rec.optJSONArray("relations"), f)
        credit(rec).takeIf { it.isNotBlank() }?.let { f.credits += Credit("performer", it, "MusicBrainz") }
        val releases = rec.optJSONArray("releases").objects()
        val original = releases.filter { it.optString("status").equals("Official", true) }
            .minByOrNull { it.optString("date").ifBlank { "9999" } } ?: releases.firstOrNull()
        f.album = original?.optString("title")?.ifBlank { null }
        for (r in rec.optJSONArray("relations").objects()) {
            val url = r.optJSONObject("url")?.optString("resource") ?: continue
            if (r.optString("type") == "wikidata") f.wikidata = url.substringAfterLast('/')
        }
        // The work: who composed it and wrote its words, and its own Wikidata.
        val work = rec.optJSONArray("relations").objects().firstOrNull { it.optString("target-type") == "work" }
            ?.optJSONObject("work")?.optString("id")?.takeIf { it.isNotBlank() }
        if (work != null) {
            NameSources.getJson(NameSources.mb("work/$work", "inc" to "artist-rels+url-rels"), musicBrainz = true)?.let { w ->
                mbRelCredits(w.optJSONArray("relations"), f)
                w.optJSONArray("languages")?.optString(0)?.ifBlank { null }?.let { f.language = it }
                for (r in w.optJSONArray("relations").objects()) {
                    val url = r.optJSONObject("url")?.optString("resource") ?: continue
                    if (r.optString("type") == "wikidata" && f.wikidata == null) f.wikidata = url.substringAfterLast('/')
                }
            }
        }
        // Other covers: the fronts of the release groups it is on (a few).
        val groups = releases.mapNotNull { r ->
            r.optJSONObject("release-group")?.let { g -> g.optString("id") to (g.optString("title").ifBlank { r.optString("title") }) }
        }.filter { it.first.isNotBlank() }.distinctBy { it.first }.take(5)
        for ((gid, title) in groups) {
            val caa = runCatching { json("https://coverartarchive.org/release-group/$gid") }.getOrNull() ?: continue
            val front = caa.optJSONArray("images").objects().firstOrNull { it.optBoolean("front") } ?: continue
            val url = front.optJSONObject("thumbnails")?.let { t -> t.optString("large").ifBlank { t.optString("500") } }
                ?.ifBlank { null } ?: front.optString("image").ifBlank { null } ?: continue
            f.covers += Cover(url.replace("http://", "https://"), title)
        }
        return f
    }

    // ── Wikidata and Wikipedia ─────────────────────────────────────────────

    private fun wikiLangs(): List<String> =
        (listOf(Locale.getDefault().language) + listOf("es", "en", "ja")).filter { it.isNotBlank() }.distinct()

    private fun wikipedia(wikidataId: String?, titles: List<String>, artist: String): Found? {
        val f = Found()
        var page: Pair<String, String>? = null // lang to title
        if (wikidataId != null) {
            val e = json(
                "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$wikidataId&props=sitelinks|claims&format=json",
            )?.optJSONObject("entities")?.optJSONObject(wikidataId)
            if (e != null) {
                val links = e.optJSONObject("sitelinks")
                page = wikiLangs().firstNotNullOfOrNull { l ->
                    links?.optJSONObject("${l}wiki")?.optString("title")?.takeIf { it.isNotBlank() }?.let { l to it }
                }
                wikidataFacts(e, f)
                f.links += Link("Wikidata", "https://www.wikidata.org/wiki/$wikidataId")
            }
        }
        if (page == null) {
            // Searched: an article whose title is the song's, that names its artist.
            loop@ for (lang in listOf("en", wikiLangs().first()).distinct()) {
                for (t in titles.take(2)) {
                    val s = json(
                        "https://$lang.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=4&srsearch=" +
                            enc("$t $artist"),
                    )?.optJSONObject("query")?.optJSONArray("search").objects()
                    val hit = s.firstOrNull { r ->
                        val title = r.optString("title")
                        sameTitle(listOf(t), title.replace(Regex("""\s*\(.*\)"""), "")) &&
                            norm(r.optString("snippet")).contains(norm(artist).take(12))
                    }
                    if (hit != null) {
                        page = lang to hit.optString("title")
                        break@loop
                    }
                }
            }
        }
        val (lang, title) = page ?: return f.takeIf { it.credits.isNotEmpty() || it.genres.isNotEmpty() }
        val summary = json("https://$lang.wikipedia.org/api/rest_v1/page/summary/${enc(title.replace(' ', '_')).replace("+", "%20")}")
        val text = summary?.optString("extract")?.takeIf { it.length > 40 }
        val url = summary?.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page")
            ?: "https://$lang.wikipedia.org/wiki/${enc(title.replace(' ', '_'))}"
        if (text != null) f.facts += Fact(clip(text), "Wikipedia", url)
        f.links += Link("Wikipedia", url)
        f.sources += "Wikipedia"
        return f
    }

    /** The Wikidata item of the recording with this ISRC (property P1243). */
    private fun wikidataByIsrc(isrc: String): String? {
        val q = "SELECT ?i WHERE { ?i wdt:P1243 \"$isrc\" } LIMIT 1"
        val r = json(
            "https://query.wikidata.org/sparql?format=json&query=${enc(q)}",
            mapOf("Accept" to "application/sparql-results+json"),
        ) ?: return null
        return r.optJSONObject("results")?.optJSONArray("bindings").objects().firstOrNull()
            ?.optJSONObject("i")?.optString("value")?.substringAfterLast('/')?.takeIf { it.startsWith("Q") }
    }

    // ── TheAudioDB (its free public key) ───────────────────────────────────

    private fun audioDb(titles: List<String>, artist: String): Found? {
        var t: JSONObject? = null
        for (title in titles.take(2)) {
            t = json("https://www.theaudiodb.com/api/v1/json/123/searchtrack.php?s=${enc(artist)}&t=${enc(title)}")
                ?.optJSONArray("track").objects()
                .firstOrNull { sameTitle(titles, it.optString("strTrack")) && sameArtist(artist, it.optString("strArtist")) }
            if (t != null) break
        }
        val track = t ?: return null
        val f = Found()
        f.sources += "TheAudioDB"
        fun str(k: String) = track.optString(k).takeIf { it.isNotBlank() && it != "null" }
        val lang = Locale.getDefault().language.uppercase()
        (str("strDescription$lang") ?: str("strDescriptionEN"))?.takeIf { it.length > 40 }
            ?.let { f.facts += Fact(clip(it), "TheAudioDB", null) }
        listOfNotNull(str("strGenre"), str("strStyle")).forEach { f.genres += it }
        str("strMood")?.let { f.stats["mood"] = it }
        str("strTheme")?.let { f.stats["theme"] = it }
        str("strAlbum")?.let { f.album = it }
        str("strTrackThumb")?.let { f.covers += Cover(it, "TheAudioDB") }
        str("strMusicVid")?.let { f.links += Link("Videoclip", it) }
        str("idTrack")?.let { f.links += Link("TheAudioDB", "https://www.theaudiodb.com/track/$it") }
        return f
    }

    /** Composer, lyricist, producer, label, genre and date as Wikidata states them. */
    private fun wikidataFacts(e: JSONObject, f: Found) {
        val claims = e.optJSONObject("claims") ?: return
        fun ids(p: String) = claims.optJSONArray(p).objects().mapNotNull {
            it.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("id")?.takeIf { s -> s.isNotBlank() }
        }
        val wanted = mapOf("P86" to "composer", "P676" to "lyricist", "P162" to "producer", "P175" to "performer", "P264" to "label", "P136" to "genre")
        val byProp = wanted.keys.associateWith { ids(it).take(6) }
        val all = byProp.values.flatten().distinct()
        if (all.isNotEmpty()) {
            val langs = (wikiLangs() + "en").distinct().joinToString("|")
            val labels = json(
                "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${all.take(45).joinToString("|")}&props=labels&languages=$langs&format=json",
            )?.optJSONObject("entities")
            fun label(id: String): String? {
                val l = labels?.optJSONObject(id)?.optJSONObject("labels") ?: return null
                return (wikiLangs() + "en").firstNotNullOfOrNull { l.optJSONObject(it)?.optString("value")?.ifBlank { null } }
            }
            for ((p, list) in byProp) for (id in list) {
                val name = label(id) ?: continue
                when (wanted[p]) {
                    "label" -> f.label = f.label ?: name
                    "genre" -> f.genres += name
                    else -> f.credits += Credit(wanted[p]!!, name, "Wikidata")
                }
            }
        }
        claims.optJSONArray("P577").objects().firstOrNull()?.optJSONObject("mainsnak")?.optJSONObject("datavalue")
            ?.optJSONObject("value")?.optString("time")?.trimStart('+')?.take(10)?.replace("-00", "")?.let { f.releaseDate = it }
    }

    // ── Genius ─────────────────────────────────────────────────────────────

    private fun genius(titles: List<String>, artist: String): Found? {
        var songId: Long? = null
        for (t in titles.take(2)) {
            val body = json(
                "https://genius.com/api/search/multi?q=${enc("$artist $t")}",
                mapOf("User-Agent" to "BitChord"),
            ) ?: continue
            val hits = body.optJSONObject("response")?.optJSONArray("sections").objects()
                .firstOrNull { it.optString("type") == "song" }?.optJSONArray("hits").objects()
                .mapNotNull { it.optJSONObject("result") }
            val best = hits.firstOrNull { r ->
                val path = r.optString("path")
                !path.contains("translation", true) && !path.contains("romanized", true) &&
                    sameTitle(titles, r.optString("title")) && sameArtist(artist, r.optString("artist_names"))
            }
            songId = best?.optLong("id")?.takeIf { it > 0 }
            if (songId != null) break
        }
        val id = songId ?: return null
        val s = json("https://genius.com/api/songs/$id?text_format=plain", mapOf("User-Agent" to "BitChord"))
            ?.optJSONObject("response")?.optJSONObject("song") ?: return null
        val f = Found()
        f.sources += "Genius"
        s.optString("url").ifBlank { null }?.let { f.links += Link("Genius", it) }
        s.optJSONObject("description")?.optString("plain")?.trim()?.takeIf { it.length > 20 && it != "?" }
            ?.let { f.facts += Fact(clip(it), "Genius", s.optString("url").ifBlank { null }) }
        s.optString("release_date").ifBlank { null }?.let { f.releaseDate = it }
        s.optJSONObject("album")?.optString("name")?.ifBlank { null }?.let { f.album = it }
        s.optString("language").ifBlank { null }?.let { f.language = it }
        s.optString("recording_location").ifBlank { null }?.let { f.stats["recorded_at"] = it }
        s.optString("song_art_image_url").ifBlank { null }?.takeIf { !it.contains("default_cover") }
            ?.let { f.covers += Cover(it, "Genius") }
        s.optJSONObject("stats")?.optLong("pageviews")?.takeIf { it > 0 }?.let { f.stats["genius_views"] = it.toString() }
        fun person(role: String, a: JSONObject): Credit? {
            val name = a.optString("name").ifBlank { return null }
            val image = a.optString("image_url").takeIf { it.startsWith("http") && !it.contains("default_avatar") }
            return Credit(role, name, "Genius", geniusId = a.optLong("id").takeIf { it > 0 }, image = image)
        }
        fun people(key: String, role: String) = s.optJSONArray(key).objects().mapNotNull { person(role, it) }.forEach { f.credits += it }
        s.optJSONObject("primary_artist")?.let { a -> person("performer", a)?.let { f.credits += it } }
        people("writer_artists", "writer")
        people("producer_artists", "producer")
        people("featured_artists", "featured")
        for (p in s.optJSONArray("custom_performances").objects()) {
            val role = p.optString("label")
            if (role.isBlank()) continue
            p.optJSONArray("artists").objects().mapNotNull { person(role, it) }.forEach { f.credits += it }
        }
        for (r in s.optJSONArray("song_relationships").objects()) {
            val kind = r.optString("relationship_type").ifBlank { r.optString("type") }
            if (kind.isBlank()) continue
            for (o in r.optJSONArray("songs").objects().take(12)) {
                val t = o.optString("title")
                if (t.isBlank()) continue
                val year = o.optJSONObject("release_date_components")?.optInt("year")?.takeIf { it > 0 }?.toString()
                    ?: o.optString("release_date_for_display").takeLast(4).takeIf { it.all(Char::isDigit) }
                f.relations += Relation(
                    kind = kind,
                    title = t,
                    artist = o.optString("artist_names").ifBlank { o.optJSONObject("primary_artist")?.optString("name").orEmpty() },
                    year = year,
                    art = o.optString("song_art_image_thumbnail_url").ifBlank { null },
                    url = o.optString("url").ifBlank { null },
                )
            }
        }
        return f
    }

    // ── Last.fm ────────────────────────────────────────────────────────────

    private fun lastFm(title: String, artist: String): Found? {
        val key = DnaKeys.lastFm.value.ifBlank { BuildConfig.LASTFM_API_KEY }.ifBlank { return null }
        val lang = Locale.getDefault().language.ifBlank { "en" }
        val t = json(
            "https://ws.audioscrobbler.com/2.0/?method=track.getInfo&format=json&autocorrect=1&lang=$lang" +
                "&api_key=${enc(key)}&artist=${enc(artist)}&track=${enc(title)}",
        )?.optJSONObject("track") ?: return null
        val f = Found()
        f.sources += "Last.fm"
        t.optString("url").ifBlank { null }?.let { f.links += Link("Last.fm", it) }
        t.optString("listeners").takeIf { it.isNotBlank() && it != "0" }?.let { f.stats["lastfm_listeners"] = it }
        t.optString("playcount").takeIf { it.isNotBlank() && it != "0" }?.let { f.stats["lastfm_plays"] = it }
        t.optJSONObject("toptags")?.optJSONArray("tag").objects().take(5).forEach { f.genres += it.optString("name") }
        t.optJSONObject("album")?.let { a ->
            f.album = a.optString("title").ifBlank { null }
            a.optJSONArray("image").objects().lastOrNull()?.optString("#text")?.ifBlank { null }
                ?.let { f.covers += Cover(it, "Last.fm") }
        }
        t.optJSONObject("wiki")?.let { w ->
            val text = w.optString("content").ifBlank { w.optString("summary") }
                .replace(Regex("""<a href=.*?</a>\.?"""), "")
                .replace(Regex("""User-contributed text is available.*$""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("<[^>]+>"), "").trim()
            if (text.length > 40) f.facts += Fact(clip(text), "Last.fm", t.optString("url").ifBlank { null })
        }
        return f
    }

    // ── iTunes and Deezer ──────────────────────────────────────────────────

    private fun iTunes(title: String, artist: String, seconds: Int?): Found? {
        val arr = json("https://itunes.apple.com/search?entity=song&limit=8&term=${enc("$title $artist")}")
            ?.optJSONArray("results").objects()
        val r = arr.firstOrNull {
            sameTitle(listOf(title), it.optString("trackName")) && sameArtist(artist, it.optString("artistName")) &&
                sameLength(seconds, it.optLong("trackTimeMillis").takeIf { ms -> ms > 0 }?.let { ms -> (ms / 1000).toInt() })
        }
            ?: return null
        val f = Found()
        f.sources += "iTunes"
        r.optString("primaryGenreName").ifBlank { null }?.let { f.genres += it }
        r.optString("releaseDate").take(10).ifBlank { null }?.let { f.releaseDate = it }
        r.optString("collectionName").ifBlank { null }?.let { f.album = it }
        r.optString("artworkUrl100").ifBlank { null }?.let {
            f.covers += Cover(it.replace("100x100bb", "1200x1200bb"), "Apple Music")
        }
        r.optString("trackViewUrl").ifBlank { null }?.let { f.links += Link("Apple Music", it.substringBefore("&uo=")) }
        return f
    }

    private fun deezer(titles: List<String>, artist: String, seconds: Int?): Found? {
        var hit: JSONObject? = null
        for (title in titles.take(2)) {
            val arr = json("https://api.deezer.com/search?limit=10&q=${enc("artist:\"$artist\" track:\"$title\"")}")
                ?.optJSONArray("data").objects()
            hit = arr.firstOrNull {
                sameTitle(titles, it.optString("title")) && sameArtist(artist, it.optJSONObject("artist")?.optString("name").orEmpty()) &&
                    sameLength(seconds, it.optInt("duration"))
            }
            if (hit != null) break
        }
        if (hit == null) return null
        val t = json("https://api.deezer.com/track/${hit.optLong("id")}") ?: hit
        val f = Found()
        f.sources += "Deezer"
        t.optDouble("bpm").takeIf { !it.isNaN() && it > 0 }?.let { f.bpm = String.format(Locale.ROOT, "%.0f", it) }
        t.optString("release_date").ifBlank { null }?.takeIf { it != "0000-00-00" }?.let { f.releaseDate = it }
        t.optString("isrc").ifBlank { null }?.let { f.isrc = it }
        t.optJSONObject("album")?.let { a ->
            a.optString("title").ifBlank { null }?.let { f.album = it }
            a.optString("cover_xl").ifBlank { null }?.let { f.covers += Cover(it, "Deezer") }
        }
        t.optJSONArray("contributors").objects().forEach { c ->
            val name = c.optString("name").ifBlank { return@forEach }
            f.credits += Credit(
                if (c.optString("role") == "Featured") "featured" else "performer", name, "Deezer",
                image = c.optString("picture_xl").ifBlank { c.optString("picture_big") }.takeIf { it.startsWith("http") && !it.contains("/artist//") },
            )
        }
        t.optString("link").ifBlank { null }?.let { f.links += Link("Deezer", it) }
        return f
    }

    // ── Discogs (your own free token) ──────────────────────────────────────

    private fun discogs(title: String, artist: String): Found? {
        val token = DnaKeys.discogs.value.ifBlank { return null }
        val auth = mapOf("Authorization" to "Discogs token=$token")
        val results = json(
            "https://api.discogs.com/database/search?type=release&per_page=8&track=${enc(title)}&artist=${enc(artist)}",
            auth,
        )?.optJSONArray("results").objects()
        // The earliest release with the song: the original, not a compilation.
        val hit = results.filter { it.optString("year").isNotBlank() }.minByOrNull { it.optString("year") } ?: results.firstOrNull()
            ?: return null
        val r = json("https://api.discogs.com/releases/${hit.optLong("id")}", auth) ?: return null
        val f = Found()
        f.sources += "Discogs"
        r.optString("uri").ifBlank { null }?.let { f.links += Link("Discogs", it) }
        r.optJSONArray("labels").objects().firstOrNull()?.let { l ->
            val name = l.optString("name").replace(Regex("""\s*\(\d+\)$"""), "")
            val cat = l.optString("catno").takeIf { it.isNotBlank() && !it.equals("none", true) }
            f.label = listOfNotNull(name.ifBlank { null }, cat).joinToString(" · ").ifBlank { null }
        }
        r.optString("released").ifBlank { null }?.let { f.releaseDate = it }
        (r.optJSONArray("genres").let { a -> (0 until (a?.length() ?: 0)).map { a!!.optString(it) } } +
            r.optJSONArray("styles").let { a -> (0 until (a?.length() ?: 0)).map { a!!.optString(it) } })
            .forEach { f.genres += it }
        fun extra(a: JSONArray?) = a.objects().forEach { x ->
            val name = x.optString("anv").ifBlank { x.optString("name") }.replace(Regex("""\s*\(\d+\)$"""), "").ifBlank { return@forEach }
            val role = x.optString("role").ifBlank { return@forEach }
            f.credits += Credit(role, name, "Discogs", discogsId = x.optLong("id").takeIf { it > 0 })
        }
        // The song's own credits, then the release's that name no tracks (they cover them all).
        r.optJSONArray("tracklist").objects().firstOrNull { sameTitle(listOf(title), it.optString("title")) }
            ?.let { extra(it.optJSONArray("extraartists")) }
        extra(JSONArray(r.optJSONArray("extraartists").objects().filter { it.optString("tracks").isBlank() }))
        r.optJSONArray("images").objects().take(3).forEach { im ->
            im.optString("uri").ifBlank { null }?.let { f.covers += Cover(it, "Discogs · " + (r.optString("title").ifBlank { "" })) }
        }
        r.optString("notes").trim().takeIf { it.length > 40 }?.let { f.facts += Fact(clip(it, 800), "Discogs", r.optString("uri").ifBlank { null }) }
        return f
    }

    // ── AnimeThemes ────────────────────────────────────────────────────────

    private suspend fun animeThemes(song: Song): Found? {
        val video = com.music.bitchord.data.video.AnimeThemes
        val base = bareTitle(song.title)
        val themes = (video.search(base) + titles(song.title).drop(1).flatMap { video.search(it) })
            .filter { video.sameTitle(song, it) && video.sameArtist(song, it) }
        if (themes.isEmpty()) return null
        val f = Found()
        f.sources += "AnimeThemes"
        themes.map { it.label }.distinct().forEach { f.uses += it }
        return f
    }

    // ── JSON ───────────────────────────────────────────────────────────────

    private fun toJson(d: Dna): JSONObject = JSONObject().apply {
        put("k", d.key); put("t", d.title); put("a", d.artist); put("at", d.fetchedAt); put("v", d.version)
        d.album?.let { put("al", it) }; d.releaseDate?.let { put("rd", it) }; d.label?.let { put("lb", it) }; d.catalog?.let { put("cat", it) }
        d.isrc?.let { put("isrc", it) }; d.bpm?.let { put("bpm", it) }; d.language?.let { put("lang", it) }
        put("g", JSONArray(d.genres))
        put("c", JSONArray(d.credits.map { c ->
            JSONObject().put("r", c.role).put("n", c.name).put("s", c.source).apply {
                c.mbid?.let { put("mb", it) }; c.geniusId?.let { put("gn", it) }; c.discogsId?.let { put("dc", it) }
                c.ytId?.let { put("yt", it) }; c.image?.let { put("im", it) }; c.vgmdbId?.let { put("vg", it) }
            }
        }))
        put("f", JSONArray(d.facts.map { JSONObject().put("x", it.text).put("s", it.source).put("u", it.url ?: "") }))
        put("cv", JSONArray(d.covers.map { JSONObject().put("u", it.url).put("l", it.label) }))
        put("rel", JSONArray(d.relations.map {
            JSONObject().put("k", it.kind).put("t", it.title).put("a", it.artist).put("y", it.year ?: "").put("i", it.art ?: "").put("u", it.url ?: "")
        }))
        put("use", JSONArray(d.uses))
        put("st", JSONObject(d.stats as Map<*, *>))
        put("ln", JSONArray(d.links.map { JSONObject().put("l", it.label).put("u", it.url) }))
        put("src", JSONArray(d.sources))
        put("fail", JSONArray(d.failed))
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

    private fun fromJson(o: JSONObject): Dna = Dna(
        key = o.optString("k"),
        title = o.optString("t"),
        artist = o.optString("a"),
        fetchedAt = o.optLong("at"),
        version = o.optInt("v"),
        album = o.optString("al").ifBlank { null },
        releaseDate = o.optString("rd").ifBlank { null },
        label = o.optString("lb").ifBlank { null },
        catalog = o.optString("cat").ifBlank { null },
        isrc = o.optString("isrc").ifBlank { null },
        bpm = o.optString("bpm").ifBlank { null },
        language = o.optString("lang").ifBlank { null },
        genres = o.optJSONArray("g").strings(),
        credits = o.optJSONArray("c").objects().map {
            Credit(
                it.optString("r"), it.optString("n"), it.optString("s"),
                mbid = it.optString("mb").ifBlank { null },
                geniusId = it.optLong("gn").takeIf { v -> v > 0 },
                discogsId = it.optLong("dc").takeIf { v -> v > 0 },
                ytId = it.optString("yt").ifBlank { null },
                image = it.optString("im").ifBlank { null },
                vgmdbId = it.optString("vg").ifBlank { null },
            )
        },
        facts = o.optJSONArray("f").objects().map { Fact(it.optString("x"), it.optString("s"), it.optString("u").ifBlank { null }) },
        covers = o.optJSONArray("cv").objects().map { Cover(it.optString("u"), it.optString("l")) },
        relations = o.optJSONArray("rel").objects().map {
            Relation(
                it.optString("k"), it.optString("t"), it.optString("a"),
                it.optString("y").ifBlank { null }, it.optString("i").ifBlank { null }, it.optString("u").ifBlank { null },
            )
        },
        uses = o.optJSONArray("use").strings(),
        stats = o.optJSONObject("st")?.let { s -> s.keys().asSequence().associateWith { s.optString(it) } }.orEmpty(),
        links = o.optJSONArray("ln").objects().map { Link(it.optString("l"), it.optString("u")) },
        sources = o.optJSONArray("src").strings(),
        failed = o.optJSONArray("fail").strings(),
    )

    /** Whether [song] is a file of yours (pCloud) — used by the page's "File" section. */
    fun isPcloud(song: Song): Boolean = WebDavConfig.isWebDavId(song.videoId)
}

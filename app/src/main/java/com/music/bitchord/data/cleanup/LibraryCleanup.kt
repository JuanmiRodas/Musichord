package com.music.bitchord.data.cleanup

import android.content.Context
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.covers.CoverWriter
import com.music.bitchord.data.covers.CoverTags
import com.music.bitchord.data.edits.TagWriter
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.OwnFileMatcher
import com.music.bitchord.data.webdav.LibraryFolders
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.data.webdav.WebDavRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * BitChord Audio: the pCloud library cleanup — one card per album folder, swiped.
 *
 * For each folder the assistant proposes the release it is (artist, album, album artist,
 * year, genre, every track's title and number, the cover), from YouTube Music's albums
 * and — with a Gemini key — from reading the folder, its cover and the web. Swiped
 * right, it is written into the files at once: tags, cover, lyrics (word by word when
 * there are), and lossless files made into AAC 320 kbps, the originals moved to an
 * archive folder in pCloud rather than deleted. Swiped left, another proposal, taking
 * into account what you wrote on the card.
 */
object LibraryCleanup {

    private const val TAG = "BitChord"
    const val FILE = "library_review.json"
    const val ARCHIVE = "_Archivo BitChord"
    private val LOSSLESS = setOf("flac", "wav", "aif", "aiff", "alac", "ape", "wv")

    data class FileInfo(
        val id: String,
        val name: String,
        val title: String,
        val artist: String,
        val album: String?,
        val track: Int?,
        val seconds: Int?,
        val ext: String,
        val quality: String?,
        val thumb: String?,
    )

    data class Group(val key: String, val path: String, val files: List<FileInfo>, val issues: List<String>) {
        val cover get() = files.firstNotNullOfOrNull { it.thumb }
    }

    /** One file's fix; [album], [year] and [cover] set when it is not the folder's release (a folder of loose songs). */
    data class TrackFix(
        val id: String,
        val title: String,
        val artist: String,
        val track: Int?,
        val disc: Int?,
        val album: String? = null,
        val year: String? = null,
        val cover: String? = null,
    )

    /** Not a release to propose: karaoke, off vocal, "originally performed by", tribute or cover compilations. */
    private val JUNK = Regex("""(?i)karaoke|カラオケ|off[\s-]?vocal|オフボーカル|originally performed|in the style of|made famous|instrumental version|tribute|backing track|cover version|piano version|lullaby|8-?bit|music box""")
    private fun junk(vararg s: String?) = s.any { it != null && JUNK.containsMatchIn(it) }

    /** A folder that holds loose songs, not one release. */
    private val LOOSE = Regex("""(?i)^(otros|others|misc\.?|miscellaneous|varios|singles?|sueltas?|canciones|songs|unsorted|random|extras?|downloads?|descargas)$""")

    data class Proposal(
        val artist: String,
        val albumArtist: String,
        val album: String,
        val year: String?,
        val genre: String?,
        val coverUrl: String?,
        val ytAlbumId: String?,
        val tracks: List<TrackFix>,
        val reason: String,
        val source: String,
        /** How sure the assistant is (0–1). */
        val confidence: Double = 0.5,
        /** What it would need to know, when it is lost. */
        val question: String? = null,
    )

    data class State(
        val loading: Boolean = false,
        val groups: List<Group> = emptyList(),
        val current: Group? = null,
        val proposal: Proposal? = null,
        val left: Int = 0,
        val applying: String? = null,
        val applied: Int = 0,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var app: Context? = null
    private var store: File? = null
    private val done = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val rejected = HashMap<String, MutableList<Proposal>>()
    private val queue = Channel<Pair<Group, Proposal>>(Channel.UNLIMITED)
    @Volatile private var worker: Job? = null
    @Volatile private var proposing: Job? = null

    fun init(context: Context) {
        app = context.applicationContext
        store = File(context.filesDir, FILE)
        Gemini.init(context)
        runCatching {
            store?.takeIf { it.isFile }?.readText()?.let { t -> JSONObject(t).let { o -> o.keys().forEach { done[it] = o.getString(it) } } }
        }
    }

    private fun save() {
        val o = JSONObject()
        done.forEach { (k, v) -> o.put(k, v) }
        runCatching { store?.writeText(o.toString()) }
        app?.let { com.music.bitchord.data.sync.SettingsSync.changed(it) }
    }

    /** BitChord Audio: for the assistant — folders it decided ("auto"), and the review queue it left. */
    fun isDone(key: String) = done.containsKey(key)
    fun markDone(key: String, how: String) { done[key] = how; save() }
    fun reopen(key: String) { done.remove(key); save() }
    internal fun groups(): List<Group> = buildGroups()
    internal suspend fun proposeFor(g: Group, hint: String?): Proposal? = proposalFor(g, hint)
    internal suspend fun write(g: Group, p: Proposal, convert: Boolean) = apply(g, p, convert)

    /** Proposals the assistant left for you, by folder (shown first, as it left them). */
    val waiting = java.util.concurrent.ConcurrentHashMap<String, Proposal>()

    /** The proposal on the card, as you edited it. */
    fun edit(p: Proposal) {
        _state.value = _state.value.copy(proposal = p)
    }

    private fun dirOf(id: String) = WebDavConfig.fileUrlOf(id)?.substringBeforeLast('/')
    private fun ext(id: String) = WebDavConfig.fileUrlOf(id)?.substringAfterLast('.')?.substringBefore('?')?.lowercase().orEmpty()
    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    private fun rootOf(url: String) = LibraryFolders.musicFolders().map { WebDavConfig.normalizeUrl(it.url).trimEnd('/') }
        .firstOrNull { url.startsWith(it) }

    private fun seconds(s: Song): Int? = s.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
        ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }

    /** Every album folder of your pCloud library not reviewed yet, the most in need first. */
    private fun buildGroups(): List<Group> {
        val library = WebDavRepository.library().filter { WebDavConfig.isWebDavId(it.videoId) }
        return library.groupBy { dirOf(it.videoId).orEmpty() }
            .filterKeys { it.isNotEmpty() && !done.containsKey(it) && !it.contains("/" + URLEncoder.encode(ARCHIVE, "UTF-8").replace("+", "%20")) && !decode(it).contains(ARCHIVE) }
            .map { (dir, songs) ->
                val files = songs.sortedWith(compareBy({ it.trackNumber ?: 999 }, { it.title })).map { s ->
                    FileInfo(
                        id = s.videoId,
                        name = decode(WebDavConfig.fileUrlOf(s.videoId).orEmpty().substringAfterLast('/')),
                        title = s.title, artist = s.artist, album = s.albumName, track = s.trackNumber,
                        seconds = seconds(s), ext = ext(s.videoId), quality = s.sourceQuality, thumb = s.thumbnailUrl,
                    )
                }
                val issues = buildList {
                    val unnamed = files.mapNotNull { WebDavRepository.unnamedTags[it.id] }
                    if (unnamed.any { "artist" in it }) add("artist")
                    if (unnamed.any { "album" in it }) add("album")
                    if (files.none { it.thumb != null }) add("cover")
                    if (files.any { it.track == null }) add("track")
                    if (files.any { it.ext in LOSSLESS }) add("lossless")
                }
                val root = rootOf(dir)
                Group(dir, decode(if (root != null) dir.removePrefix(root) else dir).trim('/'), files, issues)
            }
            // Not the folders you told the assistant to leave alone.
            .filter { g -> !Assistant.excluded(g.path) }
            // What the assistant left for you first, then the most in need.
            .sortedWith(compareByDescending<Group> { waiting.containsKey(it.key) }.thenByDescending { it.issues.size }.thenBy { it.path })
    }

    /** Opens the review: the folders, and a proposal for the first. */
    fun open() {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, message = null)
        scope.launch {
            val groups = buildGroups()
            _state.value = _state.value.copy(groups = groups, left = groups.size, loading = false)
            next()
        }
    }

    private fun next() {
        val g = _state.value.groups.firstOrNull { !done.containsKey(it.key) }
        _state.value = _state.value.copy(current = g, proposal = null, left = _state.value.groups.count { !done.containsKey(it.key) })
        if (g == null) return
        // The assistant's own proposal when it left one.
        val ready = waiting[g.key]
        if (ready != null) _state.value = _state.value.copy(proposal = ready, loading = false)
        else propose(g, Assistant.hintFor(g.path))
    }

    private fun propose(g: Group, hint: String?) {
        proposing?.cancel()
        _state.value = _state.value.copy(loading = true)
        proposing = scope.launch {
            val p = runCatching { proposalFor(g, hint) }
                .onFailure { TrackLog.w(TAG, "cleanup: proposal for ${g.path} failed: ${it.message}") }
                .getOrNull()
            if (_state.value.current?.key == g.key) {
                _state.value = _state.value.copy(proposal = p, loading = false, message = if (p == null) "nothing" else null)
            }
        }
    }

    /** Swiped right: written into the files now (in the background). */
    fun accept() {
        val g = _state.value.current ?: return
        val p = _state.value.proposal ?: return
        done[g.key] = "applied"
        waiting.remove(g.key)
        Assistant.forget(g.key)
        save()
        queue.trySend(g to p)
        startWorker()
        next()
    }

    /** Swiped left: another proposal, with what you wrote. */
    fun reject(hint: String?) {
        val g = _state.value.current ?: return
        _state.value.proposal?.let { rejected.getOrPut(g.key) { ArrayList() } += it }
        waiting.remove(g.key)
        // What you wrote is remembered for the folder (the assistant uses it too).
        hint?.takeIf { it.isNotBlank() }?.let { Assistant.addHint(g.path, it) }
        propose(g, Assistant.hintFor(g.path))
    }

    /** Left as it is (and not shown again). */
    fun keep() {
        val g = _state.value.current ?: return
        done[g.key] = "kept"
        waiting.remove(g.key)
        Assistant.forget(g.key)
        save()
        next()
    }

    // ── Proposals ──

    private data class Candidate(val browseId: String, val title: String, val subtitle: String, val thumb: String?, val songs: List<Song>)

    private suspend fun candidates(g: Group, hint: String?): List<Candidate> {
        val folder = g.path.split('/').filter { it.isNotBlank() }
        val artistGuess = g.files.map { it.artist }.filter { WebDavRepository.named(it) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: folder.firstOrNull().orEmpty()
        val albumGuess = g.files.mapNotNull { it.album }.filter { WebDavRepository.named(it) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: folder.lastOrNull().orEmpty()
        val queries = listOfNotNull(
            hint?.let { "$it $albumGuess" },
            "$artistGuess $albumGuess",
            albumGuess,
            g.files.firstOrNull()?.let { "${it.title} $artistGuess" },
        ).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val found = LinkedHashMap<String, SearchResult.Browse>()
        for (q in queries) {
            val ownJunk = g.files.any { junk(it.title, it.album) }
            YtMusicRepository.search(q, SearchFilter.ALBUMS).getOrNull().orEmpty()
                .filterIsInstance<SearchResult.Browse>()
                .filter { it.item.type == BrowseType.ALBUM }
                // Never a karaoke or cover release, unless the files themselves are one.
                .filter { ownJunk || !junk(it.item.title, it.item.subtitle) }
                // The folder's artist when it names one.
                .filter { artistGuess.isBlank() || WebDavRepository.named(artistGuess).not() || OwnFileMatcher.sameArtist(artistGuess, it.item.subtitle) }
                .forEach { found.putIfAbsent(it.item.browseId, it) }
            if (found.size >= 6) break
        }
        return found.values.take(5).map { b ->
            val songs = YtMusicRepository.browseSongs(b.item.browseId).getOrNull()?.songs.orEmpty()
            Candidate(b.item.browseId, b.item.title, b.item.subtitle, b.item.thumbnailUrl, songs)
        }
    }

    /** How well a release's tracks fit the folder's files: titles and lengths. */
    private fun fit(g: Group, c: Candidate): Double {
        if (c.songs.isEmpty()) return 0.0
        var score = 0.0
        for (f in g.files) {
            val best = c.songs.maxOfOrNull { s ->
                val title = if (OwnFileMatcher.allKeys(s.title).any { it in OwnFileMatcher.allKeys(f.title) }) 1.0 else 0.0
                val len = if (f.seconds != null && seconds(s) != null && kotlin.math.abs(f.seconds - seconds(s)!!) <= 3) 0.6 else 0.0
                title + len
            } ?: 0.0
            score += best
        }
        val sizePenalty = kotlin.math.abs(c.songs.size - g.files.size) * 0.15
        return score / g.files.size - sizePenalty
    }

    private fun yearOf(subtitle: String) = Regex("""\b(19|20)\d{2}\b""").find(subtitle)?.value

    private fun bigCover(url: String?) = url?.replace(Regex("=w\\d+-h\\d+.*$"), "=w1200-h1200")
        ?.replace(Regex("""/\d+x\d+bb\."""), "/1200x1200bb.")

    /** The files matched to [c]'s tracks: by title, else by length, else by order. */
    private fun mapTracks(g: Group, c: Candidate?, artist: String): List<TrackFix> = g.files.mapIndexed { i, f ->
        val s = c?.songs?.firstOrNull { s -> OwnFileMatcher.allKeys(s.title).any { it in OwnFileMatcher.allKeys(f.title) } }
            ?: c?.songs?.firstOrNull { s -> f.seconds != null && seconds(s)?.let { kotlin.math.abs(it - f.seconds) <= 2 } == true }
        val n = s?.let { c!!.songs.indexOf(it) + 1 } ?: f.track ?: (i + 1)
        TrackFix(f.id, s?.title ?: f.title, s?.artist?.takeIf { WebDavRepository.named(it) } ?: artist, n, null)
    }

    private suspend fun proposalFor(g: Group, hint: String?): Proposal? {
        val cands = candidates(g, hint)
        val before = rejected[g.key].orEmpty()
        // Gemini searching the web can take a while; past 75 s, YouTube Music's answer instead.
        if (Gemini.ready) kotlinx.coroutines.withTimeoutOrNull(75_000) { ai(g, hint, cands, before) }?.takeIf { p -> p.album.isNotBlank() || p.tracks.any { it.album != null } }?.let { return it }
        // Without the assistant: the release whose tracks fit best, one not turned down.
        val ranked = cands.sortedByDescending { fit(g, it) }
            .filter { c -> before.none { it.ytAlbumId == c.browseId } }
        val c = ranked.firstOrNull()?.takeIf { fit(g, it) >= 0.45 && !LOOSE.matches(g.path.substringAfterLast('/')) }
            ?: return perTrack(g, hint, before)
        val parts = c.subtitle.split("•", "·").map { it.trim() }
        val artist = parts.getOrNull(1)?.takeIf { it.isNotBlank() && yearOf(it) == null } ?: g.files.first().artist
        return Proposal(
            artist = artist, albumArtist = artist, album = c.title, year = yearOf(c.subtitle), genre = null,
            coverUrl = bigCover(c.thumb), ytAlbumId = c.browseId, tracks = mapTracks(g, c, artist),
            reason = "YouTube Music", source = "YouTube Music",
            // Every file matched by title and length on one release: as sure as it gets without the assistant.
            confidence = if (fit(g, c) >= 1.4) 0.8 else 0.5,
        )
    }

    /** A title made searchable: "Mirai (Corrector Yui ending 1)" → "Mirai". */
    private fun cleanTitle(t: String) = t
        .replace(Regex("""[(\[（【][^)\]）】]*[)\]）】]"""), " ")
        .replace(Regex("""(?i)\b(opening|ending|op|ed|insert song|theme|tv\s*size|full\s*version|version)\b\s*\d*"""), " ")
        .replace(Regex("""^\s*\d{1,3}[\s.\-_]+"""), " ")
        .replace(Regex("""\s+"""), " ").trim(' ', '-', '_')

    /**
     * Each file found on its own (a folder of loose songs): the song YouTube Music has with
     * that name — by the folder's artist, the same length, not karaoke — and its release.
     */
    private suspend fun perTrack(g: Group, hint: String?, before: List<Proposal>): Proposal? {
        val folder = g.path.split('/').filter { it.isNotBlank() }
        val context = folder.dropLast(if (LOOSE.matches(folder.lastOrNull().orEmpty())) 1 else 0).lastOrNull().orEmpty()
        val artistGuess = g.files.map { it.artist }.filter { WebDavRepository.named(it) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val turnedDown = before.flatMap { p -> p.tracks.map { it.id + "|" + it.title + "|" + it.album } }.toSet()
        val tracks = g.files.map { f ->
            val clean = cleanTitle(f.title)
            val queries = listOfNotNull(
                hint?.let { "$clean $it" },
                artistGuess?.let { "$clean $it" },
                "$clean $context",
                f.title,
            ).distinct()
            var pick: Song? = null
            for (q in queries) {
                val found = YtMusicRepository.search(q, SearchFilter.SONGS).getOrNull().orEmpty()
                    .mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
                    .filter { !junk(it.title, it.albumName) || junk(f.title) }
                    .filter { artistGuess == null || OwnFileMatcher.sameArtist(artistGuess, it.artist) }
                    .filter { s -> OwnFileMatcher.allKeys(s.title).any { k -> k in OwnFileMatcher.allKeys(clean) } || OwnFileMatcher.allKeys(clean).any { it in OwnFileMatcher.allKeys(s.title) } }
                    .filter { (f.id + "|" + it.title + "|" + it.albumName) !in turnedDown }
                pick = found.firstOrNull { s -> f.seconds != null && seconds(s)?.let { kotlin.math.abs(it - f.seconds) <= 3 } == true }
                    ?: found.firstOrNull()
                if (pick != null) break
            }
            if (pick == null) TrackFix(f.id, f.title, artistGuess ?: f.artist, f.track, null)
            else TrackFix(f.id, pick.title, pick.artist, f.track, null, album = pick.albumName, cover = bigCover(pick.thumbnailUrl))
        }
        if (tracks.none { it.album != null }) return null
        val artist = artistGuess ?: tracks.groupingBy { it.artist }.eachCount().maxByOrNull { it.value }!!.key
        return Proposal(
            artist = artist, albumArtist = artist, album = "", year = null, genre = null,
            coverUrl = tracks.firstNotNullOfOrNull { it.cover }, ytAlbumId = null, tracks = tracks,
            reason = "YouTube Music, canción por canción", source = "YouTube Music",
        )
    }

    private suspend fun ai(g: Group, hint: String?, cands: List<Candidate>, before: List<Proposal>): Proposal? {
        val files = JSONArray()
        g.files.forEachIndexed { i, f ->
            files.put(JSONObject().put("i", i).put("file", f.name).put("title", f.title).put("artist", f.artist)
                .put("album", f.album ?: "").put("track", f.track ?: JSONObject.NULL).put("seconds", f.seconds ?: JSONObject.NULL))
        }
        val yt = JSONArray()
        cands.forEach { c ->
            yt.put(JSONObject().put("id", c.browseId).put("title", c.title).put("subtitle", c.subtitle)
                .put("tracks", JSONArray(c.songs.mapIndexed { i, s -> "${i + 1}. ${s.title} — ${s.artist} (${s.durationText ?: "?"})" })))
        }
        val no = JSONArray(before.map { "${it.artist} — ${it.album} (${it.year ?: "?"})" })
        val prompt = """
You are a music librarian fixing the tags of one album folder in a personal music library.
Folder path: ${g.path}
Files (i = index): $files
YouTube Music albums that may be this release: $yt
Proposals the owner already REJECTED (do not repeat them): $no
${if (hint != null) "The owner adds: $hint" else ""}
The attached image (if any) is the cover found with the files.
Work out which official release this folder is (search the web: Discogs, MusicBrainz, VGMdb, Wikipedia, Spotify, Apple Music, YouTube Music). Rules:
- Propose releases exactly as they appear on Spotify / Apple Music (official album name, artist, year, track order).
- NEVER propose karaoke, off vocal, instrumental, "originally performed by", tribute or cover releases unless the files themselves clearly are that.
- Use every clue: folder names are context (an artist, an anime or game name). A title like "Mirai (Corrector Yui ending 1)" or a folder "Corrector Yui" means: find that anime's ending 1, check it matches the title, and take its official single/album, artist and year.
- A folder named like "Otros", "Singles", "Misc" (or files clearly from different releases) holds loose songs: then give each track its own "album", "year" and "coverUrl", and set the top-level "album" to "".
- Use the official romanized/Latin names when the release has them, else the original. Anime and game songs: credit the real performing artist, not the anime.
Answer ONLY a JSON object:
{"artist": "...", "albumArtist": "...", "album": "...", "year": "YYYY or null", "genre": "... or null",
 "youtubeAlbumId": "one of the ids above when it is the same release, else null",
 "coverUrl": "a direct image URL of the official cover (large) or null",
 "reason": "one short sentence, in Spanish, why",
 "confidence": 0.0-1.0 (how sure you are this is right; 0.9+ only when titles, lengths and the release all agree),
 "question": "in Spanish, what you would need the owner to tell you, when you are not sure; else null",
 "tracks": [{"i": 0, "title": "...", "artist": "...", "track": 1, "disc": null, "album": "only for loose songs, else null", "year": null, "coverUrl": null}]}
Every file index must appear once in tracks.
""".trimIndent()
        val cover = g.cover?.let { url -> runCatching { CoverWriter.fetch(url) }.getOrNull() }
        val o = Gemini.ask(prompt, cover?.bytes, cover?.mime) ?: return null
        val artist = o.optString("artist").takeIf { it.isNotBlank() && it != "null" } ?: return null
        val album = o.optString("album").takeIf { it != "null" }.orEmpty()
        val ytId = o.optString("youtubeAlbumId").takeIf { id -> cands.any { it.browseId == id } }
        val c = cands.firstOrNull { it.browseId == ytId }
        val arr = o.optJSONArray("tracks") ?: JSONArray()
        val byIndex = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.associateBy { it.optInt("i", -1) }
        val fallback = mapTracks(g, c, artist)
        val tracks = g.files.mapIndexed { i, f ->
            val t = byIndex[i]
            if (t == null) fallback[i] else TrackFix(
                f.id,
                t.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: fallback[i].title,
                t.optString("artist").takeIf { it.isNotBlank() && it != "null" } ?: artist,
                t.optInt("track", 0).takeIf { it > 0 } ?: fallback[i].track,
                t.optInt("disc", 0).takeIf { it > 0 },
                album = t.optString("album").takeIf { it.isNotBlank() && it != "null" },
                year = t.optString("year").takeIf { Regex("""\d{4}""").matches(it) },
                cover = t.optString("coverUrl").takeIf { it.startsWith("http") },
            )
        }
        val coverUrl = o.optString("coverUrl").takeIf { it.startsWith("http") }
        return Proposal(
            artist = artist,
            albumArtist = o.optString("albumArtist").takeIf { it.isNotBlank() && it != "null" } ?: artist,
            album = album,
            year = o.optString("year").takeIf { Regex("""\d{4}""").matches(it) } ?: c?.let { yearOf(it.subtitle) },
            genre = o.optString("genre").takeIf { it.isNotBlank() && it != "null" },
            coverUrl = bigCover(c?.thumb) ?: coverUrl ?: album.takeIf { it.isNotBlank() }?.let { itunesCover(artist, it) },
            ytAlbumId = ytId,
            tracks = tracks,
            reason = o.optString("reason").takeIf { it.isNotBlank() && it != "null" } ?: "Gemini",
            source = "Gemini",
            confidence = o.optDouble("confidence", 0.5).takeIf { !it.isNaN() } ?: 0.5,
            question = o.optString("question").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    /** The cover Apple's catalogue has for [album], large. */
    private fun itunesCover(artist: String, album: String): String? = runCatching {
        val term = URLEncoder.encode("$artist $album", "UTF-8")
        val req = Request.Builder().url("https://itunes.apple.com/search?entity=album&limit=3&term=$term").build()
        Http.client.newCall(req).execute().use { r ->
            val o = JSONObject(r.body?.string().orEmpty())
            o.optJSONArray("results")?.optJSONObject(0)?.optString("artworkUrl100")?.let { bigCover(it) }
        }
    }.getOrNull()

    // ── Writing ──

    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            for ((g, p) in queue) {
                while (AppSettings.meteredConnection.value != false) {
                    _state.value = _state.value.copy(applying = "wifi")
                    delay(30_000)
                }
                _state.value = _state.value.copy(applying = p.album)
                runCatching { apply(g, p) }.onFailure { TrackLog.w(TAG, "cleanup: writing ${g.path} failed: ${it.message}") }
                _state.value = _state.value.copy(applying = null, applied = _state.value.applied + 1)
            }
        }
    }

    private suspend fun apply(g: Group, p: Proposal, convert: Boolean = true) {
        Assistant.recordUndo(g, p)
        val context = app ?: return
        val covers = HashMap<String, CoverWriter.Image?>()
        suspend fun coverAt(url: String?) = url?.let { u -> covers.getOrPut(u) { runCatching { CoverWriter.fetch(u) }.getOrNull() } }
        val u = AppSettings.webdavUsername.value
        val pw = AppSettings.webdavPassword.value
        var ok = 0
        for (t in p.tracks) {
            val f = g.files.firstOrNull { it.id == t.id } ?: continue
            val text = CoverTags.Text(
                title = t.title, artist = t.artist, album = t.album ?: p.album.takeIf { it.isNotBlank() },
                albumArtist = if (t.album != null) t.artist.split(",", "&", " feat").first().trim() else p.albumArtist,
                year = t.year ?: p.year?.takeIf { t.album == null }, track = t.track?.toString(), genre = p.genre,
            )
            val lyrics = runCatching {
                com.music.bitchord.download.LyricsTag.forTrack(
                    Song(videoId = t.id, title = t.title, artist = t.artist, thumbnailUrl = null,
                        durationText = f.seconds?.let { "%d:%02d".format(it / 60, it % 60) }, albumName = t.album ?: p.album),
                )
            }.getOrNull()
            val change = TagWriter.Change(text = text, discNumber = t.disc?.toString(), lyrics = lyrics?.let { it.enhanced ?: it.plain }, cover = coverAt(t.cover ?: p.coverUrl))
            val result = if (convert && f.ext in LOSSLESS) toAac(context, f, change, u, pw) else when (val o = TagWriter.intoPcloud(context, f.id, change)) {
                is CoverWriter.Outcome.Saved -> "Saved"
                else -> o.toString()
            }
            TrackLog.d(TAG, "cleanup: '${t.title}' (${p.album}) — $result")
            if (result.startsWith("Saved") || result == "converted") ok++
        }
        TrackLog.d(TAG, "cleanup: ${p.artist} — ${p.album}: $ok of ${p.tracks.size} files written")
    }

    /** A lossless file made into AAC 320 with its new tags; the original into the archive folder. */
    private suspend fun toAac(context: Context, f: FileInfo, change: TagWriter.Change, u: String, pw: String): String {
        val url = WebDavConfig.fileUrlOf(f.id) ?: return "no url"
        val dir = File(context.cacheDir, "cleanup").apply { mkdirs() }
        val src = File(dir, "in.${f.ext}")
        val out = File(dir, "out.m4a")
        try {
            WebDavClient.download(url, u, pw, src).getOrElse { return "download failed" }
            if (!Transcoder.toAac320(src, out)) return "transcode failed"
            TagWriter.intoLocalFile(out, change)
            val newUrl = url.substringBeforeLast('.') + ".m4a"
            out.inputStream().use { WebDavClient.putFile(newUrl, it, out.length(), "audio/mp4", u, pw, overwrite = true) }
                .getOrElse { return "upload failed" }
            // The original kept, in the archive, at the same place under it.
            val root = rootOf(url) ?: url.substringBeforeLast('/')
            val rel = url.removePrefix(root).trimStart('/')
            val archiveRoot = root + "/" + URLEncoder.encode(ARCHIVE, "UTF-8").replace("+", "%20")
            var at = archiveRoot
            WebDavClient.ensureCollection(at, u, pw)
            rel.split('/').dropLast(1).forEach { seg -> at += "/$seg"; WebDavClient.ensureCollection(at, u, pw) }
            WebDavClient.move(url, "$archiveRoot/$rel", u, pw).getOrElse { return "converted (original left in place)" }
            return "converted"
        } finally {
            src.delete(); out.delete()
        }
    }
}

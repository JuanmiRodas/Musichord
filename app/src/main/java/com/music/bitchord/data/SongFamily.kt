package com.music.bitchord.data

import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.names.NameSources
import com.music.bitchord.data.names.NameSources.objects
import com.music.bitchord.data.sources.OwnFileMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: a song's family, for "Appears on…":
 *  - the albums this very recording is on ([AlbumAppearances], with their covers);
 *  - its other performances by the same artist — live, acoustic, remix, a TV-size cut —
 *    from your likes, your library and YouTube Music;
 *  - the same song by other artists: only what MusicBrainz says is the same *work*
 *    (one composition, every recording of it), so a shared title alone never counts.
 */
object SongFamily {

    private const val TAG = "BitChord"

    data class Other(val title: String, val artist: String, val album: String?, val song: Song?)

    private fun seconds(s: Song): Int? = s.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
        ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }

    private fun sameTitle(a: String, b: String): Boolean {
        val ka = OwnFileMatcher.allKeys(a)
        return OwnFileMatcher.allKeys(b).any { it in ka }
    }

    // ── BitChord Audio: related artists (an artist's units and projects: Yuki Kajiura and
    // FictionJunction) — from YouTube Music's artist page, kept in the family file. ──────

    private val related = ConcurrentHashMap<String, Set<String>>()

    suspend fun relatedTo(artist: String): Set<String> {
        val k = OwnFileMatcher.key(artist.split(",", "&", " feat").first())
        if (k.isEmpty()) return emptySet()
        related[k]?.let { return it }
        val names = runCatching {
            val id = YtMusicRepository.findArtistPageId(artist).getOrNull() ?: return@runCatching emptySet<String>()
            val page = YtMusicRepository.artistPage(id).getOrNull() ?: return@runCatching emptySet<String>()
            page.sections.flatMap { it.items }.filter { it.browseId?.startsWith("UC") == true }.map { it.title }.toSet()
        }.getOrDefault(emptySet())
        related[k] = names
        scheduleSave()
        return names
    }

    private fun relatedArtist(a: String, b: String): Boolean {
        val k = OwnFileMatcher.key(a.split(",", "&", " feat").first())
        val kb = OwnFileMatcher.key(b.split(",", "&", " feat").first())
        return related[k]?.any { OwnFileMatcher.sameArtist(it, b) } == true || related[kb]?.any { OwnFileMatcher.sameArtist(it, a) } == true
    }

    private fun ownPool(): List<Song> =
        LikedMusicCache.likedSongs.value +
            runCatching { com.music.bitchord.data.webdav.WebDavRepository.library() }.getOrDefault(emptyList()) +
            OwnLibrary.uploads.value

    /**
     * [song]'s other performances: the same artist's (or a related one's — a unit, a
     * project) other recordings — live, acoustic, remix, other concerts — and other
     * artists' versions a tribute or covers release vouches for. A live recording of the
     * same title in your own music counts whoever is credited.
     */
    fun performancesAmong(song: Song, pool: List<Song>, own: Set<String>): List<Song> =
        pool.asSequence()
            .filter { it.videoId != song.videoId && !it.isVideo }
            .filter { sameTitle(song.title, it.title) }
            .filter {
                OwnFileMatcher.sameArtist(song.artist, it.artist) || relatedArtist(song.artist, it.artist) || coverOf(song, it) ||
                    (it.videoId in own && "live" in OwnFileMatcher.kindsOf(it.title, it.albumName))
            }
            .filter { !OwnFileMatcher.sameRecording(song.title, song.albumName, seconds(song), it.title, it.albumName, seconds(it)) }
            .distinctBy { OwnFileMatcher.kindsOf(it.title, it.albumName).sorted().joinToString() + "|" + it.albumName.orEmpty().lowercase() + "|" + ((seconds(it) ?: 0) / 3) }
            .take(20)
            .toList()

    /** Ready at once when the background mapping ([mapAll]) has been through this song. */
    fun cachedPerformances(song: Song): List<Song>? = index[familyKey(song)]?.first
    fun cachedOthers(song: Song): List<Other>? = index[familyKey(song)]?.second

    /** The same artist's other recordings of [song]: live, acoustic, remix… and other concerts. */
    suspend fun performances(song: Song): List<Song> = withContext(Dispatchers.IO) {
        runCatching { relatedTo(song.artist) }
        val pool = ArrayList<Song>()
        pool += ownPool()
        // Asked with the artist, and by the title alone (other artists' versions).
        val bare = song.title.replace(Regex("""\s*[(\[（【].*?[)\]）】]\s*"""), " ").trim()
        val found = (withTimeoutOrNull(8_000) {
            YtMusicRepository.search("${song.title} ${song.artist}", SearchFilter.SONGS).getOrNull()
        }.orEmpty() + withTimeoutOrNull(8_000) {
            YtMusicRepository.search(bare, SearchFilter.SONGS).getOrNull()
        }.orEmpty()).mapNotNull { r ->
            when (r) {
                is SearchResult.Track -> r.song
                is SearchResult.TopTrack -> r.song
                else -> null
            }
        }
        pool += found
        val own = (com.music.bitchord.data.webdav.WebDavRepository.library() + OwnLibrary.uploads.value).mapTo(HashSet()) { it.videoId }
        performancesAmong(song, pool, own).also { remember(song, perf = it) }
    }

    // ── BitChord Audio: the family map — worked out in the background for your liked songs
    // and your library, kept in song_family.json (synced with your other settings). ─────

    const val FILE = "song_family.json"
    private val index = ConcurrentHashMap<String, Pair<List<Song>?, List<Other>?>>()
    private var file: java.io.File? = null
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var saveJob: kotlinx.coroutines.Job? = null
    @Volatile private var mapping: kotlinx.coroutines.Job? = null

    private fun familyKey(s: Song) = OwnFileMatcher.key(s.title) + "|" + OwnFileMatcher.key(s.artist) + "|" + s.albumName.orEmpty().lowercase() + "|" + ((seconds(s) ?: 0) / 3)

    private fun songJson(s: Song) = org.json.JSONObject().apply {
        put("v", s.videoId); put("t", s.title); put("a", s.artist)
        s.albumName?.let { put("al", it) }; s.albumId?.let { put("ai", it) }
        s.durationText?.let { put("d", it) }; s.thumbnailUrl?.let { put("th", it) }
    }

    private fun songFrom(o: org.json.JSONObject) = Song(
        videoId = o.optString("v"), title = o.optString("t"), artist = o.optString("a"),
        thumbnailUrl = o.optString("th").ifBlank { null }, durationText = o.optString("d").ifBlank { null },
        albumName = o.optString("al").ifBlank { null }, albumId = o.optString("ai").ifBlank { null },
    )

    @Volatile private var appContext: android.content.Context? = null

    fun init(context: android.content.Context) {
        appContext = context.applicationContext
        file = java.io.File(context.filesDir, FILE)
        bg.launch { load() }
    }

    /** Read again (after a restore from pCloud). */
    fun load() {
        val text = runCatching { file?.takeIf { it.isFile }?.readText() }.getOrNull() ?: return
        runCatching {
            val root = org.json.JSONObject(text)
            root.optJSONObject("songs")?.let { songs ->
                songs.keys().forEach { k ->
                    val o = songs.getJSONObject(k)
                    val perf = o.optJSONArray("p")?.let { a -> (0 until a.length()).map { songFrom(a.getJSONObject(it)) } }
                    val others = o.optJSONArray("o")?.let { a ->
                        (0 until a.length()).map { val x = a.getJSONObject(it); Other(x.optString("t"), x.optString("a"), x.optString("al").ifBlank { null }, null) }
                    }
                    index[k] = perf to others
                }
            }
            root.optJSONObject("related")?.let { rel ->
                rel.keys().forEach { k -> val a = rel.getJSONArray(k); related[k] = (0 until a.length()).map { a.getString(it) }.toSet() }
            }
        }
    }

    private fun remember(song: Song, perf: List<Song>? = null, others: List<Other>? = null) {
        val k = familyKey(song)
        val old = index[k]
        index[k] = (perf ?: old?.first) to (others ?: old?.second)
        scheduleSave()
    }

    private fun scheduleSave() {
        if (saveJob?.isActive == true) return
        saveJob = bg.launch {
            kotlinx.coroutines.delay(20_000)
            val root = org.json.JSONObject()
            val songs = org.json.JSONObject()
            index.forEach { (k, v) ->
                songs.put(k, org.json.JSONObject().apply {
                    v.first?.let { put("p", org.json.JSONArray(it.map(::songJson))) }
                    v.second?.let { put("o", org.json.JSONArray(it.map { o -> org.json.JSONObject().apply { put("t", o.title); put("a", o.artist); o.album?.let { a -> put("al", a) } } })) }
                })
            }
            root.put("songs", songs)
            root.put("related", org.json.JSONObject().apply { related.forEach { (k, v) -> put(k, org.json.JSONArray(v.toList())) } })
            runCatching { file?.writeText(root.toString()) }
            appContext?.let { com.music.bitchord.data.sync.SettingsSync.changed(it) }
        }
    }

    /**
     * Works the family out for every liked song and every song of your library, a little at a
     * time while the app runs: performances from what is on the phone at once (cheap), then —
     * for liked songs, slowly — YouTube Music's search and MusicBrainz's works.
     */
    fun mapAll() {
        if (mapping?.isActive == true) return
        mapping = bg.launch {
            kotlinx.coroutines.delay(60_000)
            val liked = LikedMusicCache.likedSongs.value
            val library = runCatching { com.music.bitchord.data.webdav.WebDavRepository.library() }.getOrDefault(emptyList())
            val pool = ownPool()
            val own = (library + OwnLibrary.uploads.value).mapTo(HashSet()) { it.videoId }
            // 1. Related artists, once per artist of your likes (YouTube Music, a request each).
            liked.map { it.artist }.distinctBy { OwnFileMatcher.key(it) }.take(400).forEach { a ->
                val k = OwnFileMatcher.key(a.split(",", "&", " feat").first())
                // On Wi-Fi only: a request per artist is not for mobile data.
                while (com.music.bitchord.data.settings.AppSettings.meteredConnection.value != false) kotlinx.coroutines.delay(60_000)
                if (k.isNotEmpty() && related[k] == null) { runCatching { relatedTo(a) }; kotlinx.coroutines.delay(1_500) }
            }
            // 2. Local performances for everything (no network).
            var n = 0
            for (s in (liked + library).distinctBy { familyKey(it) }) {
                if (index[familyKey(s)]?.first != null) continue
                remember(s, perf = performancesAmong(s, pool, own))
                if (++n % 200 == 0) kotlinx.coroutines.yield()
            }
            TrackLog.d(TAG, "song family: performances mapped for $n songs")
            // 3. Liked songs: YouTube Music's other versions and MusicBrainz's other artists, slowly.
            var online = 0
            for (s in liked) {
                if (com.music.bitchord.data.settings.AppSettings.meteredConnection.value != false) { kotlinx.coroutines.delay(60_000); continue }
                if (index[familyKey(s)]?.second != null) continue
                runCatching { performances(s) }
                runCatching { remember(s, others = byOtherArtists(s)) }
                online++
                kotlinx.coroutines.delay(3_000)
            }
            TrackLog.d(TAG, "song family: $online liked songs looked up online")
        }
    }

    /**
     * Whether [b] (another artist's) is a version of [a]: one of them is on a tribute or
     * covers release, or says whose song it is ("原曲歌手", "originally by") — a shared
     * title alone is not enough.
     */
    private fun coverOf(a: Song, b: Song): Boolean {
        fun covers(s: Song) = OwnFileMatcher.kindsOf(s.title, s.albumName).let { "tribute" in it || "cover" in it } ||
            Regex("""原曲|originally|オリジナル歌手|カバー""", RegexOption.IGNORE_CASE).containsMatchIn(s.title + " " + s.albumName.orEmpty())
        return covers(a) || covers(b)
    }

    private val worksCache = ConcurrentHashMap<String, List<Other>>()

    /**
     * The same song recorded by other artists: [song]'s recording found on MusicBrainz, its
     * work (the composition), and every recording of that work credited to someone else.
     * Nothing when MusicBrainz knows no work for it — a title alone is not enough.
     */
    suspend fun byOtherArtists(song: Song): List<Other> = withContext(Dispatchers.IO) {
        val cacheKey = OwnFileMatcher.key(song.title) + "|" + OwnFileMatcher.key(song.artist)
        worksCache[cacheKey]?.let { return@withContext it }
        index[familyKey(song)]?.second?.let { return@withContext it }
        val result = runCatching { lookUpWork(song) }.getOrNull().orEmpty()
        worksCache[cacheKey] = result
        remember(song, others = result)
        result
    }

    private suspend fun lookUpWork(song: Song): List<Other> {
        val title = song.title.replace(Regex("""\s*[(\[（【].*?[)\]）】]\s*"""), " ").trim()
        val names = listOf(title to song.artist)
        var workId: String? = null
        for ((t, a) in names) {
            val q = "recording:\"${NameSources.lucene(t)}\" AND artist:\"${NameSources.lucene(a.split(",", "&", " feat").first().trim())}\""
            val found = NameSources.getJson(NameSources.mb("recording", "query" to q, "limit" to "8"), musicBrainz = true) ?: continue
            val recordings = found.optJSONArray("recordings").objects().filter { it.optInt("score") >= 85 }
            for (r in recordings.take(4)) {
                val id = r.optString("id").takeIf { it.isNotBlank() } ?: continue
                val rec = NameSources.getJson(NameSources.mb("recording/$id", "inc" to "work-rels"), musicBrainz = true) ?: continue
                workId = rec.optJSONArray("relations").objects()
                    .firstOrNull { it.optString("target-type") == "work" }
                    ?.optJSONObject("work")?.optString("id")?.takeIf { it.isNotBlank() }
                if (workId != null) break
            }
            if (workId != null) break
        }
        val work = workId ?: run {
            TrackLog.d(TAG, "song family: no MusicBrainz work for '${song.title}' — no versions by others shown")
            return emptyList()
        }
        val browse = NameSources.getJson(
            NameSources.mb("recording", "work" to work, "inc" to "artist-credits+releases", "limit" to "100"),
            musicBrainz = true,
        ) ?: return emptyList()
        val others = browse.optJSONArray("recordings").objects().mapNotNull { r ->
            val credit = r.optJSONArray("artist-credit").objects().joinToString("") { c ->
                c.optString("name") + c.optString("joinphrase")
            }.trim()
            if (credit.isBlank() || OwnFileMatcher.sameArtist(song.artist, credit)) return@mapNotNull null
            val album = r.optJSONArray("releases").objects().firstOrNull()?.optString("title")
            Other(r.optString("title"), credit, album, null)
        }.distinctBy { OwnFileMatcher.key(it.artist) }.take(25)
        TrackLog.d(TAG, "song family: '${song.title}' — work $work, ${others.size} other artists")
        return others
    }

    /** A YouTube Music copy of [other] to play: same title (any script), same artist. */
    suspend fun findToPlay(other: Other): Song? = withContext(Dispatchers.IO) {
        other.song ?: withTimeoutOrNull(8_000) {
            YtMusicRepository.search("${other.title} ${other.artist}", SearchFilter.SONGS).getOrNull()
        }.orEmpty().firstNotNullOfOrNull { r ->
            val s = when (r) {
                is SearchResult.Track -> r.song
                is SearchResult.TopTrack -> r.song
                else -> null
            } ?: return@firstNotNullOfOrNull null
            s.takeIf { sameTitle(other.title, it.title) && OwnFileMatcher.sameArtist(other.artist, it.artist) }
        }
    }
}

package com.music.bitchord.data

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.OwnFileMatcher
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.data.webdav.WebDavRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * BitChord Audio: the heart on songs that aren't YouTube's — your pCloud
 * files, a copy picked from JioSaavn.
 *
 * A like goes to YouTube whenever the song is there, so Liked Music stays the
 * one list on every device: the YouTube song the file stood in for (recorded
 * when the queue swapped it — [link]), else the YouTube song found by name,
 * artist and length. Only a song YouTube doesn't have is liked here, on the
 * device, and shown in Liked Music with the rest ([liked]).
 */
object LocalLikes {

    private const val PREFS = "bitchord_local_likes"
    private const val LINK = "link:"
    private const val LIKED = "liked:"
    private const val GONE = "gone:"

    private var prefs: SharedPreferences? = null

    private val _links = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Non-YouTube song id → the YouTube song it is. */
    val links: StateFlow<Map<String, String>> = _links.asStateFlow()

    private val _liked = MutableStateFlow<Map<String, Song>>(emptyMap())

    /** Songs liked here because YouTube doesn't have them. */
    val liked: StateFlow<Map<String, Song>> = _liked.asStateFlow()

    /** Songs seen in the queue, so a heart on one knows its name. Not stored. */
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Song>()

    /** BitChord Audio: a song played lately by its id (what was un-liked, when Liked Music hasn't it). */
    fun seenSong(id: String): Song? = seen[id]

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val links = HashMap<String, String>()
        val liked = HashMap<String, Song>()
        p.all.forEach { (k, v) ->
            when {
                k.startsWith(LINK) && v is String -> links[k.removePrefix(LINK)] = v
                k.startsWith(LIKED) && v is String -> songFrom(v)?.let {
                    liked[k.removePrefix(LIKED)] = it
                    runCatching { JSONObject(v).optString("anchor").takeIf(String::isNotEmpty) }.getOrNull()
                        ?.let { a -> anchors[k.removePrefix(LIKED)] = a }
                }
            }
        }
        _links.value = links
        _liked.value = liked
    }

    fun isYouTubeId(id: String): Boolean =
        !WebDavConfig.isWebDavId(id) && SourceRegistry.parseTrackKey(id) == null &&
            !id.startsWith("content://") && !id.startsWith("file://")

    /** [replacement] played in [original]'s place: a heart on it is a heart on [original]. */
    fun link(replacement: Song, original: Song) {
        seen[replacement.videoId] = replacement
        if (replacement.videoId == original.videoId || !isYouTubeId(original.videoId)) return
        if (_links.value[replacement.videoId] == original.videoId) return
        _links.value = _links.value + (replacement.videoId to original.videoId)
        prefs?.edit()?.putString(LINK + replacement.videoId, original.videoId)?.apply()
        CloudBackup.soon(PREFS)
    }

    fun youTubeOf(id: String): String? = _links.value[id]

    /** BitChord Audio: every id known to be the same song as [id] (your file, other uploads, the liked one). */
    fun sameAs(id: String): Set<String> {
        val links = _links.value
        val roots = setOfNotNull(id, links[id])
        return roots + links.filter { (k, v) -> v in roots || k in roots }.flatMap { listOf(it.key, it.value) }
    }

    /** BitChord Audio: [a] and [b] are not the same song — a heart on one isn't on the other. */
    fun unlink(a: String, b: String) {
        val drop = _links.value.filter { (k, v) -> (k == a && v == b) || (k == b && v == a) }.keys
        if (drop.isEmpty()) return
        _links.value = _links.value - drop
        prefs?.edit()?.apply { drop.forEach { remove(LINK + it) } }?.apply()
    }

    /**
     * [id] is the same recording as the liked song [likedId] (another upload
     * of it on YouTube, your file, JioSaavn's copy) — found for a search or
     * the player, not stored: worked out again next time.
     */
    fun alias(id: String, likedId: String) {
        if (id == likedId || _links.value[id] == likedId) return
        _links.value = _links.value + (id to likedId)
    }

    /** A song seen playing, so a heart tapped on it knows its name. */
    fun saw(song: Song) {
        seen[song.videoId] = song
    }

    fun songFor(id: String): Song? =
        seen[id] ?: _liked.value[id] ?: WebDavRepository.library().firstOrNull { it.videoId == id }

    /**
     * BitChord Audio: where in Liked Music each song liked here belongs — the
     * YouTube song that was newest when it was liked. It is shown just above
     * that one, so later likes go above it instead of under it forever.
     */
    private val anchors = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun anchorOf(id: String): String? = anchors[id]

    /**
     * BitChord Audio: [mine] placed among [list] — each above the song that
     * was newest when it was liked; one with no such song left (or liked
     * before this was kept) goes at the end, not over every newer like.
     */
    fun placeAmong(mine: List<Song>, list: List<Song>): List<Song> {
        if (mine.isEmpty()) return list
        val byAnchor = mine.groupBy { anchors[it.videoId] }
        val ids = list.mapTo(HashSet()) { it.videoId }
        val out = ArrayList<Song>(list.size + mine.size)
        // Liked here before anything was on YouTube's list at all.
        byAnchor[TOP]?.let(out::addAll)
        for (song in list) {
            byAnchor[song.videoId]?.let(out::addAll)
            out += song
        }
        byAnchor.forEach { (anchor, songs) -> if (anchor != TOP && (anchor == null || anchor !in ids)) out.addAll(songs) }
        return out
    }

    /** BitChord Audio: the song's cover now — your file's folder art when the like saved none. */
    fun withCover(song: Song): Song {
        found[song.videoId]?.let { return song.copy(thumbnailUrl = song.thumbnailUrl ?: it.first, durationText = song.durationText ?: it.second) }
        if (!song.thumbnailUrl.isNullOrBlank() || !WebDavConfig.isWebDavId(song.videoId)) return song
        val library = WebDavRepository.library()
        val own = library.firstOrNull { it.videoId == song.videoId }
        val thumb = own?.thumbnailUrl
            ?: WebDavConfig.fileUrlOf(song.videoId)?.substringBeforeLast('/')?.let { folder ->
                library.firstOrNull { !it.thumbnailUrl.isNullOrBlank() && WebDavConfig.fileUrlOf(it.videoId)?.substringBeforeLast('/') == folder }?.thumbnailUrl
            }
        return if (thumb != null) song.copy(thumbnailUrl = thumb) else song
    }

    private const val TOP = "^"

    /** BitChord Audio: covers (and lengths) found for liked songs that had none: id → (cover, length). */
    private val found = java.util.concurrent.ConcurrentHashMap<String, Pair<String?, String?>>()
    @Volatile private var filling = false

    /**
     * BitChord Audio: your local likes shown without a cover (a pCloud file with no picture
     * in it or its folder, a song liked from another source) get one: the file's own when
     * the library has it now, the download's, else the same song's on YouTube Music (title
     * and artist, any script). Kept with the like, so it is found once.
     */
    private const val NO_COVER = "nocover:"

    suspend fun fillCovers() {
        if (filling) return
        filling = true
        try {
            // Twice-liked songs first: one like each.
            runCatching { dedupe() }
            // The cover search knows more places now: the "not found" marks of before are forgotten once.
            prefs?.let { p ->
                if (!p.getBoolean("nocover_v2", false)) {
                    val e = p.edit()
                    p.all.keys.filter { it.startsWith(NO_COVER) }.forEach { e.remove(it) }
                    e.putBoolean("nocover_v2", true).apply()
                }
            }
            val library = WebDavRepository.library().associateBy { it.videoId }
            for ((id, s) in _liked.value) {
                if (!s.thumbnailUrl.isNullOrBlank() && !s.durationText.isNullOrBlank()) continue
                val shown = withCover(s)
                var cover = shown.thumbnailUrl ?: com.music.bitchord.download.Downloads.savedArtwork(id)
                    ?: com.music.bitchord.data.covers.CoverOverrides.get(s)
                val inLibrary = library[id] ?: WebDavRepository.library().firstOrNull { f ->
                    runCatching { java.net.URLDecoder.decode(f.videoId, "UTF-8") == java.net.URLDecoder.decode(id, "UTF-8") }.getOrDefault(false)
                }
                var length = s.durationText ?: inLibrary?.durationText
                cover = cover ?: inLibrary?.thumbnailUrl
                // A file of yours with no picture: the cover of its album (another file in the folder).
                if (cover == null && id.startsWith("webdav:")) {
                    val folder = id.substringBeforeLast('/')
                    cover = WebDavRepository.library().firstOrNull { it.videoId.startsWith("$folder/") && !it.thumbnailUrl.isNullOrBlank() }?.thumbnailUrl
                }
                // Looked for in the last week and not found: not searched again on every start.
                val tried = prefs?.getLong(NO_COVER + id, 0L) ?: 0L
                if (cover == null && System.currentTimeMillis() - tried < 7 * 24 * 3_600_000L) continue
                if (cover == null) {
                    val hit = runCatching {
                        YtMusicRepository.search("${s.title} ${s.artist}", SearchFilter.SONGS).getOrNull().orEmpty()
                            .mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
                            .firstOrNull { c ->
                                OwnFileMatcher.allKeys(c.title).any { it in OwnFileMatcher.allKeys(s.title) } &&
                                    s.artist.split(",", "&", "·").any { a -> OwnFileMatcher.sameArtist(a.trim(), c.artist) }
                            }
                    }.getOrNull()
                    cover = hit?.thumbnailUrl
                    length = length ?: hit?.durationText
                    // Not on YouTube Music: the shop's cover of the song (iTunes), when it is there.
                    if (cover == null) cover = runCatching {
                        com.music.bitchord.data.cleanup.CoverMatch.itunes("${s.albumName ?: s.title} ${s.artist.split(',', '·').first().trim()}").firstOrNull()
                    }.getOrNull()
                    kotlinx.coroutines.delay(1_500)
                }
                if (cover == null) prefs?.edit()?.putLong(NO_COVER + id, System.currentTimeMillis())?.apply()
                if (cover == null && length == null) continue
                found[id] = cover to length
                val updated = s.copy(thumbnailUrl = s.thumbnailUrl ?: cover, durationText = s.durationText ?: length)
                _liked.value = _liked.value + (id to updated)
                prefs?.edit()?.putString(LIKED + id, songJson(updated, anchors[id]))?.apply()
                TrackLog.d("BitChord", "likes: a cover for '${s.title}' — ${cover?.take(60) ?: "none"}")
            }
        } finally {
            filling = false
        }
    }

    // ── BitChord Audio: one like per song, whatever copies of it there are ──

    private fun seconds(s: Song): Int? = s.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
        ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }

    private val NOTE = Regex("\\s*[(\\[（【][^)\\]）】]*(karaoke|off ?vocal|instrumental|inst\\.|live|remix|mix|ver\\.|version|edit|acoustic)[^)\\]）】]*[)\\]）】]", RegexOption.IGNORE_CASE)
    private val VARIANT = Regex("(?i)karaoke|off ?vocal|instrumental|\\binst\\b|live|remix|\\bmix\\b|acoustic|ver\\.|version|edit|カラオケ")

    /** Whether [a] and [b] are the same song: one title (in any script), one artist, one length. */
    fun sameSong(a: Song, b: Song): Boolean {
        if (a.videoId == b.videoId) return true
        // A karaoke, live or other version is never the original.
        if (VARIANT.containsMatchIn(a.title) != VARIANT.containsMatchIn(b.title)) return false
        val ka = runCatching { OwnFileMatcher.allKeys(a.title.replace(NOTE, "")) }.getOrDefault(emptySet()).filter { it.removePrefix("~").length >= 3 }
        val kb = runCatching { OwnFileMatcher.allKeys(b.title.replace(NOTE, "")) }.getOrDefault(emptySet())
        if (ka.none { it in kb }) return false
        val artistA = a.artist.split(',', '&', '·').map { it.trim() }.filter { it.isNotEmpty() }
        if (artistA.none { x -> b.artist.split(',', '&', '·').any { y -> OwnFileMatcher.sameArtist(x, y.trim()) } }) return false
        val sa = seconds(a)
        val sb = seconds(b)
        return sa == null || sb == null || kotlin.math.abs(sa - sb) <= 3
    }

    /** A song already liked that is [song] (another copy of it), if any. */
    fun likedTwin(song: Song): Song? =
        (LikedMusicCache.likedSongs.value + _liked.value.values).firstOrNull { it.videoId != song.videoId && sameSong(song, it) }

    /** Which of two copies keeps the like: the one on the phone, then the one with a cover and a length. */
    private fun rank(s: Song): Int =
        (if (com.music.bitchord.download.Downloads.localCopyOf(s.videoId) != null) 8 else 0) +
            (if (isYouTubeId(s.videoId)) 4 else 0) +
            (if (!s.thumbnailUrl.isNullOrBlank()) 2 else 0) +
            (if (!s.durationText.isNullOrBlank()) 1 else 0)

    /**
     * Likes of the same song twice (two files of it in a pCloud folder, liked with the whole
     * album; or a file and its YouTube release): the copy that ranks lower loses its like.
     * Only likes kept here (files) are removed — a YouTube like stays as YouTube has it.
     */
    fun dedupe() {
        val all = (LikedMusicCache.likedSongs.value + _liked.value.values).distinctBy { it.videoId }
        val gone = HashSet<String>()
        for (s in all) {
            if (s.videoId in gone || isYouTubeId(s.videoId) || s.videoId !in _liked.value) continue
            val twin = all.firstOrNull { it.videoId != s.videoId && it.videoId !in gone && sameSong(s, it) } ?: continue
            val loser = if (rank(twin) >= rank(s)) s else twin
            if (isYouTubeId(loser.videoId) || loser.videoId !in _liked.value) continue
            gone += loser.videoId
            TrackLog.d("BitChord", "likes: '${loser.title}' liked twice — kept '${(if (loser === s) twin else s).title}', removed the other copy")
        }
        gone.forEach { id -> _liked.value[id]?.let { setLocal(it, false) } }
        if (gone.isNotEmpty()) TrackLog.d("BitChord", "likes: ${gone.size} duplicate like(s) removed")
    }

    fun setLocal(song: Song, like: Boolean) {
        val id = song.videoId
        if (like) {
            // Another copy of this song is liked already (a whole folder liked, with two files
            // of a song in it): one like is enough.
            // (Only two files: a like moved from a video or a YouTube song onto your file is a swap.)
            likedTwin(song)?.takeIf { !isYouTubeId(it.videoId) && !isYouTubeId(id) }?.let { twin ->
                TrackLog.d("BitChord", "likes: '${song.title}' is liked already ('${twin.title}'); not liked twice")
                return
            }
            val anchor = LikedMusicCache.likedSongs.value.firstOrNull { it.videoId != id }?.videoId ?: TOP
            anchors[id] = anchor
            _liked.value = _liked.value + (id to song)
            prefs?.edit()?.putString(LIKED + id, songJson(song, anchor))?.remove(GONE + id)?.apply()
        } else {
            _liked.value = _liked.value - id
            // Marked, so pCloud's copy doesn't bring it back.
            prefs?.edit()?.remove(LIKED + id)?.putBoolean(GONE + id, true)?.apply()
        }
        // BitChord Audio: these likes live nowhere else — to pCloud right away.
        CloudBackup.soon(PREFS)
    }

    /** The YouTube song [song] is, by name, artist and length — or null. */
    suspend fun findOnYouTube(song: Song): Song? {
        val seconds = song.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
            ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }
        val results = YtMusicRepository.search("${song.title} ${song.artist}", SearchFilter.SONGS).getOrNull().orEmpty()
            .mapNotNull {
                when (it) {
                    is SearchResult.Track -> it.song
                    is SearchResult.TopTrack -> it.song
                    else -> null
                }
            }
            .filterNot { it.isVideo }
        val target = TrackMatcher.Target(title = song.title, artist = song.artist, album = song.albumName, durationSec = seconds)
        val keys = OwnFileMatcher.titleKeys(song.title)
        return (TrackMatcher.ranked(results, target).firstOrNull()
            ?: results.firstOrNull { r ->
                OwnFileMatcher.titleKeys(r.title).any { it in keys } &&
                    OwnFileMatcher.sameArtist(song.artist, r.artist)
            }).also { found ->
                if (found == null) TrackLog.d("BitChord", "likes: no YouTube match for '${song.title}' among " +
                    results.take(4).joinToString { "'${it.title}' · ${it.artist} ${it.durationText}" })
            }
    }

    private fun songJson(s: Song, anchor: String? = null): String = JSONObject().apply {
        anchor?.let { put("anchor", it) }
        put("id", s.videoId)
        put("title", s.title)
        put("artist", s.artist)
        s.albumName?.let { put("album", it) }
        s.durationText?.let { put("duration", it) }
        s.thumbnailUrl?.let { put("thumb", it) }
        s.sourceQuality?.let { put("quality", it) }
    }.toString()

    private fun songFrom(json: String): Song? = runCatching {
        val o = JSONObject(json)
        fun str(k: String) = if (o.has(k) && !o.isNull(k)) o.getString(k) else null
        Song(
            videoId = o.getString("id"),
            title = o.getString("title"),
            artist = o.optString("artist"),
            thumbnailUrl = str("thumb"),
            albumName = str("album"),
            durationText = str("duration"),
            sourceQuality = str("quality"),
        )
    }.getOrNull()
}

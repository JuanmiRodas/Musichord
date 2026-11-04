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

    private var prefs: SharedPreferences? = null

    private val _links = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Non-YouTube song id → the YouTube song it is. */
    val links: StateFlow<Map<String, String>> = _links.asStateFlow()

    private val _liked = MutableStateFlow<Map<String, Song>>(emptyMap())

    /** Songs liked here because YouTube doesn't have them. */
    val liked: StateFlow<Map<String, Song>> = _liked.asStateFlow()

    /** Songs seen in the queue, so a heart on one knows its name. Not stored. */
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Song>()

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val links = HashMap<String, String>()
        val liked = HashMap<String, Song>()
        p.all.forEach { (k, v) ->
            when {
                k.startsWith(LINK) && v is String -> links[k.removePrefix(LINK)] = v
                k.startsWith(LIKED) && v is String -> songFrom(v)?.let { liked[k.removePrefix(LIKED)] = it }
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
    }

    fun youTubeOf(id: String): String? = _links.value[id]

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

    fun setLocal(song: Song, like: Boolean) {
        val id = song.videoId
        if (like) {
            _liked.value = _liked.value + (id to song)
            prefs?.edit()?.putString(LIKED + id, songJson(song))?.apply()
        } else {
            _liked.value = _liked.value - id
            prefs?.edit()?.remove(LIKED + id)?.apply()
        }
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
        return TrackMatcher.ranked(results, target).firstOrNull()
            ?: results.firstOrNull { r ->
                OwnFileMatcher.titleKeys(r.title).any { it in keys } &&
                    OwnFileMatcher.sameArtist(song.artist, r.artist)
            }
    }

    private fun songJson(s: Song): String = JSONObject().apply {
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

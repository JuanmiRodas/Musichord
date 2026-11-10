package com.music.bitchord.data.video

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import com.music.bitchord.R
import com.music.bitchord.data.LocalLikes
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.VersionTimeMap
import com.music.bitchord.data.sources.SongOrigins
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A video to show for a song: on YouTube, or on bilibili. */
data class VideoRef(val site: Site, val id: String) {
    enum class Site { YOUTUBE, BILIBILI }

    /** What the video engine files it under: a YouTube id as itself, so its caches carry over. */
    val key: String get() = if (site == Site.YOUTUBE) id else "bili:$id"

    fun encode(): String = "${site.name}:$id"

    companion object {
        fun decode(s: String): VideoRef? {
            val site = runCatching { Site.valueOf(s.substringBefore(':')) }.getOrNull() ?: return null
            val id = s.substringAfter(':', "").ifBlank { return null }
            return VideoRef(site, id)
        }
    }
}

/**
 * BitChord Audio: video mode — the player's video button.
 *
 * While on, whatever plays shows a music video in place of its cover: the
 * one you linked to the song (long press on the button), else the video
 * YouTube Music pairs with the song, else the best match on YouTube. The
 * audio stays what it was — your file, its quality, the queue, crossfade —
 * and the picture follows its clock (through YouTube's own time map between
 * song and video, when it has one), so the lyrics stay in time underneath.
 */
object VideoMode {
    private const val TAG = "VideoMode"
    private const val KEY_ON = "on"
    private const val LINK = "link:"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: SharedPreferences? = null
    private var app: Context? = null

    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on.asStateFlow()

    /** Media ids with a video ready to show, and the video. */
    private val _ready = MutableStateFlow<Map<String, VideoRef>>(emptyMap())
    val ready: StateFlow<Map<String, VideoRef>> = _ready.asStateFlow()

    /** The media id being looked up now, for the button's spinner. */
    private val _looking = MutableStateFlow<String?>(null)
    val looking: StateFlow<String?> = _looking.asStateFlow()

    /** The song whose video search is open (long press). */
    private val _searchFor = MutableStateFlow<Song?>(null)
    val searchFor: StateFlow<Song?> = _searchFor.asStateFlow()

    /** The YouTube song each media id's video was paired against, for its time map. */
    private val pairedFrom = ConcurrentHashMap<String, String>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun init(context: Context) {
        val p = context.getSharedPreferences("bitchord_video_mode", Context.MODE_PRIVATE)
        prefs = p
        app = context.applicationContext
        _on.value = p.getBoolean(KEY_ON, false)
    }

    fun setOn(value: Boolean) {
        _on.value = value
        prefs?.edit()?.putBoolean(KEY_ON, value)?.apply()
    }

    fun openSearch(song: Song) { _searchFor.value = song }
    fun closeSearch() { _searchFor.value = null }

    private fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** Every name a song goes by here: its id, its original's, and its title with its first artist. */
    private fun keysOf(song: Song): List<String> = buildList {
        add("id:" + song.videoId)
        SongOrigins.originOf(song.videoId)?.let { add("id:" + it.videoId) }
        val artist = song.artist.split(',', '&').firstOrNull().orEmpty()
        val t = norm(song.title)
        if (t.isNotEmpty()) add("n:$t|${norm(artist)}")
    }.distinct()

    /** The video you linked to [song], if any. */
    fun linked(song: Song): VideoRef? {
        val p = prefs ?: return null
        return keysOf(song).firstNotNullOfOrNull { k -> p.getString(LINK + k, null)?.let(VideoRef::decode) }
    }

    /** [ref] is [song]'s video from now on, wherever it plays from; video mode turns on. */
    fun link(song: Song, ref: VideoRef) {
        val e = prefs?.edit() ?: return
        keysOf(song).forEach { e.putString(LINK + it, ref.encode()) }
        e.apply()
        missing.remove(song.videoId)
        pairedFrom.remove(song.videoId)
        _ready.update { it + (song.videoId to ref) }
        TrackLog.d(TAG, "video for '${song.title}' linked: ${ref.encode()}")
        setOn(true)
    }

    fun cached(mediaId: String): VideoRef? = _ready.value[mediaId]

    /** YouTube's song→video time map for what [mediaId] shows, when it paired them. */
    fun timeMap(mediaId: String, ref: VideoRef): VersionTimeMap? {
        if (ref.site != VideoRef.Site.YOUTUBE) return null
        val from = pairedFrom[mediaId] ?: return null
        return YtMusicRepository.versionTimeMap(from, ref.id)
    }

    /** Looks [song]'s video up, unless known, missing, or already being looked up. */
    fun request(song: Song, sayIfMissing: Boolean = false) {
        if (cached(song.videoId) != null) return
        if (song.videoId in missing) {
            if (sayIfMissing) say(R.string.video_mode_none)
            return
        }
        if (!inFlight.add(song.videoId)) return
        _looking.value = song.videoId
        scope.launch {
            try {
                val ref = runCatching { resolve(song) }.getOrNull()
                if (ref == null) {
                    missing += song.videoId
                    TrackLog.d(TAG, "no video for '${song.title}'")
                    if (sayIfMissing) say(R.string.video_mode_none)
                } else {
                    TrackLog.d(TAG, "video for '${song.title}': ${ref.encode()}")
                    _ready.update { (it + (song.videoId to ref)).entries.toList().takeLast(64).associate { e -> e.key to e.value } }
                }
            } finally {
                inFlight.remove(song.videoId)
                if (_looking.value == song.videoId) _looking.value = null
            }
        }
    }

    private suspend fun resolve(song: Song): VideoRef? {
        linked(song)?.let { return it }
        if (song.isVideo && LocalLikes.isYouTubeId(song.videoId)) return VideoRef(VideoRef.Site.YOUTUBE, song.videoId)
        // The YouTube song this plays in place of, when it does: YouTube Music
        // pairs videos with its own songs (with a time map), not with files.
        val base = SongOrigins.originOf(song.videoId)?.takeIf { LocalLikes.isYouTubeId(it.videoId) }
            ?: song.takeIf { LocalLikes.isYouTubeId(it.videoId) }
        val search = base ?: song
        val found = runCatching { YtMusicRepository.resolveVideo(search) }.getOrNull()
            ?.takeUnless { TrackMatcher.isKaraokeLike(it.title, it.albumName) }
            ?: bestVideo(song)
        if (found == null) return null
        if (base != null) pairedFrom[song.videoId] = base.videoId
        return VideoRef(VideoRef.Site.YOUTUBE, found.videoId)
    }

    /** The closest YouTube video to [song] by title, artist and length. */
    private suspend fun bestVideo(song: Song): Song? {
        val results = youTube("${song.title} ${song.artist.split(',').first().trim()}")
        val target = TrackMatcher.targetOf(song)
        return TrackMatcher.best(results, target)
    }

    /** YouTube's videos for [query], karaoke and the like left out unless asked for. */
    suspend fun youTube(query: String): List<Song> = withContext(Dispatchers.IO) {
        val wantsKaraoke = TrackMatcher.isKaraokeLike(query)
        YtMusicRepository.search(query, SearchFilter.VIDEOS).getOrNull().orEmpty()
            .mapNotNull {
                when (it) {
                    is SearchResult.Track -> it.song
                    is SearchResult.TopTrack -> it.song
                    else -> null
                }
            }
            .filter { wantsKaraoke || !TrackMatcher.isKaraokeLike(it.title, it.albumName) }
    }

    private fun say(text: Int) {
        val ctx = app ?: return
        scope.launch(Dispatchers.Main) { Toast.makeText(ctx, ctx.getString(text), Toast.LENGTH_SHORT).show() }
    }
}

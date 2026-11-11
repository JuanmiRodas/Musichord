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
    private const val WRONG = "wrong:"

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
        _lyrics.value = p.getBoolean("lyrics", true)
        _frame.value = if (p.contains("frame")) p.getInt("frame", 2) else if (p.getBoolean("square", false)) 0 else 2
        _lyricsScale.value = p.getFloat("lyrics_scale", 1f)
        _translation.value = p.getBoolean("translation", false)
    }

    fun setOn(value: Boolean) {
        _on.value = value
        if (!value) _originalAudio.value = false
        prefs?.edit()?.putBoolean(KEY_ON, value)?.apply()
    }

    // ── What the video screen shows ───────────────────────────────────────

    private val _lyrics = MutableStateFlow(true)
    /** Lyrics over the video (the fullscreen and player buttons). */
    val lyrics: StateFlow<Boolean> = _lyrics.asStateFlow()
    fun setLyrics(on: Boolean) { _lyrics.value = on; prefs?.edit()?.putBoolean("lyrics", on)?.apply() }

    private val _translation = MutableStateFlow(false)
    /** Fullscreen: the sung line with its translation, rather than two lines. */
    val translation: StateFlow<Boolean> = _translation.asStateFlow()
    fun setTranslation(on: Boolean) { _translation.value = on; prefs?.edit()?.putBoolean("translation", on)?.apply() }

    /** How the player frames the video: 0 in the album square, 1 to the screen's edges, 2 at its own shape. */
    private val _frame = MutableStateFlow(2)
    val frame: StateFlow<Int> = _frame.asStateFlow()
    fun setFrame(value: Int) { _frame.value = value.mod(3); prefs?.edit()?.putInt("frame", _frame.value)?.apply() }

    /** The size of video mode's lyrics, as a scale of the default. */
    val LYRICS_SCALES = listOf(0.85f, 1f, 1.2f, 1.4f)
    private val _lyricsScale = MutableStateFlow(1f)
    val lyricsScale: StateFlow<Float> = _lyricsScale.asStateFlow()
    fun setLyricsScale(value: Float) { _lyricsScale.value = value; prefs?.edit()?.putFloat("lyrics_scale", value)?.apply() }

    private val _originalAudio = MutableStateFlow(false)
    /** The music video's own sound, in place of the song's (not remembered). */
    val originalAudio: StateFlow<Boolean> = _originalAudio.asStateFlow()
    fun setOriginalAudio(on: Boolean) { _originalAudio.value = on }

    // ── How a video looks: letterbox bars, text burnt into its bottom ─────

    /** Fractions of the picture's height that are black bars, and whether its bottom carries text. */
    data class Looks(val barTop: Float, val barBottom: Float, val busyBottom: Boolean)

    private val _currentLooks = MutableStateFlow<Looks?>(null)
    /** The looks of the video on screen now, once known. */
    val currentLooks: StateFlow<Looks?> = _currentLooks.asStateFlow()

    /** Measured once per video, kept (and backed up with the rest of video mode). */
    fun looksOf(key: String): Looks? = prefs?.getString("looks:$key", null)?.split(',')?.takeIf { it.size == 3 }?.let {
        Looks(it[0].toFloatOrNull() ?: 0f, it[1].toFloatOrNull() ?: 0f, it[2] == "1")
    }

    fun saveLooks(key: String, looks: Looks) {
        prefs?.edit()?.putString("looks:$key", "${looks.barTop},${looks.barBottom},${if (looks.busyBottom) 1 else 0}")?.apply()
        TrackLog.d(TAG, "video $key looks: bars ${"%.2f".format(looks.barTop)}/${"%.2f".format(looks.barBottom)}, text at the bottom: ${looks.busyBottom}")
    }

    fun showLooks(looks: Looks?) { _currentLooks.value = looks }

    // ── The search, remembered ────────────────────────────────────────────

    /** What was last searched for [song]'s video, else for any. */
    fun lastQuery(song: Song): String? =
        prefs?.getString("q:id:" + song.videoId, null)

    fun rememberQuery(song: Song, query: String) {
        prefs?.edit()?.putString("q:id:" + song.videoId, query)?.apply()
    }

    // ── SponsorBlock: the parts of a music video that aren't the song ─────

    private val offTopic = ConcurrentHashMap<String, List<LongRange>>()

    /** The non-music stretches of YouTube video [id] (SponsorBlock), ms, in order; empty if none known. */
    fun nonMusic(id: String): List<LongRange> = offTopic[id].orEmpty()

    /** Asks SponsorBlock once per video. */
    fun fetchNonMusic(id: String) {
        if (offTopic.containsKey(id)) return
        offTopic[id] = emptyList()
        scope.launch {
            val url = "https://sponsor.ajay.app/api/skipSegments?videoID=" + id +
                "&categories=" + java.net.URLEncoder.encode("[\"music_offtopic\"]", "UTF-8")
            val body = runCatching {
                com.music.bitchord.data.Http.client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use {
                    if (it.isSuccessful) it.body?.string() else null
                }
            }.getOrNull() ?: return@launch
            val ranges = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonArray }
                    .mapNotNull { e ->
                        val seg = ((e as? kotlinx.serialization.json.JsonObject)?.get("segment") as? kotlinx.serialization.json.JsonArray)
                            ?: return@mapNotNull null
                        val a = seg.getOrNull(0)?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
                        val b = seg.getOrNull(1)?.toString()?.toDoubleOrNull() ?: return@mapNotNull null
                        (a * 1000).toLong()..(b * 1000).toLong()
                    }
                    .filter { it.last > it.first }
                    .sortedBy { it.first }
            }.getOrDefault(emptyList())
            offTopic[id] = ranges
            if (ranges.isNotEmpty()) TrackLog.d(TAG, "SponsorBlock: ${ranges.size} non-music parts in $id")
        }
    }

    /**
     * Where in the video the song is at [audioMs]: past every non-music
     * stretch that comes before it (an intro skit, a dialogue in the middle).
     */
    fun skipNonMusic(id: String, audioMs: Long): Long {
        var v = audioMs
        for (r in nonMusic(id)) {
            if (r.first <= v) v += r.last - r.first else break
        }
        return v
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
        _ready.update { it + idsOf(song).associateWith { ref } }
        if (ref.site == VideoRef.Site.YOUTUBE) fetchNonMusic(ref.id)
        TrackLog.d(TAG, "video for '${song.title}' linked: ${ref.encode()}")
        setOn(true)
    }

    fun cached(mediaId: String): VideoRef? = _ready.value[mediaId]
        ?: SongOrigins.originOf(mediaId)?.let { _ready.value[it.videoId] }

    /** The ids a song's video is filed under: its own, and the one it stands in for. */
    private fun idsOf(song: Song): List<String> =
        listOfNotNull(song.videoId, SongOrigins.originOf(song.videoId)?.videoId).distinct()

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
                    if (ref.site == VideoRef.Site.YOUTUBE) fetchNonMusic(ref.id)
                    _ready.update { (it + idsOf(song).associateWith { ref }).entries.toList().takeLast(64).associate { e -> e.key to e.value } }
                }
            } finally {
                inFlight.remove(song.videoId)
                if (_looking.value == song.videoId) _looking.value = null
            }
        }
    }

    /** Other videos for a song, best first, for when the one chosen can't be played. */
    private val alternatives = ConcurrentHashMap<String, MutableList<VideoRef>>()

    /** Videos YouTube wouldn't play here; never offered again this session. */
    private val unplayable = ConcurrentHashMap.newKeySet<String>()

    private suspend fun resolve(song: Song): VideoRef? {
        val linked = linked(song)?.takeUnless { it.key in unplayable || isWrong(song, it) }
        if (song.isVideo && LocalLikes.isYouTubeId(song.videoId) && song.videoId !in unplayable) {
            return VideoRef(VideoRef.Site.YOUTUBE, song.videoId)
        }
        // The YouTube song this plays in place of, when it does: YouTube Music
        // pairs videos with its own songs (with a time map), not with files.
        val base = SongOrigins.originOf(song.videoId)?.takeIf { LocalLikes.isYouTubeId(it.videoId) }
            ?: song.takeIf { LocalLikes.isYouTubeId(it.videoId) }
        val search = base ?: song
        val paired = if (linked == null) {
            runCatching { YtMusicRepository.resolveVideo(search) }.getOrNull()
                ?.takeUnless { TrackMatcher.isKaraokeLike(it.title, it.albumName) }
        } else null
        // Every likely video, best first: the one you linked or YouTube pairs,
        // then the search's matches — so a video YouTube won't play here
        // gives way to the next rather than to nothing.
        val searched = runCatching {
            val results = youTube("${song.title} ${song.artist.split(',').first().trim()}")
            TrackMatcher.ranked(results, TrackMatcher.targetOf(song)).ifEmpty {
                // Videos are credited to channels ("Hiroyuki Sawano" for
                // SawanoHiroyuki[nZk]) and titled freely ("\"B-Cuz\" from …
                // 10th Anniversary"): failing a full match, a video whose
                // title carries the song's — the guest credit and asides
                // left out — in the order YouTube ranked them.
                val core = norm(com.music.bitchord.data.lyrics.LyricNames.cleanTitles(song.title).lastOrNull() ?: song.title)
                if (core.length < 2) emptyList() else results.filter { norm(it.title).contains(core) }
            }
        }.getOrDefault(emptyList())
        val all = (listOfNotNull(linked) + listOfNotNull(paired?.let { VideoRef(VideoRef.Site.YOUTUBE, it.videoId) }) +
            searched.map { VideoRef(VideoRef.Site.YOUTUBE, it.videoId) })
            .distinctBy { it.key }
            .filterNot { it.key in unplayable || isWrong(song, it) }
        val first = all.firstOrNull() ?: return null
        alternatives[song.videoId] = all.drop(1).take(5).toMutableList()
        if (base != null && paired != null && first.id == paired.videoId) pairedFrom[song.videoId] = base.videoId
        return first
    }

    /**
     * BitChord Audio: the video shown for [song] is the wrong one — unlinked,
     * never offered for this song again, and the next likely video takes
     * its place.
     */
    fun markWrong(song: Song) {
        val ref = _ready.value[song.videoId] ?: linked(song) ?: return
        val e = prefs?.edit() ?: return
        keysOf(song).forEach { k ->
            if (prefs?.getString(LINK + k, null)?.let(VideoRef::decode) == ref) e.remove(LINK + k)
            val wrong = prefs?.getStringSet(WRONG + k, emptySet()).orEmpty() + ref.key
            e.putStringSet(WRONG + k, wrong)
        }
        e.apply()
        TrackLog.d(TAG, "video ${ref.encode()} marked wrong for '${song.title}'")
        val next = alternatives[song.videoId]?.let { list ->
            list.removeAll { it.key == ref.key || isWrong(song, it) }
            list.removeFirstOrNull()
        }
        _ready.update { if (next != null) it + idsOf(song).associateWith { next } else it - idsOf(song).toSet() }
        if (next == null) {
            missing.remove(song.videoId)
            request(song, sayIfMissing = true)
        } else if (next.site == VideoRef.Site.YOUTUBE) fetchNonMusic(next.id)
    }

    private fun isWrong(song: Song, ref: VideoRef): Boolean =
        keysOf(song).any { k -> ref.key in prefs?.getStringSet(WRONG + k, emptySet()).orEmpty() }

    /**
     * BitChord Audio: [ref] can't be played here (YouTube refused every
     * client): the song's next likely video takes its place.
     */
    fun failed(mediaId: String, ref: VideoRef) {
        unplayable += ref.key
        if (_ready.value[mediaId] != ref) return
        val next = alternatives[mediaId]?.let { list ->
            while (list.isNotEmpty() && list.first().key in unplayable) list.removeAt(0)
            list.removeFirstOrNull()
        }
        pairedFrom.remove(mediaId)
        TrackLog.d(TAG, "video ${ref.encode()} won't play here; " + (next?.let { "trying ${it.encode()}" } ?: "no other video"))
        _ready.update { if (next != null) it + (mediaId to next) else it - mediaId }
        if (next == null) missing += mediaId
        else if (next.site == VideoRef.Site.YOUTUBE) fetchNonMusic(next.id)
    }

    /** BitChord Audio: the YouTube release of [song] — what it plays in place of, or the best match. */
    suspend fun youTubeSongOf(song: Song): String? {
        if (LocalLikes.isYouTubeId(song.videoId) && !song.isVideo) return song.videoId
        SongOrigins.originOf(song.videoId)?.takeIf { LocalLikes.isYouTubeId(it.videoId) && !it.isVideo }?.let { return it.videoId }
        LocalLikes.youTubeOf(song.videoId)?.let { return it }
        val results = YtMusicRepository.search("${song.title} ${song.artist.split(',').first().trim()}", SearchFilter.SONGS)
            .getOrNull().orEmpty()
            .mapNotNull { (it as? SearchResult.Track)?.song ?: (it as? SearchResult.TopTrack)?.song }
            .filterNot { it.isVideo }
        return TrackMatcher.best(results, TrackMatcher.targetOf(song))?.videoId
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

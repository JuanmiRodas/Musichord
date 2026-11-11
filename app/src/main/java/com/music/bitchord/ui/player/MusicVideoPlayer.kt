package com.music.bitchord.ui.player

import android.content.Context
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.metrolist.innertubex.extraction.StreamResolveException
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.ChunkedDataSource
import com.music.bitchord.playback.MusicOnlyPlayback
import com.music.bitchord.playback.toSong
import com.music.bitchord.ui.rememberIsForeground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A music video's picture, the way the web player's videoDeck plays one: a
 * second ExoPlayer, video only and silent, held to the main player's clock.
 *
 * The audio is untouched — it stays in [com.music.bitchord.playback.PlaybackService]
 * with everything that runs on it (the cache, the EQ, crossfade, Listen
 * Together) — and stays the clock. The picture follows it: its rate nudged a
 * few percent for small drift, a seek for a large one. It streams only while a
 * surface wants it, so a music video playing with the screen off or the player
 * shut costs no more data than the song.
 *
 * Surfaces take turns holding the one decoder, the last one mounted in front —
 * the full player over the mini player, the landscape player over either — as
 * only one of them is ever on screen at a time.
 */
@OptIn(UnstableApi::class)
object MusicVideoEngine {

    private const val TAG = "MusicVideo"

    /** Past this the picture jumps to the audio; under it, its rate is nudged. */
    private const val SEEK_DRIFT_MS = 450L
    private const val IGNORE_DRIFT_MS = 25L
    private const val SYNC_EVERY_MS = 250L

    /** No second seek inside this, while the first is still landing. */
    private const val SEEK_COOLDOWN_MS = 1_500L
    private const val MAPPED_SEEK_DRIFT_MS = 1_200L
    private const val MAPPED_SEEK_COOLDOWN_MS = 5_000L

    /** How long the picture outlives its last surface: a rotation, a sheet reopened. */
    private const val RELEASE_GRACE_MS = 4_000L

    /** Bounded ranges, for the same reason as the audio — see [ChunkedDataSource]. */
    private const val CHUNK_BYTES = 1L * 1024 * 1024

    /** A googlevideo URL lives about six hours; a resolved stream is reused well inside that. */
    private const val STREAM_TTL_MS = 60 * 60 * 1000L

    private val scope = MainScope()

    private var controller: Player? = null
    private var player: ExoPlayer? = null
    private class Surface(val view: TextureView, val priority: Int)

    private val surfaces = ArrayList<Surface>()

    /** The surface in front: the highest priority, the latest of equals. */
    private fun top(): TextureView? = surfaces.lastOrNull { s -> surfaces.none { it.priority > s.priority } }?.view

    /** The video loaded (or loading) into [player]. */
    private var loadedId: String? = null

    /** BitChord Audio: the video [loadedId] names, and the track it is shown for. */
    private var loadedRef: com.music.bitchord.data.video.VideoRef? = null
    private var loadedFor: String? = null

    /** BitChord Audio: what to show for the playing track — its own video, or video mode's. */
    private data class Want(val mediaId: String, val ref: com.music.bitchord.data.video.VideoRef)
    private var loadJob: Job? = null
    private var syncJob: Job? = null
    private var releaseJob: Job? = null
    private var lastSeekAt = 0L
    private var seekStartedAt = 0L

    /** How far ahead of the audio a seek is aimed: what the last one took to land. */
    private var seekLeadMs = 250L

    /** Resolved streams by video: the stream, the height it was asked at, and when. */
    private val streams = LinkedHashMap<String, Triple<InnerTubeXResolver.VideoStream, Int, Long>>()

    /**
     * Tracks with no picture to show, so a track change back to one doesn't ask
     * again — until the session changes, which can open an age gate.
     */
    private val noPicture = HashSet<String>()

    /** Videos the age gate stopped, asked as age-restricted from the start. */
    private val ageGated = HashSet<String>()

    /** Clients whose picture googlevideo refused mid-playback, by video. */
    private val refused = HashMap<String, MutableSet<String>>()

    /** The client that minted the loaded picture, for [refused]. */
    private var loadedProfile: String? = null

    init {
        // Signing in is what opens an age gate, so every "no picture" verdict
        // and every URL minted for the old session is owed a fresh ask.
        scope.launch {
            InnerTubeXResolver.sessionGeneration.drop(1).collect {
                noPicture.clear()
                streams.clear()
                refused.clear()
                refresh()
            }
        }
        // Turning audio only on drops a picture already showing; turning it
        // off lets the current video's picture load.
        scope.launch {
            AppSettings.audioOnly.drop(1).collect { refresh() }
        }
        scope.launch {
            AppSettings.preferMusicOnly.drop(1).collect { refresh() }
        }
        // BitChord Audio: video mode turned on or off, or a video found for a track.
        scope.launch {
            com.music.bitchord.data.video.VideoMode.on.drop(1).collect {
                askVideoMode()
                refresh()
            }
        }
        scope.launch {
            com.music.bitchord.data.video.VideoMode.ready.drop(1).collect { refresh() }
        }
        scope.launch {
            // The video's sound is loaded with its picture in video mode
            // (silent), so switching to it is a change of volumes, not a
            // reload — a reload had to ask YouTube again, and lost the
            // picture whenever YouTube refused the second time.
            com.music.bitchord.data.video.VideoMode.originalAudio.drop(1).collect { applySound() }
        }
    }

    private val _shownVideoId = MutableStateFlow<String?>(null)

    /** The video whose frames are on screen now, once its first one has landed. */
    val shownVideoId: StateFlow<String?> = _shownVideoId.asStateFlow()

    private val _frameOn = MutableStateFlow<TextureView?>(null)

    /** The surface the last frame was drawn to. */
    internal val frameOn: StateFlow<TextureView?> = _frameOn.asStateFlow()

    private val _quality = MutableStateFlow(MusicVideoQualityUi())

    /** The heights the loaded video offers, the held one, and the one on screen. */
    val quality: StateFlow<MusicVideoQualityUi> = _quality.asStateFlow()

    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null

    /** What the loaded stream was asked for, so a failed high one can step down once. */
    private var loadedCap = 0

    /** Videos whose held height wouldn't decode here, played at [AUTO_HEIGHT] instead. */
    private val stepDown = HashSet<String>()

    private val _playing = MutableStateFlow(false)

    /** Whether the audio the picture follows is playing — what keeps the screen awake. */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _aspect = MutableStateFlow(16f / 9f)

    /** The picture's width over its height. */
    val aspect: StateFlow<Float> = _aspect.asStateFlow()

    private val controllerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            askVideoMode()
            refresh()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playing.value = isPlaying
            sync()
        }
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = sync()
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // A seek on the audio: follow it at once rather than on the next tick.
            lastSeekAt = 0L
            sync()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            _frameOn.value = top()
            _shownVideoId.value = loadedFor
            applySound()
            startAlign()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            val width = videoSize.width * videoSize.pixelWidthHeightRatio
            if (width > 0f && videoSize.height > 0) {
                _aspect.value = width / videoSize.height
                _quality.value = _quality.value.copy(activeHeight = videoSize.height)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && _dip.value) {
                scope.launch {
                    delay(120)
                    _dip.value = false
                }
            }
            if (playbackState == Player.STATE_READY && seekStartedAt > 0L) {
                val took = System.currentTimeMillis() - seekStartedAt
                seekLeadMs = ((seekLeadMs + took) / 2).coerceIn(0L, 1_500L)
                seekStartedAt = 0L
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = loadedId ?: return
            TrackLog.w(TAG, "video: playback failed at ${loadedCap}p (${error.errorCodeName})", about = id)
            streams.remove(id)
            // googlevideo turned this client's URL away: the next client in
            // InnerTubeX's catalog gets a turn, as the audio's probe does.
            val profile = loadedProfile
            if (profile != null && isRefusal(error)) {
                val skip = refused.getOrPut(id) { HashSet() }
                if (skip.add(profile) && skip.size <= MAX_REFUSED_CLIENTS) {
                    TrackLog.w(TAG, "video: $profile refused; asking the next client", about = id)
                    InnerTubeXResolver.onVideoRefused()
                    reload()
                    return
                }
            }
            // Above 1080p YouTube serves VP9 and AV1, which not every phone
            // decodes: one more try on the H.264 ladder before giving up.
            if (loadedCap > AUTO_HEIGHT && stepDown.add(id)) {
                reload()
                return
            }
            noPicture += id
            unload()
        }
    }

    /** The app's MediaController, whose position the picture follows. Main thread. */
    fun attach(controller: Player?) {
        if (this.controller === controller) return
        this.controller?.removeListener(controllerListener)
        this.controller = controller
        controller?.addListener(controllerListener)
        _playing.value = controller?.isPlaying == true
        askVideoMode()
        refresh()
    }

    /**
     * Puts the picture on [view] until the returned release is called, unless
     * a surface of higher [priority] holds it — the full player's outranks the
     * mini player's, whichever was mounted last. Main thread.
     */
    internal fun claim(view: TextureView, priority: Int): () -> Unit {
        releaseJob?.cancel()
        surfaces.removeAll { it.view === view }
        surfaces += Surface(view, priority)
        val app = view.context.applicationContext
        appContext = app
        if (prefs == null) {
            prefs = app.getSharedPreferences("music_video", Context.MODE_PRIVATE).also { stored ->
                val held = stored.getInt(KEY_QUALITY, 0).takeIf { it > 0 }
                _quality.value = _quality.value.copy(held = held)
            }
        }
        val exo = ensurePlayer(app)
        if (top() === view) {
            _frameOn.value = null
            exo.setVideoTextureView(view)
            repaint()
        }
        refresh()
        return release@{
            val wasTop = top() === view
            surfaces.removeAll { it.view === view }
            if (!wasTop) return@release
            if (_frameOn.value === view) _frameOn.value = null
            val next = top()
            if (next != null) {
                exo.setVideoTextureView(next)
                repaint()
            } else {
                exo.clearVideoTextureView(view)
                releaseJob = scope.launch {
                    delay(RELEASE_GRACE_MS)
                    if (surfaces.isEmpty()) unload()
                }
            }
        }
    }

    private fun ensurePlayer(context: Context): ExoPlayer = player ?: ExoPlayer.Builder(context)
        .build()
        .apply {
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            addListener(playerListener)
        }
        .also { player = it }

    /** A paused decoder handed a new surface draws nothing until asked to. */
    private fun repaint() {
        val exo = player ?: return
        if (!exo.playWhenReady && exo.playbackState != Player.STATE_IDLE) {
            exo.seekTo(exo.currentPosition)
        }
    }

    /** The track the picture should be of: the controller's, when it is a music video. */
    private fun wanted(): Want? {
        val song = controller?.currentMediaItem?.toSong() ?: return null
        // BitChord Audio: video mode — your choice, so neither audio only nor
        // music-only playback keeps the picture away.
        if (com.music.bitchord.data.video.VideoMode.on.value) {
            com.music.bitchord.data.video.VideoMode.cached(song.videoId)
                ?.takeIf { it.key !in noPicture }
                ?.let { return Want(song.videoId, it) }
        }
        // BitChord Audio: with audio only on, no picture is ever fetched or
        // decoded — not for a video still waiting to be swapped for its
        // release, and not for one the listener switched to by hand.
        if (AppSettings.audioOnly.value) return null
        return song.takeIf {
            it.isVideo && it.videoId !in noPicture && MusicOnlyPlayback.showsPicture(it.videoId)
        }?.let { Want(it.videoId, com.music.bitchord.data.video.VideoRef(com.music.bitchord.data.video.VideoRef.Site.YOUTUBE, it.videoId)) }
    }

    /** BitChord Audio: in video mode, the playing track's video is looked up as it starts. */
    private fun askVideoMode() {
        if (!com.music.bitchord.data.video.VideoMode.on.value) return
        val song = controller?.currentMediaItem?.toSong() ?: return
        com.music.bitchord.data.video.VideoMode.request(song)
    }

    private fun refresh() {
        val want = if (surfaces.isEmpty()) null else wanted()
        if (want == null) {
            // The picture is kept through the grace period after its last
            // surface goes; a track change to one with none drops it now.
            if (surfaces.isNotEmpty() || wanted()?.ref?.key != loadedId) unload()
            return
        }
        if (want.ref.key == loadedId) {
            if (loadedFor != want.mediaId) {
                loadedFor = want.mediaId
                if (_shownVideoId.value != null) _shownVideoId.value = want.mediaId
            }
            startSync()
            return
        }
        load(want.ref, want.mediaId)
    }

    private fun reload() {
        val ref = loadedRef ?: return
        val forId = loadedFor ?: return
        load(ref, forId)
    }

    /**
     * BitChord Audio: where the picture should be for the audio at [audioMs]:
     * through YouTube's own song→video time map when video mode has one.
     */
    private fun videoPosition(audioMs: Long): Long {
        val ref = loadedRef ?: return audioMs
        val forId = loadedFor ?: return audioMs
        if (ref.key == forId) return audioMs
        val vm = com.music.bitchord.data.video.VideoMode
        vm.timeMap(forId, ref)?.let { return it.map(audioMs) }
        // Measured: the song's notes found in the video's own sound — a live
        // take, another cut (see [VideoAligner]).
        com.music.bitchord.playback.smart.VideoAligner.timeline(forId, ref.key)?.let { return it.map(audioMs) }
        // No pairing from YouTube: the song runs through the video minus the
        // parts SponsorBlock marks as not music (intros, skits, dialogue).
        if (ref.site == com.music.bitchord.data.video.VideoRef.Site.YOUTUBE) return vm.skipNonMusic(ref.id, audioMs)
        return audioMs
    }

    /** BitChord Audio: the sync measurement waiting for the picture to be up — see [VideoAligner]. */
    private var pendingAlign: (() -> Unit)? = null
    private var alignJob: Job? = null

    private fun startAlign() {
        val go = pendingAlign ?: return
        pendingAlign = null
        alignJob?.cancel()
        go()
    }

    private val _dip = MutableStateFlow(false)

    /**
     * BitChord Audio: true for the moment the picture jumps to a new place
     * (a sync found later, a large drift) — the surface fades through it.
     */
    val dip: StateFlow<Boolean> = _dip.asStateFlow()

    /** BitChord Audio: the video's own sound in place of the song's — see [VideoMode.originalAudio]. */
    private var videoSound = false

    private fun applySound() {
        val want = com.music.bitchord.data.video.VideoMode.originalAudio.value &&
            com.music.bitchord.data.video.VideoMode.on.value && loadedRef != null && loadedFor != null
        if (want == videoSound) return
        videoSound = want
        player?.volume = if (want) 1f else 0f
        controller?.volume = if (want) 0f else 1f
        TrackLog.d(TAG, "video: ${if (want) "the video's own sound" else "the song's sound"}")
    }

    /**
     * Holds the picture at [height] from now on, or lets it choose with null —
     * the web player's quality box. The playing video reloads at once, at the
     * moment the audio is at.
     */
    fun setQuality(height: Int?) {
        prefs?.edit()?.apply {
            if (height == null) remove(KEY_QUALITY) else putInt(KEY_QUALITY, height)
        }?.apply()
        _quality.value = _quality.value.copy(held = height)
        val id = loadedId ?: return
        streams.remove(id)
        stepDown.remove(id)
        reload()
    }

    /** The height asked for: the held one, else what Auto opens at for this connection. */
    private fun capFor(videoId: String): Int {
        val metered = AppSettings.meteredConnection.value == true
        val auto = if (metered) METERED_HEIGHT else AUTO_HEIGHT
        val held = _quality.value.held ?: return auto
        return if (videoId in stepDown) minOf(held, AUTO_HEIGHT) else held
    }

    /** A refusal from googlevideo itself, as opposed to a decoder or network failure. */
    private fun isRefusal(error: PlaybackException): Boolean =
        generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .any { it.responseCode in REFUSAL_CODES }

    /**
     * [videoId]'s picture, asked plainly and then — when that found nothing —
     * as age-restricted, which InnerTubeX needs told: see
     * [InnerTubeXResolver.extractVideo]. A video that needed it is asked that
     * way from the start next time.
     */
    private suspend fun resolve(videoId: String, cap: Int): InnerTubeXResolver.VideoStream? {
        val skip = refused[videoId].orEmpty().toSet()
        if (videoId !in ageGated) {
            val plain = try {
                withContext(Dispatchers.IO) { InnerTubeXResolver.extractVideo(videoId, cap, skip) }
            } catch (e: StreamResolveException) {
                if (e.reason !in ESCALATED_REASONS) throw e
                null
            }
            if (plain != null) return plain
            TrackLog.d(TAG, "video: nothing asked plainly; asking as age-restricted", about = videoId)
        }
        return withContext(Dispatchers.IO) {
            InnerTubeXResolver.extractVideo(videoId, cap, skip, ageRestricted = true)
        }?.also { ageGated += videoId }
    }

    private fun load(ref: com.music.bitchord.data.video.VideoRef, forId: String) {
        unload()
        val videoId = ref.key
        loadedId = videoId
        loadedRef = ref
        loadedFor = forId
        val cap = capFor(videoId)
        loadedCap = cap
        if (ref.site == com.music.bitchord.data.video.VideoRef.Site.BILIBILI) {
            loadJob = scope.launch { loadBilibili(ref.id, videoId) }
            return
        }
        loadJob = scope.launch {
            val stream = try {
                cachedStream(videoId, cap)
                    ?: resolve(videoId, cap)?.also { streams[videoId] = Triple(it, cap, System.currentTimeMillis()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: StreamResolveException) {
                TrackLog.w(TAG, "video: no picture (${e.reason}): ${e.message}", about = videoId)
                if (loadedId != videoId) return@launch
                loadedId = null
                // Settled until something changes it: the age gate by a sign-in
                // (which clears [noPicture]), the rest not at all. A network
                // failure is asked again on the next surface or track change.
                if (e.reason != StreamResolveException.Reason.NETWORK) {
                    noPicture += videoId
                    // BitChord Audio: video mode moves on to the song's next video.
                    loadedFor?.let { forId -> if (forId != videoId || com.music.bitchord.data.video.VideoMode.on.value) com.music.bitchord.data.video.VideoMode.failed(forId, ref) }
                }
                return@launch
            } catch (e: Exception) {
                // Not remembered as pictureless: the next surface or track
                // change asks again.
                TrackLog.w(TAG, "video: couldn't resolve the picture: ${e.message}", about = videoId)
                if (loadedId == videoId) loadedId = null
                return@launch
            }
            if (loadedId != videoId) return@launch
            // A catalogue track's "video" is its cover held still; nothing to show.
            if (stream == null || stream.aspect < MIN_VIDEO_ASPECT) {
                noPicture += videoId
                loadedFor?.let { forId -> if (com.music.bitchord.data.video.VideoMode.on.value) com.music.bitchord.data.video.VideoMode.failed(forId, ref) }
                loadedId = null
                return@launch
            }
            val exo = player ?: return@launch
            val upstream = OkHttpDataSource.Factory(Http.client)
                .setDefaultRequestProperties(stream.headers)
            val factory = ProgressiveMediaSource.Factory(ChunkedDataSource.Factory(upstream, CHUNK_BYTES))
            val picture = factory.createMediaSource(MediaItem.fromUri(stream.url))
            // BitChord Audio: the video's own sound rides along only while asked for.
            val sound = stream.audioUrl?.takeIf { com.music.bitchord.data.video.VideoMode.on.value }
            val source = if (sound != null) {
                androidx.media3.exoplayer.source.MergingMediaSource(picture, factory.createMediaSource(MediaItem.fromUri(sound)))
            } else picture
            if (stream.aspect > 0f) _aspect.value = stream.aspect
            _quality.value = _quality.value.copy(heights = stream.heights, activeHeight = stream.height)
            // BitChord Audio: video mode's picture for another recording than
            // the one playing, with no pairing from YouTube: measure the sync.
            val forId = loadedFor
            val itsSound = stream.audioUrl
            val ctx = appContext
            if (forId != null && forId != videoId && itsSound != null && ctx != null &&
                com.music.bitchord.data.video.VideoMode.timeMap(forId, ref) == null
            ) {
                controller?.currentMediaItem?.toSong()?.takeIf { it.videoId == forId }?.let { song ->
                    pendingAlign = {
                    com.music.bitchord.playback.smart.VideoAligner.request(ctx, song, videoId, itsSound, stream.headers) {
                        val yt = com.music.bitchord.data.video.VideoMode.youTubeSongOf(song) ?: return@request null
                        val got = withContext(Dispatchers.IO) { InnerTubeXResolver.extract(yt, 64) } ?: return@request null
                        got.url to got.headers
                    }
                    }
                    // Started once the picture is up (or after 5 s regardless):
                    // the measuring mustn't hold the video back.
                    alignJob?.cancel()
                    alignJob = scope.launch {
                        delay(5_000)
                        startAlign()
                    }
                }
            }
            loadedProfile = stream.profileId
            val start = videoPosition(controller?.currentPosition?.coerceAtLeast(0L) ?: 0L)
            exo.setMediaSource(source, start + seekLeadMs)
            exo.playWhenReady = controller?.isPlaying == true
            exo.prepare()
            lastSeekAt = System.currentTimeMillis()
            seekStartedAt = lastSeekAt
            startSync()
        }
    }

    /** BitChord Audio: a bilibili video's MP4, the same way. */
    private suspend fun loadBilibili(bvid: String, key: String) {
        val stream = runCatching { com.music.bitchord.data.video.Bilibili.stream(bvid) }.getOrNull()
        if (loadedId != key) return
        if (stream == null) {
            TrackLog.w(TAG, "video: no bilibili stream", about = key)
            noPicture += key
            loadedId = null
            return
        }
        val exo = player ?: return
        val upstream = OkHttpDataSource.Factory(Http.client).setDefaultRequestProperties(stream.headers)
        val source = ProgressiveMediaSource.Factory(ChunkedDataSource.Factory(upstream, CHUNK_BYTES))
            .createMediaSource(MediaItem.fromUri(stream.url))
        if (stream.height > 0) _aspect.value = stream.width.toFloat() / stream.height
        _quality.value = _quality.value.copy(heights = emptyList(), activeHeight = stream.height)
        loadedProfile = null
        val start = videoPosition(controller?.currentPosition?.coerceAtLeast(0L) ?: 0L)
        exo.setMediaSource(source, start + seekLeadMs)
        exo.playWhenReady = controller?.isPlaying == true
        exo.prepare()
        lastSeekAt = System.currentTimeMillis()
        seekStartedAt = lastSeekAt
        startSync()
    }

    private fun cachedStream(videoId: String, cap: Int): InnerTubeXResolver.VideoStream? {
        val (stream, askedFor, at) = streams[videoId] ?: return null
        if (askedFor != cap || System.currentTimeMillis() - at > STREAM_TTL_MS) {
            streams.remove(videoId)
            return null
        }
        return stream
    }

    private fun unload() {
        loadJob?.cancel()
        loadJob = null
        syncJob?.cancel()
        syncJob = null
        loadedId = null
        loadedRef = null
        loadedFor = null
        loadedProfile = null
        _shownVideoId.value = null
        pendingAlign = null
        alignJob?.cancel()
        _dip.value = false
        applySound()
        _frameOn.value = null
        _quality.value = _quality.value.copy(heights = emptyList(), activeHeight = null)
        player?.let {
            it.stop()
            it.clearMediaItems()
        }
        while (streams.size > 8) streams.remove(streams.keys.first())
    }

    private fun startSync() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            while (isActive) {
                sync()
                delay(SYNC_EVERY_MS)
            }
        }
    }

    /** Holds the picture to the audio: same play state, same rate, the same moment. */
    private fun sync() {
        val audio = controller ?: return
        val exo = player ?: return
        if (loadedId == null || exo.playbackState == Player.STATE_IDLE || exo.playbackState == Player.STATE_ENDED) return
        val speed = audio.playbackParameters.speed
        val audioPos = videoPosition(audio.currentPosition)
        val now = System.currentTimeMillis()
        if (!audio.isPlaying) {
            exo.playWhenReady = false
            setSpeed(exo, speed)
            // Paused: the exact frame, so a scrub shows where it lands.
            if (abs(exo.currentPosition - audioPos) > 40L && now - lastSeekAt > 120L) seek(exo, audioPos)
            return
        }
        exo.playWhenReady = true
        if (exo.playbackState == Player.STATE_BUFFERING) return
        val drift = exo.currentPosition - audioPos
        // BitChord Audio: a video that isn't the playing recording is held
        // more loosely — re-placed only when it's more than a second out, and
        // at most every 5 s; the rest is caught up by playing a touch faster
        // or slower. Its timeline is approximate, and a picture re-placed
        // every time it wobbled is the stutter.
        val mapped = loadedFor != null && loadedFor != loadedId
        val seekAt = if (mapped) MAPPED_SEEK_DRIFT_MS else SEEK_DRIFT_MS
        val cooldown = if (mapped) MAPPED_SEEK_COOLDOWN_MS else SEEK_COOLDOWN_MS
        if (abs(drift) > seekAt) {
            if (now - lastSeekAt > cooldown) {
                // A jump of seconds (the sync arriving, a scrub): faded through.
                if (abs(drift) > 2_000L && _shownVideoId.value != null) {
                    _dip.value = true
                    val target = audioPos + seekLeadMs
                    lastSeekAt = now
                    scope.launch {
                        delay(180)
                        seek(exo, videoPosition(audio.currentPosition) + seekLeadMs)
                        delay(2_000)
                        _dip.value = false
                    }
                    if (target < 0) return
                } else {
                    seek(exo, audioPos + seekLeadMs)
                }
            }
            return
        }
        // Catch up (or wait) over about a second, rather than jumping —
        // gently while its own sound is heard, where a rate change is a pitch change.
        val most = if (videoSound) 0.02f else 0.1f
        val nudge = if (abs(drift) > IGNORE_DRIFT_MS) (-drift / 1000f).coerceIn(-most, most) else 0f
        setSpeed(exo, speed * (1f + nudge))
    }

    private fun seek(exo: ExoPlayer, positionMs: Long) {
        lastSeekAt = System.currentTimeMillis()
        seekStartedAt = lastSeekAt
        exo.seekTo(positionMs.coerceAtLeast(0L))
    }

    private fun setSpeed(exo: ExoPlayer, speed: Float) {
        if (abs(exo.playbackParameters.speed - speed) > 0.005f) {
            exo.playbackParameters = PlaybackParameters(speed)
        }
    }

    /** Narrower than this is a cover held still, not a picture worth showing. */
    private const val MIN_VIDEO_ASPECT = 1.2f

    /** Where Auto opens, as on the web player: the top of YouTube's H.264 ladder. */
    private const val AUTO_HEIGHT = 1080
    private const val METERED_HEIGHT = 480
    private const val KEY_QUALITY = "quality"

    /** The same as the audio's: googlevideo's ways of refusing a URL. */
    private val REFUSAL_CODES = setOf(403, 404, 410)

    /** How many refused clients a picture is worth before it's given up — the audio's three. */
    private const val MAX_REFUSED_CLIENTS = 3

    /**
     * What the plain ask fails with when the age gate is why: InnerTubeX names
     * the gate only when no client answered at all, and otherwise reports the
     * clients that answered without a picture as no playable stream.
     */
    private val ESCALATED_REASONS = setOf(
        StreamResolveException.Reason.AGE_RESTRICTED,
        StreamResolveException.Reason.NO_PLAYABLE_STREAM,
    )
}

/**
 * Where the playing music video shows: [videoId]'s picture, fitted into or
 * cropped to these bounds. Shows nothing until a frame of that video has
 * landed here, so a caller keeps its still artwork underneath and the picture
 * fades in over it. Holds the picture only while the app is on screen.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun AndroidMusicVideo(
    videoId: String,
    fit: Boolean,
    onRenderedChanged: (Boolean) -> Unit,
    modifier: Modifier,
    /** Which surface wins when two are mounted: the full player's, over the mini player's. */
    priority: Int = 1,
) {
    val foreground = rememberIsForeground()
    var view by remember { mutableStateOf<TextureView?>(null) }
    DisposableEffect(view, foreground, priority) {
        val target = view
        val release = if (target != null && foreground) MusicVideoEngine.claim(target, priority) else null
        onDispose { release?.invoke() }
    }

    val shown by MusicVideoEngine.shownVideoId.collectAsStateWithLifecycle()
    val frameOn by MusicVideoEngine.frameOn.collectAsStateWithLifecycle()
    val aspect by MusicVideoEngine.aspect.collectAsStateWithLifecycle()
    val rendered = view != null && frameOn === view && shown == videoId
    val playing by MusicVideoEngine.playing.collectAsStateWithLifecycle()

    // A video being watched keeps the screen awake, as any video player does —
    // only while it plays, and only on the surface its frames are reaching.
    val awake = rendered && playing
    LaunchedEffect(view, awake) { view?.keepScreenOn = awake }

    val report by rememberUpdatedState(onRenderedChanged)
    LaunchedEffect(rendered) { report(rendered) }
    DisposableEffect(Unit) { onDispose { report(false) } }

    val dip by MusicVideoEngine.dip.collectAsStateWithLifecycle()
    val alpha by animateFloatAsState(
        targetValue = if (rendered && !dip) 1f else 0f,
        animationSpec = tween(durationMillis = if (dip) 160 else 320),
        label = "musicVideoAlpha",
    )

    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                TextureView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    // Blend rather than punch a hole: the still art stays
                    // visible underneath for the length of the fade.
                    isOpaque = false
                    this.alpha = 0f
                    view = this
                }
            },
            update = { it.alpha = alpha },
            onRelease = { if (view === it) view = null },
            // The picture at its own shape: letterboxed into the bounds, or
            // overflowing them and cropped by the clip above.
            modifier = Modifier.layout { measurable, constraints ->
                val width = constraints.maxWidth
                val height = if (constraints.hasBoundedHeight) {
                    constraints.maxHeight
                } else {
                    (width / aspect).roundToInt()
                }
                val boundsAspect = width.toFloat() / height.coerceAtLeast(1)
                val wider = boundsAspect > aspect
                val (w, h) = if (wider == fit) {
                    (height * aspect).roundToInt() to height
                } else {
                    width to (width / aspect).roundToInt()
                }
                val placeable = measurable.measure(Constraints.fixed(w.coerceAtLeast(1), h.coerceAtLeast(1)))
                layout(width, height) {
                    placeable.place((width - w) / 2, (height - h) / 2)
                }
            },
        )
    }
}

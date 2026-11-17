package com.music.bitchord.playback.tv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.playback.cast.CastSink
import com.music.bitchord.playback.cast.RemoteSpeaker
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * BitChord Audio: the LG TV app as the speaker, on the same model as Cast and
 * the watch — the phone's player keeps the queue and is held paused, the TV
 * plays it and owns the clock.
 *
 * Unlike the watch, the TV is a whole player: it is handed the queue from the
 * playing track on ([AHEAD] of them) and moves through it by itself, so it
 * plays on with the phone locked, away or switched off. The phone follows it
 * when it moves on, and loads it again when the phone's queue changes under
 * the playing track (an append — autoplay topping up — is left alone: the TV
 * asks for more by running out, and the phone's next move sends it).
 *
 * Only YouTube tracks go: the TV plays from YouTube, not from this phone's
 * files or other sources. Main thread throughout.
 */
class TvSpeaker(
    private val localPlayer: () -> ExoPlayer?,
    private val onChanged: () -> Unit,
) : RemoteSpeaker {

    override var sink: CastSink? = null

    override var active = false
        private set

    private var now: TvLink.NowOnTv? = null
    private var tvStatus: String = ""
    private var wantsPlay = false
    private var loading = false
    private var finished = false
    private var requestedPositionMs = 0L
    private var requestedAt = 0L

    /** The video ids the TV holds, in its queue's order. */
    private var onTv: List<String> = emptyList()

    override val playWhenReady: Boolean get() = wantsPlay
    override val isPlaying: Boolean get() = active && !loading && now?.playing == true

    override val playbackState: Int
        get() = when {
            loading || tvStatus == "loading" || tvStatus == "buffering" -> Player.STATE_BUFFERING
            finished -> Player.STATE_ENDED
            else -> Player.STATE_READY
        }

    override val positionMs: Long
        get() {
            val s = now
            if (s == null || loading || SystemClock.elapsedRealtime() - requestedAt < SEEK_GRACE_MS) return requestedPositionMs
            if (!s.playing) return s.positionMs
            val ran = SystemClock.elapsedRealtime() - s.at
            return (s.positionMs + ran).let { if (s.durationMs > 0) it.coerceAtMost(s.durationMs) else it }
        }

    override val durationMs: Long get() = now?.durationMs?.takeIf { it > 0 } ?: C.TIME_UNSET

    override val deviceVolume: Int get() = ((now?.volume ?: 1f) * 100).roundToInt()

    fun currentId(): String? = now?.videoId ?: onTv.firstOrNull()

    // ---- start / stop -------------------------------------------------------------

    fun start() {
        if (active) return
        val player = localPlayer() ?: return
        if (player.mediaItemCount == 0) {
            // Nothing queued here: the TV just becomes the remote's target.
            active = true
            sink?.onRemoteRouteChanged()
            onChanged()
            return
        }
        active = true
        val position = player.currentPosition.coerceAtLeast(0L)
        // Quiet and idle, as for the watch: a paused player would keep
        // buffering every track the TV moves it to.
        player.pause()
        player.stop()
        // Picking the TV is asking it to play, even from a paused phone.
        load(player.currentMediaItem, position, play = true)
        sink?.onRemoteRouteChanged()
        onChanged()
    }

    fun stop(resumeHere: Boolean) {
        if (!active) return
        val s = now
        val wasPlaying = wantsPlay
        val position = positionMs
        TvLink.send(JSONObject().put("type", "pause"))
        active = false
        loading = false
        finished = false
        onTv = emptyList()
        now = null
        localPlayer()?.let { player ->
            val index = s?.videoId?.let { indexOf(player, it) }
            if (index != null) player.seekTo(index, position) else player.seekTo(position)
            if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) player.prepare()
            if (resumeHere && wasPlaying) player.play()
        }
        wantsPlay = false
        sink?.onRemoteRouteChanged()
        onChanged()
    }

    /** The queue sent again from where the TV is (after a video was shown in its place). */
    fun reload() {
        if (!active) return
        val player = localPlayer() ?: return
        load(player.currentMediaItem, positionMs, play = true)
    }

    // ---- transport ----------------------------------------------------------------

    override fun play() {
        if (!active) return
        wantsPlay = true
        if (finished) {
            finished = false
            localPlayer()?.currentMediaItem?.let { load(it, 0L, play = true) }
            return
        }
        command("resume")
        onChanged()
    }

    override fun pause() {
        if (!active) return
        wantsPlay = false
        command("pause")
        onChanged()
    }

    override fun seekTo(positionMs: Long) {
        if (!active) return
        requestedPositionMs = positionMs.coerceAtLeast(0L)
        requestedAt = SystemClock.elapsedRealtime()
        TvLink.send(JSONObject().put("type", "seek").put("positionSec", requestedPositionMs / 1000.0))
        onChanged()
    }

    override fun setVolume(fraction: Float) {
        if (!active) return
        TvLink.send(JSONObject().put("type", "volume").put("value", fraction.coerceIn(0f, 1f).toDouble()))
    }

    // ---- the phone's player moved ----------------------------------------------------

    override fun onLocalTransition(item: MediaItem?, reason: Int) {
        if (!active || item == null) return
        if (item.mediaId == now?.videoId && !loading) return
        val at = onTv.indexOf(item.mediaId)
        if (at >= 0 && !loading) {
            finished = false
            requestedPositionMs = 0L
            requestedAt = SystemClock.elapsedRealtime()
            TvLink.send(JSONObject().put("type", "jump").put("index", at))
            onChanged()
        } else {
            load(item, 0L, play = true)
        }
    }

    override fun onLocalQueueChanged() {
        if (!active) return
        main.removeCallbacks(queueCheck)
        main.postDelayed(queueCheck, 1_500)
    }

    private val queueCheck = Runnable {
        val player = localPlayer() ?: return@Runnable
        val playing = now?.videoId ?: return@Runnable
        val index = indexOf(player, playing) ?: return@Runnable
        val ahead = idsFrom(player, index)
        val held = onTv.drop(onTv.indexOf(playing).coerceAtLeast(0))
        val common = minOf(ahead.size, held.size)
        // Only a change in what is already on the TV is worth a reload.
        if (ahead.take(common) != held.take(common)) load(player.getMediaItemAt(index), positionMs, play = wantsPlay)
    }

    override fun onLocalRepeatChanged(repeatMode: Int) = Unit

    override fun onLocalStartedPlaying(player: Player) {
        if (!active) return
        player.pause()
        play()
    }

    // ---- the TV moved -------------------------------------------------------------------

    internal fun onTvState(state: TvLink.NowOnTv?, status: String) {
        if (!active) return
        val before = now
        now = state
        tvStatus = status
        requestedAt = 0L
        if (state != null && (state.videoId in onTv)) loading = false
        if (!loading && state != null) wantsPlay = state.playing || status == "loading" || status == "buffering"

        val player = localPlayer()
        if (player != null && state != null && state.videoId.isNotEmpty() && player.currentMediaItem?.mediaId != state.videoId) {
            // The TV moved on, or somebody used its remote: the phone's queue follows.
            indexOf(player, state.videoId)?.let { player.seekTo(it, 0L) }
        }
        if (state != null && state.videoId != before?.videoId) TvLink.lyricsResend()
        // The TV ran out of the tracks it was given: the phone's queue goes on.
        val atEnd = state != null && !state.playing && state.durationMs > 0 &&
            state.positionMs >= state.durationMs - 1_500 && state.videoId == onTv.lastOrNull()
        if (!loading && atEnd && player != null) {
            when {
                player.hasNextMediaItem() -> player.seekToNextMediaItem()
                player.repeatMode == Player.REPEAT_MODE_ALL && player.mediaItemCount > 0 -> player.seekTo(0, 0L)
                else -> { wantsPlay = false; finished = true }
            }
        }
        sink?.onRemotePlaybackChanged()
        if (before?.volume != state?.volume) sink?.onRemoteVolumeChanged()
        onChanged()
    }

    // ---- loading ------------------------------------------------------------------------

    private fun load(item: MediaItem?, positionMs: Long, play: Boolean) {
        val player = localPlayer() ?: return
        item ?: return
        val index = indexOf(player, item.mediaId) ?: player.currentMediaItemIndex
        if (index !in 0 until player.mediaItemCount) return
        val tracks = JSONArray()
        val ids = mutableListOf<String>()
        val last = (index + AHEAD).coerceAtMost(player.mediaItemCount - 1)
        for (i in index..last) {
            val it = player.getMediaItemAt(i)
            if (!YOUTUBE_ID.matches(it.mediaId)) continue
            ids += it.mediaId
            tracks.put(
                JSONObject()
                    .put("videoId", it.mediaId)
                    .put("title", it.mediaMetadata.title?.toString().orEmpty())
                    .put("artist", it.mediaMetadata.artist?.toString().orEmpty())
                    .put("album", it.mediaMetadata.albumTitle?.toString())
                    .put("thumbnail", it.mediaMetadata.artworkUri?.toString()),
            )
        }
        if (ids.isEmpty()) return
        // The first track sent is where the phone is only if it is a YouTube one.
        val startsHere = ids.first() == item.mediaId
        wantsPlay = play
        loading = true
        finished = false
        requestedPositionMs = if (startsHere) positionMs else 0L
        requestedAt = SystemClock.elapsedRealtime()
        onTv = ids
        TvLink.send(
            JSONObject()
                .put("type", "play")
                .put("tracks", tracks)
                .put("index", 0)
                .put("positionSec", requestedPositionMs / 1000.0)
                .put("title", "BitChord Audio"),
            onFailed = { if (active) TvLink.disconnect(resumeHere = false) },
        )
        main.postDelayed({ TvLink.lyricsResend() }, 1_500)
        onChanged()
    }

    private fun idsFrom(player: Player, index: Int): List<String> {
        val last = (index + AHEAD).coerceAtMost(player.mediaItemCount - 1)
        return (index..last).map { player.getMediaItemAt(it).mediaId }.filter { YOUTUBE_ID.matches(it) }
    }

    private fun command(type: String) = TvLink.send(JSONObject().put("type", type))

    private fun indexOf(player: Player, mediaId: String): Int? {
        val from = player.currentMediaItemIndex.coerceAtLeast(0)
        return (from until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
            ?: (0 until from).firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
    }

    private val main = Handler(Looper.getMainLooper())

    private companion object {
        /** Tracks handed to the TV past the playing one. */
        const val AHEAD = 60

        const val SEEK_GRACE_MS = 1_500L

        val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}

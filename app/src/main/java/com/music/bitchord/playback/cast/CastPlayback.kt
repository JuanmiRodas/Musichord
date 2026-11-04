package com.music.bitchord.playback.cast

import android.net.Uri
import android.os.SystemClock
import com.music.bitchord.data.DebugLog as Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueData
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.api.Result
import com.google.android.gms.common.images.WebImage
import com.music.bitchord.R
import com.music.bitchord.playback.toMediaItem
import com.music.bitchord.playback.toSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/** A stream the receiver can open by itself: a URL and what it holds. */
class CastStream(val url: String, val mimeType: String, val artworkUrl: String? = null)

/**
 * BitChord Audio: tracks a receiver failed to play from their direct address;
 * from then on the phone serves them itself (see `CastStreamServer`).
 */
object CastRouting {
    val proxyOnly: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
}

/** Told when the receiver changes something the session player reports as its own. */
interface CastSink {
    /** Play state, buffering, or the playhead moved. */
    fun onRemotePlaybackChanged()

    /** The music moved to a receiver, or back. */
    fun onRemoteRouteChanged()

    /** The receiver's volume changed, from here or from its own remote. */
    fun onRemoteVolumeChanged()
}

/**
 * What plays on the receiver while a Cast session is up.
 *
 * The phone's player stays the owner of the *queue* — every row, reorder,
 * autoplay refill, lyric and widget keeps reading it exactly as before — and is
 * held paused while the receiver is the speaker. The receiver is the owner of
 * the *clock*: [positionMs], [isPlaying] and the rest come from it, and
 * the session player reports them in place of the paused local ones, so the
 * app, the lock screen and the notification all show what the receiver is
 * doing. Two things keep the halves in step:
 *
 *  - The phone moving to another track (a skip, a tap in the queue) loads that
 *    track on the receiver.
 *  - The receiver moving to another track (it finished one, or somebody used
 *    its own remote) moves the phone's player to the same one.
 *
 * Which is which is told by comparing media ids rather than by a flag: the
 * player reports its own moves a beat after they happen, so a flag raised
 * around a seek would already be down by the time the report arrives, whereas
 * "the receiver is already on this track" stays true.
 *
 * A receiver cannot ask the service to resolve a `bitchord://` address, so the
 * stream URL is resolved here, on the phone, and the receiver is handed the
 * finished URL. It cannot send the headers a resolver may have wanted either,
 * and it cannot reach a file on this phone at all; those tracks are refused
 * with a message rather than loaded and left silent.
 *
 * The receiver's queue holds the playing track and a few after it, so that it
 * moves on by itself with no gap and with the phone asleep. Everything runs on
 * the main thread.
 */
class CastPlayback(
    private val scope: CoroutineScope,
    private val localPlayer: () -> ExoPlayer?,
    private val resolve: suspend (MediaItem) -> CastStream?,
    private val say: (Int) -> Unit,
) : RemoteSpeaker {
    override var sink: CastSink? = null

    init {
        scope.launch {
            CastController.volume.drop(1).collect { if (active) sink?.onRemoteVolumeChanged() }
        }
    }

    /** Whether the receiver is the speaker. Everything else here is meaningful only while it is. */
    override var active = false
        private set

    /** Set by [CastController.disconnect]: the listener chose this phone, so keep playing on it. */
    var resumeHereOnEnd = false

    private var session: CastSession? = null
    private var client: RemoteMediaClient? = null

    /** The listener wants sound; what [playWhenReady] reports. */
    private var wantsPlay = false

    /** A track is on its way to the receiver and nothing has come back yet. */
    private var loading = false

    /** The receiver ran out of queue. */
    private var finished = false

    /** Where a load or a seek asked the receiver to be, until it says where it is. */
    private var requestedPositionMs = 0L
    private var requestedAt = 0L

    private var loadGeneration = 0
    private var loadJob: Job? = null
    private var reconcileJob: Job? = null
    private var reconcileAgain = false

    private var lastItemId = MediaQueueItem.INVALID_ITEM_ID

    /** What the receiver was doing when its session started to end. */
    private var endPositionMs = 0L
    private var endMediaId: String? = null
    private var endWasPlaying = false

    override val isPlaying: Boolean
        get() = active && !loading && target == null && client?.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_PLAYING

    /**
     * BitChord Audio: the track the speaker was just sent to (a skip, a
     * load), until it says it is playing it. Meanwhile the phone shows that
     * track at its start, waiting — not the old one's clock running on (the
     * app "playing" while the speaker was stuck, then starting from 0).
     */
    @Volatile private var target: String? = null
    private var targetWatch: Job? = null
    private val retriedFromPhone = HashSet<String>()

    private fun expect(mediaId: String?) {
        target = mediaId
        targetWatch?.cancel()
        if (mediaId == null) return
        targetWatch = scope.launch {
            delay(TARGET_STUCK_MS)
            if (target != mediaId || !active) return@launch
            val player = localPlayer() ?: return@launch
            val index = indexOfMediaId(player, mediaId) ?: return@launch
            if (retriedFromPhone.add(mediaId)) {
                // Stuck: the speaker couldn't get the stream it was given (a
                // slow or refused link). Once more, served by the phone.
                Log.w(TAG, "cast: speaker still not playing ${player.getMediaItemAt(index).mediaMetadata.title} after ${TARGET_STUCK_MS}ms; sending it again from the phone")
                CastRouting.proxyOnly.add(mediaId)
                streams.remove(mediaId)
                load(player.getMediaItemAt(index), requestedPositionMs, play = true)
            } else {
                Log.w(TAG, "cast: speaker still not playing $mediaId after a second try; giving up on it")
                target = null
                loading = false
                say(R.string.cast_track_unsupported)
                sink?.onRemotePlaybackChanged()
            }
        }
    }

    override val playWhenReady: Boolean get() = wantsPlay

    /** In [Player]'s own terms, so the session player can answer with it directly. */
    override val playbackState: Int
        get() = when {
            loading || target != null -> Player.STATE_BUFFERING
            finished -> Player.STATE_ENDED
            else -> when (client?.mediaStatus?.playerState) {
                MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING -> Player.STATE_BUFFERING
                else -> Player.STATE_READY
            }
        }

    override val positionMs: Long
        get() {
            val live = client?.takeIf { !loading && target == null }?.approximateStreamPosition
            val asked = requestedPositionMs
            // A seek is asked of the receiver and answered a moment later; in
            // between, its own clock still reads the old place and the bar would
            // spring back to it.
            return if (live == null || SystemClock.elapsedRealtime() - requestedAt < SEEK_GRACE_MS) {
                asked
            } else {
                live.coerceAtLeast(0L)
            }
        }

    override val durationMs: Long
        get() = client?.streamDuration?.takeIf { it > 0 } ?: C.TIME_UNSET

    /** The receiver's own volume as the whole number 0..100 a [Player] device expects. */
    override val deviceVolume: Int get() = (CastController.volume.value * 100f).toInt()

    override fun setVolume(fraction: Float) = CastController.setVolume(fraction)

    override fun play() {
        if (!active) return
        wantsPlay = true
        finished = false
        client?.play()
        sink?.onRemotePlaybackChanged()
    }

    override fun pause() {
        if (!active) return
        wantsPlay = false
        client?.pause()
        sink?.onRemotePlaybackChanged()
    }

    override fun seekTo(positionMs: Long) {
        val client = client ?: return
        if (!active) return
        requestedPositionMs = positionMs.coerceAtLeast(0L)
        requestedAt = SystemClock.elapsedRealtime()
        client.seek(MediaSeekOptions.Builder().setPosition(requestedPositionMs).build())
        sink?.onRemotePlaybackChanged()
    }

    // ---- session lifecycle --------------------------------------------------

    private val queueCallback = object : com.google.android.gms.cast.framework.media.MediaQueue.Callback() {}

    private val callback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = onStatus()
    }

    fun onSessionBegan(newSession: CastSession, resumed: Boolean, rejoined: Boolean = false) {
        try {
            beginSession(newSession, resumed, rejoined)
        } catch (t: Throwable) {
            // This runs inside the framework's callback; an exception here ends
            // the app. A receiver that could not be set up is a failed cast.
            Log.e(TAG, "onSessionBegan threw", t)
            release()
            say(R.string.cast_connect_failed)
        }
    }

    private fun beginSession(newSession: CastSession, resumed: Boolean, rejoined: Boolean = false) {
        val remote = newSession.remoteMediaClient ?: return
        if (active && remote === client) return
        release()
        session = newSession
        client = remote
        remote.registerCallback(callback)
        // Keeps the speaker's full queue (item ids) followed — see [remoteIds].
        runCatching { remote.mediaQueue.registerCallback(queueCallback) }
        active = true
        finished = false

        val player = localPlayer()
        // BitChord Audio: our own speaker app holds the queue itself.
        if (CastConnect.isOurs(newSession)) {
            val wasPlaying = player != null && player.playWhenReady && player.playbackState != Player.STATE_ENDED
            val position = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
            player?.pause()
            wantsPlay = wasPlaying
            connect = ConnectLink(scope, newSession, localPlayer, resolve) {
                sink?.onRemotePlaybackChanged()
            }.also { it.start(pushPhone = !resumed && wasPlaying, positionMs = position, play = wasPlaying) }
            sink?.onRemoteRouteChanged()
            sink?.onRemotePlaybackChanged()
            return
        }
        if (player != null) {
            val wasPlaying = player.playWhenReady && player.playbackState != Player.STATE_ENDED
            val position = player.currentPosition.coerceAtLeast(0L)
            // The phone goes quiet first, whatever happens next, so the two
            // are never both audible.
            player.pause()
            if (resumed) {
                // BitChord Audio: a session that survived the app (an update,
                // a crash): what the speaker plays is only known once its
                // status comes in — right after a resume it often hasn't yet.
                // Until then nothing is sent, so the music there carries on.
                pendingAdopt = PendingAdopt(wasPlaying, position, rejoined)
                if (remote.mediaStatus?.mediaInfo != null) {
                    adoptOrLoad()
                } else {
                    remote.requestStatus()
                    val generation = pendingAdopt
                    scope.launch {
                        delay(ADOPT_WAIT_MS)
                        if (pendingAdopt === generation) adoptOrLoad()
                    }
                }
            } else {
                load(player.currentMediaItem, position, play = wasPlaying)
            }
        }
        sink?.onRemoteRouteChanged()
        sink?.onRemotePlaybackChanged()
        startHeartbeat()
    }

    private var heartbeat: Job? = null

    /** Set while the session is with our own speaker app (BitChord Connect). */
    private var connect: ConnectLink? = null

    /**
     * BitChord Audio: every 20 seconds while casting, the speaker is asked
     * where it is and the phone made to agree — the speaker is the truth for
     * what plays and where; the phone for what comes next. Covers whatever a
     * missed update left out of step (the phone asleep, the Wi-Fi dozing, the
     * app reopened): the phone follows the speaker's track, and the speaker's
     * queue ahead is topped up.
     */
    private var stallWatch: Job? = null
    private val stallRetries = HashMap<String, Int>()

    /**
     * BitChord Audio: a speaker left hanging — sent to a song (a skip) and
     * then sitting on its loading screen, or idle with nothing loaded, while
     * you want music. Seen in the log: a skip, the speaker "interrupted" with
     * no song, then nothing for a minute until the session dropped. After 12
     * seconds like that the song the phone is on is sent again (twice at
     * most per song; then the next one).
     */
    private fun startStallWatch() {
        stallWatch?.cancel()
        stallWatch = scope.launch {
            var since = 0L
            while (active) {
                delay(3_000)
                val remote = client ?: break
                if (connect != null || pendingAdopt != null || loading || !wantsPlay || finished) { since = 0L; continue }
                val status = remote.mediaStatus
                val state = status?.playerState
                val stuck = status == null ||
                    state == MediaStatus.PLAYER_STATE_LOADING ||
                    state == MediaStatus.PLAYER_STATE_BUFFERING ||
                    (state == MediaStatus.PLAYER_STATE_IDLE && status.idleReason != MediaStatus.IDLE_REASON_FINISHED) ||
                    (state == MediaStatus.PLAYER_STATE_UNKNOWN)
                if (!stuck) { since = 0L; continue }
                if (since == 0L) { since = SystemClock.elapsedRealtime(); continue }
                if (SystemClock.elapsedRealtime() - since < STALL_MS) continue
                since = 0L
                val player = localPlayer() ?: continue
                val item = player.currentMediaItem ?: continue
                val tries = (stallRetries[item.mediaId] ?: 0) + 1
                stallRetries[item.mediaId] = tries
                if (tries <= 2) {
                    Log.w(TAG, "cast: speaker stuck (state $state, idle ${status?.idleReason}) on ${item.mediaMetadata.title}; sending it again (try $tries)")
                    if (tries == 2) { CastRouting.proxyOnly.add(item.mediaId); streams.keys.removeAll { it.startsWith(item.mediaId) } }
                    load(item, remote.approximateStreamPosition.coerceAtLeast(0L).takeIf { tries == 1 } ?: 0L, play = true)
                } else if (player.hasNextMediaItem()) {
                    Log.w(TAG, "cast: ${item.mediaMetadata.title} won't start on the speaker; going on to the next song")
                    player.seekToNextMediaItem()
                }
            }
        }
    }

    private fun startHeartbeat() {
        startStallWatch()
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (active) {
                delay(HEARTBEAT_MS)
                val remote = client ?: break
                runCatching { remote.requestStatus() }
                delay(1_500)
                if (!active || loading || pendingAdopt != null) continue
                if (SystemClock.elapsedRealtime() - lastLocalMoveAt < 8_000) continue
                val status = remote.mediaStatus ?: continue
                val player = localPlayer() ?: continue
                val onSpeaker = receiverMediaId(status) ?: continue
                if (status.playerState == MediaStatus.PLAYER_STATE_IDLE) continue
                if (!sameTrack(onSpeaker, player.currentMediaItem?.mediaId)) {
                    Log.d(TAG, "cast: phone was out of step with the speaker; following it")
                    val index = indexOfMediaId(player, onSpeaker)
                    if (index != null) {
                        lastLocalMoveAt = SystemClock.elapsedRealtime()
                        player.seekTo(index, remote.approximateStreamPosition.coerceAtLeast(0L))
                    } else {
                        lastLocalMoveAt = SystemClock.elapsedRealtime()
                        if (adoptReceiverQueue(player, remote, onSpeaker)) {
                            player.currentMediaItem?.mediaId?.takeIf { it != onSpeaker }?.let { alias = onSpeaker to it }
                        }
                    }
                }
                lastItemId = status.currentItemId
                scheduleReconcile()
            }
        }
    }

    private class PendingAdopt(val wasPlaying: Boolean, val position: Long, val rejoined: Boolean)

    /** Set from a resumed session's start until the speaker has said what it plays. */
    private var pendingAdopt: PendingAdopt? = null

    /** The track the receiver is on: its queue entry, else the media it reports. */
    private fun receiverMediaId(status: MediaStatus?): String? {
        status ?: return null
        return status.queueItems.firstOrNull { it.itemId == status.currentItemId }?.mediaId()
            ?: status.mediaInfo?.customData?.optString(KEY_MEDIA_ID)?.ifEmpty { null }
    }

    /**
     * A resumed session, its status in (or waited for long enough): the phone
     * follows the speaker when it plays something of ours; a rejoined speaker
     * that turned out to hold nothing is let go of; otherwise the phone's track
     * is sent, as for a new session.
     */
    private fun adoptOrLoad() {
        val pending = pendingAdopt ?: return
        if (adoptingNow) return
        adoptingNow = true
        try {
            adoptOrLoad(pending)
        } finally {
            adoptingNow = false
            pendingAdopt = null
        }
    }

    private var adoptingNow = false

    private fun adoptOrLoad(pending: PendingAdopt) {
        val remote = client ?: return
        val player = localPlayer() ?: return
        val status = remote.mediaStatus
        val onReceiver = receiverMediaId(status)
        if (onReceiver != null && (player.currentMediaItem?.mediaId == onReceiver || adoptReceiverQueue(player, remote, onReceiver))) {
            lastLocalMoveAt = SystemClock.elapsedRealtime()
            lastItemId = status?.currentItemId ?: MediaQueueItem.INVALID_ITEM_ID
            wantsPlay = status?.playerState == MediaStatus.PLAYER_STATE_PLAYING
            // What the speaker holds after this track was handed out by the
            // app before it went away (links that may be dead now): rebuilt.
            refreshAhead = true
            player.currentMediaItem?.mediaId?.takeIf { it != onReceiver }?.let { alias = onReceiver to it }
            scheduleReconcile()
            com.music.bitchord.data.TrackLog.d(TAG, "cast: took up the speaker's track $onReceiver")
            sink?.onRemotePlaybackChanged()
            return
        }
        if (pending.rejoined) {
            com.music.bitchord.data.TrackLog.d(TAG, "cast: the speaker holds nothing of ours; leaving it")
            active = false
            CastController.forgetAndLeave()
            return
        }
        load(player.currentMediaItem, pending.position, play = pending.wasPlaying)
    }

    /** Set after taking up a speaker's queue: everything queued after its track is replaced once. */
    private var refreshAhead = false

    /** Plans in a row given up because the queue moved; see [reconcileOnce]. */
    private var replans = 0

    /**
     * BitChord Audio: puts the phone's player on the receiver's track — found
     * in the phone's queue, or the phone's queue replaced by the receiver's —
     * held paused, as the speaker plays. True when the phone now matches.
     */
    private fun adoptReceiverQueue(player: ExoPlayer, remote: RemoteMediaClient, onReceiver: String): Boolean {
        val status = remote.mediaStatus ?: return false
        val position = remote.approximateStreamPosition.coerceAtLeast(0L)
        indexOfMediaId(player, onReceiver)?.let { index ->
            player.seekTo(index, position)
            com.music.bitchord.data.TrackLog.d(TAG, "cast: rejoined; phone moved to the speaker's track")
            return true
        }
        val info = status.queueItems.firstOrNull { it.itemId == status.currentItemId }?.media ?: status.mediaInfo ?: return false
        val meta = info.metadata
        val title = meta?.getString(MediaMetadata.KEY_TITLE) ?: return false
        val artist = meta.getString(MediaMetadata.KEY_ARTIST).orEmpty()
        // The same song under another id (another copy of it was sent):
        // found in the phone's queue by its name, so the queue stays as it was.
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val wantTitle = norm(title)
        val wantArtist = norm(artist)
        val byName = (0 until player.mediaItemCount).firstOrNull { i ->
            val m = player.getMediaItemAt(i).mediaMetadata
            norm(m.title?.toString().orEmpty()) == wantTitle &&
                (wantArtist.isEmpty() || norm(m.artist?.toString().orEmpty()) == wantArtist)
        }
        if (byName != null) {
            player.getMediaItemAt(byName).mediaId.takeIf { it != onReceiver }?.let { alias = onReceiver to it }
            player.seekTo(byName, position)
            com.music.bitchord.data.TrackLog.d(TAG, "cast: rejoined; phone moved to the speaker's track (by name)")
            return true
        }
        // Not in the phone's queue at all: put in where the phone is, so the
        // rest of the queue (the playlist it came from) is kept around it.
        val carried = info.customData?.optString(KEY_SONG)?.ifEmpty { null }
            ?.let(com.music.bitchord.playback.LastPlayed::songFromJson)
        val item = carried?.toMediaItem() ?: com.music.bitchord.data.model.Song(
            videoId = onReceiver,
            title = title,
            artist = artist,
            thumbnailUrl = meta.images.firstOrNull()?.url?.toString(),
            albumName = meta.getString(MediaMetadata.KEY_ALBUM_TITLE),
            durationText = info.streamDuration.takeIf { it > 0 }?.let { ms -> "%d:%02d".format(ms / 60_000, ms / 1000 % 60) },
        ).toMediaItem()
        if (player.mediaItemCount == 0) {
            // Nothing here at all (another phone, a fresh start): the
            // speaker's whole queue, as it carries it.
            val all = status.queueItems.mapNotNull { q ->
                val m = q.media ?: return@mapNotNull null
                m.customData?.optString(KEY_SONG)?.ifEmpty { null }
                    ?.let(com.music.bitchord.playback.LastPlayed::songFromJson)?.toMediaItem()
            }
            val at = all.indexOfFirst { it.mediaId == onReceiver }
            if (at >= 0) {
                player.setMediaItems(all, at, position)
            } else {
                player.setMediaItems(listOf(item), 0, position)
            }
            player.playWhenReady = false
            player.prepare()
        } else {
            val at = (player.currentMediaItemIndex + 1).coerceIn(0, player.mediaItemCount)
            player.addMediaItem(at, item)
            player.seekTo(at, position)
        }
        com.music.bitchord.data.TrackLog.d(TAG, "cast: rejoined; the speaker's track put into the phone's queue")
        return true
    }

    fun onSessionEnding(ending: CastSession) {
        val remote = ending.remoteMediaClient ?: client ?: return
        endPositionMs = remote.approximateStreamPosition.coerceAtLeast(0L)
        endMediaId = remote.mediaStatus?.let { status ->
            status.queueItems.firstOrNull { it.itemId == status.currentItemId }?.mediaId()
        }
        endWasPlaying = wantsPlay && remote.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_PLAYING
    }

    /**
     * The receiver let go. Its place is carried back to the phone's player so
     * that the track, and the second in it, are still there. Whether sound
     * follows depends on who asked: a listener who picked "this phone" wants it
     * to go on playing here; a receiver that was switched off, or a Wi-Fi that
     * dropped, should not have the phone start playing out loud unprompted.
     */
    fun onSessionEnded(error: Int) {
        if (!active) return
        val resume = resumeHereOnEnd && endWasPlaying
        resumeHereOnEnd = false
        release()
        val player = localPlayer()
        if (player != null) {
            val index = indexOfMediaId(player, endMediaId)
            if (index != null) player.seekTo(index, endPositionMs) else player.seekTo(endPositionMs)
            if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) player.prepare()
            if (resume) player.play()
        }
        Log.i(TAG, "session ended: error=$error resumeHere=$resume")
        sink?.onRemoteRouteChanged()
        sink?.onRemotePlaybackChanged()
    }

    fun onConnectFailed() = say(R.string.cast_connect_failed)

    /** Lets go of the receiver without touching the phone's player. */
    fun release() {
        loadJob?.cancel()
        reconcileJob?.cancel()
        runCatching { client?.mediaQueue?.unregisterCallback(queueCallback) }
        client?.unregisterCallback(callback)
        client = null
        session = null
        active = false
        loading = false
        wantsPlay = false
        finished = false
        lastItemId = MediaQueueItem.INVALID_ITEM_ID
        pendingAdopt = null
        refreshAhead = false
        alias = null
        heartbeat?.cancel()
        heartbeat = null
        stallWatch?.cancel()
        stallWatch = null
        ledger.clear()
        connect?.stop()
        connect = null
        target = null
        targetWatch?.cancel()
    }

    /**
     * BitChord Audio: after taking up a speaker that plays another copy of the
     * phone's track (same song, other id): the speaker's id, then the phone's.
     */
    private var alias: Pair<String, String>? = null

    private fun sameTrack(remoteId: String?, phoneId: String?): Boolean =
        remoteId != null && phoneId != null && (remoteId == phoneId || alias == (remoteId to phoneId))

    // ---- the phone's player moved -------------------------------------------

    /** A new track became the phone's current one. */
    override fun onLocalTransition(item: MediaItem?, reason: Int) {
        if (!active || item == null) return
        lastLocalMoveAt = SystemClock.elapsedRealtime()
        connect?.let { c ->
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) c.onPhoneMoved(item) else c.onQueueChanged()
            return
        }
        val remote = client ?: return
        if (pendingAdopt != null) return
        if (sameTrack(remoteMediaId(), item.mediaId)) {
            // Already there: the receiver moved first and this is the phone
            // catching up, or a queue edit that left the playing track alone.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) scheduleReconcile()
            return
        }
        val queuedId = remoteIds(remote).let { ids ->
            val here = ids.indexOf(remote.mediaStatus?.currentItemId ?: MediaQueueItem.INVALID_ITEM_ID)
            ids.drop(here + 1).firstOrNull { remoteIdOf(remote, it) == item.mediaId }
        }
        if (queuedId != null && !loading) {
            // A track the receiver already holds is a jump, not a reload, and
            // the jump is instant.
            wantsPlay = true
            finished = false
            requestedPositionMs = 0L
            requestedAt = SystemClock.elapsedRealtime()
            Log.d(TAG, "cast: phone moved to ${item.mediaMetadata.title}; speaker jumps to its queued copy")
            expect(item.mediaId)
            remote.queueJumpToItem(queuedId, null)
            sink?.onRemotePlaybackChanged()
        } else {
            // Skips in quick succession: only the one stopped on is fetched.
            load(item, 0L, play = true, settleMs = SKIP_SETTLE_MS)
        }
    }

    /** The phone's queue changed under the playing track. */
    override fun onLocalQueueChanged() {
        connect?.let { it.onQueueChanged(); return }
        scheduleReconcile()
    }

    override fun onLocalRepeatChanged(repeatMode: Int) {
        if (!active) return
        connect?.let { it.repeat(repeatMode); return }
        client?.queueSetRepeatMode(receiverRepeat(repeatMode), null)
    }

    /**
     * Something other than the session player started the phone's own player
     * while the receiver is the speaker. The phone goes quiet again and the
     * request is made of the receiver instead.
     */
    override fun onLocalStartedPlaying(player: Player) {
        if (!active) return
        player.pause()
        play()
    }

    // ---- the receiver moved -------------------------------------------------

    /** When the phone last moved to another track (a skip, a tap in the queue). */
    private var lastLocalMoveAt = 0L

    /** When the speaker started playing each of its queue entries, by item id. */
    private val playStartedAt = HashMap<Int, Long>()

    private fun onStatus() {
        val remote = client ?: return
        val status = remote.mediaStatus ?: return
        if (connect != null) {
            // Our speaker moves on by itself and says so (ConnectLink): only
            // whether it is playing is read from here.
            requestedAt = 0L
            when (status.playerState) {
                MediaStatus.PLAYER_STATE_PLAYING -> { wantsPlay = true; loading = false }
                MediaStatus.PLAYER_STATE_PAUSED -> { wantsPlay = false; loading = false }
                else -> Unit
            }
            sink?.onRemotePlaybackChanged()
            return
        }
        if (pendingAdopt != null) {
            if (status.mediaInfo != null || status.playerState == MediaStatus.PLAYER_STATE_IDLE) adoptOrLoad()
            return
        }
        val state = status.playerState
        target?.let { t ->
            if (sameTrack(receiverMediaId(status), t) &&
                (state == MediaStatus.PLAYER_STATE_PLAYING || state == MediaStatus.PLAYER_STATE_PAUSED)
            ) {
                target = null
                targetWatch?.cancel()
                requestedAt = SystemClock.elapsedRealtime()
            }
        }
        // Whatever the phone last asked for has been answered by now.
        requestedAt = 0L

        when (state) {
            MediaStatus.PLAYER_STATE_PLAYING -> {
                // When this entry started, by the clock (pauses only make it
                // look longer played, never shorter).
                playStartedAt.getOrPut(status.currentItemId) { SystemClock.elapsedRealtime() - status.streamPosition }
                wantsPlay = true
                loading = false
                finished = false
            }
            MediaStatus.PLAYER_STATE_PAUSED -> {
                wantsPlay = false
                loading = false
            }
            MediaStatus.PLAYER_STATE_IDLE -> {
                Log.d(TAG, "cast: speaker idle (reason ${status.idleReason}) on ${status.getQueueItemById(status.currentItemId)?.media?.metadata?.getString(MediaMetadata.KEY_TITLE)}")
                if (!loading && status.idleReason == MediaStatus.IDLE_REASON_FINISHED) {
                    wantsPlay = false
                    // BitChord Audio: only a track the phone is still on can
                    // have finished. Right after the phone moves (a skip), the
                    // receiver may still report the track it was told to leave
                    // as "finished" — taking that as the end of the new one
                    // skipped a second track.
                    val finishedId = status.getQueueItemById(status.currentItemId)?.mediaId()
                    val phoneId = localPlayer()?.currentMediaItem?.mediaId
                    val justMoved = SystemClock.elapsedRealtime() - lastLocalMoveAt < LOCAL_MOVE_GRACE_MS
                    val played = playStartedAt[status.currentItemId]?.let { SystemClock.elapsedRealtime() - it } ?: Long.MAX_VALUE
                    val length = status.getQueueItemById(status.currentItemId)?.media?.streamDuration ?: 0L
                    val player = localPlayer()
                    val index = finishedId?.let { id -> player?.let { indexOfMediaId(it, id) } }
                    if (justMoved || (finishedId != null && phoneId != null && !sameTrack(finishedId, phoneId))) {
                        Log.d(TAG, "receiver finished $finishedId, phone is on $phoneId: not moving on")
                    } else if (length > 30_000 && played < 5_000 && finishedId != null && player != null && index != null &&
                        CastRouting.proxyOnly.add(finishedId)
                    ) {
                        // BitChord Audio: "finished" seconds into a whole song
                        // is a stream that broke off (an expired or cut link),
                        // not the end of it: tried again, served by the phone,
                        // instead of skipping the song.
                        Log.w(TAG, "cast: speaker ended $finishedId after ${played}ms of ${length}ms; its stream broke — trying it again from the phone")
                        load(player.getMediaItemAt(index), played, play = true)
                        return
                    } else {
                        Log.d(TAG, "cast: speaker finished $finishedId after ${played}ms of ${length}ms")
                        onReceiverFinished()
                    }
                } else if (status.idleReason == MediaStatus.IDLE_REASON_ERROR) {
                    // BitChord Audio: a track the receiver couldn't fetch by
                    // itself is tried once more, served by the phone.
                    val failedId = status.getQueueItemById(status.currentItemId)?.mediaId()
                        ?: localPlayer()?.currentMediaItem?.mediaId
                    val player = localPlayer()
                    val index = failedId?.let { id -> player?.let { indexOfMediaId(it, id) } }
                    if (failedId != null && player != null && index != null && CastRouting.proxyOnly.add(failedId)) {
                        Log.w(TAG, "receiver couldn't play $failedId directly; serving it from the phone")
                        val movedJustNow = SystemClock.elapsedRealtime() - lastLocalMoveAt < LOCAL_MOVE_GRACE_MS
                        if (!movedJustNow && player.currentMediaItemIndex != index) player.seekTo(index, 0L)
                        load(player.getMediaItemAt(index), status.streamPosition.coerceAtLeast(0L), play = true)
                        return
                    }
                    wantsPlay = false
                    loading = false
                    say(R.string.cast_track_unsupported)
                }
            }
        }

        val itemId = status.currentItemId
        if (itemId != MediaQueueItem.INVALID_ITEM_ID && itemId != lastItemId) {
            // BitChord Audio: the speaker left the last track by itself after
            // a few seconds of a whole song — it couldn't play its stream and
            // went on to the next (a Cast queue does that). Not a skip anyone
            // asked for: that track is played again, served by the phone.
            val left = lastItemId
            // BitChord Audio: the speaker was sent to a song (a skip) and left
            // it again before it ever played — its stream failed and a Cast
            // queue goes on to the next by itself, which skipped a second song.
            // That song is sent again (from the phone, as a fresh load), once.
            val wantedId = target
            val nowId = status.getQueueItemById(itemId)?.mediaId() ?: ledger[itemId]
            val leftId0 = status.getQueueItemById(left)?.mediaId() ?: ledger[left]
            if (wantedId != null && !loading && sameTrack(leftId0, wantedId) && !sameTrack(nowId, wantedId)) {
                val phone = localPlayer()
                val index = phone?.let { indexOfMediaId(it, wantedId) }
                if (phone != null && index != null && retriedFromPhone.add(wantedId)) {
                    Log.w(TAG, "cast: speaker couldn't start ${phone.getMediaItemAt(index).mediaMetadata.title} and went on by itself; sending it again")
                    CastRouting.proxyOnly.add(wantedId)
                    streams.keys.removeAll { it.startsWith(wantedId) }
                    lastItemId = itemId
                    if (phone.currentMediaItemIndex != index) {
                        lastLocalMoveAt = SystemClock.elapsedRealtime()
                        phone.seekTo(index, 0L)
                    }
                    load(phone.getMediaItemAt(index), 0L, play = true)
                    return
                }
            }
            val leftItem = status.getQueueItemById(left)
            val leftPlayed = playStartedAt[left]?.let { SystemClock.elapsedRealtime() - it }
            val leftLength = leftItem?.media?.streamDuration ?: 0L
            val askedToMove = SystemClock.elapsedRealtime() - lastLocalMoveAt < LOCAL_MOVE_GRACE_MS
            val leftId = leftItem?.mediaId()
            val phone = localPlayer()
            if (!askedToMove && !loading && leftId != null && phone != null && leftLength > 30_000 &&
                leftPlayed != null && leftPlayed < 5_000
            ) {
                val index = indexOfMediaId(phone, leftId)
                Log.w(TAG, "cast: speaker skipped ${leftItem.media?.metadata?.getString(MediaMetadata.KEY_TITLE) ?: leftId} after ${leftPlayed ?: 0}ms (its stream failed)")
                if (index != null && CastRouting.proxyOnly.add(leftId)) {
                    lastItemId = itemId
                    if (phone.currentMediaItemIndex != index) {
                        lastLocalMoveAt = SystemClock.elapsedRealtime()
                        phone.seekTo(index, 0L)
                    }
                    load(phone.getMediaItemAt(index), 0L, play = true)
                    return
                }
            }
            lastItemId = itemId
            val mediaId = status.getQueueItemById(itemId)?.mediaId()
            val player = localPlayer()
            Log.d(TAG, "cast: speaker now on ${status.getQueueItemById(itemId)?.media?.metadata?.getString(MediaMetadata.KEY_TITLE) ?: mediaId}")
            // Right after the phone moved, the receiver is catching up: what it
            // reports then is the old track, not a move of its own to follow.
            val catchingUp = SystemClock.elapsedRealtime() - lastLocalMoveAt < LOCAL_MOVE_GRACE_MS
            if (!catchingUp && mediaId != null && player != null && !sameTrack(mediaId, player.currentMediaItem?.mediaId)) {
                indexOfMediaId(player, mediaId)?.let { player.seekTo(it, 0L) }
            }
            scheduleReconcile()
        }
        sink?.onRemotePlaybackChanged()
    }

    /** The receiver played its last queued track to the end. */
    private fun onReceiverFinished() {
        val player = localPlayer() ?: return
        Log.d(TAG, "cast: speaker ran out of queued tracks; phone moves on")
        when {
            // The receiver holds only a few tracks ahead, so running out of
            // them is not the end of the phone's queue.
            player.hasNextMediaItem() -> player.seekToNextMediaItem()
            player.repeatMode == Player.REPEAT_MODE_ALL && player.mediaItemCount > 0 -> player.seekTo(0, 0L)
            else -> finished = true
        }
    }

    // ---- loading ------------------------------------------------------------

    /**
     * Puts [item] on the receiver, replacing its queue, and then lets
     * [reconcile] fill in what follows. Only the playing track is resolved
     * before the receiver is asked to start: the rest are resolved while it
     * plays, so a cast begins as quickly as a single stream resolves.
     */
    private fun load(item: MediaItem?, positionMs: Long, play: Boolean, settleMs: Long = 0L) {
        val remote = client ?: return
        loadJob?.cancel()
        // A queue being planned for the old track would land after the new one.
        reconcileJob?.cancel()
        reconcileJob = null
        reconcileAgain = false
        val generation = ++loadGeneration
        Log.d(TAG, "cast: loading ${item?.mediaMetadata?.title ?: item?.mediaId} on the speaker (whole queue replaced)")
        expect(item?.mediaId)
        wantsPlay = play
        loading = true
        finished = false
        requestedPositionMs = positionMs
        requestedAt = SystemClock.elapsedRealtime()
        sink?.onRemotePlaybackChanged()

        loadJob = scope.launch {
            try {
                if (settleMs > 0) delay(settleMs)
                val t0 = SystemClock.elapsedRealtime()
                loadNow(item, positionMs, play, generation, remote)
                Log.d(TAG, "cast: speaker load took ${SystemClock.elapsedRealtime() - t0}ms")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "load threw", t)
                loading = false
                wantsPlay = false
                say(R.string.cast_connect_failed)
                sink?.onRemotePlaybackChanged()
            }
        }
    }

    private suspend fun loadNow(item: MediaItem?, positionMs: Long, play: Boolean, generation: Int, remote: RemoteMediaClient) {
        run {
            val queueItem = item?.let { buildQueueItem(it, play) }
            if (generation != loadGeneration || !active) return@run
            if (queueItem == null) {
                // Nothing to play, so nothing to wait for. The receiver is left
                // as it was; only the phone's idea of it is corrected.
                loading = false
                wantsPlay = false
                if (item != null) say(R.string.cast_track_unsupported)
                sink?.onRemotePlaybackChanged()
                return@run
            }
            val repeat = receiverRepeat(localPlayer()?.repeatMode ?: Player.REPEAT_MODE_OFF)
            val request = MediaLoadRequestData.Builder()
                .setQueueData(
                    MediaQueueData.Builder()
                        .setItems(listOf(queueItem))
                        .setStartIndex(0)
                        .setRepeatMode(repeat)
                        .setStartTime(positionMs)
                        .build(),
                )
                .setAutoplay(play)
                .setCurrentTime(positionMs)
                .build()
            // A speaker that never answers would leave this "loading" for good.
            val result = kotlinx.coroutines.withTimeoutOrNull(LOAD_TIMEOUT_MS) { remote.load(request).awaitResult() }
            if (generation != loadGeneration || !active) return@run
            if (result == null || !result.status.isSuccess) {
                Log.w(TAG, "cast: speaker load failed: ${result?.status?.statusCode ?: "no answer in ${LOAD_TIMEOUT_MS}ms"}")
                loading = false
                wantsPlay = false
                say(R.string.cast_track_unsupported)
                sink?.onRemotePlaybackChanged()
                return@run
            }
            lastItemId = remote.mediaStatus?.currentItemId ?: lastItemId
            ledger.clear()
            item?.let { if (lastItemId != MediaQueueItem.INVALID_ITEM_ID) ledger[lastItemId] = it.mediaId }
            loading = false
            sink?.onRemotePlaybackChanged()
            scheduleReconcile()
        }
    }

    private fun scheduleReconcile() {
        if (!active) return
        if (reconcileJob?.isActive == true) {
            reconcileAgain = true
            return
        }
        reconcileJob = scope.launch {
            do {
                reconcileAgain = false
                // Let the receiver's own status catch up with whatever just
                // changed it; reading its queue earlier reads the old one.
                delay(RECONCILE_DELAY_MS)
                if (active && !loading) {
                    try {
                        reconcileOnce()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.e(TAG, "reconcile threw", t)
                    }
                }
            } while (reconcileAgain && active)
        }
    }

    /**
     * Makes what the receiver has queued after the playing track match what the
     * phone's queue says comes next — [AHEAD] tracks of it. The matching
     * prefix is kept (it may already be buffering); the first track that
     * differs and everything after it is replaced.
     */
    private suspend fun reconcileOnce() {
        val remote = client ?: return
        val player = localPlayer() ?: return
        val status = remote.mediaStatus ?: return
        val startItemId = status.currentItemId
        // BitChord Audio: the speaker's whole queue, by item id. The media
        // status only carries the few items around the one playing — read
        // from there, most of the queue looked missing, so every pass removed
        // what it could see and added the rest again: the queue grew with
        // copies, and songs came round a second time.
        val ids = remoteIds(remote)
        val here = ids.indexOf(startItemId)
        if (here < 0) return
        val aheadIds = ids.drop(here + 1)

        val current = player.currentMediaItemIndex
        if (current == C.INDEX_UNSET) return
        val phoneId = player.currentMediaItem?.mediaId
        val generation = loadGeneration
        val wanted = ((current + 1)..minOf(current + AHEAD, player.mediaItemCount - 1))
            .map { player.getMediaItemAt(it) }

        var kept = 0
        val refreshing = refreshAhead
        if (refreshing) {
            refreshAhead = false
        } else {
            while (kept < aheadIds.size && kept < wanted.size && remoteIdOf(remote, aheadIds[kept]) == wanted[kept].mediaId) {
                kept++
            }
        }
        if (kept == aheadIds.size && kept == wanted.size) return

        // One track at a time — its stream found (the slow part), then put on
        // the speaker only if nothing moved meanwhile (a skip, the speaker
        // moving on, a new load).
        fun stale(): Boolean =
            !active || loading || generation != loadGeneration ||
                remote.mediaStatus?.currentItemId != startItemId ||
                localPlayer()?.currentMediaItem?.mediaId != phoneId
        fun replan() {
            Log.d(TAG, "cast: queue changed while finding the next streams; planning again")
            if (refreshing) refreshAhead = true
            if (++replans <= 3) reconcileAgain = true
        }
        // What doesn't match goes first (quick), then the missing ones.
        val drop = aheadIds.drop(kept)
        if (drop.isNotEmpty()) {
            if (stale()) return replan()
            remote.queueRemoveItems(drop.toIntArray(), null).awaitResult()
            drop.forEach { ledger.remove(it) }
            if (drop.size > wanted.size) Log.d(TAG, "cast: took ${drop.size} stray entries off the speaker's queue")
        }
        val queued = mutableListOf<String>()
        for (next in wanted.drop(kept)) {
            val queueItem = buildQueueItem(next, autoplay = true) ?: break
            if (stale()) return replan()
            val before = remoteIds(remote).toHashSet()
            remote.queueInsertItems(arrayOf(queueItem), MediaQueueItem.INVALID_ITEM_ID, null).awaitResult()
            learnNewId(remote, before, next.mediaId)
            queued += next.mediaId
        }
        replans = 0
        if (queued.isNotEmpty()) {
            Log.d(TAG, "cast: speaker has the next ${kept + queued.size} queued (${queued.size} added)")
        }
    }

    /** Receiver item id → the phone's media id, for the items this phone put there. */
    private val ledger = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** Every item id in the speaker's queue, in order (the media status alone holds only a few). */
    private fun remoteIds(remote: RemoteMediaClient): List<Int> {
        val all = runCatching { remote.mediaQueue.itemIds.toList() }.getOrDefault(emptyList())
        if (all.isNotEmpty()) return all
        return remote.mediaStatus?.queueItems?.map { it.itemId }.orEmpty()
    }

    private fun remoteIdOf(remote: RemoteMediaClient, itemId: Int): String? =
        ledger[itemId]
            ?: remote.mediaStatus?.getQueueItemById(itemId)?.mediaId()?.also { ledger[itemId] = it }
            ?: runCatching {
                val q = remote.mediaQueue
                val at = q.indexOfItemWithId(itemId)
                if (at >= 0) q.getItemAtIndex(at, true)?.mediaId() else null
            }.getOrNull()?.also { ledger[itemId] = it }

    /** After an insert: the one new item id is [mediaId]'s. */
    private suspend fun learnNewId(remote: RemoteMediaClient, before: Set<Int>, mediaId: String) {
        repeat(15) {
            val fresh = remoteIds(remote).filter { it !in before }
            if (fresh.size == 1) {
                ledger[fresh.first()] = mediaId
                return
            }
            if (fresh.isNotEmpty()) return
            delay(100)
        }
    }

    /**
     * BitChord Audio: streams already found, by track, for a while — a skip
     * onto a track that was queued on the speaker and then taken off again
     * (the queue re-planned) doesn't search for it all over again.
     */
    private val streams = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, CastStream>>()

    private suspend fun buildQueueItem(item: MediaItem, autoplay: Boolean): MediaQueueItem? {
        val now = SystemClock.elapsedRealtime()
        // Once a track must be served by the phone, a direct link found before doesn't count.
        val key = item.mediaId + if (item.mediaId in CastRouting.proxyOnly) "#phone" else ""
        val cached = streams[key]?.takeIf { now - it.first < STREAM_TTL_MS }?.second
        val stream = cached ?: try {
            resolve(item)?.also { streams[key] = now to it }
        } catch (e: Exception) {
            Log.w(TAG, "could not resolve ${item.mediaId} for casting", e)
            null
        } ?: return null

        val metadata = item.mediaMetadata
        val music = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            metadata.title?.let { putString(MediaMetadata.KEY_TITLE, it.toString()) }
            metadata.artist?.let { putString(MediaMetadata.KEY_ARTIST, it.toString()) }
            metadata.albumTitle?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it.toString()) }
            (stream.artworkUrl?.let(Uri::parse) ?: metadata.artworkUri?.takeIf { it.scheme == "http" || it.scheme == "https" })
                ?.let { addImage(WebImage(it)) }
        }
        val info = MediaInfo.Builder(stream.url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(stream.mimeType)
            .setMetadata(music)
            // The whole song, so a phone that has lost its queue (an update, a
            // crash, another phone) can take the speaker's up as it is.
            .setCustomData(
                JSONObject()
                    .put(KEY_MEDIA_ID, item.mediaId)
                    .put(KEY_SONG, runCatching { com.music.bitchord.playback.LastPlayed.songToJson(item.toSong()) }.getOrNull()),
            )
            .apply { metadata.durationMs?.takeIf { it > 0 }?.let(::setStreamDuration) }
            .build()
        return MediaQueueItem.Builder(info).setAutoplay(autoplay).build()
    }

    // ---- small helpers ------------------------------------------------------

    private fun MediaQueueItem.mediaId(): String? = media?.customData?.optString(KEY_MEDIA_ID)?.ifEmpty { null }

    private fun remoteMediaId(): String? {
        val status = client?.mediaStatus ?: return null
        return status.getQueueItemById(status.currentItemId)?.mediaId() ?: ledger[status.currentItemId]
    }

    /**
     * The row of [mediaId] nearest the one playing — a track queued twice is
     * the case where more than one answers, and the near one is the one meant.
     */
    private fun indexOfMediaId(player: Player, mediaId: String?): Int? {
        if (mediaId == null) return null
        val at = player.currentMediaItemIndex.coerceAtLeast(0)
        return (0 until player.mediaItemCount)
            .filter { player.getMediaItemAt(it).mediaId == mediaId }
            .minByOrNull { kotlin.math.abs(it - at) }
    }

    private fun receiverRepeat(repeatMode: Int): Int =
        if (repeatMode == Player.REPEAT_MODE_ONE) {
            MediaStatus.REPEAT_MODE_REPEAT_SINGLE
        } else {
            // Repeat-all is the phone's to do: the receiver holds a window of
            // the queue, and looping the window is not looping the queue.
            MediaStatus.REPEAT_MODE_REPEAT_OFF
        }

    /**
     * Not named `await`: [PendingResult] has a blocking member of that name,
     * and a member always beats an extension — so the blocking one ran, on the
     * main thread, and threw.
     */
    private suspend fun <R : Result> PendingResult<R>.awaitResult(): R =
        suspendCancellableCoroutine { continuation ->
            setResultCallback { continuation.resume(it) }
            continuation.invokeOnCancellation { cancel() }
        }

    companion object {
        private const val TAG = "BitChordCast"
        private const val KEY_MEDIA_ID = "mediaId"
        private const val KEY_SONG = "song"

        /** Tracks queued on the speaker after its own: it plays on through them if the phone sleeps. */
        private const val AHEAD = 8
        private const val RECONCILE_DELAY_MS = 300L

        /** A skip waits this long for the next one before its track is fetched. */
        private const val SKIP_SETTLE_MS = 450L

        /** A speaker not playing what it was sent after this long is stuck. */
        private const val TARGET_STUCK_MS = 15_000L
        private const val LOAD_TIMEOUT_MS = 20_000L

        /** How long a found stream is reused (links last hours; kept well short of that). */
        private const val STREAM_TTL_MS = 20 * 60_000L

        /** How long the speaker may hang (loading, idle with nothing) before the song is sent again. */
        private const val STALL_MS = 12_000L

        /** How often the phone checks it agrees with the speaker. */
        private const val HEARTBEAT_MS = 20_000L

        /** How long a resumed session waits for the speaker to say what it plays. */
        private const val ADOPT_WAIT_MS = 6_000L
        private const val SEEK_GRACE_MS = 1_500L

        /** How long after the phone moves a "finished" from the receiver is taken as stale. */
        private const val LOCAL_MOVE_GRACE_MS = 4_000L

        /**
         * What a stream is, for a receiver that is told rather than left to
         * guess. googlevideo URLs say so themselves; the rest are named by
         * their extension, and an unnamed one is taken to be MP4 audio, which
         * is what nearly every unnamed stream here is.
         */
        fun mimeTypeOf(uri: Uri): String {
            uri.getQueryParameter("mime")?.takeIf { it.isNotBlank() }?.let { return it }
            val path = uri.lastPathSegment?.lowercase().orEmpty()
            return when {
                path.endsWith(".flac") -> "audio/flac"
                path.endsWith(".mp3") -> "audio/mpeg"
                path.endsWith(".ogg") || path.endsWith(".opus") -> "audio/ogg"
                path.endsWith(".wav") -> "audio/wav"
                path.endsWith(".mpd") -> "application/dash+xml"
                path.endsWith(".m3u8") -> "application/x-mpegurl"
                else -> "audio/mp4"
            }
        }
    }
}

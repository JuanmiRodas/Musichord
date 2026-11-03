package com.music.bitchord.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: a music video is played as its audio release, and the video
 * itself is never loaded.
 *
 * Upstream v1.9 dropped the "prefer music-only version" switch and plays music
 * videos as videos, leaving only a manual switch on the player. Swapping once a
 * video is already playing means it has started loading by then, so the swap
 * here happens before an item can become current, at three points:
 *
 * 1. [resolveStart] — a play request. The item it starts on is resolved before
 *    the player is handed the queue, so the first track is audio from the start.
 * 2. [lookAhead] — the queue moving. The next [LOOK_AHEAD] items are resolved in
 *    the background and replaced in place while they are still upcoming, which
 *    the listener never sees or hears.
 * 3. [onBecameCurrent] — the fallback for a video that reached the player some
 *    other way (a restored queue, a jump past the look-ahead window, a slow
 *    first lookup): its sound keeps playing while the release is found, then
 *    the player cross-fades onto the release at the same point in the song.
 *
 * The picture is never shown while audio only is on (see MusicVideoEngine),
 * so until a swap lands a video is only ever heard, never seen.
 *
 * A video the listener switched to by hand ([keepAsVideo]) is left alone, and
 * nothing changes inside a Listen Together party, where everyone hears the same
 * version.
 */
internal class AudioFirst(
    private val scope: CoroutineScope,
    /** The session's active player now — it can change across a crossfade or a version swap. */
    private val currentPlayer: () -> Player?,
    /**
     * Moves the playing item onto [MediaItem] in place, aligned and
     * cross-faded; false when it can't right now.
     */
    private val swapCurrent: (MediaItem) -> Boolean,
) {
    /** Video ids whose lookup is running. Main thread only. */
    private val inFlight = HashSet<String>()

    /** Videos the listener chose to watch: never swapped again this session. */
    private val keepVideo: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Videos with no separate audio release, so they are not looked up again. */
    private val noAudio: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val enabled: Boolean
        get() = AppSettings.audioOnly.value && !ListenTogether.state.value.inParty

    fun keepAsVideo(videoId: String) {
        keepVideo += videoId
    }

    private fun wants(song: Song): Boolean =
        song.isVideo &&
            // A copy picked by hand plays instead (see OwnFirst), not YouTube's audio.
            com.music.bitchord.data.sources.PreferredSource.get(song).let {
                it !is com.music.bitchord.data.sources.PreferredSource.File && it !is com.music.bitchord.data.sources.PreferredSource.Chosen
            } &&
            song.localUri == null &&
            song.videoId !in keepVideo &&
            song.videoId !in noAudio

    /**
     * The audio release standing in for [song], carrying the queue placement of
     * the item it replaces, or null when there is none.
     */
    private suspend fun resolve(song: Song): Song? {
        val official = runCatching { YtMusicRepository.resolveAudio(song) }
            .onFailure { TrackLog.d(TAG, "audio-first lookup failed: ${it.message}", about = song.videoId) }
            .getOrNull()
        // Fan uploads — lyric videos, "Artist - Title (Official Video)",
        // VEVO and "- Topic" channels — aren't paired with a release and their
        // titles don't search well as they are; asked again by the song's
        // own name they usually are a catalogue track.
        val audio = official?.takeIf { it.videoId != song.videoId && !it.isVideo }
            ?: runCatching { VideoTitles.findRelease(song) }.getOrNull()
        if (audio == null || audio.videoId == song.videoId || audio.isVideo) {
            noAudio += song.videoId
            return null
        }
        return audio.copy(
            isVideoOrigin = true,
            queueTier = song.queueTier,
            queueEntryId = song.queueEntryId,
            radioName = song.radioName,
            playbackSource = song.playbackSource,
            playbackSourceType = song.playbackSourceType,
            playbackSourceId = song.playbackSourceId,
        )
    }

    /** Point 1: the item a play request starts on, as audio. Runs on the session's IO future. */
    suspend fun resolveStart(items: List<MediaItem>, startIndex: Int): List<MediaItem> {
        if (!enabled) return items
        val item = items.getOrNull(startIndex) ?: return items
        val song = item.toSong()
        if (!wants(song)) return items
        val audio = withTimeoutOrNull(START_TIMEOUT_MS) { resolve(song) } ?: return items
        TrackLog.d(TAG, "audio-first: '${song.title}' starts as ${audio.videoId}", about = song.videoId)
        return items.toMutableList().also { it[startIndex] = audio.toMediaItem() }
    }

    /** Point 2: replaces the next few videos in the queue while they are still upcoming. Main thread. */
    fun lookAhead(player: Player) {
        if (!enabled) return
        val count = player.mediaItemCount
        if (count == 0) return
        val current = player.currentMediaItemIndex.coerceAtLeast(0)
        val end = minOf(count - 1, current + LOOK_AHEAD)
        if (end <= current) return
        for (index in current + 1..end) {
            val item = player.getMediaItemAt(index)
            val song = item.toSong()
            if (!wants(song) || !inFlight.add(song.videoId)) continue
            scope.launch {
                val audio = try {
                    withContext(Dispatchers.IO) { resolve(song) }
                } finally {
                    inFlight -= song.videoId
                }
                audio ?: return@launch
                val live = currentPlayer() ?: return@launch
                // Found again rather than trusted: the queue may have moved,
                // and an item that has become current is the fallback's job.
                val now = live.currentMediaItemIndex
                val at = (now + 1 until live.mediaItemCount).firstOrNull {
                    live.getMediaItemAt(it).mediaId == item.mediaId
                } ?: return@launch
                live.replaceMediaItem(at, audio.toMediaItem())
                TrackLog.d(TAG, "audio-first: queued '${song.title}' replaced by ${audio.videoId}", about = song.videoId)
            }
        }
    }

    /**
     * Point 3: a video that became current anyway. It keeps playing — as
     * sound only, the picture is never loaded while audio only is on — while
     * its release is found, and is then cross-faded onto the release at the
     * matching moment by [swapCurrent], the player's own version switch. No
     * pause, no restart from zero. Main thread.
     */
    fun onBecameCurrent(item: MediaItem?) {
        if (!enabled || item == null) return
        val song = item.toSong()
        if (!wants(song) || !inFlight.add(song.videoId)) return
        scope.launch {
            val audio = try {
                withContext(Dispatchers.IO) { withTimeoutOrNull(FALLBACK_TIMEOUT_MS) { resolve(song) } }
            } finally {
                inFlight -= song.videoId
            }
            audio ?: return@launch
            // The switch declines while an Automix blend is running; that
            // blend ends within seconds, so it is asked again a few times.
            repeat(SWAP_ATTEMPTS) {
                val live = currentPlayer() ?: return@launch
                if (live.currentMediaItem?.mediaId != item.mediaId) return@launch
                if (swapCurrent(audio.toMediaItem())) {
                    TrackLog.d(TAG, "audio-first: '${song.title}' cross-faded to ${audio.videoId}", about = song.videoId)
                    return@launch
                }
                delay(SWAP_RETRY_MS)
            }
        }
    }

    private companion object {
        const val TAG = "BitChord"
        /** How many upcoming items are kept resolved ahead of the playhead. */
        const val LOOK_AHEAD = 3
        /**
         * How long a play request waits for its first track's release before
         * starting on the video's own sound. Short: a cache hit is instant,
         * and a slow lookup carries on in [onBecameCurrent] while it plays.
         */
        const val START_TIMEOUT_MS = 1_500L
        const val SWAP_ATTEMPTS = 6
        const val SWAP_RETRY_MS = 2_000L
        const val FALLBACK_TIMEOUT_MS = 15_000L
    }
}

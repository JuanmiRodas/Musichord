package com.music.bitchord.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.OwnFileMatcher
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceMap
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BitChord Audio: a song you have as a file plays from the file — by putting
 * the file in the queue in the song's place, the way [AudioFirst] puts a
 * video's audio release there.
 *
 * Done on the queue rather than inside the stream resolver because the
 * resolver never runs for a large part of what gets played: a download (your
 * offline Liked Music), a track whose bytes are already cached. Swapping the
 * item covers all of them alike, and the player then shows the file as what
 * is playing, with its own quality.
 *
 * Three points, as with [AudioFirst]: the item a play request starts on, the
 * next few items as the queue moves, and — for one that slipped through — a
 * cross-fade to the file just after it became current.
 *
 * Which file, and whether at all, is [OwnFileMatcher.decide]: the one you
 * picked from the player, else the best match if it is lossless or at least
 * 256 kbps. Nothing happens offline (the download is what can play then), in
 * a Listen Together party, or for a song pinned to YouTube.
 */
internal class OwnFirst(
    private val scope: CoroutineScope,
    private val currentPlayer: () -> Player?,
    private val swapCurrent: (MediaItem) -> Boolean,
) {
    private val inFlight = HashSet<String>()

    private val enabled: Boolean
        get() = AppSettings.ownFilesAllowedNow &&
            AppSettings.sourcePreference.value != AppSettings.SOURCE_YOUTUBE_FIRST &&
            !ListenTogether.state.value.inParty

    /**
     * BitChord Audio: JioSaavn's strictly matched copy (see [SourceMap]) may
     * stand in for a YouTube song even while JioSaavn is off for live
     * substitution — on any connection, the way a download may use it.
     */
    /** Anything at all to do: online and not in a party (each path checks its own rule). */
    private val anyEnabled: Boolean
        get() = AppSettings.meteredConnection.value != null && !ListenTogether.state.value.inParty

    private val saavnEnabled: Boolean
        get() = AppSettings.sourcePreference.value != AppSettings.SOURCE_YOUTUBE_FIRST &&
            !ListenTogether.state.value.inParty &&
            AppSettings.meteredConnection.value != null &&
            SourceMap.saavnPlayable()

    /** The best copy for [song]: your file when it is the best, else JioSaavn's when better than YouTube's. */
    private suspend fun bestFor(song: Song): Song? =
        bestForUnchecked(song)?.takeIf { it.videoId !in broken }

    private suspend fun bestForUnchecked(song: Song): Song? {
        // A copy you picked by hand comes first, on any connection: it is
        // your choice, not the own-files rule (which waits for Wi-Fi).
        when (val choice = com.music.bitchord.data.sources.PreferredSource.get(song)) {
            is com.music.bitchord.data.sources.PreferredSource.Chosen ->
                // Unless you said it is another song.
                return if (!com.music.bitchord.data.sources.NotSame.blocked(song.videoId, choice.song.videoId) && AppSettings.meteredConnection.value != null && !ListenTogether.state.value.inParty) {
                    asStandIn(choice.song, song)
                } else {
                    null
                }
            com.music.bitchord.data.sources.PreferredSource.YouTube -> return null
            is com.music.bitchord.data.sources.PreferredSource.File -> return if (enabled) fileFor(song) else null
            null -> Unit
        }
        if (enabled) fileFor(song)?.let { return it }
        if (!saavnEnabled) return null
        val copy = SourceMap.saavnSongFor(song) ?: return null
        return asStandIn(copy, song)
    }

    /** [copy] in [song]'s place in the queue, sharing its heart. */
    private fun asStandIn(copy: Song, song: Song): Song? {
        if (copy.videoId == song.videoId) return null
        com.music.bitchord.data.LocalLikes.link(copy, song)
        com.music.bitchord.data.sources.SongOrigins.record(copy, song)
        return copy.copy(
            queueTier = song.queueTier,
            queueEntryId = song.queueEntryId,
            radioName = song.radioName,
            playbackSource = song.playbackSource,
            playbackSourceType = song.playbackSourceType,
            playbackSourceId = song.playbackSourceId,
        )
    }

    private fun wants(song: Song): Boolean =
        // A video row too when you picked a copy for it by hand (your file in
        // place of a "MUSIC VIDEO" upload, say): the pick holds.
        (!song.isVideo || com.music.bitchord.data.sources.PreferredSource.get(song).let {
            it is com.music.bitchord.data.sources.PreferredSource.File || it is com.music.bitchord.data.sources.PreferredSource.Chosen
        }) &&
            !WebDavConfig.isWebDavId(song.videoId) &&
            SourceRegistry.parseTrackKey(song.videoId) == null &&
            song.localUri == null &&
            !OriginalVersion.isPinned(song.videoId)

    private suspend fun fileFor(song: Song): Song? {
        val seconds = song.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
            ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }
        fun decide() = runCatching { OwnFileMatcher.decide(song.title, song.artist, seconds, song.albumName, song.videoId) }.getOrNull()
        var verdict = decide()
        // BitChord Audio: a lossy file below JioSaavn's 320 kbps is only chosen
        // once JioSaavn has been asked for the song — the background lookup may
        // not have reached it yet, and a better copy there wins.
        val candidate = (verdict as? OwnFileMatcher.Play)?.file
        if (candidate != null && WebDavConfig.isWebDavId(candidate.videoId) &&
            OwnFileMatcher.qualityRank(candidate, seconds) < SourceMap.SAAVN_KBPS &&
            SourceMap.saavnPlayable()
        ) {
            runCatching { SourceMap.ensureSaavn(song) }
            verdict = decide()
        }
        return when (val v = verdict) {
            is OwnFileMatcher.Play -> if (v.file.videoId == song.videoId) null else v.file.also {
                com.music.bitchord.data.LocalLikes.link(it, song)
            }.copy(
                queueTier = song.queueTier,
                queueEntryId = song.queueEntryId,
                radioName = song.radioName,
                playbackSource = song.playbackSource,
                playbackSourceType = song.playbackSourceType,
                playbackSourceId = song.playbackSourceId,
            )
            else -> null
        }
    }

    /** The item a play request starts on, as your file. */
    suspend fun resolveStart(items: List<MediaItem>, startIndex: Int): List<MediaItem> {
        if (!anyEnabled) return items
        val item = items.getOrNull(startIndex) ?: return items
        val song = item.toSong()
        if (!wants(song)) return items
        val file = withContext(Dispatchers.Default) { bestFor(song) } ?: return items
        TrackLog.d(TAG, "own-first: '${song.title}' starts from your file", about = song.videoId)
        return items.toMutableList().also { it[startIndex] = file.toMediaItem() }
    }

    /** The next few items, replaced by your files while still upcoming. Main thread. */
    fun lookAhead(player: Player) {
        if (!anyEnabled) return
        val count = player.mediaItemCount
        if (count == 0) return
        val current = player.currentMediaItemIndex.coerceAtLeast(0)
        val end = minOf(count - 1, current + LOOK_AHEAD)
        if (end <= current) return
        for (index in current + 1..end) {
            val item = player.getMediaItemAt(index)
            val song = item.toSong()
            if (!wants(song) || !inFlight.add(item.mediaId)) continue
            scope.launch {
                val file = try {
                    withContext(Dispatchers.Default) { bestFor(song) }
                } finally {
                    inFlight -= item.mediaId
                }
                file ?: return@launch
                val live = currentPlayer() ?: return@launch
                val at = (live.currentMediaItemIndex + 1 until live.mediaItemCount).firstOrNull {
                    live.getMediaItemAt(it).mediaId == item.mediaId
                } ?: return@launch
                live.replaceInQueue(at, file.toMediaItem())
                TrackLog.d(TAG, "own-first: queued '${song.title}' replaced by your file", about = song.videoId)
            }
        }
    }

    /** A song that became current from YouTube anyway: cross-faded to your file. Main thread. */
    fun onBecameCurrent(item: MediaItem?) {
        if (!anyEnabled || item == null) return
        val song = item.toSong()
        if (!wants(song) || !inFlight.add(item.mediaId)) return
        scope.launch {
            val file = try {
                withContext(Dispatchers.Default) { bestFor(song) }
            } finally {
                inFlight -= item.mediaId
            }
            file ?: return@launch
            val live = currentPlayer() ?: return@launch
            if (live.currentMediaItem?.mediaId != item.mediaId) return@launch
            // Already playing a better copy (JioSaavn's 320 kbps found at play
            // time): not swapped down to the file.
            val playingFrom = com.music.bitchord.data.NerdStats.sourceFor(item.mediaId).orEmpty()
            val fileRank = if (WebDavConfig.isWebDavId(file.videoId)) OwnFileMatcher.qualityRank(file) else SourceMap.SAAVN_KBPS
            if (playingFrom.contains("JioSaavn", ignoreCase = true) && fileRank < 9_000 && fileRank <= SourceMap.SAAVN_KBPS) {
                TrackLog.d(TAG, "own-first: '${song.title}' stays on JioSaavn (better than your $fileRank kbps file)", about = song.videoId)
                return@launch
            }
            if (swapCurrent(file.toMediaItem())) {
                TrackLog.d(TAG, "own-first: '${song.title}' cross-faded to your file", about = song.videoId)
            }
        }
    }

    companion object {
        private const val TAG = "BitChord"

        /** Copies that wouldn't play this session: not put back in a song's place. */
        val broken: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        private const val LOOK_AHEAD = 3
    }
}

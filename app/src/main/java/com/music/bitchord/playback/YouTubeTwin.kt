package com.music.bitchord.playback

import android.content.Context
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: another YouTube upload of the same recording, for a track
 * YouTube won't serve under its own id — an album's copy that is region-locked,
 * Premium-only or simply has no playable client, while the same song sits on
 * YouTube under another release (a single, a best-of, the artist's own topic
 * channel, the official video at the same length).
 *
 * Found by the same matcher every other source goes through: same title and
 * version, a shared artist, the same runtime — never a karaoke, cover or
 * "originally sung by" row. What it found is kept (and backed up with the
 * other prefs), so the next play goes straight to it.
 */
internal object YouTubeTwin {
    private const val TAG = "BitChord"
    private const val PREFS = "bitchord_youtube_twins"
    private const val NONE = "-"

    /** Video uploads only when their runtime is this close: no intro, no skit. */
    private const val VIDEO_SECONDS = 4

    private val known = ConcurrentHashMap<String, String>()

    /**
     * A URL for [target] from another upload than [videoId], or null when
     * nothing on YouTube is the same recording and plays.
     */
    suspend fun stream(context: Context, videoId: String, target: TrackMatcher.Target): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val held = known[videoId] ?: prefs.getString(videoId, null)
        if (held == NONE) return null
        if (held != null) {
            tryResolve(held)?.let {
                TrackLog.d(TAG, "'${target.title}' plays from its other YouTube upload $held", about = videoId)
                return it
            }
        }
        val candidates = withTimeoutOrNull(15_000) { candidates(videoId, target) }.orEmpty()
        for (candidate in candidates.take(4)) {
            if (candidate.videoId == held) continue
            val url = tryResolve(candidate.videoId) ?: continue
            known[videoId] = candidate.videoId
            prefs.edit().putString(videoId, candidate.videoId).apply()
            TrackLog.i(
                TAG,
                "YouTube won't serve '${target.title}' as $videoId; playing the same recording " +
                    "from ${candidate.videoId} ('${candidate.title}' · ${candidate.albumName ?: candidate.artist})",
                about = videoId,
            )
            return url
        }
        // Remembered as "none" only for this run: a new upload can appear.
        known[videoId] = NONE
        TrackLog.w(TAG, "no other YouTube upload of '${target.title}' plays", about = videoId)
        return null
    }

    private suspend fun tryResolve(id: String): String? = try {
        StreamResolver.resolve(id)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }

    private suspend fun candidates(videoId: String, target: TrackMatcher.Target): List<Song> {
        val karaoke = TrackMatcher.isKaraokeLike(target.title, target.album)
        val query = TrackMatcher.queries(target).firstOrNull() ?: return emptyList()
        val songs = search(query, SearchFilter.SONGS)
        val videos = search(query, SearchFilter.VIDEOS)
        val clean = { list: List<Song> ->
            list.filter { it.videoId != videoId && (karaoke || !TrackMatcher.isKaraokeLike(it.title, it.albumName)) }
        }
        val fromSongs = TrackMatcher.ranked(clean(songs), target)
        val fromVideos = TrackMatcher.ranked(clean(videos), target)
            .filter { TrackMatcher.withinSeconds(it, target, VIDEO_SECONDS) }
        return (fromSongs + fromVideos).distinctBy { it.videoId }
    }

    private suspend fun search(query: String, filter: SearchFilter): List<Song> =
        YtMusicRepository.search(query, filter).getOrNull().orEmpty().mapNotNull {
            when (it) {
                is SearchResult.Track -> it.song
                is SearchResult.TopTrack -> it.song
                else -> null
            }
        }
}

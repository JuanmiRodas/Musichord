package com.music.bitchord.playback

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.TrackLog

/**
 * BitChord Audio: the YouTube songs YouTube has refused here for good —
 * Premium-only, unavailable, region-locked. For these your own file plays
 * whatever its quality (it is the only copy there is), a "keep the YouTube
 * version" pin no longer holds, and the queue swaps the file in ahead of time.
 *
 * Kept (and backed up with the other prefs) so the next play doesn't have to
 * fail first; a song YouTube later serves again is taken off the list.
 */
object YouTubeRefusals {
    private const val PREFS = "bitchord_youtube_refused"
    private var prefs: SharedPreferences? = null
    @Volatile private var ids: Set<String> = emptySet()

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        ids = p.all.keys.toSet()
    }

    fun has(videoId: String?): Boolean = videoId != null && videoId in ids

    fun record(videoId: String, reason: String) {
        if (videoId in ids) return
        ids = ids + videoId
        prefs?.edit()?.putString(videoId, reason)?.apply()
        TrackLog.i("BitChord", "YouTube refuses this song here ($reason): your files and other sources may stand in", about = videoId)
    }

    fun clear(videoId: String) {
        if (videoId !in ids) return
        ids = ids - videoId
        prefs?.edit()?.remove(videoId)?.apply()
    }
}

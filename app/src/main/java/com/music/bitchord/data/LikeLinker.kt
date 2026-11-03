package com.music.bitchord.data

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.JapaneseNames
import com.music.bitchord.data.sources.OwnFileMatcher
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.delay

/**
 * BitChord Audio: keeps Liked Music one list with YouTube Music's.
 *
 * A song liked here that YouTube didn't seem to have when you liked it (your
 * pCloud file, a JioSaavn copy, a song whose name is spelt differently there)
 * is liked only on the phone. In the background, on Wi-Fi, each one is looked
 * for on YouTube Music again — by its name, its Japanese names, artist and
 * length — and once found it is liked there, so the YouTube Music app has it
 * too, and the copy here is linked to it (its heart is that song's heart).
 * Each song is tried again once a week until found.
 */
object LikeLinker {

    private const val TAG = "BitChord"
    private const val PREFS = "bitchord_like_linker"
    private const val RETRY_AFTER_MS = 7L * 24 * 60 * 60 * 1000

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    @Volatile
    private var running = false

    suspend fun run() {
        if (running) return
        running = true
        try {
            val now = System.currentTimeMillis()
            val local = LocalLikes.liked.value.values.filter { s ->
                LocalLikes.youTubeOf(s.videoId) == null &&
                    now - (prefs?.getLong(s.videoId, 0L) ?: 0L) > RETRY_AFTER_MS
            }
            if (local.isEmpty()) return
            TrackLog.d(TAG, "likes: looking for ${local.size} songs liked only here on YouTube Music")
            var linked = 0
            for (song in local) {
                if (AppSettings.meteredConnection.value != false) break
                val yt = runCatching { find(song) }.getOrNull()
                if (yt == null) {
                    prefs?.edit()?.putLong(song.videoId, System.currentTimeMillis())?.apply()
                } else if (YtMusicRepository.rate(yt.videoId, LikeStatus.LIKE).isSuccess) {
                    LocalLikes.link(song, yt)
                    LocalLikes.setLocal(song, false)
                    linked++
                } else {
                    break // not signed in, or YouTube refusing: next time
                }
                delay(1_500)
            }
            TrackLog.d(TAG, "likes: $linked songs now liked on YouTube Music too")
        } finally {
            running = false
        }
    }

    /** The YouTube Music song [song] is: by its name, then its Japanese names; same artist and length (±3 s). */
    private suspend fun find(song: Song): Song? {
        LocalLikes.findOnYouTube(song)?.let { return it }
        val names = runCatching { JapaneseNames.of(song) }.getOrDefault(JapaneseNames.Names.NONE)
        if (names.isEmpty) return null
        val seconds = song.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
            ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }
        val keys = OwnFileMatcher.titleKeys(song.title) + runCatching { OwnFileMatcher.soundKeys(song.title) }.getOrDefault(emptySet())
        for (q in names.queries(song.artist).take(3)) {
            val rows = YtMusicRepository.search(q, SearchFilter.SONGS).getOrNull().orEmpty().mapNotNull {
                when (it) {
                    is SearchResult.Track -> it.song
                    is SearchResult.TopTrack -> it.song
                    else -> null
                }
            }.filterNot { it.isVideo }
            rows.firstOrNull { r ->
                val rKeys = OwnFileMatcher.titleKeys(r.title) + runCatching { OwnFileMatcher.soundKeys(r.title) }.getOrDefault(emptySet())
                val rSec = TrackMatcher.secondsOf(r.durationText)
                rKeys.any { it in keys } &&
                    (seconds == null || rSec == null || kotlin.math.abs(seconds - rSec) <= 3) &&
                    (OwnFileMatcher.sameArtist(song.artist, r.artist) || names.artists.any { OwnFileMatcher.sameArtist(it, r.artist) })
            }?.let { return it }
            delay(500)
        }
        return null
    }
}

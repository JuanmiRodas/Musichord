package com.music.bitchord.data

import com.music.bitchord.data.model.LikeStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * BitChord Audio: whether a song is liked, the way the player screen says it —
 * this session's taps, the whole saved Liked Music, your files through the
 * YouTube song they play for, and songs liked only here. The notification,
 * the lock screen and the widget read it too, so their star is the screen's.
 *
 * [toggle] is the player screen's own heart while the app is open (it knows
 * other uploads of a liked song and your own files); without it the caller
 * falls back to a plain YouTube rating.
 */
object LikeLookup {

    @Volatile
    var toggle: ((String) -> Unit)? = null

    fun status(videoId: String): LikeStatus? {
        val overrides = LikeState.overrides.value
        overrides[videoId]?.let { return it }
        if (LocalLikes.liked.value.containsKey(videoId)) return LikeStatus.LIKE
        if (videoId in LikedMusicCache.likedIds.value) return LikeStatus.LIKE
        LocalLikes.links.value[videoId]?.let { yt ->
            overrides[yt]?.let { return it }
            if (yt in LikedMusicCache.likedIds.value) return LikeStatus.LIKE
        }
        return null
    }

    fun isLiked(videoId: String): Boolean = status(videoId) == LikeStatus.LIKE

    /** Changes whenever any of what [status] reads changes. */
    val changes: Flow<Any> = combine(
        LikeState.overrides,
        LocalLikes.liked,
        LocalLikes.links,
        LikedMusicCache.likedIds,
    ) { a, b, c, d -> listOf(a.size, a.hashCode(), b.size, c.size, d.size, d.hashCode()) }
}

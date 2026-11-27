package com.music.bitchord.data

/**
 * BitChord Audio: YouTube saying "too many requests" — its "Sorry…" page (a 403 that
 * is not an expired session). While it holds, the background work steps aside for
 * [PAUSE_MS] so what you ask for yourself (a search, a video) still gets through,
 * and the request is not sent again straight away, which only lengthens the block.
 */
object YouTubeLimit {

    private const val PAUSE_MS = 30 * 60_000L

    @Volatile private var until = 0L

    /** Whether [failure] is YouTube's rate-limit page. */
    fun isLimit(failure: Throwable?): Boolean {
        val m = failure?.message ?: return false
        return m.contains("<title>Sorry", ignoreCase = true) || m.contains("/sorry/", ignoreCase = true)
    }

    /** Noted when YouTube answered with its rate-limit page. */
    fun hit(label: String) {
        val now = System.currentTimeMillis()
        val fresh = now >= until
        until = now + PAUSE_MS
        if (fresh) TrackLog.w("YouTubeLimit", "YouTube is limiting requests ($label); background work leaves it alone for 30 min")
    }

    /** Whether background work should leave YouTube alone now. */
    fun active(): Boolean = System.currentTimeMillis() < until
}

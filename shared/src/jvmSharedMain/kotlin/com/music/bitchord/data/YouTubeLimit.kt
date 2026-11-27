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

    /**
     * Marks work that runs by itself (the night's jobs): its YouTube Music requests go one
     * at a time, [GAP_MS] apart, so they never add up to what Google takes for a robot.
     * What you ask for yourself carries no mark and is never held back.
     */
    object Background : kotlin.coroutines.AbstractCoroutineContextElement(BackgroundKey)

    object BackgroundKey : kotlin.coroutines.CoroutineContext.Key<Background>

    private const val GAP_MS = 4_000L
    private val paceLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var lastBackground = 0L

    /** Waits for background work's turn at YouTube (nothing for your own requests). */
    suspend fun pace() {
        if (kotlin.coroutines.coroutineContext[BackgroundKey] == null) return
        paceLock.lock()
        try {
            val wait = lastBackground + GAP_MS - System.currentTimeMillis()
            if (wait > 0) kotlinx.coroutines.delay(wait)
            lastBackground = System.currentTimeMillis()
        } finally {
            paceLock.unlock()
        }
    }
}

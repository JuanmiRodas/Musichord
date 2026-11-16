package com.music.bitchord.playback.smart

import java.util.concurrent.ConcurrentHashMap

/**
 * Whole-track analyses that ran out of memory, so they are tried again later instead of being
 * written off for the session (fork; STATUS item 7).
 *
 * [TrackAnalyzer.request] records any throwable from the whole-track pass as "analysed, nothing
 * found", so the track is not retried on every 250 ms tick. Right for a track that cannot be
 * decoded; wrong for an `OutOfMemoryError`, which says how full the heap was at that moment
 * (the owner's phone failed a 52 MB allocation with 46 MB free on one song and analysed it fine
 * another time), not anything about the track. Such a track was left without Automix analysis
 * until the app restarted.
 *
 * So an OOM is counted instead: the track waits [backoffMs] (a tick's retry would meet the same
 * heap) and is tried again by the next `request` after that, which comes while it is next in the
 * queue or once it plays. After [maxAttempts] OOMs it is written off as before, so a track that
 * never fits does not keep allocating every half minute.
 */
internal class ForkOomRetry(
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val backoffMs: Long = BACKOFF_MS,
    private val clock: () -> Long,
) {
    private class Strikes(val count: Int, val at: Long)

    private val strikes = ConcurrentHashMap<String, Strikes>()

    /** False while [trackId] is waiting after an OOM; true once the wait is over or the cap was reached. */
    fun mayRun(trackId: String): Boolean {
        val last = strikes[trackId] ?: return true
        return last.count >= maxAttempts || clock() - last.at >= backoffMs
    }

    /**
     * Records an OOM for [trackId]. True means "leave it unrecorded, it will be retried"; false
     * means the cap is reached and the caller writes it off as it does any other failure.
     */
    fun retryLater(trackId: String): Boolean {
        val now = clock()
        val count = strikes.merge(trackId, Strikes(1, now)) { old, _ -> Strikes(old.count + 1, now) }!!.count
        return count < maxAttempts
    }

    /** The track analysed, or a new copy of it reopened it: it starts level. */
    fun reset(trackId: String) {
        strikes.remove(trackId)
    }

    fun clear() = strikes.clear()

    companion object {
        const val MAX_ATTEMPTS = 3
        const val BACKOFF_MS = 30_000L

        /** True when [error], or anything it wraps, is an OutOfMemoryError. */
        fun isOom(error: Throwable): Boolean {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < 8) {
                if (current is OutOfMemoryError) return true
                current = current.cause
                depth++
            }
            return false
        }
    }
}

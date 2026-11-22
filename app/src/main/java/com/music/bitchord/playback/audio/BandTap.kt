package com.music.bitchord.playback.audio

import com.music.bitchord.ui.player.AudioBands
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * BitChord Audio: bass / mids / highs for the LastWave-style player's waves.
 *
 * Reads the processed block (never changes it) with two one-pole filters —
 * under ~200 Hz is the bass, 200 Hz–2.5 kHz the mids, the rest the highs —
 * which is a few multiplications a sample, and only while the player wants it
 * ([AudioBands.wanted]). Each block's levels are kept with its presentation
 * time and handed to the player when the sink's clock reaches that time, so
 * the waves move with what is heard rather than with what is decoded ahead.
 */
internal class BandTap {
    private var lowA = 0f
    private var midA = 0f
    private var low = 0f
    private var high = 0f

    private val times = LongArray(RING)
    private val levels = FloatArray(RING * 3)
    private var head = 0
    private var count = 0
    private var lastPublished = Long.MIN_VALUE

    private val peak = floatArrayOf(1e-4f, 1e-4f, 1e-4f)
    private val raws = FloatArray(3)
    private val out = FloatArray(3)
    private var bassAverage = 0f
    private var lastBeatUs = Long.MIN_VALUE

    fun configure(sampleRate: Int) {
        if (sampleRate <= 0) return
        lowA = 1f - exp(-2.0 * Math.PI * LOW_HZ / sampleRate).toFloat()
        midA = 1f - exp(-2.0 * Math.PI * MID_HZ / sampleRate).toFloat()
        clear()
    }

    fun clear() {
        synchronized(this) {
            count = 0
            head = 0
            lastPublished = Long.MIN_VALUE
        }
        low = 0f
        high = 0f
    }

    fun analyze(block: AudioBlock, timeUs: Long) {
        if (!AudioBands.wanted || lowA == 0f || timeUs < 0L) return
        val frames = block.frameCount
        val ch = block.channelCount
        if (frames <= 0 || ch <= 0) return
        val s = block.samples
        var b = 0.0
        var m = 0.0
        var t = 0.0
        var lp1 = low
        var lp2 = high
        var i = 0
        val inv = 1f / ch
        for (f in 0 until frames) {
            var x = 0f
            for (c in 0 until ch) x += s[i + c]
            i += ch
            x *= inv
            lp1 += lowA * (x - lp1)
            lp2 += midA * (x - lp2)
            val bass = lp1
            val mid = lp2 - lp1
            val tre = x - lp2
            b += bass * bass
            m += mid * mid
            t += tre * tre
        }
        low = lp1
        high = lp2
        synchronized(this) {
            val slot = head
            times[slot] = timeUs
            levels[slot * 3] = sqrt(b / frames).toFloat()
            levels[slot * 3 + 1] = sqrt(m / frames).toFloat()
            levels[slot * 3 + 2] = sqrt(t / frames).toFloat()
            head = (head + 1) % RING
            if (count < RING) count++
        }
    }

    /** The sink's clock is at [positionUs]: publish the block being heard now. */
    fun publish(positionUs: Long) {
        if (!AudioBands.wanted) return
        var found = -1
        synchronized(this) {
            if (count == 0) return
            var best = Long.MIN_VALUE
            for (k in 0 until count) {
                val slot = (head - 1 - k + RING) % RING
                val at = times[slot]
                if (at <= positionUs && at > best) {
                    best = at
                    found = slot
                }
            }
            if (found < 0 || times[found] == lastPublished) return
            lastPublished = times[found]
        }
        val raw0 = levels[found * 3]
        val raw1 = levels[found * 3 + 1]
        val raw2 = levels[found * 3 + 2]
        raws[0] = raw0
        raws[1] = raw1
        raws[2] = raw2
        for (k in 0..2) {
            peak[k] = maxOf(raws[k], peak[k] * PEAK_DECAY, 1e-4f)
            out[k] = sqrt((raws[k] / peak[k]).coerceIn(0f, 1f))
        }
        // A kick: the bass well above its own recent average, not too soon after the last one.
        val at = lastPublished
        val beat = raw0 > bassAverage * BEAT_RATIO && raw0 > peak[0] * 0.35f &&
            (lastBeatUs == Long.MIN_VALUE || at - lastBeatUs > MIN_BEAT_GAP_US || at < lastBeatUs)
        if (beat) lastBeatUs = at
        bassAverage += (raw0 - bassAverage) * 0.08f
        AudioBands.publish(out[0], out[1], out[2], beat)
    }

    private companion object {
        const val RING = 96
        const val LOW_HZ = 200.0
        const val MID_HZ = 2500.0
        /** Per published block (~100 a second): a loud bar fades from the scale in a few seconds. */
        const val PEAK_DECAY = 0.996f
        const val BEAT_RATIO = 1.45f
        const val MIN_BEAT_GAP_US = 260_000L
    }
}

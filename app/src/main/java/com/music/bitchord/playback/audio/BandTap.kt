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
        resetRhythm()
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
        // BitChord Audio: the thumb's beat follows one part of the song and keeps to it —
        // the drums', the piano's, the guitar's: whichever band hits most regularly. Each
        // band's onsets (a rise well above its usual rise) are timed; the band whose gaps
        // between onsets are the steadiest leads, and only changes when another has been
        // clearly steadier for a while.
        val at = lastPublished
        if (at < lastAt) resetRhythm()
        lastAt = at
        var beat = false
        for (k in 0..2) {
            val flux = (raws[k] - prevRaw[k]).coerceAtLeast(0f)
            prevRaw[k] = raws[k]
            fluxMean[k] += (flux - fluxMean[k]) * 0.02f
            fluxVar[k] += ((flux - fluxMean[k]) * (flux - fluxMean[k]) - fluxVar[k]) * 0.02f
            val threshold = fluxMean[k] + 1.6f * sqrt(fluxVar[k])
            // (Never hit yet counts as long ago: at − MIN_VALUE overflowed, and no onset was ever taken.)
            val since = if (lastOnset[k] == Long.MIN_VALUE) Long.MAX_VALUE else at - lastOnset[k]
            if (flux > threshold && flux > peak[k] * 0.04f && since > MIN_ONSET_GAP_US) {
                if (lastOnset[k] != Long.MIN_VALUE && since < 2_000_000L) {
                    gaps[k][gapHead[k]] = since.toFloat()
                    gapHead[k] = (gapHead[k] + 1) % GAPS
                    if (gapCount[k] < GAPS) gapCount[k]++
                }
                lastOnset[k] = at
                // The thumb's pulse: the drums and bass first; another band only while
                // the bass has had no hit for a few seconds (and then the steadiest one).
                if (k == 0 || (k == lead && (lastOnset[0] == Long.MIN_VALUE || at - lastOnset[0] > 3_000_000L))) beat = true
            }
        }
        if (at - lastChoice > CHOOSE_EVERY_US) {
            lastChoice = at
            val scores = FloatArray(3) { steadiness(it) }
            val best = scores.indices.maxByOrNull { scores[it] } ?: lead
            if (best != lead && scores[best] > scores[lead] * 1.35f + 0.05f) {
                if (++challenger[best] >= 3) { lead = best; challenger.fill(0) }
            } else {
                challenger.fill(0)
            }
        }
        AudioBands.publish(out[0], out[1], out[2], beat)
    }

    private var lead = 0
    private var lastAt = Long.MIN_VALUE
    private var lastChoice = Long.MIN_VALUE
    private val prevRaw = FloatArray(3)
    private val fluxMean = FloatArray(3)
    private val fluxVar = FloatArray(3)
    private val lastOnset = LongArray(3) { Long.MIN_VALUE }
    private val gaps = Array(3) { FloatArray(GAPS) }
    private val gapHead = IntArray(3)
    private val gapCount = IntArray(3)
    private val challenger = IntArray(3)

    /** How steady a band's onsets are: many, evenly spaced (or at multiples of one gap). */
    private fun steadiness(k: Int): Float {
        val n = gapCount[k]
        if (n < 4) return 0f
        val g = gaps[k].copyOf(n).sorted()
        val median = g[n / 2]
        if (median <= 0f) return 0f
        // Each gap measured against the nearest whole multiple of the median: a skipped hit still counts as on time.
        var err = 0f
        for (x in g) {
            val m = (x / median).let { kotlin.math.round(it).coerceAtLeast(1f) }
            err += kotlin.math.abs(x - m * median) / median
        }
        return (1f / (err / n + 0.08f)) * (n / GAPS.toFloat())
    }

    private fun resetRhythm() {
        lastOnset.fill(Long.MIN_VALUE)
        gapCount.fill(0)
        gapHead.fill(0)
        challenger.fill(0)
        lastChoice = Long.MIN_VALUE
    }

    private companion object {
        const val RING = 96
        const val LOW_HZ = 200.0
        const val MID_HZ = 2500.0
        /** Per published block (~100 a second): a loud bar fades from the scale in a few seconds. */
        const val PEAK_DECAY = 0.996f
        const val MIN_ONSET_GAP_US = 180_000L
        const val GAPS = 16
        const val CHOOSE_EVERY_US = 2_000_000L
    }
}

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
        // BitChord Audio: the thumb's pulse — the song's beat, locked on. The kick (bass energy
        // well above its last second's average, rising) is detected; the gaps between kicks
        // give the tempo; once the kicks are regular, the pulse lands on every beat of that
        // tempo, kept in phase by each kick that comes — so it follows the drums steadily
        // even where a hit is soft or missing, and never fires at random.
        val at = lastPublished
        if (at < lastAt || at - lastAt > 1_000_000L) resetRhythm()
        lastAt = at
        AudioBands.publish(out[0], out[1], out[2], beatAt(at, raw0))
    }

    private var lastAt = Long.MIN_VALUE
    private val energy = FloatArray(AVG_BLOCKS)
    private var energyHead = 0
    private var energyCount = 0
    private var prevEnergy = 0f
    private var lastKick = Long.MIN_VALUE
    private val gaps = LongArray(GAPS)
    private var gapHead = 0
    private var gapCount = 0
    private var period = 0L
    private var nextBeat = Long.MIN_VALUE
    private var locked = 0

    private fun beatAt(at: Long, bass: Float): Boolean {
        val e = bass * bass
        var avg = 0f
        for (k in 0 until energyCount) avg += energy[k]
        avg = if (energyCount > 0) avg / energyCount else e
        var variance = 0f
        for (k in 0 until energyCount) variance += (energy[k] - avg) * (energy[k] - avg)
        variance = if (energyCount > 0) variance / energyCount else 0f
        energy[energyHead] = e
        energyHead = (energyHead + 1) % AVG_BLOCKS
        if (energyCount < AVG_BLOCKS) energyCount++
        // The louder the bass swings, the higher the bar (the classic sound-energy beat rule).
        val c = (1.55f - 0.25f * (variance / (avg * avg + 1e-9f)).coerceAtMost(2f)).coerceAtLeast(1.25f)
        val rising = e > prevEnergy
        prevEnergy = e
        val sinceKick = if (lastKick == Long.MIN_VALUE) Long.MAX_VALUE else at - lastKick
        val kick = energyCount >= AVG_BLOCKS / 2 && rising && e > avg * c && e > 1e-7f && sinceKick > MIN_KICK_GAP_US
        if (kick) {
            if (lastKick != Long.MIN_VALUE && sinceKick < 2_000_000L) {
                gaps[gapHead] = sinceKick
                gapHead = (gapHead + 1) % GAPS
                if (gapCount < GAPS) gapCount++
                tempo()
            }
            lastKick = at
        }
        if (period <= 0L || locked < 3) {
            // Not locked yet: the kicks themselves.
            if (kick && period > 0L) nextBeat = at + period
            return kick
        }
        // Locked: a beat on the grid; a kick near it pulls the grid onto the kick.
        if (kick) {
            val off = at - (nextBeat - period)
            if (kotlin.math.abs(off) < period / 4) {
                nextBeat = at + period
                locked = minOf(locked + 1, 12)
                return true
            } else if (kotlin.math.abs(at - nextBeat) < period / 4) {
                nextBeat = at + period
                locked = minOf(locked + 1, 12)
                return true
            } else {
                locked--
            }
        }
        if (at >= nextBeat) {
            while (nextBeat <= at) nextBeat += period
            // Beats with no kick for long (a break): the grid fades out until the drums return.
            if (lastKick != Long.MIN_VALUE && at - lastKick > period * 6) { locked = 0; return false }
            return true
        }
        return false
    }

    /** The tempo from the kicks' gaps: the most common gap, folded into one beat (0.33–1 s). */
    private fun tempo() {
        if (gapCount < 4) return
        val folded = (0 until gapCount).map { k ->
            var g = gaps[k].toDouble()
            while (g > 1_000_000.0) g /= 2.0
            while (g < 330_000.0) g *= 2.0
            g
        }.sorted()
        val median = folded[folded.size / 2]
        val close = folded.count { kotlin.math.abs(it - median) < median * 0.08 }
        if (close * 2 >= folded.size) {
            val p = folded.filter { kotlin.math.abs(it - median) < median * 0.08 }.average().toLong()
            if (period == 0L || kotlin.math.abs(p - period) > period / 12) {
                period = p
                locked = 3
            } else {
                period = (period * 3 + p) / 4
                locked = minOf(locked + 1, 12)
            }
            if (nextBeat == Long.MIN_VALUE || nextBeat < lastKick) nextBeat = lastKick + period
        }
    }

    private fun resetRhythm() {
        energyCount = 0
        energyHead = 0
        lastKick = Long.MIN_VALUE
        gapCount = 0
        gapHead = 0
        period = 0L
        nextBeat = Long.MIN_VALUE
        locked = 0
    }

    private companion object {
        const val RING = 96
        const val LOW_HZ = 200.0
        const val MID_HZ = 2500.0
        /** Per published block (~100 a second): a loud bar fades from the scale in a few seconds. */
        const val PEAK_DECAY = 0.996f
        /** About a second of blocks (~100 a second published). */
        const val AVG_BLOCKS = 90
        const val MIN_KICK_GAP_US = 220_000L
        const val GAPS = 12
    }
}

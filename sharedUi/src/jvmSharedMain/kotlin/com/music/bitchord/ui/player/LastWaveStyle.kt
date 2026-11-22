package com.music.bitchord.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * BitChord Audio: what the music sounds like right now, in three bands, for the
 * LastWave-style player. Written by the audio sink (at the moment the sound is
 * actually heard, not when it is decoded) and read by the player's frame clock.
 *
 * Nothing is measured unless someone [want]s it: the sink checks [wanted]
 * before touching a sample, so the classic player and a closed player cost nothing.
 */
object AudioBands {
    /** 0..1, each normalised against its own recent peak. */
    @Volatile var bass = 0f
        private set
    @Volatile var mid = 0f
        private set
    @Volatile var treble = 0f
        private set
    /** [System.nanoTime] of the last reading; stale readings mean nothing is being heard. */
    @Volatile var updatedAt = 0L
        private set
    /** [System.nanoTime] of the last kick, a sudden rise of the bass. */
    @Volatile var beatAt = 0L
        private set

    private val wanters = AtomicInteger()
    val wanted: Boolean get() = wanters.get() > 0

    fun want(on: Boolean) {
        if (on) wanters.incrementAndGet() else wanters.updateAndGet { (it - 1).coerceAtLeast(0) }
    }

    fun publish(bass: Float, mid: Float, treble: Float, beat: Boolean) {
        this.bass = bass
        this.mid = mid
        this.treble = treble
        val now = System.nanoTime()
        if (beat) beatAt = now
        updatedAt = now
    }
}

/**
 * The three waves' state. Moved forward only while the song plays, so a pause
 * leaves every wave exactly where it was — no fall back to a resting shape.
 */
@Stable
class WaveMotion internal constructor() {
    /** One phase per wave, in radians: bass, mids, highs. */
    internal val phase = floatArrayOf(2.2f, 1.2f, 0f)
    internal val level = floatArrayOf(0.42f, 0.38f, 0.34f)
    /** The same bands followed over a second or two: the backdrop's, which must never flicker. */
    internal val slow = floatArrayOf(0.42f, 0.38f, 0.34f)
    /** The backdrop's own slow clock, seconds. */
    internal var drift = 0f
    internal var beat = 0f
    private var beatSeen = 0L

    /** Bumped every frame the waves move: read in a draw to redraw with them. */
    var frame by mutableLongStateOf(0L)
        internal set

    internal fun step(dt: Float) {
        val fresh = System.nanoTime() - AudioBands.updatedAt < STALE_NS
        var energy = 0f
        for (i in 0..2) {
            val target = if (fresh) {
                when (i) { 0 -> AudioBands.bass; 1 -> AudioBands.mid; else -> AudioBands.treble }
            } else {
                IDLE_LEVEL[i]
            }
            level[i] += (target - level[i]) * (1f - exp(-dt * RESPONSE[i]))
            slow[i] += (target - slow[i]) * (1f - exp(-dt * SLOW_RESPONSE))
            energy += slow[i]
            phase[i] = (phase[i] + dt * SPEED[i] * (0.55f + 0.8f * level[i])) % TWO_PI_X8
        }
        drift += dt * (0.7f + 0.5f * energy / 3f)
        val beatAt = AudioBands.beatAt
        if (fresh && beatAt != beatSeen) {
            beatSeen = beatAt
            beat = 1f
        }
        beat *= exp(-dt / BEAT_DECAY_S)
        frame++
    }

    private companion object {
        const val STALE_NS = 450_000_000L
        val IDLE_LEVEL = floatArrayOf(0.42f, 0.38f, 0.34f)
        /** How fast each wave follows its band, per second: the highs quicker, the bass swells. */
        val RESPONSE = floatArrayOf(6f, 8f, 10f)
        const val SLOW_RESPONSE = 0.7f
        /** LastWave's periods, 2.4 s, 1.8 s and 1.3 s, as radians per second. */
        val SPEED = floatArrayOf(2.618f, 3.491f, 4.833f)
        const val BEAT_DECAY_S = 0.22f
        const val TWO_PI_X8 = (2.0 * PI * 8.0).toFloat()
    }
}

/** The LastWave player look: present only while the setting is on. */
@Stable
class LastWaveLook internal constructor(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    /** The artwork's colours deepened and saturated, as LastWave's fluid backdrop shows them. */
    val deep: List<Color>,
    val motion: WaveMotion,
)

val LocalLastWave = compositionLocalOf<LastWaveLook?> { null }

/**
 * The look for the player, or null with the classic style. The waves run on the
 * frame clock only while [playing]; the bands are measured only while this is composed.
 */
@Composable
internal fun rememberLastWaveLook(enabled: Boolean, artworkUrl: String?, playing: Boolean): LastWaveLook? {
    if (!enabled) return null
    val motion = remember { WaveMotion() }
    DisposableEffect(Unit) {
        AudioBands.want(true)
        onDispose { AudioBands.want(false) }
    }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                motion.step(dt)
            }
        }
    }
    val colors = rememberArtworkColors(artworkUrl).colors
    return remember(colors) {
        val c0 = colors.getOrElse(0) { Color(0xFF6A7FDB) }
        val c1 = colors.getOrElse(1) { c0 }
        val c2 = colors.getOrElse(2) { c1 }
        LastWaveLook(
            primary = luminous(c0, 0.38f),
            secondary = luminous(c1, 0.32f),
            tertiary = luminous(c2, 0.28f),
            deep = listOf(c0, c1, c2).map { deepen(it) },
            motion = motion,
        )
    }
}

/** LastWave's ensureLuminousForDarkBackground: lifted toward white until it reads on dark. */
private fun luminous(c: Color, min: Float): Color {
    val l = c.luminance()
    if (l >= min) return c
    return lerp(c, Color.White, ((min - l) / (1f - l)).coerceIn(0f, 1f))
}

/** LastWave's fluid grade: saturation ×1.9, brightness ×0.52. */
private fun deepen(c: Color): Color {
    val hsv = c.toHsv()
    hsv[1] = (hsv[1] * 1.9f).coerceIn(0f, 1f)
    hsv[2] = (hsv[2] * 0.52f).coerceIn(0.06f, 0.42f)
    return hsvColor(hsv)
}

/** LastWave's shiftTonal: lightness moved by [lightness], saturation scaled by [saturation]. */
private fun Color.shiftTonal(lightness: Float, saturation: Float): Color {
    val hsv = toHsv()
    val original = hsv[1]
    hsv[1] = (hsv[1] * saturation).coerceIn(if (original > 0.05f) 0.15f else 0f, 1f)
    hsv[2] = (hsv[2] + lightness).coerceIn(0.25f, 1f)
    return hsvColor(hsv)
}

private fun Color.toHsv(): FloatArray {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val s = if (max == 0f) 0f else d / max
    return floatArrayOf(h, s, max)
}

private fun hsvColor(hsv: FloatArray): Color = Color.hsv(hsv[0].coerceIn(0f, 359.99f), hsv[1].coerceIn(0f, 1f), hsv[2].coerceIn(0f, 1f))

/** Paths reused across frames, so the waves allocate nothing while they move. */
internal class WavePaths {
    val waves = Array(3) { Path() }
}

/**
 * LastWave's wavy seek bar: a thin rounded track and, over the played part,
 * three rolling contour lines in the artwork's colours — here one per band,
 * bass the longest and highest, highs the shortest — each rising and rolling
 * with its own part of the sound. Paused, they hold the shape they had;
 * dragging flattens them so the thumb reads, as LastWave does.
 */
internal fun DrawScope.drawLastWaveTrack(
    look: LastWaveLook,
    paths: WavePaths,
    fraction: Float,
    flatten: Float,
    showThumb: Boolean,
    /** Drawn over the empty track and under the played part, as the Automix window is. */
    underFill: DrawScope.(top: Float, thickness: Float) -> Unit = { _, _ -> },
) {
    val width = size.width
    if (width <= 0f || size.height <= 0f) return
    val motion = look.motion
    motion.frame // redraw with the waves
    val centerY = size.height / 2f + 3.dp.toPx()
    val thumbX = (fraction.coerceIn(0f, 1f) * width).coerceIn(0f, width)
    val trackPx = 4.5.dp.toPx()
    val half = trackPx / 2f
    val topY = centerY - half

    drawLine(Color.White.copy(alpha = 0.22f), Offset(0f, centerY), Offset(width, centerY), trackPx, StrokeCap.Round)
    underFill(topY, trackPx)

    val primary = look.primary
    val baseline = listOf(primary.shiftTonal(0.20f, 0.90f), primary.shiftTonal(-0.05f, 1.15f))
    if (thumbX > 0f) {
        val transition = minOf(44.dp.toPx(), thumbX * 0.48f)
        val invTransition = if (transition > 0f) 1f / transition else 0f
        val step = 1.5.dp.toPx()
        val pulse = 1f + 0.06f * motion.beat
        for (i in 0..2) {
            val amplitude = (WAVE_AMP_DP[i] * (0.35f + 0.75f * motion.level[i]) * pulse).dp.toPx()
                .let { it + (1.2.dp.toPx() - it) * flatten }
            val k = (2 * PI / WAVE_LENGTH_DP[i].dp.toPx()).toFloat()
            val path = paths.waves[i]
            path.reset()
            path.moveTo(0f, topY)
            var x = 0f
            while (x < thumbX) {
                val envelope = if (invTransition > 0f) {
                    smootherstep(x * invTransition) * smootherstep((thumbX - x) * invTransition)
                } else {
                    1f
                }
                path.lineTo(x, topY - (0.5f + 0.5f * sin(x * k - motion.phase[i])) * amplitude * envelope)
                x += step
            }
            path.lineTo(thumbX, topY)
        }
        val activeWidth = thumbX.coerceAtLeast(1f)
        val tertiary = look.tertiary
        val secondary = look.secondary
        val layers = arrayOf(
            listOf(tertiary.shiftTonal(0.18f, 0.85f).copy(alpha = 0.40f), tertiary.shiftTonal(-0.06f, 1.15f).copy(alpha = 0.30f)),
            listOf(secondary.shiftTonal(-0.08f, 1.15f).copy(alpha = 0.68f), secondary.shiftTonal(0.18f, 0.85f).copy(alpha = 0.58f)),
            listOf(baseline[0].copy(alpha = 0.95f), baseline[1].copy(alpha = 0.88f)),
        )
        val strokes = floatArrayOf(1.0f, 1.2f, 1.6f)
        clipRect(left = -half, right = thumbX + half) {
            for (i in 0..2) {
                drawPath(
                    paths.waves[i],
                    Brush.horizontalGradient(layers[i], 0f, activeWidth),
                    style = Stroke(strokes[i].dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            drawLine(Brush.horizontalGradient(baseline, 0f, activeWidth), Offset(0f, centerY), Offset(thumbX, centerY), trackPx, StrokeCap.Round)
        }
    }

    if (showThumb) {
        val thumbColor = primary.shiftTonal(0.06f, 1.05f)
        val radius = 7.5.dp.toPx()
        val center = Offset(thumbX, centerY)
        drawCircle(thumbColor.copy(alpha = 0.35f), radius + 3.5.dp.toPx(), center)
        drawCircle(thumbColor, radius, center)
        drawCircle(Color.White.copy(alpha = 0.55f), radius, center, style = Stroke(1.2.dp.toPx()))
    }
}

/** LastWave's three waves, back to front: here bass, mids, highs. */
private val WAVE_AMP_DP = floatArrayOf(13f, 10f, 7.5f)
private val WAVE_LENGTH_DP = floatArrayOf(160f, 125f, 95f)

private fun smootherstep(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * c * (c * (c * 6f - 15f) + 10f)
}

/**
 * LastWave's dark liquid glass behind a control, without changing its size or
 * place: a smoked body that darkens what is behind it, a faint sheen at the top
 * and a thin bright rim. [width] by [height] centred on the control (a disc when
 * equal, a capsule otherwise); null fills the control.
 */
internal fun Modifier.lastWaveGlass(on: Boolean, width: Dp? = null, height: Dp? = width, active: Boolean = false): Modifier =
    if (!on) this else drawBehind { drawLastWaveGlass(width?.toPx() ?: size.width, height?.toPx() ?: size.height, active) }

internal fun DrawScope.drawLastWaveGlass(w: Float, h: Float, active: Boolean = false) {
    val left = (size.width - w) / 2f
    val top = (size.height - h) / 2f
    val r = CornerRadius(h / 2f)
    val origin = Offset(left, top)
    val box = Size(w, h)
    drawRoundRect(
        color = if (active) Color.White.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.30f),
        topLeft = origin, size = box, cornerRadius = r,
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.07f), Color.Transparent),
            startY = top, endY = top + h * 0.5f,
        ),
        topLeft = origin, size = box, cornerRadius = r,
    )
    val stroke = 1.dp.toPx()
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.18f)),
            startY = top, endY = top + h,
        ),
        topLeft = Offset(left + stroke / 2f, top + stroke / 2f),
        size = Size(w - stroke, h - stroke),
        cornerRadius = CornerRadius((h - stroke) / 2f),
        style = Stroke(stroke),
    )
}

/**
 * LastWave's backdrop for the lower half of the player: the artwork's colours,
 * deepened and saturated as its fluid background grades them, drifting slowly
 * under its dark scrim. The drift quickens a little with the music and each
 * band's colour swells with it, followed over a second or two so nothing
 * flickers; paused, it holds still.
 */
@Composable
internal fun LastWaveBackdrop(look: LastWaveLook, alpha: () -> Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val a = alpha()
        if (a <= 0f) return@Canvas
        val m = look.motion
        m.frame
        val w = size.width
        val h = size.height
        val t = m.drift
        for (i in 0..2) {
            val c = look.deep[i]
            val cx = w * (GLOW_X[i] + 0.10f * sin(t * GLOW_RATE[i] + i * 2.1f))
            val cy = h * (0.80f + 0.07f * sin(t * GLOW_RATE[i] * 0.7f + i * 1.3f))
            val radius = w * GLOW_R[i] * (0.9f + 0.2f * m.slow[i])
            val strength = (0.62f + 0.25f * m.slow[i]) * a
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(c.copy(alpha = strength), c.copy(alpha = strength * 0.55f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius.coerceAtLeast(1f),
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
        }
        // LastWave's scrim over its artwork, from the middle down.
        drawRect(
            brush = Brush.verticalGradient(
                0.00f to Color.Transparent,
                0.40f to Color.Transparent,
                0.65f to Color.Black.copy(alpha = 0.40f * a),
                1.00f to Color.Black.copy(alpha = 0.72f * a),
            ),
        )
    }
}

private val GLOW_X = floatArrayOf(0.20f, 0.55f, 0.85f)
private val GLOW_R = floatArrayOf(0.95f, 0.85f, 0.75f)
private val GLOW_RATE = floatArrayOf(0.11f, 0.15f, 0.19f)

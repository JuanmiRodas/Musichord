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
    internal val phase = FloatArray(3)
    internal val level = floatArrayOf(0.42f, 0.38f, 0.34f)
    internal var beat = 0f
    private var beatSeen = 0L

    /** Bumped every frame the waves move: read in a draw to redraw with them. */
    var frame by mutableLongStateOf(0L)
        internal set

    internal fun step(dt: Float) {
        val fresh = System.nanoTime() - AudioBands.updatedAt < STALE_NS
        for (i in 0..2) {
            val target = if (fresh) {
                when (i) { 0 -> AudioBands.bass; 1 -> AudioBands.mid; else -> AudioBands.treble }
            } else {
                IDLE_LEVEL[i]
            }
            val k = 1f - exp(-dt * RESPONSE[i])
            level[i] += (target - level[i]) * k
            phase[i] = (phase[i] + dt * SPEED[i] * (0.5f + 0.9f * level[i])) % TWO_PI_X8
        }
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
        /** How fast each wave follows its band, per second: the highs flicker, the bass swells. */
        val RESPONSE = floatArrayOf(9f, 13f, 18f)
        /** Radians per second at a middling level: LastWave's 2π in 3 s is the mid. */
        val SPEED = floatArrayOf(1.5f, 2.1f, 2.9f)
        const val BEAT_DECAY_S = 0.19f
        const val TWO_PI_X8 = (2.0 * PI * 8.0).toFloat()
    }
}

/** The LastWave player look: present only while the setting is on. */
@Stable
class LastWaveLook internal constructor(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
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
    val primary = luminous(colors.getOrElse(0) { Color.White }, 0.40f)
    val secondary = luminous(colors.getOrElse(1) { primary }, 0.34f)
    val tertiary = luminous(colors.getOrElse(2) { secondary }, 0.30f)
    return remember(primary, secondary, tertiary) { LastWaveLook(primary, secondary, tertiary, motion) }
}

/** [c] lifted toward white until it reads on a dark backdrop. */
private fun luminous(c: Color, min: Float): Color {
    val l = c.luminance()
    if (l >= min) return c
    return lerp(c, Color.White, ((min - l) / (1f - l)).coerceIn(0f, 1f))
}

/** Paths reused across frames, so the waves allocate nothing while they move. */
internal class WavePaths {
    val mask = Path()
    val rect = Path()
    val pill = Path()
    val waves = Array(3) { Path() }
}

/**
 * LastWave's waveform seek bar, with three waves instead of two — one per band,
 * bass at the back and widest, the highs in front — each rising and moving with
 * its own part of the sound, the bass leaning left and the highs right, and the
 * whole set nudged by the kick. [baseHeight] is the bar's own thickness, so the
 * bar keeps our height; the waves only ever rise above it into the touch area.
 */
internal fun DrawScope.drawLastWaveTrack(
    look: LastWaveLook,
    paths: WavePaths,
    fraction: Float,
    baseHeight: Float,
    inactive: Color,
    maxAmplitude: Float,
    showThumb: Boolean,
    /** Drawn over the empty bar and under the played part, as the Automix window is. */
    underFill: DrawScope.() -> Unit = {},
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    look.motion.frame // redraw with the waves
    val motion = look.motion
    val centerY = h / 2f
    val splitX = (fraction.coerceIn(0f, 1f) * w)
    val top = centerY - baseHeight / 2f
    val corner = CornerRadius(baseHeight / 2f)

    drawRoundRect(color = inactive, topLeft = Offset(0f, top), size = Size(w, baseHeight), cornerRadius = corner)
    underFill()

    if (splitX > 0f) {
        paths.rect.reset()
        paths.rect.addRect(Rect(baseHeight / 2f, -h, w, centerY))
        paths.pill.reset()
        paths.pill.addRoundRect(RoundRect(0f, top, w, top + baseHeight, corner))
        paths.mask.reset()
        paths.mask.op(paths.rect, paths.pill, PathOperation.Union)

        val step = 4f
        val pulse = 1f + 0.18f * motion.beat
        for (i in 0..2) {
            val path = paths.waves[i]
            path.reset()
            path.moveTo(0f, top)
            val amp = maxAmplitude * WAVE_SCALE[i] * (0.22f + 0.78f * motion.level[i]) * pulse
            var x = 0f
            while (true) {
                val cx = x.coerceAtMost(w)
                val t = cx / w
                val originEase = smoothstep(0f, w * 0.15f, cx)
                val knobEase = 1f - smoothstep(splitX - w * 0.15f, splitX, cx)
                val lean = when (i) {
                    0 -> 1.2f - 0.4f * t
                    1 -> 1f
                    else -> 0.8f + 0.4f * t
                }
                val raw = sin(t * WAVE_FREQ[i] - motion.phase[i]) * 0.5f + 0.5f
                val y = raw.pow(0.7f) * amp * lean * originEase * knobEase
                path.lineTo(cx, top - y)
                if (cx >= w) break
                x += step
            }
            path.lineTo(w, top + baseHeight + 10f)
            path.lineTo(0f, top + baseHeight + 10f)
            path.close()
        }

        val width = splitX.coerceAtLeast(1f)
        val brushes = arrayOf(
            Brush.horizontalGradient(
                listOf(lerp(look.tertiary, Color.White, 0.12f).copy(alpha = 0.46f), look.tertiary.copy(alpha = 0.34f)),
                0f, width,
            ),
            Brush.horizontalGradient(
                listOf(lerp(look.secondary, Color.White, 0.16f).copy(alpha = 0.60f), look.secondary.copy(alpha = 0.46f)),
                0f, width,
            ),
            Brush.horizontalGradient(
                listOf(lerp(look.primary, Color.White, 0.22f).copy(alpha = 0.96f), look.primary.copy(alpha = 0.88f)),
                0f, width,
            ),
        )
        clipPath(paths.mask) {
            clipRect(left = 0f, right = splitX) {
                for (i in 0..2) drawPath(paths.waves[i], brushes[i])
            }
        }
    }

    if (showThumb) {
        val outer = 8.dp.toPx()
        val inner = 5.2.dp.toPx()
        val c = Offset(splitX, centerY)
        drawCircle(look.primary.copy(alpha = 0.32f + 0.18f * motion.beat), radius = outer + 2.5.dp.toPx(), center = c)
        drawCircle(Color.White.copy(alpha = 0.95f), radius = outer, center = c)
        drawCircle(look.primary, radius = inner, center = c)
        drawCircle(Color.White.copy(alpha = 0.5f), radius = inner, center = c, style = Stroke(1.dp.toPx()))
    }
}

/** Back to front: bass, mids, highs. */
private val WAVE_SCALE = floatArrayOf(1f, 0.78f, 0.56f)
private val WAVE_FREQ = floatArrayOf((2.8 * PI).toFloat(), (4.6 * PI).toFloat(), (7.2 * PI).toFloat())

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val d = edge1 - edge0
    if (d == 0f) return 0f
    val t = ((x - edge0) / d).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * LastWave's liquid glass, drawn behind a control without changing its size or
 * place: a clear body a little brighter at the top, light pooling at the bottom,
 * a bright specular rim. [width] by [height] centred on the control (a disc when
 * equal, a capsule otherwise); null fills the control.
 */
internal fun Modifier.lastWaveGlass(on: Boolean, width: Dp? = null, height: Dp? = width, pressed: () -> Float = { 0f }): Modifier =
    if (!on) this else drawBehind { drawLastWaveGlass(width?.toPx() ?: size.width, height?.toPx() ?: size.height, pressed()) }

internal fun DrawScope.drawLastWaveGlass(w: Float, h: Float, pressed: Float = 0f) {
    val left = (size.width - w) / 2f
    val top = (size.height - h) / 2f
    val r = CornerRadius(h / 2f)
    val origin = Offset(left, top)
    val box = Size(w, h)
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.17f + 0.08f * pressed), Color.White.copy(alpha = 0.06f + 0.06f * pressed)),
            startY = top, endY = top + h,
        ),
        topLeft = origin, size = box, cornerRadius = r,
    )
    drawRoundRect(
        brush = Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.11f), Color.Transparent),
            center = Offset(size.width / 2f, top + h * 0.9f),
            radius = (minOf(w, h) * 0.75f).coerceAtLeast(1f),
        ),
        topLeft = origin, size = box, cornerRadius = r,
    )
    // The specular: a sheen across the top third.
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.20f), Color.Transparent),
            startY = top, endY = top + h * 0.42f,
        ),
        topLeft = Offset(left + w * 0.08f, top + 1.dp.toPx()),
        size = Size(w * 0.84f, h * 0.42f),
        cornerRadius = CornerRadius(h * 0.21f),
    )
    val stroke = 1.dp.toPx()
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.30f)),
            startY = top, endY = top + h,
        ),
        topLeft = Offset(left + stroke / 2f, top + stroke / 2f),
        size = Size(w - stroke, h - stroke),
        cornerRadius = CornerRadius((h - stroke) / 2f),
        style = Stroke(stroke),
    )
}

/**
 * LastWave's moving colours at the bottom of the player, one light per band —
 * the bass's on the left, the mids' in the middle, the highs' on the right —
 * each growing with its band and all of them breathing on the kick. Frozen,
 * like the waves, while the song is paused.
 */
@Composable
internal fun LastWaveBottomGlow(look: LastWaveLook, alpha: () -> Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val a = alpha()
        if (a <= 0f) return@Canvas
        val m = look.motion
        m.frame
        val w = size.width
        val h = size.height
        val colors = arrayOf(look.tertiary, look.secondary, look.primary)
        for (i in 0..2) {
            val level = m.level[i]
            val drift = sin(m.phase[i] * 0.33f + i * 2.1f)
            val cx = w * GLOW_X[i] + drift * w * 0.07f
            val cy = h * (1.0f - 0.05f * level) + sin(m.phase[i] * 0.21f + i) * h * 0.015f
            val radius = w * GLOW_R[i] * (0.82f + 0.36f * level) * (1f + 0.07f * m.beat)
            val strength = (0.20f + 0.26f * level + 0.08f * m.beat).coerceAtMost(0.6f) * a
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(colors[i].copy(alpha = strength), colors[i].copy(alpha = strength * 0.4f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius.coerceAtLeast(1f),
                ),
                radius = radius,
                center = Offset(cx, cy),
            )
        }
    }
}

private val GLOW_X = floatArrayOf(0.18f, 0.52f, 0.84f)
private val GLOW_R = floatArrayOf(0.70f, 0.58f, 0.50f)

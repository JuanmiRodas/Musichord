package com.music.bitchord.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
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

    /** Each band's beat-following envelope: quick to rise on a hit, slower to fall. */
    private val envelope = FloatArray(3)
    /**
     * What each band did over the last seconds, newest first, one sample per 1/60 s: a
     * wave is drawn from it so a hit enters at the left and rolls toward the thumb.
     */
    private val history = Array(3) { FloatArray(HISTORY) }
    private var head = 0
    private var pending = 0f

    /** The band [i]'s envelope [age] seconds ago, 0..1. */
    internal fun past(i: Int, age: Float): Float {
        val pos = (age * HISTORY_HZ).coerceIn(0f, (HISTORY - 2).toFloat())
        val k = pos.toInt()
        val f = pos - k
        val h = history[i]
        val a = h[(head - k + HISTORY) % HISTORY]
        val b = h[(head - k - 1 + HISTORY * 2) % HISTORY]
        return a + (b - a) * f
    }

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
        // The waves' envelopes: a hit lifts its band at once, which then eases back down;
        // the band's own average is taken off so the rhythm stands out of the loudness.
        for (i in 0..2) {
            val target = if (fresh) {
                when (i) { 0 -> AudioBands.bass; 1 -> AudioBands.mid; else -> AudioBands.treble }
            } else {
                IDLE_LEVEL[i]
            }
            // The band's level, its quiet part pressed down so the hits stand out.
            val lifted = target.coerceIn(0f, 1f).let { it * it * (1.5f - 0.5f * it) }
            val rate = if (lifted > envelope[i]) ATTACK[i] else RELEASE[i]
            envelope[i] += (lifted - envelope[i]) * (1f - exp(-dt * rate))
        }
        pending += dt
        while (pending >= 1f / HISTORY_HZ) {
            pending -= 1f / HISTORY_HZ
            head = (head + 1) % HISTORY
            for (i in 0..2) history[i][head] = envelope[i]
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
        val SPEED = floatArrayOf(2.618f, 1.9f, 4.833f)
        const val BEAT_DECAY_S = 0.22f
        const val HISTORY_HZ = 60f
        const val HISTORY = 180
        /** Drums hit hard and short; the voice swells; the highs flicker. */
        val ATTACK = floatArrayOf(40f, 18f, 30f)
        val RELEASE = floatArrayOf(7f, 4f, 9f)
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
    // LastWave's own picks: Palette's vibrant, light vibrant and dark vibrant swatches.
    val context = coil3.compose.LocalPlatformContext.current
    var swatches by remember(artworkUrl) { androidx.compose.runtime.mutableStateOf<List<Color?>>(listOf(null, null, null)) }
    LaunchedEffect(artworkUrl) {
        val url = artworkUrl ?: return@LaunchedEffect
        val bitmap = runCatching { loadArtworkPaletteBitmap(context, url) }.getOrNull() ?: return@LaunchedEffect
        swatches = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { lastWaveSwatches(bitmap) }
    }
    return remember(colors, swatches) {
        val c0 = swatches[0] ?: colors.getOrElse(0) { Color(0xFF6A7FDB) }
        val c1 = swatches[1] ?: lerp(colors.getOrElse(2) { c0 }, c0, 0.42f)
        val c2 = swatches[2] ?: lerp(colors.getOrElse(1) { c0 }, c0, 0.25f)
        LastWaveLook(
            primary = c0.ensureLuminous(0.38f),
            secondary = c1.ensureLuminous(0.32f),
            tertiary = c2.ensureLuminous(0.28f),
            deep = listOf(c0, c1, c2).map { deepen(it) },
            motion = motion,
        )
    }
}

/** LastWave's ensureLuminousForDarkBackground, step for step (HSL lightness raised until it reads). */
private fun Color.ensureLuminous(minLuminance: Float): Color {
    if (luminance() >= minLuminance) return this
    val hsl = toHsl()
    if (hsl[1] < 0.12f) {
        hsl[2] = hsl[2].coerceAtLeast(0.82f)
        return hslColor(hsl)
    }
    var step = 0
    while (step < 20) {
        hsl[2] = (hsl[2] + 0.04f).coerceAtMost(0.92f)
        if (hsl[1] > 0.85f && hsl[2] < 0.65f) hsl[1] = (hsl[1] * 0.96f).coerceAtLeast(0.60f)
        val candidate = hslColor(hsl)
        if (candidate.luminance() >= minLuminance) return candidate
        step++
    }
    return hslColor(hsl)
}

private fun Color.toHsl(): FloatArray {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val d = max - min
    val l = (max + min) / 2f
    val s = if (d == 0f) 0f else d / (1f - kotlin.math.abs(2f * l - 1f))
    val h = when {
        d == 0f -> 0f
        max == red -> 60f * (((green - blue) / d) % 6f)
        max == green -> 60f * (((blue - red) / d) + 2f)
        else -> 60f * (((red - green) / d) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    return floatArrayOf(h, s.coerceIn(0f, 1f), l)
}

private fun hslColor(hsl: FloatArray): Color = Color.hsl(hsl[0].coerceIn(0f, 359.99f), hsl[1].coerceIn(0f, 1f), hsl[2].coerceIn(0f, 1f))

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
 * LastWave's wavy seek bar — thin track, contour waves in the artwork's colours, its
 * thumb — with the waves played by the song, as beams of light:
 *  - in front, the drums and bass: a hit rises at the left and rolls along to the thumb;
 *  - behind it, the voice, swelling and rolling the same way, more slowly;
 *  - at the back, the highs, quick and small.
 * Each wave is its band's last second or two laid out from left (now) to the thumb (then).
 * The thumb beats with the kick. Paused, everything holds the shape it had; dragging
 * flattens the waves so the thumb reads, as LastWave does.
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
        val leftEase = minOf(16.dp.toPx(), thumbX * 0.3f)
        val rightEase = minOf(40.dp.toPx(), thumbX * 0.45f)
        val step = 1.5.dp.toPx()
        val calm = 1f - flatten
        for (i in 0..2) {
            val maxAmp = WAVE_AMP_DP[i].dp.toPx()
            val k = (2 * PI / WAVE_LENGTH_DP[i].dp.toPx()).toFloat()
            val span = WAVE_SPAN_S[i]
            val path = paths.waves[i]
            path.reset()
            path.moveTo(0f, topY)
            var x = 0f
            while (x < thumbX) {
                val envelope = smootherstep(if (leftEase > 0f) x / leftEase else 1f) *
                    smootherstep(if (rightEase > 0f) (thumbX - x) / rightEase else 1f)
                // LastWave's rolling sine is the shape; the band only swells and settles it,
                // a hit entering at the left and travelling to the thumb.
                val energy = motion.past(i, x / thumbX * span)
                val carrier = 0.5f + 0.5f * sin(x * k - motion.phase[i])
                val lift = (0.38f + 0.62f * energy) * carrier * maxAmp * calm + 1.2.dp.toPx() * flatten * carrier
                path.lineTo(x, topY - lift * envelope)
                x += step
            }
            path.lineTo(thumbX, topY)
        }
        val activeWidth = thumbX.coerceAtLeast(1f)
        val tertiary = look.tertiary
        val secondary = look.secondary
        // LastWave's own layer colours: front the artwork's main colour, then its two others.
        val layers = arrayOf(
            listOf(baseline[0].copy(alpha = 0.95f), baseline[1].copy(alpha = 0.88f)),
            listOf(secondary.shiftTonal(-0.08f, 1.15f).copy(alpha = 0.72f), secondary.shiftTonal(0.18f, 0.85f).copy(alpha = 0.62f)),
            listOf(tertiary.shiftTonal(0.18f, 0.85f).copy(alpha = 0.50f), tertiary.shiftTonal(-0.06f, 1.15f).copy(alpha = 0.40f)),
        )
        val strokes = floatArrayOf(2.0f, 1.5f, 1.3f)
        // Tall waves rise above the bar's box (behind the text above); nothing cuts them off.
        val far = size.height * 4f
        clipRect(left = -half - 6.dp.toPx(), top = -far, right = thumbX + half + 6.dp.toPx(), bottom = size.height + far) {
            drawLine(primary.copy(alpha = 0.12f), Offset(0f, centerY), Offset(thumbX, centerY), trackPx + 4.dp.toPx(), StrokeCap.Round)
            for (i in 2 downTo 0) {
                val path = paths.waves[i]
                drawPath(path, layers[i][1].copy(alpha = 0.10f), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(
                    path,
                    Brush.horizontalGradient(layers[i], 0f, activeWidth),
                    style = Stroke(strokes[i].dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
        clipRect(left = -half, top = -far, right = thumbX + half, bottom = size.height + far) {
            drawLine(Brush.horizontalGradient(baseline, 0f, activeWidth), Offset(0f, centerY), Offset(thumbX, centerY), trackPx, StrokeCap.Round)
        }
    }

    if (showThumb) {
        // The thumb pulses with the drums and bass (see BandTap): a beat on each hit,
        // breathing with the bass between them.
        val kick = motion.beat
        val bass = motion.past(0, 0f)
        val thumbColor = primary.shiftTonal(0.06f, 1.05f)
        val radius = 7.5.dp.toPx() * (1f + 0.10f * bass + 0.30f * kick)
        val halo = radius + (3.5f + 3.5f * kick).dp.toPx()
        // Kept inside the bar's ends, so the edges never cut it in half.
        val cx = thumbX.coerceIn(minOf(halo, width / 2f), maxOf(width - halo, width / 2f))
        val center = Offset(cx, centerY)
        drawCircle(thumbColor.copy(alpha = 0.30f + 0.30f * kick), halo, center)
        drawCircle(thumbColor, radius, center)
        drawCircle(Color.White.copy(alpha = 0.55f + 0.3f * kick), radius, center, style = Stroke(1.2.dp.toPx()))
    }
}

/** Front to back: drums and bass, voice, highs. */
private val WAVE_AMP_DP = floatArrayOf(22f, 17f, 12f)
private val WAVE_LENGTH_DP = floatArrayOf(160f, 125f, 95f)
/** How long a sound takes to roll from the left to the thumb, seconds. */
private val WAVE_SPAN_S = floatArrayOf(1.2f, 2.0f, 0.9f)

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
internal fun LastWaveBackdrop(
    look: LastWaveLook,
    artworkUrl: String?,
    seedKey: String,
    playing: Boolean,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Box(modifier.graphicsLayer { this.alpha = alpha() }) {
        // LastWave's own fluid background; the slow colour lights where it can't be drawn.
        LastWaveFluid(
            artworkUrl = artworkUrl,
            seedKey = seedKey,
            playing = playing,
            modifier = Modifier.matchParentSize(),
            fallback = { LastWaveLights(look, Modifier.fillMaxSize()) },
        )
        // LastWave's scrim over its artwork, from the middle down.
        Canvas(Modifier.matchParentSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    0.00f to Color.Transparent,
                    0.40f to Color.Transparent,
                    0.65f to Color.Black.copy(alpha = 0.40f),
                    1.00f to Color.Black.copy(alpha = 0.72f),
                ),
            )
        }
    }
}

@Composable
private fun LastWaveLights(look: LastWaveLook, modifier: Modifier) {
    Canvas(modifier) {
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
            val strength = 0.62f + 0.25f * m.slow[i]
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
    }
}

private val GLOW_X = floatArrayOf(0.20f, 0.55f, 0.85f)
private val GLOW_R = floatArrayOf(0.95f, 0.85f, 0.75f)
private val GLOW_RATE = floatArrayOf(0.11f, 0.15f, 0.19f)

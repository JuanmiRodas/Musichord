package com.music.bitchord.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * BitChord Audio: LastWave's liquid glass (Kyant's backdrop: the player behind
 * the button, blurred, lensed at the edge, colour-graded, under a dark veil, with
 * a specular rim and a press that swells and glows where the finger is) — on the
 * phone. Where the platform can't refract (the desktop, Android before 12) it is
 * LastWave's own fallback: the surface's colour alone.
 */
@Composable
expect fun rememberLastWaveBackdrop(): Any?

/** The layer the glass refracts: the player's backdrop, drawn before (never around) the glass. */
expect fun Modifier.lastWaveBackdropSource(backdrop: Any?): Modifier

/** LastWave's interactive dark glass over [shape], refracting [backdrop]; unchanged when it can't. */
@Composable
expect fun Modifier.lastWaveLiquidGlass(backdrop: Any?, shape: Shape, interactive: Boolean = true): Modifier

/** Whether [lastWaveLiquidGlass] really refracts here (LastWave's container colour rule). */
@Composable
expect fun lastWaveGlassActive(backdrop: Any?): Boolean

/** LastWave's three artwork colours (vibrant, light vibrant, dark vibrant, with its fallbacks). */
expect fun lastWaveSwatches(bitmap: ImageBitmap): List<Color?>

val LocalLastWaveBackdrop = staticCompositionLocalOf<Any?> { null }

/** BitChord Audio: how much of the glass shows (1) against the plain smoke (0), while it fades. */
val LocalGlassAmount = androidx.compose.runtime.compositionLocalOf { 1f }

/** LastWave's liquidGlassContainerColor: a translucent colour stays, an opaque one gives way to the glass. */
@Composable
internal fun lastWaveContainerColor(color: Color, backdrop: Any? = LocalLastWaveBackdrop.current): Color =
    if (lastWaveGlassActive(backdrop)) {
        if (color.alpha < 0.95f) color else Color.Transparent
    } else {
        color
    }

/** LastWave's LiquidGlassSurface: a Material surface with the glass inside its shape. */
@Composable
internal fun LastWaveGlassSurface(
    onClick: () -> Unit,
    shape: Shape,
    color: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
    border: BorderStroke? = null,
    interactive: Boolean = true,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalLastWaveBackdrop.current
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        color = lastWaveContainerColor(color, backdrop),
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = border,
        interactionSource = interactionSource,
        enabled = enabled,
    ) {
        val glass = LocalGlassAmount.current
        Box(
            modifier = Modifier.fillMaxSize().lastWaveLiquidGlass(backdrop, shape, interactive)
                .then(if (glass < 1f && lastWaveGlassActive(backdrop)) Modifier.background(color.copy(alpha = color.alpha * (1f - glass))) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/** LastWave's translucent veil over the full player's artwork (isTranslucent). */
internal val LastWaveVeil = Color.White.copy(alpha = 0.14f)
internal val LastWaveSide: Dp = 54.dp

/**
 * A control of ours dressed as LastWave's: its translucent veil and the glass over it,
 * clipped to [shape] — or, where the glass can't refract, the veil over a dark smoke.
 */
@Composable
internal fun Modifier.lastWaveButton(shape: Shape, active: Boolean = false): Modifier {
    val backdrop = LocalLastWaveBackdrop.current
    val veil = if (active) Color.White.copy(alpha = 0.30f) else LastWaveVeil
    val clipped = this.clip(shape)
    val glass = LocalGlassAmount.current
    return if (lastWaveGlassActive(backdrop)) {
        clipped.then(Modifier.background(veil)).lastWaveLiquidGlass(backdrop, shape)
            .then(if (glass < 1f) Modifier.background(Color.Black.copy(alpha = 0.30f * (1f - glass))).background(veil.copy(alpha = veil.alpha * (1f - glass))) else Modifier)
    } else {
        clipped.then(Modifier.background(Color.Black.copy(alpha = 0.30f)).background(veil))
    }
}

/**
 * LastWave's FluidArtworkBackground: copies of the artwork turning, twisted, graded and
 * blurred, moving only while [playing]. [fallback] shows until it is ready, and where the
 * platform can't draw it.
 */
@Composable
expect fun LastWaveFluid(artworkUrl: String?, seedKey: String, playing: Boolean, modifier: Modifier, fallback: @Composable () -> Unit)

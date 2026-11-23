package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape

@Composable
actual fun rememberLastWaveBackdrop(): Any? = null

actual fun Modifier.lastWaveBackdropSource(backdrop: Any?): Modifier = this

@Composable
actual fun Modifier.lastWaveLiquidGlass(backdrop: Any?, shape: Shape, interactive: Boolean): Modifier = this

@Composable
actual fun lastWaveGlassActive(backdrop: Any?): Boolean = false

actual fun lastWaveSwatches(bitmap: ImageBitmap): List<Color?> = listOf(null, null, null)

@Composable
actual fun LastWaveFluid(artworkUrl: String?, seedKey: String, playing: Boolean, modifier: Modifier, fallback: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(modifier) { fallback() }
}

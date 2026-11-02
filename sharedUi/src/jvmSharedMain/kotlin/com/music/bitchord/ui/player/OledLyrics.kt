package com.music.bitchord.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.music.bitchord.data.lyrics.LyricLine

/**
 * BitChord Audio: lyrics for an OLED screen. Everything black but the cover,
 * the title and artist, the line being sung and — dimmed and blurred — the
 * one after it. The screen stays on; tap anywhere (or Back) to leave.
 *
 * Whatever is drawn drifts a few pixels every minute, so no pixel lights the
 * same spot for hours.
 */
@Composable
internal fun OledLyrics(
    lines: List<LyricLine>,
    playhead: LyricPlayhead,
    isPlaying: Boolean,
    title: String,
    artist: String,
    artworkUrl: String?,
    onDismiss: () -> Unit,
) {
    KeepScreenOn(enabled = true)
    val timed = remember(lines) { lines.any { it.timeMs > 0 } }
    var index by remember(lines) { mutableIntStateOf(-1) }
    var drift by remember { mutableStateOf(IntOffset.Zero) }

    LaunchedEffect(lines, isPlaying, playhead) {
        while (true) {
            withFrameNanos { }
            if (!timed) { index = 0; continue }
            val elapsed = if (isPlaying) (System.nanoTime() - playhead.sampledAtNanos) / 1_000_000 else 0
            val now = playhead.positionMs + elapsed.coerceIn(0, 2_000)
            index = lines.indexOfLast { it.timeMs <= now }
        }
    }
    LaunchedEffect(Unit) {
        var step = 0
        while (true) {
            kotlinx.coroutines.delay(60_000)
            step++
            drift = IntOffset(((step * 7) % 13) - 6, ((step * 5) % 11) - 5)
        }
    }

    val current = lines.getOrNull(index)
    val currentText = when {
        current == null -> "•••"
        current.text.isBlank() -> "•••"
        else -> current.text
    }
    val next = (index + 1 until lines.size).map { lines[it] }.firstOrNull { it.text.isNotBlank() }?.text

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .offset { drift }
                    .padding(horizontal = 28.dp, vertical = 48.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).alpha(0.85f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(title, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(artist, color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.weight(1f))
                AnimatedContent(
                    targetState = currentText to next,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "oled-line",
                ) { (line, after) ->
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            line,
                            color = Color.White,
                            fontSize = 34.sp,
                            lineHeight = 40.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (after != null) {
                            Text(
                                after,
                                color = Color.White.copy(alpha = 0.35f),
                                fontSize = 26.sp,
                                lineHeight = 32.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.blur(3.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

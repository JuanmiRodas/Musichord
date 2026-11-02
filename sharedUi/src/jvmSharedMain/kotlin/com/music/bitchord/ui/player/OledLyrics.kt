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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
 * BitChord Audio: lyrics for an OLED screen, made to sit on the desk like an
 * always-on display. Everything black but the clock, the cover, the title and
 * artist, the line being sung and — dimmed and blurred — the one after it.
 * The screen stays on (its own window's flag, so nothing else in the player
 * can turn that off); tap anywhere (or Back) to leave.
 *
 * The status bar stays — black, with the time and the notification icons —
 * so a new message shows; the navigation bar is hidden. The clock, cover,
 * title and artist move to the next corner every 30 seconds with a fade, and
 * the lyrics drift a few pixels every minute, so no pixel lights the same
 * spot for long.
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
    var clock by remember { mutableStateOf(clockNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = clockNow()
            kotlinx.coroutines.delay(1_000L * (60 - java.time.LocalTime.now().second).coerceAtLeast(1))
        }
    }
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

    // The cover, title and artist move to the next corner every 30 seconds.
    var corner by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            corner = (corner + 1) % 4
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // No status or navigation bar: nothing lit but what is drawn here.
        ImmersiveScreen(keepOn = true, showStatusBar = true)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .windowInsetsPadding(WindowInsets.statusBars)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        ) {
            androidx.compose.animation.Crossfade(
                targetState = corner,
                animationSpec = androidx.compose.animation.core.tween(1_500),
                label = "oled-corner",
                modifier = Modifier.fillMaxSize(),
            ) { c ->
                Box(
                    Modifier.fillMaxSize().offset { drift }.padding(horizontal = 24.dp, vertical = 28.dp),
                    contentAlignment = when (c) {
                        0 -> Alignment.TopStart
                        1 -> Alignment.TopEnd
                        2 -> Alignment.BottomEnd
                        else -> Alignment.BottomStart
                    },
                ) {
                    Column(horizontalAlignment = if (c == 1 || c == 2) Alignment.End else Alignment.Start) {
                    Text(
                        clock.first,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Text(
                        clock.second,
                        color = Color.White.copy(alpha = 0.45f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            model = artworkUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).alpha(0.8f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.widthIn(max = 220.dp)) {
                            Text(title, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(artist, color = Color.White.copy(alpha = 0.45f), style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    }
                }
            }
            Box(
                Modifier.fillMaxSize().offset { drift }.padding(horizontal = 28.dp, vertical = 170.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
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
            }
        }
    }
}

/** The time ("14:05", in the language's own style) and the date ("Mon 2 Nov"). */
private fun clockNow(): Pair<String, String> {
    val now = java.time.LocalDateTime.now()
    val locale = java.util.Locale.getDefault()
    val time = now.format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale))
    val date = now.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", locale))
    return time to date
}

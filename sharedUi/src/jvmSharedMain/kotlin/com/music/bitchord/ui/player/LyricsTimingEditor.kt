package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.sharedui.resources.*
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

/**
 * BitChord Audio: tap-to-time lyrics.
 *
 * The song keeps playing; each tap on "Next line" gives the highlighted line
 * the moment it was tapped and moves on. "Back a line" steps back and replays
 * from the line before, "−5 s" rewinds, the bar seeks, and tapping a line
 * makes it the next one to time (and jumps there if it already has a time).
 * Works the same on untimed lyrics and on timed ones that need fixing.
 * Saved as the song's .lrc (see PlayerHost.saveTimedLyrics).
 */
@Composable
internal fun LyricsTimingEditor(
    lines: List<LyricLine>,
    playhead: LyricPlayhead,
    isPlaying: Boolean,
    durationMs: Long,
    onPlayPause: () -> Unit,
    /** Seeks to a time on the lyrics' clock. */
    onSeekLyric: (Long) -> Unit,
    onSave: (List<LyricLine>) -> Unit,
    onDismiss: () -> Unit,
) {
    val texts = remember(lines) { lines.filter { it.text.isNotBlank() }.map { it.text } }
    val untimed = remember(lines) { lines.none { it.timeMs > 0 } }
    val times = remember(lines) {
        mutableStateListOf<Long?>().apply {
            lines.filter { it.text.isNotBlank() }.forEach { add(if (untimed) null else it.timeMs) }
        }
    }
    var cursor by remember(lines) { mutableIntStateOf(0) }
    var nowMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val listState = rememberLazyListState()

    fun now(): Long {
        val elapsed = if (isPlaying) (System.nanoTime() - playhead.sampledAtNanos) / 1_000_000 else 0
        return (playhead.positionMs + elapsed.coerceIn(0, 2_000)).coerceAtLeast(0)
    }
    LaunchedEffect(isPlaying, playhead) {
        while (true) {
            withFrameNanos { }
            nowMs = now()
        }
    }
    LaunchedEffect(cursor) {
        if (texts.isNotEmpty()) listState.animateScrollToItem((cursor - 2).coerceAtLeast(0))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.lyrics_time_cancel), color = Color.White.copy(alpha = 0.8f)) }
                Text(
                    stringResource(Res.string.lyrics_time_header),
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                TextButton(onClick = {
                    onSave(finish(texts, times))
                }) { Text(stringResource(Res.string.lyrics_time_save), color = Color.White, fontWeight = FontWeight.Bold) }
            }
            Text(
                stringResource(Res.string.lyrics_time_help),
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(texts) { i, text ->
                    val active = i == cursor
                    val done = i < cursor
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .background(
                                if (active) Color.White.copy(alpha = 0.14f) else Color.Transparent,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable {
                                cursor = i
                                times[i]?.let { onSeekLyric((it - 2_000).coerceAtLeast(0)) }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            times[i]?.let(::stamp) ?: "--:--.--",
                            color = Color.White.copy(alpha = if (done) 0.75f else 0.4f),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(72.dp),
                        )
                        Text(
                            text,
                            color = Color.White.copy(alpha = if (active) 1f else if (done) 0.7f else 0.5f),
                            style = if (active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
            // The song: where it is, seekable, with play/pause.
            val total = durationMs.coerceAtLeast(1)
            Slider(
                value = scrubbing ?: (nowMs.toFloat() / total).coerceIn(0f, 1f),
                onValueChange = { scrubbing = it },
                onValueChangeFinished = {
                    scrubbing?.let { onSeekLyric((it * total).toLong()) }
                    scrubbing = null
                },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stamp(nowMs), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(stamp(durationMs), color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelMedium)
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = {
                    // Step back: the line before is timed again, from just before it.
                    cursor = (cursor - 1).coerceAtLeast(0)
                    val from = times.getOrNull(cursor - 1) ?: times.getOrNull(cursor)?.minus(3_000) ?: (now() - 5_000)
                    onSeekLyric(from.coerceAtLeast(0))
                }) { Text(stringResource(Res.string.lyrics_time_back)) }
                FilledTonalButton(onClick = { onSeekLyric((now() - 5_000).coerceAtLeast(0)) }) {
                    Text(stringResource(Res.string.lyrics_time_rewind))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
            }
            Button(
                onClick = {
                    if (cursor < texts.size) {
                        times[cursor] = now()
                        cursor += 1
                    }
                },
                enabled = cursor < texts.size,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().height(72.dp).padding(bottom = 8.dp),
            ) {
                Text(stringResource(Res.string.lyrics_time_next), style = MaterialTheme.typography.titleLarge)
            }
            Box(Modifier.height(4.dp))
        }
    }
}

private fun stamp(ms: Long): String {
    val m = ms / 60_000
    val s = ms / 1000 % 60
    val cs = ms / 10 % 100
    return String.format(Locale.ROOT, "%02d:%02d.%02d", m, s, cs)
}

/**
 * The lines as timed: each keeps the time it was given; a line never reached
 * comes a little after the one before, so the order always holds.
 */
private fun finish(texts: List<String>, times: List<Long?>): List<LyricLine> {
    var last = -1L
    return texts.mapIndexed { i, text ->
        val t = times[i]?.takeIf { it > last } ?: (last + 1_000).coerceAtLeast(0)
        last = t
        LyricLine(timeMs = t, text = text)
    }
}

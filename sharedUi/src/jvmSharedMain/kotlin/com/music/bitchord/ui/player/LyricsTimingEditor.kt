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
/** One row of the editor: a sung line, or an instrumental break ("…"). */
private class Entry(val text: String, var timeMs: Long?, val isBreak: Boolean = false)

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
    val untimed = remember(lines) { lines.none { it.timeMs > 0 } }
    // Blank lines are dropped: breaks are marked here, with the Break button.
    val entries = remember(lines) {
        mutableStateListOf<Entry>().apply {
            lines.filter { it.text.isNotBlank() }.forEach { add(Entry(it.text, if (untimed) null else it.timeMs)) }
        }
    }
    // [cursor]: the line "Start line" will start. [sung]: the line started last.
    var cursor by remember(lines) { mutableIntStateOf(0) }
    var inBreak by remember(lines) { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var nowMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val listState = rememberLazyListState()

    fun now(): Long {
        val elapsed = if (isPlaying) (System.nanoTime() - playhead.sampledAtNanos) / 1_000_000 else 0
        return (playhead.positionMs + elapsed.coerceIn(0, 2_000)).coerceAtLeast(0)
    }
    fun seekBy(deltaMs: Long) = onSeekLyric((now() + deltaMs).coerceIn(0, durationMs.coerceAtLeast(0)))
    fun startLine() {
        if (cursor >= entries.size) return
        inBreak = false
        entries[cursor].timeMs = now()
        cursor += 1
        revision++
    }
    fun toggleBreak() {
        if (inBreak) {
            // Break over: the next line is up; "Start line" when the singing does.
            inBreak = false
        } else {
            entries.add(cursor, Entry("", now(), isBreak = true))
            cursor += 1
            inBreak = true
        }
        revision++
    }
    fun back() {
        inBreak = false
        if (cursor == 0) return
        cursor -= 1
        // Stepping back over a break takes the break out.
        if (entries[cursor].isBreak) {
            entries.removeAt(cursor)
            revision++
            if (cursor == 0) { onSeekLyric(0); return }
            cursor -= 1
        }
        val before = entries.getOrNull(cursor - 1)?.timeMs ?: entries[cursor].timeMs?.minus(3_000) ?: (now() - 5_000)
        onSeekLyric(before.coerceAtLeast(0))
        revision++
    }

    LaunchedEffect(isPlaying, playhead) {
        while (true) {
            withFrameNanos { }
            nowMs = now()
        }
    }
    LaunchedEffect(cursor) {
        if (entries.isNotEmpty()) listState.animateScrollToItem((cursor - 2).coerceAtLeast(0))
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
                TextButton(onClick = { onSave(finish(entries)) }) {
                    Text(stringResource(Res.string.lyrics_time_save), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                stringResource(Res.string.lyrics_time_help),
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // Read so the list redraws when an entry's time changes.
            val shownRevision = revision
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(entries, key = { i, e -> "$i:${e.isBreak}:$shownRevision" }) { i, entry ->
                    val upNext = i == cursor
                    val singing = i == cursor - 1
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .background(
                                when {
                                    singing -> Color.White.copy(alpha = 0.18f)
                                    upNext -> Color.White.copy(alpha = 0.06f)
                                    else -> Color.Transparent
                                },
                                RoundedCornerShape(10.dp),
                            )
                            .clickable {
                                inBreak = false
                                cursor = i
                                entry.timeMs?.let { onSeekLyric((it - 2_000).coerceAtLeast(0)) }
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            entry.timeMs?.let(::stamp) ?: "--:--.--",
                            color = Color.White.copy(alpha = if (i < cursor) 0.75f else 0.4f),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(72.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            if (upNext) {
                                Text(
                                    stringResource(Res.string.lyrics_time_up_next),
                                    color = Color.White.copy(alpha = 0.5f),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Text(
                                if (entry.isBreak) "♪ •••" else entry.text,
                                color = Color.White.copy(alpha = if (singing) 1f else if (upNext) 0.85f else if (i < cursor) 0.6f else 0.45f),
                                style = if (singing) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                                fontWeight = if (singing) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
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
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = ::back) { Text(stringResource(Res.string.lyrics_time_back)) }
                FilledTonalButton(onClick = { seekBy(-5_000) }) { Text(stringResource(Res.string.lyrics_time_rewind)) }
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
                FilledTonalButton(onClick = { seekBy(5_000) }) { Text(stringResource(Res.string.lyrics_time_forward)) }
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = ::toggleBreak,
                    colors = if (inBreak) ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFE0A030)) else ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.weight(1f).height(72.dp),
                ) {
                    Text(
                        stringResource(if (inBreak) Res.string.lyrics_time_break_end else Res.string.lyrics_time_break),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                Button(
                    onClick = ::startLine,
                    enabled = cursor < entries.size,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    modifier = Modifier.weight(2f).height(72.dp),
                ) {
                    Text(stringResource(Res.string.lyrics_time_next), style = MaterialTheme.typography.titleLarge)
                }
            }
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
 * The lines as timed, breaks as blank lines (shown as "•••"): each keeps the
 * time it was given; a line never reached comes a little after the one before,
 * so the order always holds.
 */
private fun finish(entries: List<Entry>): List<LyricLine> {
    var last = -1L
    return entries.map { e ->
        val t = e.timeMs?.takeIf { it > last } ?: (last + 1_000).coerceAtLeast(0)
        last = t
        LyricLine(timeMs = t, text = if (e.isBreak) "" else e.text)
    }
}

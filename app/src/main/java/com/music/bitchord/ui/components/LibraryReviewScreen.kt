package com.music.bitchord.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.cleanup.Gemini
import com.music.bitchord.data.cleanup.LibraryCleanup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** BitChord Audio: the pCloud library cleanup — one album card at a time, swiped. */
object LibraryReview {
    internal val shown = MutableStateFlow(false)
    internal val tab = MutableStateFlow(0)
    fun open(assistant: Boolean = false) {
        tab.value = if (assistant) 1 else 0
        shown.value = true
        LibraryCleanup.open()
    }
}

@Composable
fun LibraryReviewHost() {
    val shown by LibraryReview.shown.collectAsStateWithLifecycle()
    if (!shown) return
    Dialog(
        onDismissRequest = { LibraryReview.shown.value = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LibraryReviewContent(onClose = { LibraryReview.shown.value = false })
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LibraryReviewContent(onClose: () -> Unit) {
    val st by LibraryCleanup.state.collectAsStateWithLifecycle()
    val key by Gemini.key.collectAsStateWithLifecycle()
    var hint by remember(st.current?.key) { mutableStateOf("") }
    var keyDraft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }

    fun swipe(right: Boolean) {
        scope.launch {
            dx.animateTo(if (right) 1500f else -1500f)
            if (right) LibraryCleanup.accept() else LibraryCleanup.reject(hint)
            dx.snapTo(0f)
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.cleanup_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(
                        stringResource(R.string.cleanup_left, st.left),
                        st.applying?.let { if (it == "wifi") stringResource(R.string.cleanup_wifi) else stringResource(R.string.cleanup_writing, it) },
                        stringResource(R.string.cleanup_done, st.applied).takeIf { st.applied > 0 },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
        }
        if (key.isBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.cleanup_gemini_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(keyDraft, { keyDraft = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.cleanup_gemini_key)) })
                TextButton(onClick = { Gemini.setKey(keyDraft); st.current?.let { LibraryCleanup.reject(null) } }, enabled = keyDraft.isNotBlank()) {
                    Text(stringResource(R.string.save))
                }
            }
        }
        // BitChord Audio: the cards, and the assistant (its questions, the chat, what it did).
        val tab by LibraryReview.tab.collectAsStateWithLifecycle()
        val asks by com.music.bitchord.data.cleanup.Assistant.attention.collectAsStateWithLifecycle()
        androidx.compose.material3.SecondaryTabRow(selectedTabIndex = tab) {
            androidx.compose.material3.Tab(selected = tab == 0, onClick = { LibraryReview.tab.value = 0 }, text = { Text(stringResource(R.string.cleanup_tab_review)) })
            androidx.compose.material3.Tab(selected = tab == 1, onClick = { LibraryReview.tab.value = 1 }, text = {
                Text(if (asks > 0) stringResource(R.string.assistant_menu_asks, asks) else stringResource(R.string.assistant_menu))
            })
        }
        if (tab == 1) {
            AssistantPane(Modifier.weight(1f).padding(top = 8.dp))
            return@Column
        }
        Spacer(Modifier.height(12.dp))
        val g = st.current
        val p = st.proposal
        var editing by remember(g?.key) { mutableStateOf(false) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                g == null && !st.loading -> Text(stringResource(R.string.cleanup_all_done), Modifier.align(Alignment.Center))
                g == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> Surface(
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 3.dp,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = dx.value
                            rotationZ = dx.value / 60f
                        }
                        .pointerInput(g.key, p) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    when {
                                        dx.value > 250f && p != null -> swipe(true)
                                        dx.value < -250f -> swipe(false)
                                        else -> scope.launch { dx.animateTo(0f) }
                                    }
                                },
                            ) { _, amount -> scope.launch { dx.snapTo(dx.value + amount) } }
                        },
                ) {
                    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                        Text(g.path, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = p?.coverUrl ?: g.cover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(112.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f)),
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                if (p == null) {
                                    if (st.loading) {
                                        CircularProgressIndicator(Modifier.size(28.dp))
                                        Text(stringResource(R.string.cleanup_thinking), style = MaterialTheme.typography.bodySmall)
                                    } else {
                                        Text(stringResource(R.string.cleanup_nothing), style = MaterialTheme.typography.bodyMedium)
                                    }
                                } else if (editing) {
                                    OutlinedTextField(p.album, { LibraryCleanup.edit(p.copy(album = it)) }, singleLine = true, label = { Text(stringResource(R.string.cleanup_album)) })
                                    OutlinedTextField(p.artist, { LibraryCleanup.edit(p.copy(artist = it, albumArtist = it)) }, singleLine = true, label = { Text(stringResource(R.string.cleanup_artist)) })
                                    OutlinedTextField(p.year.orEmpty(), { LibraryCleanup.edit(p.copy(year = it.ifBlank { null })) }, singleLine = true, label = { Text(stringResource(R.string.cleanup_year)) })
                                } else {
                                    Text(p.album.ifBlank { stringResource(R.string.cleanup_loose) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(p.artist, style = MaterialTheme.typography.bodyMedium)
                                    Text(listOfNotNull(p.year, p.genre, p.source).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(onClick = { editing = true }) { Text(stringResource(R.string.cleanup_edit)) }
                                }
                            }
                        }
                        if (p != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(p.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (g.files.any { it.ext in setOf("flac", "wav", "aif", "aiff", "alac", "ape", "wv") }) {
                                Text(stringResource(R.string.cleanup_lossless), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.height(10.dp))
                            p.tracks.forEach { t ->
                                val f = g.files.firstOrNull { it.id == t.id }
                                Row(Modifier.padding(vertical = 3.dp)) {
                                    Text("${t.track ?: "–"}", Modifier.width(28.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Column(Modifier.weight(1f)) {
                                        if (editing) {
                                            OutlinedTextField(t.title, { v ->
                                                LibraryCleanup.edit(p.copy(tracks = p.tracks.map { if (it.id == t.id) it.copy(title = v) else it }))
                                            }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                        } else {
                                            Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        if (t.album != null) {
                                            Text(listOfNotNull(t.album, t.year).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        if (f != null && (f.title != t.title || f.artist != t.artist)) {
                                            Text(
                                                "${f.title} · ${f.artist}",
                                                style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Spacer(Modifier.height(10.dp))
                            g.files.forEach { f -> Text("${f.track ?: "–"}  ${f.name}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
        if (g != null) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = hint, onValueChange = { hint = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.cleanup_hint)) }, maxLines = 3,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = { swipe(false) }, modifier = Modifier.size(64.dp), shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cleanup_another))
                }
                FilledIconButton(onClick = { LibraryCleanup.keep() }, modifier = Modifier.size(48.dp), shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.cleanup_keep))
                }
                FilledIconButton(onClick = { swipe(true) }, enabled = p != null, modifier = Modifier.size(64.dp), shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.cleanup_apply))
                }
            }
        }
    }
}


/** BitChord Audio: the assistant — its switch, where it is, its questions, the chat and what it did. */
@Composable
private fun AssistantPane(modifier: Modifier) {
    val a by com.music.bitchord.data.cleanup.Assistant.state.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf("") }
    val time = remember { java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()) }
    Column(modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.assistant_on), style = MaterialTheme.typography.titleMedium)
                val status = when {
                    !a.on -> stringResource(R.string.assistant_off)
                    !Gemini.ready -> stringResource(R.string.assistant_needs_key)
                    a.waiting != null -> stringResource(R.string.assistant_waits)
                    a.running && a.current != null -> stringResource(R.string.assistant_working, a.current!!)
                    else -> stringResource(R.string.assistant_idle)
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.assistant_counts, a.history.count { !it.undone }, com.music.bitchord.data.cleanup.LibraryCleanup.waiting.size, a.callsToday),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(checked = a.on, onCheckedChange = { com.music.bitchord.data.cleanup.Assistant.setOn(it) })
        }
        if (a.questions.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.assistant_questions), style = MaterialTheme.typography.titleMedium)
            a.questions.forEach { q ->
                var answer by remember(q.key) { mutableStateOf("") }
                Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(q.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(q.text, style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(answer, { answer = it }, Modifier.weight(1f), singleLine = true)
                            TextButton(onClick = { com.music.bitchord.data.cleanup.Assistant.answer(q, answer); answer = "" }, enabled = answer.isNotBlank()) {
                                Text(stringResource(R.string.assistant_send))
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.assistant_chat), style = MaterialTheme.typography.titleMedium)
        if (a.chat.isEmpty()) Text(stringResource(R.string.assistant_chat_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        a.chat.takeLast(40).forEach { m ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (m.fromMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(0.85f),
                ) { Text(m.text, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (a.thinking) CircularProgressIndicator(Modifier.padding(top = 8.dp).size(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            OutlinedTextField(message, { message = it }, Modifier.weight(1f), maxLines = 4, label = { Text(stringResource(R.string.assistant_talk)) })
            TextButton(onClick = { com.music.bitchord.data.cleanup.Assistant.send(message); message = "" }, enabled = message.isNotBlank() && !a.thinking) {
                Text(stringResource(R.string.assistant_send))
            }
        }
        if (a.history.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.assistant_history), style = MaterialTheme.typography.titleMedium)
            a.history.take(200).forEach { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${d.artist} — ${d.album}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${d.path} · ${time.format(java.util.Date(d.at))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (d.undone) Text(stringResource(R.string.assistant_undone), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else TextButton(onClick = { com.music.bitchord.data.cleanup.Assistant.undo(d) }) { Text(stringResource(R.string.assistant_undo)) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

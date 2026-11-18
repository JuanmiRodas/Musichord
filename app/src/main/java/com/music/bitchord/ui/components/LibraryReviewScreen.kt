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
    fun open() {
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
        Spacer(Modifier.height(12.dp))
        val g = st.current
        val p = st.proposal
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
                                } else {
                                    Text(p.album.ifBlank { stringResource(R.string.cleanup_loose) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(p.artist, style = MaterialTheme.typography.bodyMedium)
                                    Text(listOfNotNull(p.year, p.genre, p.source).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                        Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

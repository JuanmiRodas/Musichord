package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.cleanup.CleanNow
import com.music.bitchord.data.cleanup.Gemini
import com.music.bitchord.data.cleanup.OtherBrains

/**
 * BitChord Audio: "Clean with the assistant" as it happens — the song as it is, the
 * steps the assistant takes (which AI it is asking, the cover search, writing the files)
 * with whether each worked, and its proposal filling in, editable, to apply or not.
 */
@Composable
fun CleanNowHost() {
    val s by CleanNow.session.collectAsStateWithLifecycle()
    val session = s ?: return
    val now by Gemini.activity.collectAsStateWithLifecycle()
    val used by OtherBrains.lastUsed.collectAsStateWithLifecycle()
    Dialog(onDismissRequest = { CleanNow.close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.clean_with_assistant), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                // Progress at the top: working, done or failed, and what it is doing.
                if (session.working) LinearProgressIndicator(Modifier.fillMaxWidth())
                session.steps.forEach { st ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        when (st.state) {
                            CleanNow.StepState.RUNNING -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                            CleanNow.StepState.DONE -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            CleanNow.StepState.FAILED -> Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(st.text, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (session.working) now?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 22.dp, top = 2.dp))
                }
                session.failed?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }

                Spacer(Modifier.height(14.dp))
                // The song as it is, and what is proposed.
                val p = session.proposal
                Row(verticalAlignment = Alignment.Top) {
                    AsyncImage(
                        model = p?.cover ?: session.song.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.clean_now_now), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(session.song.title, style = MaterialTheme.typography.bodyMedium,
                            textDecoration = if (p != null && p.title != session.song.title) TextDecoration.LineThrough else null)
                        Text(listOfNotNull(session.song.artist, session.song.albumName).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (p != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.clean_now_proposal) + (if (p.live) " · " + stringResource(R.string.clean_now_live) else " · " + stringResource(R.string.clean_now_studio)) +
                            " · " + stringResource(R.string.clean_now_sure, (p.confidence * 100).toInt()),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    )
                    p.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    val editable = !session.working && !session.applied
                    @Composable
                    fun field(label: Int, value: String?, onChange: (String) -> Unit) {
                        OutlinedTextField(
                            value = value.orEmpty(), onValueChange = onChange, enabled = editable, singleLine = true,
                            label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                    field(R.string.clean_now_title, p.title) { CleanNow.edit(p.copy(title = it)) }
                    field(R.string.cleanup_artist, p.artist) { CleanNow.edit(p.copy(artist = it)) }
                    field(R.string.cleanup_album, p.album) { CleanNow.edit(p.copy(album = it.ifBlank { null })) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) { field(R.string.cleanup_year, p.year) { CleanNow.edit(p.copy(year = it.ifBlank { null })) } }
                        Column(Modifier.weight(1f)) { field(R.string.clean_now_track, p.track) { CleanNow.edit(p.copy(track = it.ifBlank { null })) } }
                    }
                    field(R.string.clean_now_used_in, p.usedIn) { CleanNow.edit(p.copy(usedIn = it.ifBlank { null })) }
                    if (p.cover == null && session.working) {
                        Text(stringResource(R.string.clean_now_cover_wait), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    used?.let { Text(stringResource(R.string.ai_last_used, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (session.applied) {
                        Text(stringResource(R.string.clean_now_done), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        Button(onClick = { CleanNow.close() }) { Text(stringResource(R.string.close)) }
                    } else {
                        TextButton(onClick = { CleanNow.close() }) { Text(stringResource(R.string.cancel)) }
                        if (session.failed != null && !session.working) {
                            TextButton(onClick = { CleanNow.song(session.song) }) { Text(stringResource(R.string.clean_now_retry)) }
                        }
                        Button(onClick = { CleanNow.apply() }, enabled = p != null && !session.working) { Text(stringResource(R.string.cleanup_apply)) }
                    }
                }
            }
        }
    }
}

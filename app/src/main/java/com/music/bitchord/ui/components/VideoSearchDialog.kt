package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.video.Bilibili
import com.music.bitchord.data.video.VideoMode
import com.music.bitchord.data.video.VideoRef
import kotlinx.coroutines.launch

/** One row of the video search: a YouTube or bilibili video. */
private data class VideoHit(
    val ref: VideoRef,
    val title: String,
    val by: String,
    val duration: String?,
    val thumbnail: String?,
)

/**
 * BitChord Audio: the video button held — which video a song shows in video
 * mode. Searches YouTube with the song's name (editable); bilibili only when
 * asked. The pick stays linked to the song wherever it plays from.
 */
@Composable
fun VideoSearchDialog(song: Song, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember(song.videoId) {
        mutableStateOf("${song.title} ${song.artist.split(',').first().trim()}".trim())
    }
    var asked by remember { mutableStateOf(query) }
    var youtube by remember { mutableStateOf<List<VideoHit>?>(null) }
    var bilibili by remember { mutableStateOf<List<VideoHit>?>(null) }
    var bilibiliAsked by remember { mutableStateOf(false) }
    val current = remember(song.videoId) { VideoMode.linked(song) ?: VideoMode.cached(song.videoId) }

    LaunchedEffect(asked) {
        youtube = null
        youtube = runCatching { VideoMode.youTube(asked) }.getOrDefault(emptyList()).map {
            VideoHit(VideoRef(VideoRef.Site.YOUTUBE, it.videoId), it.title, it.artist, it.durationText, it.thumbnailUrl)
        }
    }
    LaunchedEffect(asked, bilibiliAsked) {
        if (!bilibiliAsked) return@LaunchedEffect
        bilibili = null
        bilibili = runCatching { Bilibili.search(asked) }.getOrDefault(emptyList()).map {
            VideoHit(VideoRef(VideoRef.Site.BILIBILI, it.bvid), it.title, it.author, it.durationText, it.thumbnail)
        }
    }

    fun pick(hit: VideoHit) {
        VideoMode.link(song, hit.ref)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.video_mode_search_title)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.video_mode_search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { asked = query.trim() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { asked = query.trim() }) { Text(stringResource(R.string.video_mode_search_go)) }
                }
                SectionLabel("YouTube")
                Results(youtube, current, ::pick)
                if (!bilibiliAsked) {
                    TextButton(onClick = { bilibiliAsked = true }) {
                        Text(stringResource(R.string.video_mode_search_bilibili))
                    }
                } else {
                    SectionLabel("bilibili")
                    Results(bilibili, current, ::pick)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun Results(hits: List<VideoHit>?, current: VideoRef?, onPick: (VideoHit) -> Unit) {
    when {
        hits == null -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        hits.isEmpty() -> Text(
            stringResource(R.string.video_mode_search_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> hits.take(12).forEach { hit ->
            val chosen = hit.ref == current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPick(hit) }
                    .padding(vertical = 6.dp),
            ) {
                AsyncImage(
                    model = hit.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(96.dp)
                        .heightIn(min = 54.dp, max = 54.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        hit.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(hit.by.ifBlank { null }, hit.duration).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

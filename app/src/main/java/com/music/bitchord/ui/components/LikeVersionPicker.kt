package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.names.OfficialNames
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * BitChord Audio: a liked song's like moved to the version you choose — a
 * search with terms you can change (starting from the song's name and, where
 * known, its official Latin name), YouTube Music's songs and videos to pick
 * from. The pick is liked in the song's place in Liked Music and the old one
 * un-liked.
 */
object LikeVersionPicker {
    val song = MutableStateFlow<Song?>(null)

    @Volatile var onPick: ((from: Song, to: Song) -> Unit)? = null

    fun open(song: Song) {
        this.song.value = song
    }
}

@Composable
fun LikeVersionPickerHost() {
    val song by LikeVersionPicker.song.collectAsStateWithLifecycle()
    val s = song ?: return
    LikeVersionPickerDialog(s) { LikeVersionPicker.song.value = null }
}

private fun defaultQuery(song: Song): String {
    val artist = OfficialNames.primaryArtist(song.artist)
    val title = song.title.replace(Regex("""\s*[(\[（【][^)\]）】]*(?:MV|PV|Music Video|Official|Video|ver)[^)\]）】]*[)\]）】]""", RegexOption.IGNORE_CASE), "").trim()
    return "$title $artist".trim()
}

@Composable
private fun LikeVersionPickerDialog(song: Song, onDismiss: () -> Unit) {
    var query by remember(song.videoId) { mutableStateOf(defaultQuery(song)) }
    var searched by remember(song.videoId) { mutableStateOf(defaultQuery(song)) }
    var results by remember(song.videoId) { mutableStateOf<List<Song>?>(null) }
    LaunchedEffect(song.videoId, searched) {
        results = null
        val songs = YtMusicRepository.search(searched, SearchFilter.SONGS).getOrNull().orEmpty()
        val videos = YtMusicRepository.search(searched, SearchFilter.VIDEOS).getOrNull().orEmpty()
        results = (songs + videos).mapNotNull {
            when (it) {
                is SearchResult.Track -> it.song
                is SearchResult.TopTrack -> it.song
                else -> null
            }
        }.filter { it.videoId != song.videoId }.distinctBy { it.videoId }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.like_version_title)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                Text("${song.title} · ${song.artist}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.padding(4.dp))
                SearchTermsField(value = query, onValueChange = { query = it }, onSearch = { searched = query.trim() })
                Spacer(Modifier.padding(4.dp))
                val list = results
                when {
                    list == null -> Text(stringResource(R.string.cover_searching), style = MaterialTheme.typography.labelMedium)
                    list.isEmpty() -> Text(stringResource(R.string.like_version_none), style = MaterialTheme.typography.labelMedium)
                    else -> list.forEach { r ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    LikeVersionPicker.onPick?.invoke(song, r)
                                    onDismiss()
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(r.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    r.artist.takeIf { it.isNotBlank() },
                                    r.albumName?.takeIf { it.isNotBlank() },
                                    r.durationText,
                                    if (r.isVideo) stringResource(R.string.song_version_hint) else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

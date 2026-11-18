package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.AlbumAppearances
import com.music.bitchord.data.SongFamily
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * BitChord Audio: "Appears on…" — the albums this recording is on (covers to tap), its
 * other performances (live, remix…), and the same song by other artists.
 */
object AppearsOn {
    val song = MutableStateFlow<Song?>(null)
    fun show(s: Song) { song.value = s }
}

@Composable
fun AppearsOnHost(onOpenAlbum: (String, String, String?) -> Unit, onPlay: (Song) -> Unit) {
    val current by AppearsOn.song.collectAsStateWithLifecycle()
    val song = current ?: return
    val close = { AppearsOn.song.value = null }
    val scope = rememberCoroutineScope()
    val albums by produceState(AlbumAppearances.known(song), song.videoId) {
        value = runCatching { AlbumAppearances.search(song) }.getOrDefault(value)
    }
    val performances by produceState(SongFamily.cachedPerformances(song), song.videoId) {
        value = runCatching { SongFamily.performances(song) }.getOrDefault(value ?: emptyList())
    }
    val others by produceState(SongFamily.cachedOthers(song), song.videoId) {
        value = runCatching { SongFamily.byOtherArtists(song) }.getOrDefault(value ?: emptyList())
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.appears_on_title)) },
        text = {
            Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
                Text("${song.title} · ${song.artist}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                Heading(stringResource(R.string.appears_on_albums))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(albums.filter { it.browseId != null }) { a ->
                        Card(a.cover, a.name, a.source) {
                            close()
                            onOpenAlbum(a.browseId!!, a.name, a.cover)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Heading(stringResource(R.string.appears_on_performances))
                val p = performances
                when {
                    p == null -> Small(stringResource(R.string.appears_on_looking))
                    p.isEmpty() -> Small(stringResource(R.string.appears_on_none))
                    else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(p) { s ->
                            Card(s.thumbnailUrl, s.title, listOfNotNull(s.artist.takeIf { a -> !com.music.bitchord.data.sources.OwnFileMatcher.sameArtist(a, song.artist) }, s.albumName, s.durationText).joinToString(" · ")) {
                                close()
                                onPlay(s)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Heading(stringResource(R.string.appears_on_other_artists))
                val o = others
                when {
                    o == null -> Small(stringResource(R.string.appears_on_looking))
                    o.isEmpty() -> Small(stringResource(R.string.appears_on_no_work))
                    else -> o.forEach { other ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    val s = SongFamily.findToPlay(other) ?: return@launch
                                    close()
                                    onPlay(s)
                                }
                            }.padding(vertical = 6.dp),
                        ) {
                            Text(other.artist, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Small(listOfNotNull(other.title, other.album).joinToString(" · "))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun Small(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Card(cover: String?, title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.width(112.dp).clickable(onClick = onClick)) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(112.dp).clip(RoundedCornerShape(8.dp)).background(Color.Gray.copy(alpha = 0.2f)),
        )
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

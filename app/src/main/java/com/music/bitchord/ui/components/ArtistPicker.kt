package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.OwnFileMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BitChord Audio: "Go to artist" for any song — the one credited artist opens straight
 * away; several (a duet, "A & B", "feat.") open a picker with each one's photo, as
 * Spotify does. Artists the song carries no link for (your files, JioSaavn copies) are
 * found on YouTube Music by name.
 */
object ArtistPicker {

    data class Choice(val name: String, val browseId: String, val photo: String?)

    val choices = MutableStateFlow<List<Choice>?>(null)
    @Volatile var open: ((String) -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val SPLIT = Regex("""\s*(?:,|&|/|;|、|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b|\bcon\b|\bx\b|×)\s*""", RegexOption.IGNORE_CASE)

    fun names(song: Song): List<Pair<String, String?>> {
        val refs = song.artists.filter { it.name.isNotBlank() }
        if (refs.isNotEmpty()) return refs.map { it.name to it.browseId }
        val names = song.artist.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return names.map { it to if (names.size == 1) song.artistId else null }
    }

    /** Opens [song]'s artist, or asks which when there are several. */
    fun goTo(song: Song) {
        scope.launch {
            val found = withContext(Dispatchers.IO) { resolve(song) }
            when {
                found.isEmpty() -> song.artistId?.let { open?.invoke(it) }
                found.size == 1 -> open?.invoke(found.first().browseId)
                else -> choices.value = found
            }
        }
    }

    private suspend fun resolve(song: Song): List<Choice> = kotlinx.coroutines.coroutineScope {
        names(song).map { (name, id) ->
            async {
                withTimeoutOrNull(8_000) {
                    val hit = YtMusicRepository.search(name, SearchFilter.ARTISTS).getOrNull().orEmpty()
                        .mapNotNull { (it as? SearchResult.Browse)?.item }
                        .filter { it.type == BrowseType.ARTIST }
                        .firstOrNull { it.browseId == id || OwnFileMatcher.sameArtist(name, it.title) }
                    when {
                        hit != null -> Choice(hit.title, id ?: hit.browseId, hit.thumbnailUrl)
                        id != null -> Choice(name, id, null)
                        else -> YtMusicRepository.findArtistPageId(name).getOrNull()?.let { Choice(name, it, null) }
                    }
                } ?: id?.let { Choice(name, it, null) }
            }
        }.awaitAll().filterNotNull().distinctBy { it.browseId }
    }
}

@Composable
fun ArtistPickerHost() {
    val list by ArtistPicker.choices.collectAsStateWithLifecycle()
    val choices = list ?: return
    val close = { ArtistPicker.choices.value = null }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.open_artist)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                choices.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            close()
                            ArtistPicker.open?.invoke(c.browseId)
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = c.photo,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.25f)),
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
    )
}

package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Queue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.CoverPicker
import com.music.bitchord.ui.components.MetadataEditor

/**
 * BitChord Audio: songs picked on an album or playlist page — started from the
 * page's ⋯ ("Select songs"); while it lasts a tap ticks a song instead of
 * playing it. Keyed by the page, so it ends with the page.
 */
object ReleaseSelection {
    var browseId by mutableStateOf<String?>(null)
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set

    fun start(id: String) {
        browseId = id
        selected = emptySet()
    }

    fun active(id: String?) = id != null && browseId == id

    fun toggle(song: Song) {
        selected = if (song.videoId in selected) selected - song.videoId else selected + song.videoId
    }

    fun selectAll(songs: List<Song>) {
        selected = songs.mapTo(linkedSetOf()) { it.videoId }
    }

    fun end() {
        browseId = null
        selected = emptySet()
    }
}

/** The selection's actions, floated over the list as Downloads' are. */
@Composable
internal fun ReleaseSelectionBar(
    songs: List<Song>,
    onPlayNext: ((List<Song>) -> Unit)?,
    onAddToQueue: ((List<Song>) -> Unit)?,
    modifier: Modifier = Modifier,
    /** BitChord Audio: like the selection (all liked already: unlike). */
    onLike: ((List<Song>) -> Unit)? = null,
) {
    val chosen = songs.filter { it.videoId in ReleaseSelection.selected }
    val count = chosen.size
    val allSelected = songs.isNotEmpty() && count == songs.size
    val shape = RoundedCornerShape(14.dp)
    val dark = isSystemInDarkTheme()
    val tint = if (dark) Color.White else MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .shadow(10.dp, shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun action(icon: ImageVector, label: String, enabled: Boolean = count > 0, onClick: () -> Unit) {
            IconButton(onClick = onClick, enabled = enabled) {
                Icon(icon, contentDescription = label, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(21.dp))
            }
        }
        action(Icons.Rounded.Check, stringResource(R.string.select_all), enabled = !allSelected) { ReleaseSelection.selectAll(songs) }
        Text(
            text = "$count ${stringResource(R.string.selected)}",
            style = MaterialTheme.typography.titleSmall,
            color = if (dark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        onLike?.let { like -> action(Icons.Rounded.Star, stringResource(R.string.like)) { like(chosen); ReleaseSelection.end() } }
        action(Icons.Rounded.Image, stringResource(R.string.selection_cover)) { CoverPicker.open(chosen); ReleaseSelection.end() }
        action(Icons.Rounded.Edit, stringResource(R.string.selection_edit)) { MetadataEditor.open(chosen); ReleaseSelection.end() }
        onPlayNext?.let { play -> action(Icons.AutoMirrored.Rounded.PlaylistPlay, stringResource(R.string.play_next)) { play(chosen); ReleaseSelection.end() } }
        onAddToQueue?.let { add -> action(Icons.Rounded.Queue, stringResource(R.string.add_to_queue)) { add(chosen); ReleaseSelection.end() } }
        action(Icons.Rounded.Close, stringResource(R.string.cancel), enabled = true) { ReleaseSelection.end() }
    }
}

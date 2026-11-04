package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.AlbumAppearances
import com.music.bitchord.data.LikeLookup
import com.music.bitchord.data.LikedMusicCache
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.icons.BitChordIcons

/**
 * BitChord Audio: a row's star, beside its time — filled when the song is
 * liked (here, or the same song off another album), with a "+" inside when
 * it is on more than one album. A tap likes it, or unlikes the liked copy.
 */
@Composable
fun LikeStar(song: Song, tint: Color, background: Color) {
    val changes by LikeLookup.changes.collectAsState(initial = Unit)
    val likedSongs by LikedMusicCache.likedSongs.collectAsState()
    val likedCopy = remember(song.videoId, song.title, changes, likedSongs) { AlbumAppearances.likedCopy(song) }
    val several = remember(song.videoId, song.title, likedSongs) { likedCopy != null && AlbumAppearances.onSeveral(song) }
    val liked = likedCopy != null
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                LikeLookup.toggle?.invoke((likedCopy ?: song).videoId)
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (liked) BitChordIcons.StarFilled else BitChordIcons.Star,
            contentDescription = stringResource(if (liked) R.string.unlike else R.string.like),
            tint = if (liked) tint else tint.copy(alpha = 0.55f),
            modifier = Modifier.size(19.dp),
        )
        if (several) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = null,
                tint = background,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

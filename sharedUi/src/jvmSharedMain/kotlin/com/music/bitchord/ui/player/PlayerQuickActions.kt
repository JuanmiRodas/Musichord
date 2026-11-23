package com.music.bitchord.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.ThumbDownOffAlt
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * BitChord Audio: the buttons that can sit in the row above the transport, chosen and
 * ordered in Settings (up to [MAX]; the ⋯ menu is always there besides). Anything the ⋯
 * menu does for the playing song can be one of them.
 */
enum class PlayerQuickAction(val id: String, val label: String, val icon: ImageVector) {
    VIDEO("video", "Video", Icons.Rounded.SmartDisplay),
    VIDEO_AUDIO("video_audio", "Video audio", Icons.Rounded.MusicNote),
    TRANSLATION("translation", "Translation", Icons.Rounded.Translate),
    LYRICS("lyrics", "Lyrics on the cover", Icons.Rounded.Lyrics),
    STAR("star", "Like", BitChordIcons.Star),
    ALBUM("album", "Album", Icons.Rounded.Album),
    ARTIST("artist", "Artist", Icons.Rounded.Person),
    DONT_RECOMMEND("dont_recommend", "Don't recommend", Icons.Rounded.ThumbDownOffAlt),
    DOWNLOAD("download", "Download", Icons.Rounded.Download),
    DELETE_COPY("delete_copy", "Delete from the phone", Icons.Rounded.DeleteOutline),
    RADIO("radio", "Start radio", Icons.Rounded.Radio),
    ADD_TO_PLAYLIST("playlist", "Add to playlist", Icons.Rounded.PlaylistAdd),
    SHARE("share", "Share", Icons.Rounded.Share),
    SLEEP("sleep", "Sleep timer", Icons.Rounded.Bedtime),
    SOURCE("source", "Choose the source", Icons.Rounded.SwapHoriz),
    COVER("cover", "Change the cover", Icons.Rounded.Image),
    INFO("info", "Song info", Icons.Rounded.Info),
    ;

    companion object {
        const val MAX = 8
        val DEFAULT = listOf(VIDEO, VIDEO_AUDIO, TRANSLATION, LYRICS, STAR, ALBUM, ARTIST, DONT_RECOMMEND)
        fun of(id: String): PlayerQuickAction? = entries.firstOrNull { it.id == id }
    }
}

/** States the app knows and the row shows (lit buttons). */
object PlayerQuickActionState {
    /** Ids of actions that are "on" now (a download saved, the sleep timer running…). */
    val lit = MutableStateFlow<Set<String>>(emptySet())
}

internal val DefaultQuickActionIds: StateFlow<List<String>> = MutableStateFlow(PlayerQuickAction.DEFAULT.map { it.id })

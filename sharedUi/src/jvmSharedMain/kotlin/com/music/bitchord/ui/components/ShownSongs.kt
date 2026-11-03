package com.music.bitchord.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.shown

/**
 * BitChord Audio: songs as they are shown — with the details you corrected
 * by hand. [version] is bumped by the app whenever a correction changes, so
 * every row and the player draw again.
 */
object ShownSongs {
    val version = mutableIntStateOf(0)

    fun bump() {
        version.intValue++
    }
}

@Composable
fun rememberShown(song: Song): Song {
    val v = ShownSongs.version.intValue
    return remember(song, v) { song.shown() }
}

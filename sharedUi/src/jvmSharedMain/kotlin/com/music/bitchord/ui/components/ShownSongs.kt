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

/**
 * BitChord Audio: [song] for the eye — your corrections, then every name in a
 * non-Latin script with its Latin form beside it (see
 * [com.music.bitchord.data.names.DisplayNames]). Only for text on screen.
 */
@Composable
fun rememberDisplayed(song: Song): Song {
    val v = ShownSongs.version.intValue
    return remember(song, v) { song.displayed() }
}

fun Song.displayed(): Song {
    val s = shown()
    val names = com.music.bitchord.data.names.DisplayNames
    return s.copy(
        title = names.title(s.title, s.artist),
        artist = names.artist(s.artist),
        albumName = s.albumName?.let { names.album(it, s.artist) },
    )
}

/** The credit as corrected, undecorated — for linking names to artists. */
fun shownArtistOf(song: Song): String = song.shown().artist

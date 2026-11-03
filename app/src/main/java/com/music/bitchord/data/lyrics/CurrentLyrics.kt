package com.music.bitchord.data.lyrics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BitChord Audio: the words the player has loaded for the song on now, by its
 * id — so the always-on music screen shows the same lyrics without fetching
 * them again.
 */
object CurrentLyrics {
    private val _lines = MutableStateFlow<Pair<String, List<LyricLine>>?>(null)
    val lines: StateFlow<Pair<String, List<LyricLine>>?> = _lines.asStateFlow()

    fun set(videoId: String, lines: List<LyricLine>?) {
        _lines.value = if (lines.isNullOrEmpty()) null else videoId to lines
    }
}

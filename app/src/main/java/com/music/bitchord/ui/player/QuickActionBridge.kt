package com.music.bitchord.ui.player

import com.music.bitchord.data.model.Song

/**
 * BitChord Audio: where the player's quick buttons reach the app's own work — the same
 * functions the ⋯ menu calls, set by the activity that owns them.
 */
object QuickActionBridge {
    @Volatile var handler: ((id: String, song: Song) -> Unit)? = null
}

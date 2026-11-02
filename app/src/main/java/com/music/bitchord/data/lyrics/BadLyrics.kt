package com.music.bitchord.data.lyrics

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BitChord Audio: lyrics flagged as wrong for a song. The source that gave
 * them isn't asked for that song again (your own lyrics files count as one
 * source, "OWN"), and the lookup runs again without it. Synced to pCloud with
 * the settings.
 */
object BadLyrics {
    const val OWN = "OWN"
    private const val PREFS = "bitchord_bad_lyrics"

    private var prefs: SharedPreferences? = null
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** The sources flagged for [videoId]. */
    fun flagged(videoId: String): Set<String> = prefs?.getStringSet(videoId, emptySet())?.toSet().orEmpty()

    fun flag(context: Context, videoId: String, source: String) {
        val p = prefs ?: return
        p.edit().putStringSet(videoId, flagged(videoId) + source).apply()
        _version.value += 1
        com.music.bitchord.data.sync.SettingsSync.changed(context)
    }

    /** Every source allowed again for [videoId]. */
    fun reset(context: Context, videoId: String) {
        prefs?.edit()?.remove(videoId)?.apply()
        _version.value += 1
        com.music.bitchord.data.sync.SettingsSync.changed(context)
    }
}

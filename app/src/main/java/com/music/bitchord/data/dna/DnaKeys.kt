package com.music.bitchord.data.dna

import android.content.Context
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * BitChord Audio: the free keys the song's DNA can use — your own Discogs token and your
 * own Last.fm API key. They now live with the other services' keys (Settings › Services),
 * encrypted on this phone; this is the old door to them, kept for its callers. Keys typed
 * here before are moved there once.
 */
object DnaKeys {
    private const val OLD_PREFS = "bitchord_dna_keys"

    val discogs: StateFlow<String> get() = AppSettings.discogsToken
    val lastFm: StateFlow<String> get() = AppSettings.lastfmApiKey

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
        val d = p.getString("discogs", "").orEmpty().trim()
        val l = p.getString("lastfm", "").orEmpty().trim()
        if (d.isEmpty() && l.isEmpty()) return
        if (d.isNotEmpty() && AppSettings.discogsToken.value.isBlank()) AppSettings.setDiscogsToken(d)
        if (l.isNotEmpty() && AppSettings.lastfmApiKey.value.isBlank()) AppSettings.setLastfmApiKey(l)
        p.edit().clear().apply()
    }

    fun setDiscogs(v: String) = AppSettings.setDiscogsToken(v)

    fun setLastFm(v: String) = AppSettings.setLastfmApiKey(v.trim())
}

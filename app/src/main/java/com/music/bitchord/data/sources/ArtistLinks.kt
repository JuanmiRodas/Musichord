package com.music.bitchord.data.sources

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.remote.FileName
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: artist names known to be the same person, learnt from your
 * own library rather than written in.
 *
 * When a YouTube song and one of your files agree on name, album and length
 * but not on the artist as written — "浜崎あゆみ" and "Ayumi Hamasaki",
 * "La 5a Estación" and "La Quinta Estacion" — the two names are linked, and
 * from then on that artist's other songs match on name and length alone.
 * Kept on the device across restarts.
 */
object ArtistLinks {

    private const val PREFS = "bitchord_artist_links"

    private var prefs: SharedPreferences? = null
    private val links = ConcurrentHashMap<String, MutableSet<String>>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { p ->
            p.all.forEach { (k, v) ->
                (v as? Set<*>)?.filterIsInstance<String>()?.let { links[k] = ConcurrentHashMap.newKeySet<String>().apply { addAll(it) } }
            }
        }
    }

    private fun key(name: String) = FileName.key(name.replace('$', 's'))

    fun learn(youtubeArtist: String, fileArtist: String) {
        val a = key(youtubeArtist)
        val b = key(fileArtist)
        if (a.isEmpty() || b.isEmpty() || a == b) return
        val set = links.getOrPut(a) { ConcurrentHashMap.newKeySet() }
        if (set.add(b)) prefs?.edit()?.putStringSet(a, HashSet(set))?.apply()
    }

    fun linked(youtubeArtist: String, fileArtist: String): Boolean =
        links[key(youtubeArtist)]?.contains(key(fileArtist)) == true
}

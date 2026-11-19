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

    /**
     * BitChord Audio: a credit that names nobody ("Unknown artist", "Various Artists",
     * "Soundtrack") is never linked to an artist: one file tagged that way and matched to a
     * Yuki Kajiura song made every "Unknown artist" album hers.
     */
    private val NOBODY = setOf(
        "unknownartist", "unknown", "artistadesconocido", "desconocido", "variousartists", "various", "va",
        "varios", "variosartistas", "soundtrack", "ost", "anime", "untitled", "artist", "nobody",
    )
    private fun nobody(k: String) = k.isEmpty() || k in NOBODY || k.startsWith("unknown")

    fun learn(youtubeArtist: String, fileArtist: String) {
        val a = key(youtubeArtist)
        val b = key(fileArtist)
        if (nobody(a) || nobody(b) || a == b) return
        val set = links.getOrPut(a) { ConcurrentHashMap.newKeySet() }
        if (set.add(b)) prefs?.edit()?.putStringSet(a, HashSet(set))?.apply()
    }

    /** Every name [artist] is linked with, either way round (as keys: letters and digits). */
    fun aliases(artist: String): Set<String> {
        val k = key(artist)
        if (nobody(k)) return emptySet()
        val out = HashSet<String>()
        links[k]?.let(out::addAll)
        links.forEach { (a, set) -> if (k in set) out += a }
        return out.filterNot(::nobody).toSet() - k
    }

    /** BitChord Audio: the two names are different artists after all (both ways round). */
    fun forget(a: String, b: String) {
        val ka = key(a)
        val kb = key(b)
        listOf(ka to kb, kb to ka).forEach { (x, y) ->
            links[x]?.let { set -> if (set.remove(y)) prefs?.edit()?.putStringSet(x, HashSet(set))?.apply() }
        }
    }

    fun linked(youtubeArtist: String, fileArtist: String): Boolean {
        val a = key(youtubeArtist)
        val b = key(fileArtist)
        if (nobody(a) || nobody(b)) return false
        return links[a]?.contains(b) == true
    }
}

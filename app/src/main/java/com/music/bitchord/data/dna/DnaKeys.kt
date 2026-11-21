package com.music.bitchord.data.dna

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BitChord Audio: the free keys the song's DNA can use — your own Discogs token and,
 * optionally, your own Last.fm API key (the app's is used otherwise). Private: kept on
 * this phone only, never synced or backed up.
 */
object DnaKeys {
    private const val PREFS = "bitchord_dna_keys"
    private var prefs: SharedPreferences? = null

    private val _discogs = MutableStateFlow("")
    val discogs: StateFlow<String> = _discogs.asStateFlow()

    private val _lastFm = MutableStateFlow("")
    val lastFm: StateFlow<String> = _lastFm.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _discogs.value = p.getString("discogs", "").orEmpty()
        _lastFm.value = p.getString("lastfm", "").orEmpty()
    }

    fun setDiscogs(v: String) {
        _discogs.value = v.trim()
        prefs?.edit()?.putString("discogs", v.trim())?.apply()
    }

    fun setLastFm(v: String) {
        _lastFm.value = v.trim()
        prefs?.edit()?.putString("lastfm", v.trim())?.apply()
    }
}

/** BitChord Audio: credit roles, from any source and in any language, sorted into groups. */
object DnaRoles {
    enum class Group { PERFORMER, WRITING, ARRANGEMENT, PRODUCTION, ENGINEERING, LABEL, OTHER }

    fun group(role: String): Group {
        val r = role.lowercase()
        fun has(vararg w: String) = w.any { it in r }
        return when {
            has("source", "fuente", "label", "sello", "publisher", "editorial", "copyright", "phonographic", "℗", "©", "distribut") -> Group.LABEL
            has("compos", "written", "writer", "songwriter", "lyric", "letr", "autor", "author", "words", "music by", "作詞", "作曲", "lyricist", "escrit") -> Group.WRITING
            has("arrang", "arregl", "orchestrat", "orquest", "編曲") -> Group.ARRANGEMENT
            has("produc", "executive", "a&r") -> Group.PRODUCTION
            has("engineer", "ingenier", "mix", "mezcl", "master", "record", "graba", "sound", "sonido", "programm", "programa", "edit") -> Group.ENGINEERING
            has(
                "perform", "interpret", "artist", "artista", "vocal", "voz", "voice", "sing", "cant", "featur", "guitar", "guitarr", "bass",
                "bajo", "drum", "bater", "piano", "keyboard", "teclad", "synth", "sintet", "violin", "string", "cuerda", "percussion",
                "percusi", "horn", "sax", "trumpet", "trompeta", "chorus", "coro", "choir", "instrument", "rap", "dj", "band", "orchestra",
                "ensemble", "conduct",
            ) -> Group.PERFORMER
            else -> Group.OTHER
        }
    }
}

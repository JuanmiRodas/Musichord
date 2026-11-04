package com.music.bitchord.data.sources

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: songs you said are not the same song — a pair the matcher
 * took for one (same name, a similar length, the same compilation) and
 * played one in the other's place. Never matched again, either way round.
 */
object NotSame {

    private const val PREFS = "bitchord_not_same"
    private const val KEY = "pairs"

    private var prefs: SharedPreferences? = null
    private val pairs = ConcurrentHashMap.newKeySet<String>()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also { p ->
            pairs.addAll(p.getStringSet(KEY, emptySet()).orEmpty())
        }
    }

    private fun pair(a: String, b: String) = if (a < b) "$a\u0001$b" else "$b\u0001$a"

    fun blocked(a: String?, b: String?): Boolean =
        a != null && b != null && a != b && pair(a, b) in pairs

    fun add(a: String, b: String) {
        if (a == b) return
        pairs += pair(a, b)
        prefs?.edit()?.putStringSet(KEY, HashSet(pairs))?.apply()
    }
}

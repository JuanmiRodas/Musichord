package com.music.bitchord.aod

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BitChord Audio: the always-on music screen's settings — whether it takes over
 * from the phone's always-on display while music plays, how bright it is, and
 * the widgets (from any app) it shows.
 */
object AodSettings {

    private const val PREFS = "bitchord_aod"
    private var prefs: SharedPreferences? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _brightness = MutableStateFlow(0.15f)

    /** The screen's brightness while it shows, 0.02–1. */
    val brightness: StateFlow<Float> = _brightness.asStateFlow()

    private val _widgets = MutableStateFlow<List<Int>>(emptyList())

    /** The app widgets shown (ids from [AodWidgets]), in order. */
    val widgets: StateFlow<List<Int>> = _widgets.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _enabled.value = p.getBoolean("enabled", false)
        _brightness.value = p.getFloat("brightness", 0.15f)
        _widgets.value = p.getString("widgets", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        prefs?.edit()?.putBoolean("enabled", on)?.apply()
    }

    fun setBrightness(value: Float) {
        _brightness.value = value.coerceIn(0.02f, 1f)
        prefs?.edit()?.putFloat("brightness", _brightness.value)?.apply()
    }

    fun setWidgets(ids: List<Int>) {
        _widgets.value = ids
        prefs?.edit()?.putString("widgets", ids.joinToString(","))?.apply()
    }
}

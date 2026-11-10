package com.music.bitchord.data

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.HomeShelf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BitChord Audio: which of Home's shelves show, and in what order (from
 * LastWave's "Home sections"). Home's shelves come from YouTube Music and are
 * known by their titles ("Quick picks", "Listen again", "Mixed for you"…), so
 * the order is a list of titles; a shelf not in it keeps YouTube's place,
 * after the ordered ones. The "Make a mix" card is a switch of its own.
 */
object HomeSections {

    private var prefs: SharedPreferences? = null

    private val _order = MutableStateFlow<List<String>>(emptyList())
    val order: StateFlow<List<String>> = _order.asStateFlow()

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    private val _mixCard = MutableStateFlow(true)
    val mixCard: StateFlow<Boolean> = _mixCard.asStateFlow()

    /** The shelf titles Home last showed, for the settings list. */
    private val _seen = MutableStateFlow<List<String>>(emptyList())
    val seen: StateFlow<List<String>> = _seen.asStateFlow()

    private const val SEP = "\u001f"

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("bitchord_home_sections", Context.MODE_PRIVATE)
        prefs = p
        _order.value = p.getString("order", "").orEmpty().split(SEP).filter { it.isNotBlank() }
        _hidden.value = p.getString("hidden", "").orEmpty().split(SEP).filter { it.isNotBlank() }.toSet()
        _mixCard.value = p.getBoolean("mix_card", true)
        _seen.value = p.getString("seen", "").orEmpty().split(SEP).filter { it.isNotBlank() }
    }

    /** [shelves] hidden and ordered as chosen; also remembers their titles. */
    fun apply(shelves: List<HomeShelf>): List<HomeShelf> {
        val titles = shelves.map { it.title }.filter { it.isNotBlank() }
        val known = (_seen.value + titles).distinct().takeLast(60)
        if (known != _seen.value) {
            _seen.value = known
            prefs?.edit()?.putString("seen", known.joinToString(SEP))?.apply()
        }
        val order = _order.value
        val visible = shelves.filterNot { it.title in _hidden.value }
        if (order.isEmpty()) return visible
        val rank = order.withIndex().associate { (i, t) -> t to i }
        return visible.withIndex()
            .sortedWith(compareBy({ rank[it.value.title] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
    }

    /** The settings list: ordered titles first, then the rest seen. */
    fun listed(): List<String> = (_order.value + _seen.value).distinct()

    fun move(title: String, by: Int) {
        val list = listed().toMutableList()
        val i = list.indexOf(title)
        val j = (i + by).coerceIn(0, list.lastIndex)
        if (i < 0 || i == j) return
        list.add(j, list.removeAt(i))
        _order.value = list
        prefs?.edit()?.putString("order", list.joinToString(SEP))?.apply()
    }

    fun setHidden(title: String, hide: Boolean) {
        _hidden.value = if (hide) _hidden.value + title else _hidden.value - title
        prefs?.edit()?.putString("hidden", _hidden.value.joinToString(SEP))?.apply()
    }

    fun setMixCard(on: Boolean) {
        _mixCard.value = on
        prefs?.edit()?.putBoolean("mix_card", on)?.apply()
    }

    fun reset() {
        _order.value = emptyList()
        _hidden.value = emptySet()
        prefs?.edit()?.remove("order")?.remove("hidden")?.apply()
    }
}

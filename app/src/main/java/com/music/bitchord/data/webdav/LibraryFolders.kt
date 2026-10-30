package com.music.bitchord.data.webdav

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * BitChord Audio: the folders of your pCloud / WebDAV account the app reads —
 * several, each with its own name, each switched on or off on its own.
 *
 * All on the account signed in under Sources (one server, one login); each
 * folder is just a place on it. A folder holds music, lyrics (`.lrc` files,
 * searched by name — see [com.music.bitchord.data.lyrics.SidecarLyrics]), or
 * both. Removing a folder here only stops the app reading it: nothing on the
 * server is touched.
 *
 * The first music folder is also what the rest of the app knows as "the
 * WebDAV URL" ([AppSettings.webdavUrl]) — the address the sign-in is checked
 * against and the credentials are sent to — so a single-folder setup made
 * before this existed carries on as folder one.
 */
object LibraryFolders {

    enum class Kind { MUSIC, LYRICS, BOTH }

    data class Folder(
        val id: String,
        val name: String,
        val url: String,
        val enabled: Boolean = true,
        val kind: Kind = Kind.MUSIC,
    ) {
        val hasMusic: Boolean get() = kind != Kind.LYRICS
        val hasLyrics: Boolean get() = kind != Kind.MUSIC
    }

    private const val PREFS = "bitchord_library_folders"
    private const val KEY = "folders"

    private var prefs: SharedPreferences? = null
    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    fun init(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = sp
        var list = sp.getString(KEY, null)?.let(::decode).orEmpty()
        // Made before folders existed: the one URL becomes folder one.
        val legacy = AppSettings.webdavUrl.value
        if (list.isEmpty() && WebDavConfig.isConfigured(legacy)) {
            list = listOf(Folder(id = newId(), name = defaultName(legacy), url = legacy, kind = Kind.BOTH))
            sp.edit().putString(KEY, encode(list)).apply()
        }
        _folders.value = list
    }

    fun musicFolders(): List<Folder> = _folders.value.filter { it.enabled && it.hasMusic }
    fun lyricsFolders(): List<Folder> = _folders.value.filter { it.enabled && it.hasLyrics }

    fun add(name: String, url: String, kind: Kind) {
        update(_folders.value + Folder(newId(), name.trim().ifBlank { defaultName(url) }, url.trim(), true, kind))
    }

    fun setEnabled(id: String, enabled: Boolean) =
        update(_folders.value.map { if (it.id == id) it.copy(enabled = enabled) else it })

    fun edit(id: String, name: String, url: String, kind: Kind) =
        update(_folders.value.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }, url = url.trim(), kind = kind) else it })

    /** Stops reading [id]. Nothing on the server changes. */
    fun remove(id: String) = update(_folders.value.filterNot { it.id == id })

    /** Signing in to pCloud again replaces the folder it set up, keeping the others. */
    fun replacePrimary(url: String) {
        val list = _folders.value
        val first = list.indexOfFirst { it.hasMusic }
        update(
            if (first < 0) {
                listOf(Folder(newId(), defaultName(url), url, true, Kind.BOTH)) + list
            } else {
                list.toMutableList().also { it[first] = it[first].copy(url = url, name = defaultName(url), enabled = true) }
            },
        )
    }

    fun clear() = update(emptyList())

    private fun update(list: List<Folder>) {
        _folders.value = list
        prefs?.edit()?.putString(KEY, encode(list))?.apply()
        // The first music folder is the address the rest of the app signs in to.
        val primary = list.firstOrNull { it.hasMusic }?.url ?: list.firstOrNull()?.url
        if (primary != null && primary != AppSettings.webdavUrl.value) AppSettings.setWebDavUrl(primary)
    }

    private fun defaultName(url: String): String =
        if (PcloudPreset.isPcloud(url)) {
            "pCloud · " + PcloudPreset.folderOf(url).ifBlank { "/" }
        } else {
            runCatching { java.net.URI(url).path.trim('/').substringAfterLast('/') }.getOrNull()?.ifBlank { null }
                ?: WebDavConfig.hostOf(url).orEmpty()
        }

    private fun newId(): String = "f" + System.nanoTime().toString(36)

    private fun encode(list: List<Folder>): String = JSONArray().apply {
        list.forEach { f ->
            put(
                JSONObject().put("id", f.id).put("name", f.name).put("url", f.url)
                    .put("enabled", f.enabled).put("kind", f.kind.name),
            )
        }
    }.toString()

    private fun decode(raw: String): List<Folder> = runCatching {
        val a = JSONArray(raw)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Folder(
                id = o.getString("id"),
                name = o.optString("name"),
                url = o.getString("url"),
                enabled = o.optBoolean("enabled", true),
                kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.MUSIC),
            )
        }
    }.getOrDefault(emptyList())
}

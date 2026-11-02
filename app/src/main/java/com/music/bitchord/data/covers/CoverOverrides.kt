package com.music.bitchord.data.covers

import android.content.Context
import com.music.bitchord.data.model.ArtworkOverrides
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.PreferredSource
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: the cover you picked for a song, by its title and artist —
 * so the YouTube song, your file and JioSaavn's copy all show it, in lists,
 * the player and the lock screen. Kept on the phone and in pCloud
 * (`.bitchord-audio/covers.json`), so another install has them too.
 */
object CoverOverrides {

    private const val FILE = "covers.json"
    private const val REMOTE_DIR = ".bitchord-audio"

    private val covers = ConcurrentHashMap<String, String>()
    private val _version = MutableStateFlow(0)

    /** Bumped on every change, for screens to draw again. */
    val version: StateFlow<Int> = _version.asStateFlow()

    @Volatile
    private var local: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        local = File(context.filesDir, FILE)
        ArtworkOverrides.lookup = { title, artist -> if (covers.isEmpty()) null else get(title, artist) }
        scope.launch {
            runCatching { local?.takeIf { it.isFile }?.readText() }.getOrNull()?.let { read(it, replace = true) }
            remoteUrl()?.let { url ->
                WebDavClient.readText(url, AppSettings.webdavUsername.value, AppSettings.webdavPassword.value, 4 * 1024 * 1024)
                    .getOrNull()?.let { read(it, replace = false) }
            }
            _version.value++
        }
    }

    fun get(title: String, artist: String): String? = PreferredSource.keyOf(title, artist)?.let { covers[it] }

    fun get(song: Song): String? = get(song.title, song.artist)

    fun set(songs: List<Song>, url: String) {
        songs.forEach { s -> PreferredSource.keyOf(s.title, s.artist)?.let { covers[it] = url } }
        changed()
    }

    fun clear(songs: List<Song>) {
        songs.forEach { s -> PreferredSource.keyOf(s.title, s.artist)?.let { covers.remove(it) } }
        changed()
    }

    private fun changed() {
        _version.value++
        val text = JSONObject(covers as Map<*, *>).toString()
        local?.let { f -> runCatching { f.writeText(text) } }
        scope.launch {
            val dir = remoteDir() ?: return@launch
            val u = AppSettings.webdavUsername.value
            val p = AppSettings.webdavPassword.value
            val bytes = text.toByteArray(Charsets.UTF_8)
            WebDavClient.ensureCollection(dir, u, p)
            WebDavClient.putFile(dir + FILE, bytes.inputStream(), bytes.size.toLong(), "application/json", u, p, overwrite = true)
        }
    }

    private fun read(text: String, replace: Boolean) = runCatching {
        val o = JSONObject(text)
        o.keys().forEach { k -> if (replace) covers[k] = o.getString(k) else covers.putIfAbsent(k, o.getString(k)) }
    }

    private fun remoteDir(): String? {
        val base = AppSettings.webdavUrl.value
        if (!WebDavConfig.isConfigured(base)) return null
        return WebDavConfig.normalizeUrl(base).trimEnd('/') + "/$REMOTE_DIR/"
    }

    private fun remoteUrl(): String? = remoteDir()?.let { it + FILE }
}

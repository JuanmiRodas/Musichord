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
        ArtworkOverrides.lookup = { song -> if (covers.isEmpty()) null else get(song) }
        ArtworkOverrides.display = { song -> runCatching { pickedCopyOf(song) }.getOrNull() }
        scope.launch {
            runCatching { local?.takeIf { it.isFile }?.readText() }.getOrNull()?.let { read(it, replace = true) }
            remoteUrl()?.let { url ->
                WebDavClient.readText(url, AppSettings.webdavUsername.value, AppSettings.webdavPassword.value, 4 * 1024 * 1024)
                    .getOrNull()?.let { read(it, replace = false) }
            }
            _version.value++
        }
    }

    @Volatile
    private var libraryIndex: Pair<List<Song>, Map<String, Song>>? = null

    /**
     * The copy you picked by hand for [song] (your file, JioSaavn's), shown in
     * its place in lists — its title, artist and cover — or null.
     */
    fun pickedCopyOf(song: Song): Song? {
        if (WebDavConfig.isWebDavId(song.videoId) || com.music.bitchord.data.sources.SourceRegistry.parseTrackKey(song.videoId) != null) return null
        val copy = when (val c = PreferredSource.get(song)) {
            is PreferredSource.File -> {
                val library = com.music.bitchord.data.webdav.WebDavRepository.library()
                val index = libraryIndex?.takeIf { it.first === library }?.second
                    ?: library.associateBy { it.videoId }.also { libraryIndex = library to it }
                index[c.id]
            }
            is PreferredSource.Chosen -> c.song
            else -> null
        } ?: return null
        return copy.copy(isVideo = false)
    }

    /**
     * BitChord Audio: a cover is picked for one version of a song — the song on that album
     * ("Lacrimosa" on the live album), not every song of that name: the studio take on its
     * own album keeps its cover. Covers picked before this were by title and artist alone;
     * they still show where no version of the song has one of its own, and go when one is
     * picked again.
     */
    private fun albumKey(s: Song): String? = s.albumName?.takeIf { it.isNotBlank() }
        ?.let { com.music.bitchord.data.sources.OwnFileMatcher.key(it) }?.takeIf { it.isNotEmpty() }

    private fun keyOf(s: Song): String? {
        val base = PreferredSource.keyOf(s.title, s.artist) ?: return null
        return albumKey(s)?.let { "$base@$it" } ?: base
    }

    fun get(title: String, artist: String): String? = PreferredSource.keyOf(title, artist)?.let { covers[it] }

    fun get(song: Song): String? {
        val base = PreferredSource.keyOf(song.title, song.artist) ?: return null
        albumKey(song)?.let { a -> covers["$base@$a"]?.let { return it } }
        val legacy = covers[base] ?: return null
        // An old title-and-artist cover: not on a version that has its own elsewhere.
        if (albumKey(song) != null && covers.keys.any { it.startsWith("$base@") }) return null
        return legacy
    }

    fun set(songs: List<Song>, url: String) {
        songs.forEach { s ->
            keyOf(s)?.let { covers[it] = url }
            // The old cover for every version of the name goes: it was the one spilling over.
            if (albumKey(s) != null) PreferredSource.keyOf(s.title, s.artist)?.let { covers.remove(it) }
        }
        changed()
    }

    fun clear(songs: List<Song>) {
        songs.forEach { s ->
            keyOf(s)?.let { covers.remove(it) }
            PreferredSource.keyOf(s.title, s.artist)?.let { covers.remove(it) }
        }
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

package com.music.bitchord.data.edits

import android.content.Context
import com.music.bitchord.data.model.ArtworkOverrides
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
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
 * BitChord Audio: song details you corrected by hand — title, artist, album,
 * album artist, year, track number, genre — shown in place of the source's
 * everywhere (lists, the player, the lock screen, the speaker), whatever the
 * song plays from: YouTube, JioSaavn, your pCloud file or a download. The song
 * stays the same song underneath (its id, its links, its source); only what is
 * shown changes. Written into your files too when asked ([TagWriter]).
 *
 * Found by the song's id, and by its original title and artist (exactly, live
 * and other versions included) so the same song from another source shows the
 * same correction. Kept on the phone and in pCloud
 * (`.bitchord-audio/metadata.json`), so another install has them.
 */
object MetadataEdits {

    data class Edit(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val year: String? = null,
        val track: String? = null,
        val genre: String? = null,
    ) {
        val isEmpty: Boolean get() = listOf(title, artist, album, albumArtist, year, track, genre).all { it.isNullOrBlank() }

        /** [other]'s fields over these, where [other] says something. */
        fun with(other: Edit) = Edit(
            title = other.title ?: title,
            artist = other.artist ?: artist,
            album = other.album ?: album,
            albumArtist = other.albumArtist ?: albumArtist,
            year = other.year ?: year,
            track = other.track ?: track,
            genre = other.genre ?: genre,
        )

        fun toJson(): JSONObject = JSONObject().apply {
            title?.let { put("title", it) }
            artist?.let { put("artist", it) }
            album?.let { put("album", it) }
            albumArtist?.let { put("albumArtist", it) }
            year?.let { put("year", it) }
            track?.let { put("track", it) }
            genre?.let { put("genre", it) }
        }

        companion object {
            fun from(o: JSONObject) = Edit(
                title = o.optString("title").ifEmpty { null },
                artist = o.optString("artist").ifEmpty { null },
                album = o.optString("album").ifEmpty { null },
                albumArtist = o.optString("albumArtist").ifEmpty { null },
                year = o.optString("year").ifEmpty { null },
                track = o.optString("track").ifEmpty { null },
                genre = o.optString("genre").ifEmpty { null },
            )
        }
    }

    private const val FILE = "metadata.json"
    private const val REMOTE_DIR = ".bitchord-audio"

    private val edits = ConcurrentHashMap<String, Edit>()
    private val _version = MutableStateFlow(0)

    /** Bumped on every change, for screens to draw again. */
    val version: StateFlow<Int> = _version.asStateFlow()

    @Volatile
    private var local: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        if (local != null) return
        local = File(context.filesDir, FILE)
        ArtworkOverrides.edited = { song -> if (edits.isEmpty()) null else applied(song) }
        scope.launch {
            runCatching { local?.takeIf { it.isFile }?.readText() }.getOrNull()?.let { read(it, replace = true) }
            remoteUrl()?.let { url ->
                WebDavClient.readText(url, AppSettings.webdavUsername.value, AppSettings.webdavPassword.value, 4 * 1024 * 1024)
                    .getOrNull()?.let { read(it, replace = false) }
            }
            _version.value++
            com.music.bitchord.ui.components.ShownSongs.bump()
        }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The original title and artist, exactly (a live or remixed version is its own song). */
    private fun keyOf(title: String, artist: String): String? {
        val t = norm(title)
        if (t.isEmpty()) return null
        val a = norm(artist.split(Regex("""\s*(?:,|&|/|;|\bfeat\.?|\bft\.?|、)\s*""", RegexOption.IGNORE_CASE)).firstOrNull().orEmpty())
        return "k:$t|$a"
    }

    fun get(song: Song): Edit? =
        edits["id:" + song.videoId] ?: keyOf(song.title, song.artist)?.let { edits[it] }

    /** [song] as it should be shown, or null when nothing was changed for it. */
    fun applied(song: Song): Song? {
        val e = get(song) ?: return null
        return song.copy(
            title = e.title ?: song.title,
            artist = e.artist ?: song.artist,
            albumName = e.album ?: song.albumName,
            trackNumber = e.track?.substringBefore('/')?.trim()?.toIntOrNull() ?: song.trackNumber,
        )
    }

    /**
     * Saves [edit] for each of [songs] (each song's own fields kept where the
     * edit says nothing — several songs share an album, not a title).
     */
    fun set(songs: List<Song>, edit: Edit, replace: Boolean = false) {
        songs.forEach { s ->
            val merged = if (replace) edit else (get(s) ?: Edit()).with(edit)
            val keys = listOfNotNull("id:" + s.videoId, keyOf(s.title, s.artist))
            keys.forEach { k -> if (merged.isEmpty) edits.remove(k) else edits[k] = merged }
            // A cover picked for the song is found by its name: under the new
            // name too.
            val newTitle = merged.title
            val newArtist = merged.artist
            if (newTitle != null || newArtist != null) {
                com.music.bitchord.data.covers.CoverOverrides.get(s)?.let { url ->
                    com.music.bitchord.data.covers.CoverOverrides.set(
                        listOf(s.copy(title = newTitle ?: s.title, artist = newArtist ?: s.artist)),
                        url,
                    )
                }
            }
        }
        changed()
    }

    /** Back to the source's own details. */
    fun clear(songs: List<Song>) {
        songs.forEach { s ->
            edits.remove("id:" + s.videoId)
            keyOf(s.title, s.artist)?.let { edits.remove(it) }
        }
        changed()
    }

    private fun changed() {
        _version.value++
        com.music.bitchord.ui.components.ShownSongs.bump()
        val text = JSONObject().apply { edits.forEach { (k, v) -> put(k, v.toJson()) } }.toString()
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
        o.keys().forEach { k ->
            val e = Edit.from(o.getJSONObject(k))
            if (replace) edits[k] = e else edits.putIfAbsent(k, e)
        }
    }

    private fun remoteDir(): String? {
        val base = AppSettings.webdavUrl.value
        if (!WebDavConfig.isConfigured(base)) return null
        return WebDavConfig.normalizeUrl(base).trimEnd('/') + "/$REMOTE_DIR/"
    }

    private fun remoteUrl(): String? = remoteDir()?.let { it + FILE }
}

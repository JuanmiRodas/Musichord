package com.music.bitchord.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.remote.FileName
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.data.webdav.LibraryFolders
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.data.webdav.WebDavRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BitChord Audio: playlists out to readable files, and back in.
 *
 * **Out.** One JSON file per playlist — your device playlists, your YouTube
 * Music playlists and Liked Music — in a "BitChord Playlists" folder in your
 * pCloud music folder ([exportToPcloud]), or any folder on the phone
 * ([exportToFolder]). Each track says what it is (title, artist, album,
 * length) and where it plays from (pCloud with its path, YouTube with its id,
 * a phone file), indented for reading and editing by hand or by a tool.
 *
 * **In.** The same JSON — edited or not — and also `.m3u`/`.m3u8` and `.csv`
 * playlists from other apps. Each file becomes a device playlist of the same
 * name, replacing one that is already there, so a cleaned file simply takes
 * the old one's place. Each track is found ([resolve]) by, in order: its
 * pCloud id, URL or path; its YouTube id; its title and artist among your
 * pCloud files; then a YouTube Music search. What can't be found is reported.
 */
object PlaylistExchange {

    const val FOLDER_NAME = "BitChord Playlists"
    private const val FORMAT = "bitchord-playlist"

    data class Track(
        val title: String,
        val artist: String,
        val album: String? = null,
        val duration: String? = null,
        val source: String? = null,
        val id: String? = null,
        val uri: String? = null,
        val path: String? = null,
    )

    data class Playlist(val title: String, val kind: String, val tracks: List<Track>)

    data class Report(val playlists: Int, val tracks: Int, val missing: List<String>) {
        operator fun plus(o: Report) = Report(playlists + o.playlists, tracks + o.tracks, missing + o.missing)
    }

    // ── Export ───────────────────────────────────────────────────────────

    // ── Jobs ─────────────────────────────────────────────────────────────
    //
    // An export walks every YouTube playlist page by page and can take minutes.
    // Run from the settings sheet's own scope it was cancelled the moment the
    // sheet closed or the folder picker recreated it — before a single file
    // was written. Jobs run here instead, for the app's lifetime, and each
    // playlist is written as soon as it has been read.

    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _progress = MutableStateFlow<String?>(null)
    /** "3 · Anime openings" while a job runs. */
    val progress: StateFlow<String?> = _progress.asStateFlow()
    private val _report = MutableStateFlow<String?>(null)
    /** The last job's outcome, until [dismissReport]. */
    val report: StateFlow<String?> = _report.asStateFlow()

    fun dismissReport() { _report.value = null }

    /** Runs [job] unless one is running; [onDone] gets its outcome on the main thread. */
    fun startJob(onDone: (String) -> Unit = {}, job: suspend () -> String) {
        if (!_busy.compareAndSet(false, true)) return
        jobScope.launch {
            val text = runCatching { job() }.getOrElse { it.message ?: it.javaClass.simpleName }
            _progress.value = null
            _report.value = text
            _busy.value = false
            withContext(Dispatchers.Main) { onDone(text) }
        }
    }

    /**
     * Every playlist worth exporting, with all its tracks, handed to [each]
     * one at a time as it is read. Returns how many there were.
     */
    suspend fun forEachPlaylist(profile: String?, each: suspend (String, List<Song>) -> Unit): Int = withContext(Dispatchers.IO) {
        var n = 0
        suspend fun emit(title: String, songs: List<Song>) {
            if (songs.isEmpty()) return
            n++
            _progress.value = "$n · $title"
            each(title, songs)
        }
        OfflinePlaylists.playlists.value.forEach { p -> emit(p.title, p.songs) }
        // Liked Music: the saved copy, else walked whole.
        val liked = LikedMusicCache.load(profile ?: LikedMusicCache.lastProfile)?.songs
            ?: allSongs(YtMusicRepository.LIKED_MUSIC)
        emit("Liked Music", liked)
        YtMusicRepository.userPlaylists().getOrNull().orEmpty().forEach { p ->
            emit(p.title, allSongs(p.browseId))
        }
        n
    }

    private suspend fun allSongs(browseId: String): List<Song> {
        val first = YtMusicRepository.browseSongs(browseId).getOrNull() ?: return emptyList()
        val songs = first.songs.toMutableList()
        var next = first.continuation
        val seen = HashSet<String>()
        while (next != null && seen.add(next)) {
            val page = YtMusicRepository.moreSongs(next).getOrNull() ?: break
            if (page.songs.isEmpty()) break
            songs += page.songs
            next = page.continuation
        }
        return songs.distinctBy { it.videoId }
    }

    fun encode(title: String, songs: List<Song>): String {
        val tracks = JSONArray()
        songs.forEach { s -> tracks.put(trackJson(s)) }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("title", title)
            .put("exported", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(Date()))
            .put("count", songs.size)
            .put("tracks", tracks)
            .toString(2)
    }

    private fun trackJson(s: Song) = JSONObject().apply {
        put("title", s.title)
        put("artist", s.artist)
        s.albumName?.let { put("album", it) }
        s.durationText?.let { put("duration", it) }
        when {
            WebDavConfig.isWebDavId(s.videoId) -> {
                put("source", "pcloud")
                WebDavConfig.fileUrlOf(s.videoId)?.let { put("path", pathOf(it)) }
                s.sourceQuality?.let { put("quality", it) }
                put("id", s.videoId)
            }
            s.localUri != null -> {
                put("source", "device")
                put("uri", s.localUri)
                s.localPath?.let { put("path", it) }
            }
            else -> {
                put("source", "youtube")
                put("id", s.videoId)
                put("url", "https://music.youtube.com/watch?v=${s.videoId}")
            }
        }
    }

    /** "Música/Kalafina/01. Magia - Kalafina - Magia - 2011.mp3" from its pCloud URL. */
    private fun pathOf(url: String): String = runCatching {
        URLDecoder.decode(URI(url).rawPath.orEmpty().trim('/'), "UTF-8")
    }.getOrDefault(url)

    fun fileNameFor(title: String): String =
        title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "Playlist" } + ".json"

    /** The pCloud folder playlists go to: "BitChord Playlists" in the first music folder. */
    private fun pcloudDir(): String? {
        val base = LibraryFolders.musicFolders().firstOrNull()?.url ?: AppSettings.webdavUrl.value
        if (!WebDavConfig.isConfigured(base)) return null
        return WebDavConfig.normalizeUrl(base).trimEnd('/') + "/" + FOLDER_NAME.replace(" ", "%20") + "/"
    }

    suspend fun exportToPcloud(profile: String?): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = pcloudDir() ?: error("Connect pCloud under Sources first")
            val user = AppSettings.webdavUsername.value
            val pass = AppSettings.webdavPassword.value
            WebDavClient.ensureCollection(dir, user, pass).getOrThrow()
            forEachPlaylist(profile) { title, songs ->
                val bytes = encode(title, songs).toByteArray(Charsets.UTF_8)
                val name = java.net.URLEncoder.encode(fileNameFor(title), "UTF-8").replace("+", "%20")
                WebDavClient.putFile(dir + name, bytes.inputStream(), bytes.size.toLong(), "application/json", user, pass, overwrite = true)
                    .getOrThrow()
            }
        }
    }

    /** Writes every playlist into the folder [treeUri] picked on the phone. */
    suspend fun exportToFolder(context: Context, treeUri: Uri, profile: String?): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            // Kept past this call, so the write still has the folder if the
            // picker's grant would otherwise lapse with the screen that asked.
            runCatching {
                resolver.takePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val parentId = DocumentsContract.getTreeDocumentId(treeUri)
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
            val existing = childrenByName(context, treeUri, parentId)
            val written = forEachPlaylist(profile) { title, songs ->
                val name = fileNameFor(title)
                // Overwritten in place on a second export, not "Name (1).json".
                val doc = existing[name]
                    ?: DocumentsContract.createDocument(resolver, parent, "application/json", name)
                    ?: error("Couldn't create $name")
                val out = resolver.openOutputStream(doc, "wt") ?: error("Couldn't write $name")
                out.use { it.write(encode(title, songs).toByteArray(Charsets.UTF_8)) }
            }
            if (written == 0) error("No playlists found to export — sign in to YouTube Music or make a playlist first")
            written
        }
    }

    private fun childrenByName(context: Context, treeUri: Uri, parentId: String): Map<String, Uri> = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val out = HashMap<String, Uri>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                out[c.getString(1)] = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
            }
        }
        out
    }.getOrDefault(emptyMap())

    // ── Import ───────────────────────────────────────────────────────────

    /** A playlist file, whichever of JSON, M3U/M3U8 or CSV it is. */
    fun decode(text: String, fileName: String): Playlist? {
        val body = text.removePrefix("﻿")
        val base = fileName.substringAfterLast('/').substringBeforeLast('.')
        val trimmed = body.trimStart()
        return when {
            trimmed.startsWith("{") -> decodeJson(trimmed, base)
            fileName.endsWith(".csv", true) -> decodeCsv(body, base)
            else -> decodeM3u(body, base)
        }
    }

    private fun decodeJson(text: String, fallbackTitle: String): Playlist? = runCatching {
        val o = JSONObject(text)
        val arr = o.optJSONArray("tracks") ?: return null
        val tracks = (0 until arr.length()).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            fun str(k: String) = t.optString(k).takeIf { it.isNotBlank() && t.has(k) && !t.isNull(k) }
            Track(
                title = str("title") ?: return@mapNotNull null,
                artist = str("artist").orEmpty(),
                album = str("album"),
                duration = str("duration"),
                source = str("source"),
                id = str("id"),
                uri = str("uri"),
                path = str("path"),
            )
        }
        Playlist(o.optString("title").ifBlank { fallbackTitle }, "json", tracks)
    }.getOrNull()

    private fun decodeM3u(text: String, title: String): Playlist? {
        val tracks = ArrayList<Track>()
        var info: Pair<String, String>? = null
        var duration: String? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val meta = line.substringAfter(':')
                    val secs = meta.substringBefore(',').trim().toIntOrNull()
                    duration = secs?.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60, it % 60) }
                    val name = meta.substringAfter(',', "")
                    info = if (" - " in name) name.substringAfter(" - ").trim() to name.substringBefore(" - ").trim() else name.trim() to ""
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val fromName = FileName.parse(line.substringAfterLast('/').substringAfterLast('\\'), null, null)
                    val (t, a) = info ?: (fromName.title to fromName.artist.orEmpty())
                    tracks += Track(title = t, artist = a, duration = duration, path = line, uri = line.takeIf { "://" in it })
                    info = null
                    duration = null
                }
            }
        }
        return Playlist(title, "m3u", tracks).takeIf { tracks.isNotEmpty() }
    }

    private fun decodeCsv(text: String, title: String): Playlist? {
        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        fun split(line: String): List<String> {
            val out = ArrayList<String>()
            val cell = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    !quoted && (c == ',' || c == ';') -> { out += cell.toString(); cell.clear() }
                    else -> cell.append(c)
                }
                i++
            }
            out += cell.toString()
            return out.map { it.trim() }
        }
        val header = split(lines[0]).map { it.lowercase(Locale.ROOT) }
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } }
        val ti = col("title", "track", "name", "track name") ?: return null
        val ar = col("artist", "artists", "artist name")
        val al = col("album", "album name")
        val id = col("id", "videoid", "video id")
        val tracks = lines.drop(1).mapNotNull { l ->
            val c = split(l)
            val t = c.getOrNull(ti)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Track(t, ar?.let(c::getOrNull).orEmpty(), al?.let(c::getOrNull), id = id?.let(c::getOrNull)?.ifBlank { null })
        }
        return Playlist(title, "csv", tracks).takeIf { tracks.isNotEmpty() }
    }

    private val YOUTUBE_ID = Regex("""^[A-Za-z0-9_-]{11}$""")

    /** The songs for [tracks], and the tracks nothing was found for. */
    suspend fun resolve(tracks: List<Track>): Pair<List<Song>, List<Track>> = withContext(Dispatchers.IO) {
        val library = WebDavRepository.library()
        val byId = library.associateBy { it.videoId }
        val byUrl = library.mapNotNull { s -> s.localUri?.let { it to s } }.toMap()
        val byPath = library.mapNotNull { s -> s.localUri?.let { pathOf(it).lowercase(Locale.ROOT) to s } }.toMap()
        val byName = HashMap<String, Song>()
        library.forEach { s -> byName.putIfAbsent(nameKey(s.title, s.artist), s) }
        val found = ArrayList<Song>()
        val missing = ArrayList<Track>()
        for (t in tracks) {
            val song = t.id?.let(byId::get)
                ?: t.uri?.let(byUrl::get)
                ?: t.path?.lowercase(Locale.ROOT)?.trim('/')?.let { p -> byPath[p] ?: byPath.entries.firstOrNull { it.key.endsWith(p) }?.value }
                ?: t.id?.takeIf { YOUTUBE_ID.matches(it) }?.let { yt ->
                    Song(videoId = yt, title = t.title, artist = t.artist, thumbnailUrl = null, durationText = t.duration, albumName = t.album)
                }
                ?: t.uri?.takeIf { it.startsWith("content://") || it.startsWith("file://") }?.let { u ->
                    Song(videoId = t.id ?: u, title = t.title, artist = t.artist, thumbnailUrl = null, localUri = u, albumName = t.album)
                }
                ?: byName[nameKey(t.title, t.artist)]
                ?: searchYouTube(t)
            if (song != null) found += song else missing += t
        }
        found to missing
    }

    private fun nameKey(title: String, artist: String) =
        FileName.key(title) + "|" + FileName.key(artist.split(',', '&').firstOrNull().orEmpty())

    private suspend fun searchYouTube(t: Track): Song? {
        val target = TrackMatcher.Target(
            title = t.title,
            artist = t.artist,
            durationSec = t.duration?.let { Song(videoId = "", title = "", artist = "", thumbnailUrl = null, durationText = it).durationMillis() }
                ?.takeIf { it > 0 }?.let { (it / 1000).toInt() },
            album = t.album,
        )
        val candidates = withTimeoutOrNull(10_000L) {
            YtMusicRepository.search("${t.title} ${t.artist}".trim(), SearchFilter.SONGS).getOrNull()
        }.orEmpty().filterIsInstance<SearchResult.Track>().map { it.song }
        return TrackMatcher.best(candidates, target)
    }

    /** Imports one file's text as a device playlist. */
    suspend fun importText(text: String, fileName: String): Report {
        val list = decode(text, fileName) ?: return Report(0, 0, listOf("$fileName: not a playlist this app can read"))
        val (songs, missing) = resolve(list.tracks)
        if (songs.isNotEmpty()) OfflinePlaylists.replaceOrCreate(list.title, songs)
        return Report(if (songs.isNotEmpty()) 1 else 0, songs.size, missing.map { "${list.title}: ${it.title} — ${it.artist}" })
    }

    /** Imports every playlist file in the pCloud "BitChord Playlists" folder. */
    suspend fun importFromPcloud(): Result<Report> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = pcloudDir() ?: error("Connect pCloud under Sources first")
            val user = AppSettings.webdavUsername.value
            val pass = AppSettings.webdavPassword.value
            val files = WebDavClient.listFiles(dir, user, pass).getOrThrow()
                .filter { e -> PLAYLIST_EXT.any { e.displayName.endsWith(it, ignoreCase = true) } }
            files.fold(Report(0, 0, emptyList())) { acc, e ->
                val text = WebDavClient.readText(e.url, user, pass, 8 * 1024 * 1024).getOrNull()
                    ?: return@fold acc + Report(0, 0, listOf("${e.displayName}: couldn't be read"))
                acc + importText(text, e.displayName)
            }
        }
    }

    /** Imports playlist files picked on the phone. */
    suspend fun importUris(context: Context, uris: List<Uri>): Report = withContext(Dispatchers.IO) {
        uris.fold(Report(0, 0, emptyList())) { acc, uri ->
            val name = displayName(context, uri) ?: uri.lastPathSegment.orEmpty()
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { com.music.bitchord.data.lyrics.SidecarLyrics.decode(it.readBytes()) }
            }.getOrNull() ?: return@fold acc + Report(0, 0, listOf("$name: couldn't be read"))
            acc + importText(text, name)
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private val PLAYLIST_EXT = listOf(".json", ".m3u", ".m3u8", ".csv")
}

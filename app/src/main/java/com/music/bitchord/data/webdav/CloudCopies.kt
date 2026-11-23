package com.music.bitchord.data.webdav

import android.content.Context
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.OwnFileMatcher
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BitChord Audio: an album (or a playlist) copied to your pCloud — each song downloaded to
 * the phone first when it isn't, then uploaded into "Artist/Album" (a playlist: into
 * "Playlists/Name"). Songs already in your pCloud library are left alone. Runs in the
 * background with the upload notification; started from the album's cloud button, its
 * menu, or by saving the album to your library.
 */
object CloudCopies {

    private const val TAG = "BitChord"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── BitChord Audio: Wi-Fi only (the default) — asked on mobile data, a copy waits for Wi-Fi ──
    private var prefs: android.content.SharedPreferences? = null
    private var dir: java.io.File? = null
    private var app: Context? = null
    private const val SEP = "\u0001"
    val wifiOnly = kotlinx.coroutines.flow.MutableStateFlow(true)

    fun init(context: Context) {
        app = context.applicationContext
        prefs = context.getSharedPreferences("bitchord_cloud_copies", Context.MODE_PRIVATE)
        dir = java.io.File(context.filesDir, "cloud-copies").apply { mkdirs() }
        wifiOnly.value = prefs?.getBoolean("wifi_only", true) ?: true
        // Waiting copies go as soon as the phone is on Wi-Fi (or the rule is turned off).
        scope.launch {
            kotlinx.coroutines.flow.combine(com.music.bitchord.data.settings.AppSettings.meteredConnection, wifiOnly) { m, w -> m == false || (!w && m != null) }
                .collect { allowed -> if (allowed) runWaiting() }
        }
    }

    fun setWifiOnly(on: Boolean) {
        wifiOnly.value = on
        prefs?.edit()?.putBoolean("wifi_only", on)?.apply()
    }

    private fun allowedNow(): Boolean {
        val metered = com.music.bitchord.data.settings.AppSettings.meteredConnection.value
        return metered == false || (!wifiOnly.value && metered != null)
    }

    private fun keepForLater(songs: List<Song>, artist: String, title: String, playlist: Boolean) {
        val d = dir ?: return
        val name = "job-" + System.currentTimeMillis()
        runCatching { com.music.bitchord.data.SongListStore.save(java.io.File(d, "$name.json"), songs) }
        prefs?.edit()?.putString(name, listOf(artist, title, playlist.toString()).joinToString(SEP))?.apply()
        TrackLog.d(TAG, "cloud copy: '$title' kept for Wi-Fi")
    }

    private fun runWaiting() {
        val p = prefs ?: return
        val d = dir ?: return
        val ctx = app ?: return
        p.all.keys.filter { it.startsWith("job-") }.forEach { name ->
            val meta = p.getString(name, null)?.split(SEP) ?: return@forEach
            val f = java.io.File(d, "$name.json")
            val songs = com.music.bitchord.data.SongListStore.load(f)?.songs.orEmpty()
            p.edit().remove(name).apply()
            f.delete()
            if (songs.isNotEmpty() && meta.size >= 3) {
                TrackLog.d(TAG, "cloud copy: '${meta[1]}' — on Wi-Fi now, starting")
                start(ctx, songs, meta[0], meta[1], meta[2].toBoolean())
            }
        }
    }

    private fun seconds(s: Song): Int? = s.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
        ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }

    /** The folder of [artist] in your pCloud library, from a file of theirs already there. */
    private fun artistDir(artist: String): String? {
        val file = WebDavRepository.library().firstOrNull { OwnFileMatcher.sameArtist(artist, it.artist) } ?: return null
        val url = WebDavConfig.fileUrlOf(file.videoId) ?: return null
        val albumDir = url.substringBeforeLast('/')
        val folderName = WebDavRepository.artistFolderOf(file.videoId)
        // The artist folder is the album folder's parent (Artist/Album/file), or the file's own.
        val parent = albumDir.substringBeforeLast('/')
        return if (folderName != null && java.net.URLDecoder.decode(parent.substringAfterLast('/'), "UTF-8") == folderName) parent else albumDir
    }

    fun copy(context: Context, songs: List<Song>, artist: String, title: String, playlist: Boolean) {
        val app = context.applicationContext
        if (!WebDavRepository.isConfigured()) {
            android.widget.Toast.makeText(app, app.getString(com.music.bitchord.R.string.cloud_copy_no_pcloud), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (!allowedNow()) {
            keepForLater(songs, artist, title, playlist)
            android.widget.Toast.makeText(app, app.getString(com.music.bitchord.R.string.cloud_copy_waiting, title), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        android.widget.Toast.makeText(app, app.getString(com.music.bitchord.R.string.cloud_copy_started, title), android.widget.Toast.LENGTH_SHORT).show()
        start(app, songs, artist, title, playlist)
    }

    private fun start(app: Context, songs: List<Song>, artist: String, title: String, playlist: Boolean) {
        scope.launch { send(app, songs, artist, title, playlist) }
    }

    /**
     * The copy itself: tagged, into the artist's folder, and the songs that went (or were
     * already there) returned. [auto]: the downloads' sync — no track numbers (the songs
     * aren't a whole album in order), and a name already taken on pCloud is left as it is.
     */
    internal suspend fun send(app: Context, songs: List<Song>, artist: String, title: String, playlist: Boolean, auto: Boolean = false): Set<String> {
        return run {
            val library = WebDavRepository.library()
            val todo = songs.filter { s ->
                !WebDavConfig.isWebDavId(s.videoId) &&
                    // Already yours on pCloud: the same recording, by the same artist.
                    OwnFileMatcher.matches(s.title, s.artist, seconds(s), s.albumName).isEmpty() &&
                    library.none { f -> f.videoId == s.videoId }
            }
            TrackLog.d(TAG, "cloud copy: '$title' — ${todo.size} of ${songs.size} songs to send (the rest are in your pCloud)")
            val already = songs.map { it.videoId }.toSet() - todo.map { it.videoId }.toSet()
            if (todo.isEmpty()) return already
            // On the phone first (the backup sends only what is on it already).
            if (!auto) todo.filter { Downloads.localCopyOf(it.videoId) == null }.let { missing ->
                if (missing.isNotEmpty()) Downloads.enqueueAll(app, missing, title)
            }
            val readyAll = if (auto) todo.filter { it.localUri != null } else todo.mapNotNull { s ->
                val uri = Downloads.localCopyOf(s.videoId) ?: withTimeoutOrNull(10 * 60_000L) {
                    Downloads.saved.first { s.videoId in it }
                    Downloads.localCopyOf(s.videoId)
                }
                uri?.let { s.copy(localUri = it) }
            }
            // BitChord Audio: every file goes up as it is — m4a, opus and webm too (the Mac's clean
            // library takes them from pCloud and decides there).
            val refused = emptyList<Song>()
            val ready = readyAll - refused.toSet()
            if (ready.isEmpty()) return already
            // Tagged first — title, artist, album, track number and the cover — so the files are
            // whole wherever they go (a JioSaavn copy is saved without tags).
            ready.forEachIndexed { i, s ->
                // Only the app's own downloads are tagged: your other music files stay untouched.
                if (auto && Downloads.localCopyOf(s.videoId) == null) return@forEachIndexed
                runCatching {
                    val cover = (com.music.bitchord.data.covers.OfflineCovers.localFor(s.thumbnailUrl ?: "")?.let { android.net.Uri.fromFile(it).toString() }
                        ?: Downloads.savedArtwork(s.videoId)
                        ?: s.thumbnailUrl)
                        ?.let { url -> com.music.bitchord.data.covers.CoverWriter.fetch(url.replace(Regex("=w\\d+-h\\d+.*$"), "=w1200-h1200")) }
                    com.music.bitchord.data.edits.TagWriter.intoDownload(
                        app,
                        s.localUri!!,
                        com.music.bitchord.data.edits.TagWriter.Change(
                            text = com.music.bitchord.data.covers.CoverTags.Text(
                                title = s.title,
                                artist = s.artist,
                                album = if (playlist) s.albumName else (s.albumName ?: title),
                                albumArtist = if (playlist) null else artist,
                                track = if (playlist || auto) null else (i + 1).toString(),
                            ),
                            cover = cover,
                        ),
                    )
                }.onFailure { TrackLog.w(TAG, "cloud copy: tags for '${s.title}' failed: ${it.message}") }
            }
            // Into your music folder on pCloud: the artist's own folder when you have one there,
            // else a new one in your first music folder.
            val base = artistDir(artist)
            var folder = when {
                playlist -> listOf("Playlists", title)
                base != null -> listOf(title)
                else -> listOf(artist.ifBlank { "Unknown" }, title)
            }
            // The backup: "Music/Artist/Album" at the top of your pCloud.
            val music = if (auto) com.music.bitchord.data.video.PcloudVideos.serverRoot() else null
            val root = music ?: base ?: LibraryFolders.musicFolders().firstOrNull()?.url
            if (music != null) folder = listOf("Music", artist.ifBlank { "Unknown" }, title)
            TrackLog.d(TAG, "cloud copy: '$title' into ${root ?: "the top of pCloud"} / ${folder.joinToString("/")}")
            val summary = runCatching {
                WebDavUploads.upload(app, ready, folder, root?.let(WebDavConfig::normalizeUrl), unattended = if (auto) WebDavUploads.Choice.SKIP else null)
            }
                .onFailure { TrackLog.w(TAG, "cloud copy: '$title' failed: ${it.message}") }
                .getOrNull() ?: return already
            TrackLog.d(TAG, "cloud copy: '$title' — ${summary.uploaded} uploaded, ${summary.skipped} skipped, ${summary.failed} failed")
            if (!auto) runCatching { WebDavRepository.getSongs() }
            val states = WebDavUploads.active.value
            return already + ready.filter { s ->
                states[s.videoId].let { it is WebDavUploads.TrackState.Done || it is WebDavUploads.TrackState.Skipped }
            }.map { it.videoId }
        }
    }
}

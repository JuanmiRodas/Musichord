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

    private fun seconds(s: Song): Int? = s.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
        ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }

    fun copy(context: Context, songs: List<Song>, artist: String, title: String, playlist: Boolean) {
        val app = context.applicationContext
        if (!WebDavRepository.isConfigured()) {
            android.widget.Toast.makeText(app, app.getString(com.music.bitchord.R.string.cloud_copy_no_pcloud), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        android.widget.Toast.makeText(app, app.getString(com.music.bitchord.R.string.cloud_copy_started, title), android.widget.Toast.LENGTH_SHORT).show()
        scope.launch {
            val library = WebDavRepository.library()
            val todo = songs.filter { s ->
                !WebDavConfig.isWebDavId(s.videoId) &&
                    // Already yours on pCloud: the same recording, by the same artist.
                    OwnFileMatcher.matches(s.title, s.artist, seconds(s), s.albumName).isEmpty() &&
                    library.none { f -> f.videoId == s.videoId }
            }
            TrackLog.d(TAG, "cloud copy: '$title' — ${todo.size} of ${songs.size} songs to send (the rest are in your pCloud)")
            if (todo.isEmpty()) return@launch
            // On the phone first.
            todo.filter { Downloads.localCopyOf(it.videoId) == null }.let { missing ->
                if (missing.isNotEmpty()) Downloads.enqueueAll(app, missing, title)
            }
            val ready = todo.mapNotNull { s ->
                val uri = Downloads.localCopyOf(s.videoId) ?: withTimeoutOrNull(10 * 60_000L) {
                    Downloads.saved.first { s.videoId in it }
                    Downloads.localCopyOf(s.videoId)
                }
                uri?.let { s.copy(localUri = it) }
            }
            val folder = if (playlist) listOf("Playlists", title) else listOf(artist.ifBlank { "Unknown" }, title)
            val summary = runCatching { WebDavUploads.upload(app, ready, folder) }
                .onFailure { TrackLog.w(TAG, "cloud copy: '$title' failed: ${it.message}") }
                .getOrNull() ?: return@launch
            TrackLog.d(TAG, "cloud copy: '$title' — ${summary.uploaded} uploaded, ${summary.skipped} skipped, ${summary.failed} failed")
            runCatching { WebDavRepository.getSongs() }
        }
    }
}

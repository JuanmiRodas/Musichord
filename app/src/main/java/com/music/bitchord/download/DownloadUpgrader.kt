package com.music.bitchord.download

import android.content.Context
import android.content.SharedPreferences
import androidx.core.net.toUri
import com.music.bitchord.data.LocalLikes
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BitChord Audio: downloads replaced, in the background, by a better copy —
 * your pCloud file, JioSaavn's 320 kbps — when one turns up.
 *
 * Only on an unmetered connection, one song at a time, a few seconds apart.
 * A download is only replaced when the copy found is lossless or at least
 * [MIN_GAIN_KBPS] better than what is saved; the old file is removed once the
 * new one is saved. Each song is looked at once a month at most.
 */
object DownloadUpgrader {

    private const val TAG = "BitChord"
    private const val PREFS = "bitchord_download_upgrades"
    private const val RECHECK_MS = 30L * 24 * 60 * 60 * 1000
    private const val MIN_GAIN_KBPS = 64
    private const val PAUSE_MS = 4_000L
    private const val SAVE_TIMEOUT_MS = 10L * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var prefs: SharedPreferences? = null

    private val KBPS = Regex("""(\d{2,4})\s*kbps""", RegexOption.IGNORE_CASE)

    fun start(context: Context) {
        val app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        scope.launch {
            // Each time the phone gets on Wi-Fi.
            AppSettings.meteredConnection.collect { metered ->
                if (metered == false && AppSettings.upgradeDownloads.value && job?.isActive != true) {
                    job = scope.launch { pass(app) }
                }
            }
        }
    }

    private fun savedKbps(badge: String?): Int? {
        val b = badge.orEmpty()
        if (b.contains("FLAC", true) || b.contains("DOLBY", true)) return Int.MAX_VALUE
        return KBPS.find(b)?.groupValues?.get(1)?.toIntOrNull()
    }

    private suspend fun pass(context: Context) {
        delay(60_000) // after start-up has settled
        val now = System.currentTimeMillis()
        val songs = runCatching { Downloads.getDownloadedSongs(context) }.getOrDefault(emptyList())
            .filter { LocalLikes.isYouTubeId(it.videoId) }
            .filter { (savedKbps(it.downloadFormat) ?: 0) < 256 }
            .filter { now - (prefs?.getLong(it.videoId, 0L) ?: 0L) > RECHECK_MS }
        if (songs.isEmpty()) return
        TrackLog.d(TAG, "download upgrade: checking ${songs.size} downloads for a better copy")
        for (song in songs) {
            if (!scope.isActive || AppSettings.meteredConnection.value != false || !AppSettings.upgradeDownloads.value) return
            prefs?.edit()?.putLong(song.videoId, now)?.apply()
            val seconds = song.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
                ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }
            val target = TrackMatcher.Target(title = song.title, artist = song.artist, album = song.albumName, durationSec = seconds)
            val found = runCatching { SourceResolver.forDownload(target, videoId = song.videoId) }.getOrNull() ?: continue
            val had = savedKbps(song.downloadFormat) ?: 0
            val better = found.format.isLossless == true || (found.format.kbps ?: 0) >= had + MIN_GAIN_KBPS
            if (!better) continue
            val oldUri = song.localUri ?: continue
            TrackLog.d(TAG, "download upgrade: '${song.title}' ${song.downloadFormat ?: "?"} → ${found.format.summary}")
            Downloads.enqueue(context, song.copy(localUri = null, downloadFormat = null))
            // The new file is saved under the same id; the old one goes once it is.
            val newUri = withTimeoutOrNull(SAVE_TIMEOUT_MS) {
                Downloads.saved.first { (it[song.videoId] ?: oldUri) != oldUri }[song.videoId]
            }
            if (newUri != null && newUri != oldUri) {
                runCatching { DownloadStore.delete(context, oldUri.toUri()) }
            }
            delay(PAUSE_MS)
        }
    }
}

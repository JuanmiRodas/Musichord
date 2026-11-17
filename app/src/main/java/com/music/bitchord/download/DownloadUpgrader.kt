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
import kotlinx.coroutines.withContext
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
            if (song.videoId in upgradingNow) continue
            prefs?.edit()?.putLong(song.videoId, now)?.apply()
            if (upgrade(context, song)) delay(PAUSE_MS)
        }
    }

    // ---- BitChord Audio: the download playing now --------------------------------------

    private val upgradingNow: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Old files of upgraded downloads, kept until their song stops playing. */
    private val retired = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Set by the playback service: what is playing, and a cross-fade to the saved file. */
    @Volatile var playing: String? = null
    @Volatile var swapToSaved: ((videoId: String) -> Unit)? = null

    /**
     * A download that starts playing at less than 256 kbps: it plays as it is,
     * at once, while a better copy is looked for and downloaded beside it.
     * Once saved, the song cross-fades to the new file where it is; the old
     * file is deleted when the song is over, never under it.
     */
    fun onPlaying(context: Context, videoId: String) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!AppSettings.upgradeDownloads.value || AppSettings.meteredConnection.value != false) return
        if (!LocalLikes.isYouTubeId(videoId)) return
        val (uri, format) = Downloads.savedEntry(videoId) ?: return
        if ((savedKbps(format) ?: 0) >= 256) return
        val now = System.currentTimeMillis()
        if (now - (prefs?.getLong(videoId, 0L) ?: 0L) <= RECHECK_MS) return
        if (!upgradingNow.add(videoId)) return
        val app = context.applicationContext
        scope.launch {
            try {
                prefs?.edit()?.putLong(videoId, now)?.apply()
                val song = Downloads.getDownloadedSongs(app).firstOrNull { it.videoId == videoId && it.localUri == uri } ?: return@launch
                upgrade(app, song)
            } finally {
                upgradingNow -= videoId
            }
        }
    }

    /** The song moved on: old files of songs no longer playing go now. */
    fun onTrackChanged(context: Context) {
        val playing = playing
        for ((videoId, oldUri) in retired.entries.toList()) {
            if (videoId == playing) continue
            retired.remove(videoId)
            runCatching { DownloadStore.delete(context, oldUri.toUri()) }
            TrackLog.d(TAG, "download upgrade: removed the old file of $videoId")
        }
    }

    /** A better copy of [song] found, saved, and put in place. True when one was. */
    private suspend fun upgrade(context: Context, song: com.music.bitchord.data.model.Song): Boolean {
        val seconds = song.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
            ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }
        val target = TrackMatcher.Target(title = song.title, artist = song.artist, album = song.albumName, durationSec = seconds)
        val found = runCatching { SourceResolver.forDownload(target, videoId = song.videoId) }.getOrNull() ?: return false
        val had = savedKbps(song.downloadFormat) ?: 0
        val better = found.format.isLossless == true || (found.format.kbps ?: 0) >= had + MIN_GAIN_KBPS
        if (!better) return false
        val oldUri = song.localUri ?: return false
        TrackLog.d(TAG, "download upgrade: '${song.title}' ${song.downloadFormat ?: "?"} → ${found.format.summary}")
        Downloads.enqueue(context, song.copy(localUri = null, downloadFormat = null))
        // The new file is saved under the same id.
        val newUri = withTimeoutOrNull(SAVE_TIMEOUT_MS) {
            Downloads.saved.first { (it[song.videoId] ?: oldUri) != oldUri }[song.videoId]
        }
        if (newUri == null || newUri == oldUri) return false
        if (playing == song.videoId) {
            // Under the music: cross-faded to the new file; the old one goes when the song does.
            retired[song.videoId] = oldUri
            withContext(Dispatchers.Main) { swapToSaved?.invoke(song.videoId) }
            TrackLog.d(TAG, "download upgrade: '${song.title}' now plays from its new file")
        } else {
            runCatching { DownloadStore.delete(context, oldUri.toUri()) }
        }
        return true
    }
}

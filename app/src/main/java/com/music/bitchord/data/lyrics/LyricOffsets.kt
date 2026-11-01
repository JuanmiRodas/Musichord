package com.music.bitchord.data.lyrics

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceMap
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: lyrics moved earlier or later for one song, by hand.
 *
 * The shift is kept per song on the device (and so in pCloud, with the rest
 * of the settings that sync) and — for a song that is one of your files —
 * written into the .lrc beside it as the standard `[offset:]` tag, so other
 * players (LyricsX) show it right too. A song with no .lrc yet gets one,
 * made from the lyrics on screen.
 *
 * A file's own `[offset:]` is where a song starts; the lyrics are read from
 * files unshifted and moved once, on screen, by the value here. `[offset:]`
 * counts the other way round (positive = earlier), hence the sign flips.
 */
object LyricOffsets {

    private const val TAG = "BitChord"
    private const val PREFS = "bitchord_lyric_offsets"
    private const val WRITE_DELAY_MS = 2_500L

    private var prefs: SharedPreferences? = null
    private val flows = ConcurrentHashMap<String, MutableStateFlow<Long>>()
    private val pending = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** The shift for [trackId]: as set here, else its lyrics file's `[offset:]`. */
    fun flow(trackId: String): StateFlow<Long> {
        val initial = prefs?.takeIf { it.contains(trackId) }?.getLong(trackId, 0L)
            ?: -(SidecarLyrics.fileOffset(trackId) ?: 0L)
        return flows.getOrPut(trackId) { MutableStateFlow(initial) }.also {
            if (prefs?.contains(trackId) != true) it.value = initial
        }
    }

    /** Back to no shift: the lyrics were just timed by hand. */
    fun reset(trackId: String) {
        prefs?.edit()?.putLong(trackId, 0L)?.apply()
        flows[trackId]?.value = 0L
    }

    fun nudge(trackId: String, lines: List<LyricLine>, deltaMs: Long) {
        val flow = flows.getOrPut(trackId) { MutableStateFlow(0L) }
        val value = flow.value + deltaMs
        flow.value = value
        prefs?.edit()?.putLong(trackId, value)?.apply()
        pending.remove(trackId)?.cancel()
        pending[trackId] = scope.launch {
            delay(WRITE_DELAY_MS)
            runCatching { writeBeside(trackId, lines, value) }
                .onFailure { TrackLog.w(TAG, "lyrics offset: couldn't write the .lrc: ${it.message}") }
        }
    }

    /** The song's file of yours: the track itself, or the file the map plays for it. */
    private fun fileOf(trackId: String): String? = when {
        WebDavConfig.isWebDavId(trackId) -> trackId
        else -> SourceMap.lookup(trackId)?.file
    }

    private suspend fun writeBeside(trackId: String, lines: List<LyricLine>, shiftMs: Long) {
        val file = fileOf(trackId) ?: return
        val url = SidecarLyrics.besideUrl(file) ?: return
        val existing = SidecarLyrics.readBeside(file)
        val text = if (existing != null) {
            SidecarLyrics.withOffset(existing, -shiftMs)
        } else {
            val song = SourceMap.fileSong(file)
            SidecarLyrics.toLrc(lines.filter { it.text.isNotBlank() }, song?.title, song?.artist, -shiftMs)
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        WebDavClient.putFile(
            url,
            bytes.inputStream(),
            bytes.size.toLong(),
            "text/plain",
            AppSettings.webdavUsername.value,
            AppSettings.webdavPassword.value,
            overwrite = true,
        ).getOrThrow()
        SidecarLyrics.wroteBeside(file, url)
        TrackLog.d(TAG, "lyrics offset: ${-shiftMs} ms written to ${url.substringAfterLast('/')}")
    }
}

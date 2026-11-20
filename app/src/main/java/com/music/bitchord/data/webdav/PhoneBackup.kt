package com.music.bitchord.data.webdav

import android.content.Context
import android.os.PowerManager
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * BitChord Audio: a backup in your pCloud of all the music on your phone — the app's
 * downloads and your own music files — in "Music/Artist/Album" at the top of the drive.
 *
 * Runs by itself whenever the phone is on Wi-Fi (never on mobile data or in battery
 * saver), a minute after something new appears; a song already in your pCloud library
 * (the same recording) isn't sent twice, and nothing on the phone is moved or deleted.
 * On by default; switched off in Settings.
 */
object PhoneBackup {
    private const val TAG = "BitChord"
    private const val PREFS = "bitchord_phone_backup"
    private const val KEY_ON = "on"
    private const val KEY_SENT = "sent"

    data class State(val running: Boolean = false, val sent: Int = 0, val waiting: Int = 0, val current: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()
    private var app: Context? = null
    private var prefs: android.content.SharedPreferences? = null
    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on.asStateFlow()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Songs (by their local file) already in pCloud, sent or found there. */
    private fun sent(): Set<String> = prefs?.getStringSet(KEY_SENT, emptySet()).orEmpty()

    private fun markSent(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val all = sent() + keys
        prefs?.edit()?.putStringSet(KEY_SENT, all)?.apply()
        _state.value = _state.value.copy(sent = all.size)
    }

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        app = context.applicationContext
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _on.value = prefs?.getBoolean(KEY_ON, true) ?: true
        _state.value = State(sent = sent().size)
        scope.launch {
            combine(AppSettings.meteredConnection, _on, Downloads.saved) { metered, on, saved -> Triple(metered, on, saved.size) }
                .debounce(60_000L)
                .collect { (metered, on, _) -> if (on && metered == false) runNow() }
        }
    }

    fun setOn(value: Boolean) {
        _on.value = value
        prefs?.edit()?.putBoolean(KEY_ON, value)?.apply()
    }

    private fun key(s: Song): String = s.localUri ?: s.videoId

    /** Everything on the phone: the app's downloads and your own music files. */
    private suspend fun onPhone(context: Context): List<Song> {
        val downloads = Downloads.savedMetadataSongs().filter { it.localUri != null }
        val own = if (LocalMediaRepository.hasStoragePermission(context)) {
            runCatching { LocalMediaRepository.getLocalMusic(context) }.getOrDefault(emptyList())
        } else emptyList()
        return (downloads + own).filter { it.localUri != null }.distinctBy { key(it) }
    }

    private fun allowed(context: Context): Boolean {
        if (!_on.value || !WebDavRepository.isConfigured()) return false
        if (AppSettings.meteredConnection.value != false) return false
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isPowerSaveMode != true
    }

    /** Sends what isn't in pCloud yet, album by album, while Wi-Fi lasts. */
    fun runNow() {
        val ctx = app ?: return
        scope.launch {
            if (!running.tryLock()) return@launch
            var remaining = 0
            try {
                if (!allowed(ctx)) return@launch
                val done = sent()
                val todo = onPhone(ctx).filter { key(it) !in done }
                remaining = todo.size
                _state.value = State(running = true, sent = done.size, waiting = todo.size)
                if (todo.isEmpty()) return@launch
                TrackLog.d(TAG, "phone backup: ${todo.size} song(s) on the phone not in pCloud yet")
                // Album by album, into Music/Artist/Album.
                val groups = todo.groupBy { s ->
                    val artist = s.artist.split(',', '&').firstOrNull()?.trim().orEmpty().ifBlank { "Unknown" }
                    artist to (s.albumName?.takeIf { it.isNotBlank() } ?: "Singles")
                }
                var left = todo.size
                for ((group, songs) in groups) {
                    if (!allowed(ctx)) {
                        TrackLog.d(TAG, "phone backup: paused (off Wi-Fi, battery saver or switched off), $left left")
                        break
                    }
                    _state.value = _state.value.copy(current = "${group.first} — ${group.second}", waiting = left)
                    val ok = runCatching { CloudCopies.send(ctx, songs, group.first, group.second, playlist = false, auto = true) }
                        .onFailure { TrackLog.w(TAG, "phone backup: '${group.second}' failed: ${it.message}") }
                        .getOrDefault(emptySet())
                    markSent(songs.filter { it.videoId in ok }.map { key(it) })
                    left -= songs.size
                    remaining = left + songs.count { it.videoId !in ok }
                }
                TrackLog.d(TAG, "phone backup: done for now — ${sent().size} song(s) in pCloud")
            } finally {
                _state.value = _state.value.copy(running = false, current = null, waiting = remaining)
                running.unlock()
            }
        }
    }
}

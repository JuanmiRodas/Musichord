package com.music.bitchord.data.webdav

import com.music.bitchord.data.sources.OwnFileMatcher

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
 * downloads and your own music files — as a mirror: the same folders and file names,
 * under "Music" at the top of the drive (to be tidied on the computer later).
 *
 * Runs by itself whenever the phone is on Wi-Fi (never on mobile data or in battery
 * saver), a minute after something new appears; a file already sent (or already at its
 * place in pCloud) isn't sent again, and nothing on the phone is moved or deleted.
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
        if (prefs?.getBoolean(KEY_EVER, false) != true) prefs?.edit()?.putBoolean(KEY_EVER, true)?.apply()
        val all = sent() + keys
        prefs?.edit()?.putStringSet(KEY_SENT, all)?.apply()
        _state.value = _state.value.copy(sent = all.size)
    }

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        app = context.applicationContext
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _on.value = prefs?.getBoolean(KEY_ON, true) ?: true
        // Once: what was counted as backed up without going up (another format, a "match" in
        // the library) is checked again — files already in pCloud are only looked at, not resent.
        if (prefs?.getBoolean("mirror_all_v2", false) != true) {
            val had = sent().isNotEmpty()
            prefs?.edit()?.remove(KEY_SENT)?.putBoolean("mirror_all_v2", true)?.apply()
            if (had) prefs?.edit()?.putBoolean(KEY_EVER, true)?.apply()
        }
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

    private const val KEY_EVER = "ever_sent"

    /**
     * The mirror's top folder: "Music" at the top of pCloud; if it is gone after files were
     * sent, wherever the folder holding "BitChord" (the app's downloads) now is — else null.
     * Only the very first backup may make it.
     */
    private suspend fun mirrorRoot(top: String, user: String, pass: String): String? {
        val music = WebDavClient.joinUrl(top, "Music").trimEnd('/') + "/"
        if (WebDavClient.exists(music, user, pass).getOrDefault(false)) return music
        prefs?.getString("mirror_root", null)?.let { saved ->
            if (WebDavClient.exists(saved, user, pass).getOrDefault(false)) return saved
        }
        if (prefs?.getBoolean(KEY_EVER, false) != true) return music
        var level = listOf(top)
        repeat(4) {
            val next = ArrayList<String>()
            for (dir in level.take(120)) {
                for (e in WebDavClient.listFolders(dir, user, pass).getOrNull().orEmpty()) {
                    val url = e.url.trimEnd('/') + "/"
                    if (e.displayName.equals("Biblioteca", true)) continue
                    if (e.displayName.equals("BitChord", true)) {
                        val found = dir.trimEnd('/') + "/"
                        TrackLog.d(TAG, "phone backup: the mirror is now at ${android.net.Uri.decode(found)}")
                        prefs?.edit()?.putString("mirror_root", found)?.apply()
                        return found
                    }
                    next += url
                }
            }
            level = next
        }
        return null
    }

    /** Everything on the phone: the app's downloads and your own music files. */
    private suspend fun onPhone(context: Context): List<Song> {
        val downloads = Downloads.savedMetadataSongs().filter { it.localUri != null }
        val own = if (LocalMediaRepository.hasStoragePermission(context)) {
            runCatching { LocalMediaRepository.getLocalMusic(context) }.getOrDefault(emptyList())
        } else emptyList()
        return (downloads + own).filter { it.localUri != null }.distinctBy { key(it) }
    }

    private fun allowed(context: Context): Boolean {
        if (!WebDavRepository.isConfigured()) return false
        if (AppSettings.meteredConnection.value != false) return false
        // Asked for by hand: on Wi-Fi it goes, switch and battery saver aside.
        if (forced) return true
        if (!_on.value) return false
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isPowerSaveMode != true
    }

    @Volatile private var forced = false

    /**
     * BitChord Audio: "upload what's missing" — every file on the phone checked against pCloud
     * again (not only the ones never sent): what is there is only looked at, never sent twice;
     * what is missing goes up now, on Wi-Fi.
     */
    fun uploadMissing() {
        prefs?.edit()?.remove(KEY_SENT)?.apply()
        forced = true
        TrackLog.d(TAG, "phone backup: checking every file on the phone against pCloud (asked by hand)")
        runNow()
    }

    /** Where a file sits on the phone: its folders (from the top of the storage) and its name. */
    private data class Place(val folders: List<String>, val name: String, val size: Long, val mime: String)

    private fun placeOf(context: Context, uriText: String): Place? = runCatching {
        val uri = android.net.Uri.parse(uriText)
        when (uri.scheme) {
            "content" -> {
                val cols = buildList {
                    add(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                    add(android.provider.MediaStore.MediaColumns.SIZE)
                    add(android.provider.MediaStore.MediaColumns.MIME_TYPE)
                    if (android.os.Build.VERSION.SDK_INT >= 29) add(android.provider.MediaStore.MediaColumns.RELATIVE_PATH)
                    @Suppress("DEPRECATION") add(android.provider.MediaStore.MediaColumns.DATA)
                }.toTypedArray()
                context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return@use null
                    fun str(col: String) = c.getColumnIndex(col).takeIf { it >= 0 }?.let { c.getString(it) }
                    val name = str(android.provider.MediaStore.MediaColumns.DISPLAY_NAME) ?: return@use null
                    val relative = str("relative_path")
                        ?: str("_data")?.let { relativeOf(java.io.File(it).parent) }
                        ?: ""
                    Place(
                        relative.split('/').filter { it.isNotBlank() },
                        name,
                        c.getColumnIndex(android.provider.MediaStore.MediaColumns.SIZE).takeIf { it >= 0 }?.let { c.getLong(it) } ?: -1L,
                        str(android.provider.MediaStore.MediaColumns.MIME_TYPE) ?: "audio/mpeg",
                    )
                }
            }
            "file" -> {
                val f = java.io.File(uri.path ?: return@runCatching null)
                if (!f.isFile) return@runCatching null
                // Inside the app's own storage: kept under "BitChord".
                val folders = relativeOf(f.parent)?.split('/')?.filter { it.isNotBlank() } ?: listOf("BitChord")
                Place(folders, f.name, f.length(), java.net.URLConnection.guessContentTypeFromName(f.name) ?: "audio/mpeg")
            }
            else -> null
        }
    }.getOrNull()

    /** [dir] from the top of the phone's shared storage, or null when it isn't in it. */
    @Suppress("DEPRECATION")
    private fun relativeOf(dir: String?): String? {
        dir ?: return null
        val top = android.os.Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        return if (dir.startsWith("$top/") || dir == top) dir.removePrefix(top).trim('/') else null
    }

    /**
     * Sends what isn't in pCloud yet, file by file, while Wi-Fi lasts: the same folders and
     * file names as on the phone, under "Music" at the top of your pCloud ("Music/X" on the
     * phone stays "Music/X"; another folder, like "Download/X", goes to "Music/Download/X").
     * The files go as they are: nothing renamed, retagged or converted.
     */
    fun runNow() {
        val ctx = app ?: return
        scope.launch {
            if (!running.tryLock()) return@launch
            var remaining = 0
            try {
                if (!allowed(ctx)) return@launch
                val top = com.music.bitchord.data.video.PcloudVideos.serverRoot() ?: return@launch
                val user = AppSettings.webdavUsername.value
                val pass = AppSettings.webdavPassword.value
                val done = sent()
                val todo = onPhone(ctx).filter { key(it) !in done }
                remaining = todo.size
                _state.value = State(running = true, sent = done.size, waiting = todo.size)
                if (todo.isEmpty()) return@launch
                TrackLog.d(TAG, "phone backup: ${todo.size} file(s) on the phone not in pCloud yet")
                // The mirror's folder moved or renamed in pCloud: never made again from scratch
                // (everything would go up a second time) — looked for by its contents instead.
                val mirror = mirrorRoot(top, user, pass) ?: run {
                    TrackLog.w(TAG, "phone backup: the Music folder isn't at the top of pCloud any more and wasn't found — nothing sent")
                    return@launch
                }
                val made = HashSet<String>()
                for (s in todo) {
                    if (!allowed(ctx)) {
                        TrackLog.d(TAG, "phone backup: paused (off Wi-Fi, battery saver or switched off), $remaining left")
                        break
                    }
                    val uri = s.localUri ?: continue
                    val place = placeOf(ctx, uri)
                    if (place == null || place.name.endsWith(".m3u8", true)) {
                        remaining--
                        continue
                    }
                    // BitChord Audio: a mirror of the phone — every file goes up as it is (m4a, opus and
                    // webm too: the Mac's library takes them from here as they are). Skipping files
                    // "the library already has" (matched by title, often another album's or an older
                    // version) left whole albums with a few songs in pCloud.
                    if (WebDavConfig.isWebDavId(s.videoId)) {
                        markSent(listOf(key(s)))
                        remaining--
                        continue
                    }
                    val folders = if (place.folders.firstOrNull().equals("Music", true)) place.folders.drop(1) else place.folders
                    var dir = mirror
                    var ok = true
                    for (f in folders) {
                        dir = WebDavClient.joinUrl(dir, f.replace(Regex("""[\\:*?"<>|%]"""), " ").trim())
                        if (made.add(dir) && WebDavClient.ensureCollection(dir, user, pass).isFailure) { made.remove(dir); ok = false; break }
                    }
                    // One folder that can't be made skips its files, not the rest of the pass.
                    if (!ok) { TrackLog.w(TAG, "phone backup: no folder ${folders.joinToString("/")}"); continue }
                    val target = WebDavClient.joinUrl(dir, place.name)
                    _state.value = _state.value.copy(current = (folders + place.name).joinToString("/"), waiting = remaining)
                    // Already there (sent before, or put there another way): counted as backed up.
                    if (WebDavClient.exists(target, user, pass).getOrDefault(false)) {
                        markSent(listOf(key(s)))
                        remaining--
                        continue
                    }
                    val result = runCatching {
                        ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))!!.use { input ->
                            WebDavClient.putFile(target, input, place.size, place.mime, user, pass, overwrite = false).getOrThrow()
                        }
                    }
                    if (result.isSuccess) {
                        markSent(listOf(key(s)))
                        remaining--
                    } else {
                        TrackLog.w(TAG, "phone backup: '${place.name}' failed: ${result.exceptionOrNull()?.message}")
                    }
                }
                TrackLog.d(TAG, "phone backup: done for now — ${sent().size} file(s) in pCloud, $remaining left")
            } finally {
                forced = false
                _state.value = _state.value.copy(running = false, current = null, waiting = remaining)
                running.unlock()
            }
        }
    }
}

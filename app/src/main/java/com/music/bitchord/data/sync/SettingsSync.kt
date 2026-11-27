package com.music.bitchord.data.sync

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * BitChord Audio: settings, mixed playlists, per-song version choices and lyric
 * names kept in sync through the pCloud (WebDAV) folder.
 *
 * One file, `.bitchord-audio/sync.json` inside the music folder, holding a
 * snapshot of [PREFS] and [FILES]. The newest snapshot wins:
 *
 *  - **On launch**, a snapshot downloaded last time ([PENDING]) is applied
 *    before anything reads the settings — [applyPending] runs first in the
 *    application's `onCreate` — so nothing has to be reloaded live.
 *  - **After launch**, the folder's snapshot is fetched. Newer than what this
 *    phone last saw: it is kept as [PENDING] for the next launch, and nothing
 *    is uploaded meanwhile, so this phone can't overwrite it. Otherwise this
 *    phone's state is uploaded whenever it changes (debounced).
 *
 * Left out on purpose: anything secret (sign-ins, tokens, keys — passwords are
 * never in these files to begin with, they live in the encrypted account
 * store), and anything about this phone rather than about you (output device,
 * cache size, first-run flags). The sources list is left out too, since an
 * addon URL can carry its own access token.
 */
object SettingsSync {

    private const val TAG = "BitChord"
    private const val REMOTE_DIR = ".bitchord-audio"
    private const val REMOTE_FILE = "sync.json"
    private const val PENDING = "settings_sync_pending.json"
    // BitChord Audio: the [FILES] go up one by one into this subfolder, streamed from disk,
    // and only when they changed; sync.json lists them under "fileRefs". Inlined as text
    // they grew with the library until one upload asked 21 MB more of a full memory and the
    // app crashed — and a sync.json over 8 MB was never read back on the other device.
    private const val REMOTE_FILES_DIR = "sync-files/"
    private const val PENDING_FILES = "settings_sync_pending_files"
    private const val STATE_PREFS = "bitchord_audio_sync_state"
    private const val KEY_LAST_SEEN = "last_seen_saved_at"
    private const val UPLOAD_DEBOUNCE_MS = 20_000L

    /** Preference files whose contents follow you. */
    private val PREFS = listOf(
        "bitchord_settings",
        "bitchord_offline_playlists",
        "bitchord_original_versions",
        "bitchord_merged_choices",
        "bitchord_library_folders",
        // BitChord Audio: your per-song source picks, learnt artist names and
        // likes kept on the device.
        "bitchord_source_choices",
        "bitchord_artist_links",
        "bitchord_local_likes",
        "bitchord_lyric_offsets",
        "bitchord_bad_lyrics",
    )

    /** Files in the app's own storage that follow you. */
    private val FILES = listOf("lyric_aliases.json", com.music.bitchord.data.names.OfficialNames.FILE, com.music.bitchord.data.SongFamily.FILE, com.music.bitchord.playback.smart.AudioCheck.FILE, com.music.bitchord.data.cleanup.LibraryCleanup.FILE, com.music.bitchord.data.cleanup.Assistant.FILE, com.music.bitchord.data.cleanup.LikedVideos.FILE)

    private val SKIP_EXACT = setOf(
        "sources", "onboarding_complete", "last_version_code", "last_player_screen",
        "audio_cache_limit_bytes", "output_pcm_mode", "prefer_usb_dac", "usb_allow_fixed_volume",
        "convert_for_limited_dac", "show_cache_folder", "lyrics_sources_seen",
    )
    private val SKIP_PARTS = listOf(
        "password", "token", "secret", "session", "cookie", "api_key", "apikey", "auth",
        "webdav_", "smb_", "spotify_", "discord_", "listenbrainz", "lastfm_",
    )

    private fun synced(key: String): Boolean {
        val k = key.lowercase()
        return k !in SKIP_EXACT && SKIP_PARTS.none { it in k }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var uploadJob: Job? = null

    @Volatile
    private var ready = false

    /** Held so the listeners aren't collected — SharedPreferences keeps them weakly. */
    private val listeners = ArrayList<Pair<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>>()

    // ── Launch ──────────────────────────────────────────────────────────

    /** Applies a snapshot downloaded last session. Call before anything reads settings. */
    fun applyPending(context: Context) {
        val f = File(context.filesDir, PENDING)
        if (!f.isFile) return
        runCatching {
            val snap = JSONObject(f.readText())
            val prefs = snap.optJSONObject("prefs") ?: JSONObject()
            for (name in PREFS) {
                val values = prefs.optJSONObject(name) ?: continue
                val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                val edit = sp.edit()
                // Keys this phone keeps to itself stay as they are.
                sp.all.keys.filter(::synced).forEach { edit.remove(it) }
                values.keys().forEach { key ->
                    if (!synced(key)) return@forEach
                    val entry = values.getJSONObject(key)
                    when (entry.getString("t")) {
                        "b" -> edit.putBoolean(key, entry.getBoolean("v"))
                        "i" -> edit.putInt(key, entry.getInt("v"))
                        "l" -> edit.putLong(key, entry.getLong("v"))
                        "f" -> edit.putFloat(key, entry.getDouble("v").toFloat())
                        "s" -> edit.putString(key, entry.getString("v"))
                        "ss" -> edit.putStringSet(
                            key,
                            entry.getJSONArray("v").let { a -> (0 until a.length()).mapTo(HashSet()) { a.getString(it) } },
                        )
                    }
                }
                edit.commit()
            }
            // Older snapshots carry the files inline; newer ones name them, downloaded beside it.
            val files = snap.optJSONObject("files") ?: JSONObject()
            for (name in FILES) {
                if (files.has(name)) File(context.filesDir, name).writeText(files.getString(name))
            }
            val refs = snap.optJSONObject("fileRefs")
            if (refs != null) {
                val pending = File(context.filesDir, PENDING_FILES)
                for (name in FILES) {
                    if (!refs.has(name)) continue
                    val got = File(pending, name)
                    if (!got.isFile) continue
                    val dest = File(context.filesDir, name)
                    if (!got.renameTo(dest)) got.copyTo(dest, overwrite = true)
                }
            }
            state(context).edit().putLong(KEY_LAST_SEEN, snap.optLong("savedAt")).commit()
            TrackLog.d(TAG, "settings sync: applied the snapshot from ${snap.optString("device")}")
        }.onFailure { TrackLog.w(TAG, "settings sync: could not apply snapshot: ${it.message}") }
        f.delete()
        File(context.filesDir, PENDING_FILES).deleteRecursively()
    }

    /** Fetches the folder's snapshot and starts uploading this phone's changes. Call after settings init. */
    fun start(context: Context) {
        val app = context.applicationContext
        PREFS.forEach { name ->
            val sp = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || synced(key)) changed(app)
            }
            sp.registerOnSharedPreferenceChangeListener(listener)
            synchronized(listeners) { listeners += sp to listener }
        }
        scope.launch { check(app) }
    }

    /** Something synced changed outside a preference file (lyric names); schedules an upload. */
    fun changed(context: Context) {
        if (!ready) return
        uploadJob?.cancel()
        uploadJob = scope.launch {
            delay(UPLOAD_DEBOUNCE_MS)
            upload(context.applicationContext)
        }
    }

    // ── Remote ──────────────────────────────────────────────────────────

    private data class Remote(val dirUrl: String, val fileUrl: String, val user: String, val pass: String)

    private fun remote(): Remote? {
        val base = AppSettings.webdavUrl.value
        if (!WebDavConfig.isConfigured(base)) return null
        val root = WebDavConfig.normalizeUrl(base).trimEnd('/') + "/"
        val dir = "$root$REMOTE_DIR/"
        return Remote(dir, dir + REMOTE_FILE, AppSettings.webdavUsername.value, AppSettings.webdavPassword.value)
    }

    private suspend fun check(context: Context) {
        val r = remote() ?: return
        val text = WebDavClient.readText(r.fileUrl, r.user, r.pass, 8 * 1024 * 1024).getOrNull()
        val lastSeen = state(context).getLong(KEY_LAST_SEEN, 0L)
        val remoteSnap = text?.let { runCatching { JSONObject(it) }.getOrNull() }
        val remoteAt = remoteSnap?.optLong("savedAt") ?: 0L
        if (remoteSnap != null && remoteAt > lastSeen && remoteSnap.optString("device") != deviceId(context)) {
            // Newer than anything this phone has seen: it is applied on the
            // next launch, and this phone stays quiet until then.
            // Its files come down now, straight to disk, to be put in place with it.
            remoteSnap.optJSONObject("fileRefs")?.let { refs ->
                val pending = File(context.filesDir, PENDING_FILES).apply { deleteRecursively(); mkdirs() }
                for (name in FILES) {
                    if (!refs.has(name)) continue
                    val into = File(pending, name)
                    WebDavClient.download(r.dirUrl + REMOTE_FILES_DIR + name, r.user, r.pass, into)
                        .onFailure {
                            into.delete()
                            TrackLog.w(TAG, "settings sync: could not fetch $name: ${it.message}")
                        }
                }
            }
            File(context.filesDir, PENDING).writeText(text)
            TrackLog.d(TAG, "settings sync: newer settings in pCloud; they apply on the next launch")
            return
        }
        ready = true
        // First run on this phone with a folder that has nothing yet, or a
        // phone whose state moved on since its last upload: send it.
        upload(context)
    }

    private suspend fun upload(context: Context) {
        val r = remote() ?: return
        WebDavClient.ensureCollection(r.dirUrl, r.user, r.pass)
        // The files first, each streamed from a copy on disk, only those that changed.
        val refs = JSONObject()
        var dirReady = false
        val st = state(context)
        for (name in FILES) {
            val f = File(context.filesDir, name)
            if (!f.isFile) continue
            val sig = "${f.length()}:${f.lastModified()}"
            if (st.getString("file:$name", null) != sig) {
                if (!dirReady) {
                    WebDavClient.ensureCollection(r.dirUrl + REMOTE_FILES_DIR, r.user, r.pass)
                    dirReady = true
                }
                val copy = File(context.cacheDir, "sync-up-$name")
                val sent = runCatching {
                    f.copyTo(copy, overwrite = true)
                    copy.inputStream().use { input ->
                        WebDavClient.putFile(r.dirUrl + REMOTE_FILES_DIR + name, input, copy.length(), "application/json", r.user, r.pass, overwrite = true).getOrThrow()
                    }
                }
                copy.delete()
                if (sent.isFailure) {
                    TrackLog.w(TAG, "settings sync: upload of $name failed: ${sent.exceptionOrNull()?.message}")
                    return
                }
                st.edit().putString("file:$name", sig).apply()
            }
            refs.put(name, sig)
        }
        val snap = snapshot(context).put("fileRefs", refs)
        val bytes = snap.toString().toByteArray(Charsets.UTF_8)
        WebDavClient.putFile(
            r.fileUrl,
            bytes.inputStream(),
            bytes.size.toLong(),
            "application/json",
            r.user,
            r.pass,
            overwrite = true,
        ).onSuccess {
            state(context).edit().putLong(KEY_LAST_SEEN, snap.getLong("savedAt")).apply()
        }.onFailure { TrackLog.w(TAG, "settings sync: upload failed: ${it.message}") }
    }

    private fun snapshot(context: Context): JSONObject {
        val prefs = JSONObject()
        for (name in PREFS) {
            val values = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                if (!synced(key) || value == null) return@forEach
                val entry = when (value) {
                    is Boolean -> JSONObject().put("t", "b").put("v", value)
                    is Int -> JSONObject().put("t", "i").put("v", value)
                    is Long -> JSONObject().put("t", "l").put("v", value)
                    is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", value)
                    is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.map { it.toString() }))
                    else -> null
                } ?: return@forEach
                values.put(key, entry)
            }
            prefs.put(name, values)
        }
        // The files themselves aren't put in here: [upload] sends them on their own.
        return JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("device", deviceId(context))
            .put("prefs", prefs)
    }

    private fun state(context: Context) = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    @Suppress("HardwareIds")
    private fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
}

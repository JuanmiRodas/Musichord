package com.music.bitchord.data

import android.content.Context
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * BitChord Audio: what the app has learnt about your songs, backed up to your
 * pCloud once a day — and put back from there on a phone that has none of it
 * (a new phone, a reinstall).
 *
 * Kept on the device first; pCloud is the copy. Covered: the source chosen
 * for each song and the song each copy stands in for, video mode's linked
 * videos and its measured syncs, lyrics flagged wrong and lyrics offsets,
 * liked songs that aren't YouTube's, and learnt artist names. (Edited
 * details and the lyrics you kept already live in pCloud as you change them.)
 *
 * Files under `.bitchord-audio/backup/` in the WebDAV root.
 */
object CloudBackup {
    private const val TAG = "Backup"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val KEY_LAST = "last_backup"

    private val PREFS = listOf(
        "bitchord_source_choices",
        "bitchord_song_origins",
        "bitchord_video_mode",
        "bitchord_bad_lyrics",
        "bitchord_lyric_offsets",
        "bitchord_local_likes",
        "bitchord_artist_links",
        "bitchord_youtube_twins",
        "bitchord_youtube_refused",
        "bitchord_verified_copies",
    )
    private val FILES = listOf(com.music.bitchord.playback.smart.VideoAligner.FILE, com.music.bitchord.data.dna.SongDna.FILE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var appContext: Context? = null
    private val pending = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** Sets that are merged with pCloud's on every start, not only restored when empty. */
    private val MERGED = setOf("bitchord_local_likes")

    /**
     * BitChord Audio: [name] sent to pCloud a few seconds after it changes —
     * for what must not live only on the phone (likes YouTube doesn't hold),
     * rather than waiting for the daily backup.
     */
    fun soon(name: String) {
        val app = appContext ?: return
        pending.remove(name)?.cancel()
        pending[name] = scope.launch {
            delay(8_000)
            val dir = dir() ?: return@launch
            runCatching {
                WebDavClient.ensureCollection(WebDavConfig.normalizeUrl(AppSettings.webdavUrl.value).trimEnd('/') + "/.bitchord-audio/", user, pass)
                WebDavClient.ensureCollection(dir, user, pass)
                val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
                if (put(dir + "$name.json", encode(prefs.all).toString())) TrackLog.d(TAG, "backup: $name sent to pCloud")
            }.onFailure { TrackLog.w(TAG, "backup: $name not sent: ${it.message}") }
        }
    }

    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        scope.launch {
            // After start-up has settled, and only with pCloud set up.
            delay(20_000)
            val dir = dir() ?: return@launch
            runCatching { restoreMissing(app, dir) }
                .onFailure { TrackLog.w(TAG, "backup: restore failed: ${it.message}") }
            val meta = app.getSharedPreferences("bitchord_backup", Context.MODE_PRIVATE)
            if (System.currentTimeMillis() - meta.getLong(KEY_LAST, 0L) < DAY_MS) return@launch
            if (runCatching { backUp(app, dir) }.getOrDefault(false)) {
                meta.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            }
        }
    }

    private fun dir(): String? {
        val base = AppSettings.webdavUrl.value
        if (!WebDavConfig.isConfigured(base)) return null
        return WebDavConfig.normalizeUrl(base).trimEnd('/') + "/.bitchord-audio/backup/"
    }

    private val user get() = AppSettings.webdavUsername.value
    private val pass get() = AppSettings.webdavPassword.value

    private suspend fun backUp(context: Context, dir: String): Boolean {
        WebDavClient.ensureCollection(WebDavConfig.normalizeUrl(AppSettings.webdavUrl.value).trimEnd('/') + "/.bitchord-audio/", user, pass)
        WebDavClient.ensureCollection(dir, user, pass)
        var sent = 0
        for (name in PREFS) {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            if (prefs.all.isEmpty()) continue
            if (put(dir + "$name.json", encode(prefs.all).toString())) sent++
        }
        for (name in FILES) {
            val f = File(context.filesDir, name).takeIf { it.isFile } ?: continue
            if (put(dir + name, f.readText())) sent++
        }
        TrackLog.d(TAG, "backup: $sent sets sent to pCloud")
        return true
    }

    private suspend fun put(url: String, text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return WebDavClient.putFile(url, bytes.inputStream(), bytes.size.toLong(), "application/json", user, pass, overwrite = true)
            .isSuccess
    }

    /** Whatever this phone has nothing of, taken back from pCloud. */
    private suspend fun restoreMissing(context: Context, dir: String) {
        var restored = 0
        for (name in MERGED) {
            // Added from pCloud: what another phone (or an old install) liked;
            // a "gone:" mark here keeps an un-like from coming back.
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val text = WebDavClient.readText(dir + "$name.json", user, pass, 16 * 1024 * 1024).getOrNull() ?: continue
            val remote = runCatching { JSONObject(text) }.getOrNull() ?: continue
            val local = prefs.all
            val add = JSONObject()
            remote.keys().forEach { k ->
                val id = k.substringAfter(':')
                if (k !in local && "gone:$id" !in local && !k.startsWith("gone:")) add.put(k, remote.getJSONObject(k))
            }
            if (add.length() > 0) {
                runCatching { decodeInto(add, prefs) }.onSuccess {
                    restored++
                    TrackLog.d(TAG, "backup: ${add.length()} entries of $name taken from pCloud")
                    if (name == "bitchord_local_likes") LocalLikes.init(context)
                }
            }
            // And this phone's own, if pCloud lacks any.
            if (local.keys.any { !remote.has(it) }) soon(name)
        }
        for (name in PREFS) {
            if (name in MERGED) continue
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            if (prefs.all.isNotEmpty()) continue
            val text = WebDavClient.readText(dir + "$name.json", user, pass, 16 * 1024 * 1024).getOrNull() ?: continue
            if (runCatching { decodeInto(JSONObject(text), prefs) }.isSuccess) restored++
        }
        for (name in FILES) {
            val f = File(context.filesDir, name)
            if (f.isFile) continue
            val text = WebDavClient.readText(dir + name, user, pass, 16 * 1024 * 1024).getOrNull() ?: continue
            runCatching { f.writeText(text) }.onSuccess { restored++ }
        }
        if (restored > 0) {
            TrackLog.d(TAG, "backup: $restored sets restored from pCloud")
            com.music.bitchord.playback.smart.VideoAligner.reload(context)
            com.music.bitchord.data.dna.SongDna.load()
        }
    }

    private fun encode(all: Map<String, *>): JSONObject = JSONObject().apply {
        all.forEach { (k, v) ->
            val e = when (v) {
                is String -> JSONObject().put("t", "s").put("v", v)
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> null
            }
            if (e != null) put(k, e)
        }
    }

    private fun decodeInto(o: JSONObject, prefs: android.content.SharedPreferences) {
        val e = prefs.edit()
        o.keys().forEach { k ->
            val x = o.getJSONObject(k)
            when (x.getString("t")) {
                "s" -> e.putString(k, x.getString("v"))
                "b" -> e.putBoolean(k, x.getBoolean("v"))
                "l" -> e.putLong(k, x.getLong("v"))
                "i" -> e.putInt(k, x.getInt("v"))
                "f" -> e.putFloat(k, x.getDouble("v").toFloat())
                "set" -> {
                    val a = x.getJSONArray("v")
                    e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                }
            }
        }
        e.apply()
    }
}

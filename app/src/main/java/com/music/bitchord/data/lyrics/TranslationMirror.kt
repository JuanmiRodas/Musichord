package com.music.bitchord.data.lyrics

import android.content.Context
import com.music.bitchord.data.BackgroundHours
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * BitChord Audio: lyric translations (and romaji) kept for good — in the app's own files on
 * the phone, and in pCloud under `.bitchord-audio/lyrics-translations/` — so no song is
 * translated twice, on this phone or after a reinstall or on another device. Each is one
 * small gzip file named after the song's lines and the target language.
 *
 * New ones go up as they are made; those that never reached pCloud (made before this, or
 * offline) go up at night. Nothing else in pCloud is touched.
 */
object TranslationMirror : LyricsTranslation.Mirror {

    private const val TAG = "BitChord"
    private const val REMOTE_DIR = ".bitchord-audio/lyrics-translations/"
    private const val SENT = ".sent"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var dirReady = false
    private val sent = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var sentFile: File? = null

    /** Called once at start: moves the old cache in, and hooks the mirror up. */
    fun init(context: Context) {
        val app = context.applicationContext
        // From the cache Android may clear, to the app's own files.
        runCatching {
            val old = File(app.cacheDir, "lyrics_translation_v3")
            val now = File(app.filesDir, "lyrics_translation_v3").apply { mkdirs() }
            old.listFiles()?.forEach { f ->
                val dest = File(now, f.name)
                if (!dest.exists() && !f.renameTo(dest)) f.copyTo(dest)
                f.delete()
            }
            old.delete()
            sentFile = File(now, SENT).also { s -> if (s.isFile) sent.addAll(s.readLines()) }
        }
        LyricsTranslation.cacheDir = app.filesDir
        LyricsTranslation.mirror = this
        scope.launch { catchUp() }
    }

    private fun base(): Triple<String, String, String>? {
        val url = AppSettings.webdavUrl.value
        if (!WebDavConfig.isConfigured(url)) return null
        val root = WebDavConfig.normalizeUrl(url).trimEnd('/') + "/"
        return Triple(root + REMOTE_DIR, AppSettings.webdavUsername.value, AppSettings.webdavPassword.value)
    }

    override suspend fun fetch(name: String, into: File): Boolean {
        val (dir, user, pass) = base() ?: return false
        val ok = WebDavClient.download(dir + name, user, pass, into).isSuccess
        if (ok) markSent(name) else into.delete()
        return ok
    }

    override suspend fun send(name: String, file: File) {
        val (dir, user, pass) = base() ?: return
        if (!dirReady) {
            WebDavClient.ensureCollection(dir.removeSuffix("lyrics-translations/"), user, pass)
            dirReady = WebDavClient.ensureCollection(dir, user, pass).isSuccess
        }
        val result = file.inputStream().use { input ->
            WebDavClient.putFile(dir + name, input, file.length(), "application/gzip", user, pass, overwrite = true)
        }
        if (result.isSuccess) markSent(name)
    }

    private fun markSent(name: String) {
        if (!sent.add(name)) return
        runCatching { sentFile?.appendText(name + "\n") }
    }

    /** At night, the kept translations that aren't in pCloud yet go up. */
    private suspend fun catchUp() {
        while (true) {
            BackgroundHours.await()
            val dir = LyricsTranslation.keptDirectory()
            val todo = dir?.listFiles { f -> f.name.endsWith(".json.gz") && f.name !in sent }.orEmpty()
            if (todo.isNotEmpty() && base() != null) {
                var done = 0
                for (f in todo) {
                    if (!BackgroundHours.now()) break
                    runCatching { send(f.name, f) }
                    if (f.name in sent) done++
                }
                TrackLog.d(TAG, "lyrics translations: $done of ${todo.size} kept ones copied to pCloud")
            }
            delay(6 * 60 * 60_000L)
        }
    }
}

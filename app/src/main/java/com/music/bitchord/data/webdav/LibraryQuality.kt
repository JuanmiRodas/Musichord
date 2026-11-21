package com.music.bitchord.data.webdav

import android.content.Context
import com.music.bitchord.data.BackgroundHours
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.OwnFileMatcher
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * BitChord Audio: the phone's side of the tidy library on pCloud (the Mac app's).
 *
 * The library keeps MP3 320 only — made from lossless or bought as a real 320, never
 * AAC, m4a, opus or webm. So:
 *  - [isKeepable]: what may go up to pCloud at all (MP3 or lossless; the copies and the
 *    phone's mirror ask it before every upload);
 *  - [upgradeNight]: a download that isn't MP3 320 or lossless, of a song the library now
 *    has as an MP3 or lossless file of the same recording (same title in any script, same
 *    artist, length within 2 s), is downloaded again — the downloader takes the library's
 *    file over YouTube's when it is better. Quietly, at night, on Wi-Fi; each pair tried
 *    once, so a library file that turns out no better is not fetched every night;
 *  - [writeTodo]: the downloads the library has no good copy of yet go to
 *    `.bitchord-audio/todo-phone.json` on pCloud, for the Mac app's to-do list.
 */
object LibraryQuality {

    private const val TAG = "BitChord"
    private const val PREFS = "bitchord_library_quality"
    private val GOOD_EXT = setOf("mp3", "flac", "wav", "aiff", "aif", "alac")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    @Volatile private var app: Context? = null

    /** MP3 or lossless, by the file's name (or its saved format). */
    fun isKeepable(name: String?, format: String? = null): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext.isNotEmpty() && ext.length <= 5) return ext in GOOD_EXT
        val f = format?.lowercase().orEmpty()
        return f.startsWith("mp3") || "flac" in f || "lossless" in f || "alac" in f || "wav" in f
    }

    /** A saved download's quality is already what the library wants. */
    private fun isFinal(format: String?): Boolean {
        val f = format?.lowercase() ?: return false
        if ("flac" in f || "lossless" in f || "alac" in f) return true
        val kbps = Regex("""(\d{3})\s*kbps""").find(f)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return f.startsWith("mp3") && kbps >= 315
    }

    private fun seconds(s: Song): Int? = s.durationText?.split(':')?.mapNotNull { it.trim().toIntOrNull() }
        ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }

    /** The library's MP3 or lossless file of [s]'s recording, if it has one. */
    private fun libraryCopy(s: Song): Song? {
        val sec = seconds(s)
        return runCatching { OwnFileMatcher.matches(s.title, s.artist, sec, s.albumName) }.getOrDefault(emptyList())
            .filter { WebDavConfig.isWebDavId(it.videoId) && isKeepable(it.videoId.substringBefore('?')) }
            .firstOrNull { f -> val fs = seconds(f); sec == null || fs == null || kotlin.math.abs(fs - sec) <= 2 }
    }

    fun start(context: Context) {
        app = context.applicationContext
        if (job?.isActive == true) return
        job = scope.launch {
            delay(5 * 60_000L)
            while (true) {
                BackgroundHours.await()
                if (AppSettings.meteredConnection.value == false && WebDavRepository.isConfigured()) {
                    runCatching { upgradeNight() }.onFailure { TrackLog.w(TAG, "library quality: ${it.message}") }
                    runCatching { writeTodo() }.onFailure { TrackLog.w(TAG, "library quality: to-do not written: ${it.message}") }
                }
                delay(3 * 60 * 60_000L)
            }
        }
    }

    /** Downloads replaced by the library's better file of the same recording. */
    private suspend fun upgradeNight() {
        val ctx = app ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var n = 0
        for (s in Downloads.savedMetadataSongs()) {
            if (!BackgroundHours.now() || AppSettings.meteredConnection.value != false) break
            if (WebDavConfig.isWebDavId(s.videoId)) continue
            val format = Downloads.savedEntry(s.videoId)?.second
            if (isFinal(format)) continue
            val copy = libraryCopy(s) ?: continue
            val tried = "tried:" + s.videoId + "|" + copy.videoId
            if (prefs.getBoolean(tried, false)) continue
            prefs.edit().putBoolean(tried, true).apply()
            // The library's file must answer before the phone's copy goes.
            val url = WebDavConfig.fileUrlOf(copy.videoId) ?: continue
            val there = WebDavClient.exists(url, AppSettings.webdavUsername.value, AppSettings.webdavPassword.value).getOrDefault(false)
            if (!there) continue
            TrackLog.d(TAG, "download upgraded: '${s.title}' ${format ?: "?"} → your library's ${copy.videoId.substringAfterLast('/').substringAfterLast('.').uppercase()}")
            runCatching { Downloads.delete(ctx, s.videoId) }
            Downloads.enqueue(ctx, s, "library")
            n++
            delay(4_000)
        }
        if (n > 0) TrackLog.d(TAG, "library quality: $n downloads replaced by your library's copies")
    }

    /** What the phone has that the library still lacks in good quality → the Mac app's to-do. */
    private suspend fun writeTodo() {
        val items = JSONArray()
        for (s in Downloads.savedMetadataSongs()) {
            if (WebDavConfig.isWebDavId(s.videoId)) continue
            val format = Downloads.savedEntry(s.videoId)?.second
            if (isFinal(format) || libraryCopy(s) != null) continue
            items.put(
                JSONObject()
                    .put("title", s.title).put("artist", s.artist).put("album", s.albumName ?: "")
                    .put("duration", s.durationText ?: "").put("format", format ?: "").put("id", s.videoId),
            )
        }
        val base = WebDavConfig.normalizeUrl(AppSettings.webdavUrl.value).trimEnd('/')
        val user = AppSettings.webdavUsername.value
        val pass = AppSettings.webdavPassword.value
        WebDavClient.ensureCollection("$base/.bitchord-audio/", user, pass)
        val body = JSONObject().put("savedAt", System.currentTimeMillis()).put("songs", items).toString().toByteArray()
        WebDavClient.putFile("$base/.bitchord-audio/todo-phone.json", body.inputStream(), body.size.toLong(), "application/json", user, pass, overwrite = true)
            .onSuccess { TrackLog.d(TAG, "library quality: ${items.length()} songs on the phone's to-do for the library") }
    }
}

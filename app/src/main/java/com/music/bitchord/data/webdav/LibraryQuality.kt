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
 *  - [upgradeNight]: a download that isn't MP3 320 or lossless is replaced by the
 *    library's file of the same recording — checked by fingerprint, and the old file
 *    deleted only once the new one is in place. Quietly, at night, on Wi-Fi; each pair
 *    tried once;
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

    /**
     * Downloads replaced by the library's better file of the same recording, safely:
     *  1. the library's file is downloaded beside the current download (nothing touched yet);
     *  2. it must really be better — lossless, or an MP3 of 315 kbps or more;
     *  3. both are fingerprinted, as the link check does: only a bit error under 0.33
     *     (the same recording) goes on;
     *  4. the new file is put in place and recorded, and only then is the old one deleted.
     * Any step that fails leaves the current download exactly as it was. A song playing
     * right now waits for the next round.
     */
    private suspend fun upgradeNight() {
        val ctx = app ?: return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val user = AppSettings.webdavUsername.value
        val pass = AppSettings.webdavPassword.value
        val work = java.io.File(ctx.cacheDir, "library-upgrade").apply { mkdirs() }
        var n = 0
        for (s in Downloads.savedMetadataSongs()) {
            if (!BackgroundHours.now() || AppSettings.meteredConnection.value != false) break
            if (WebDavConfig.isWebDavId(s.videoId)) continue
            val (current, format) = Downloads.savedEntry(s.videoId) ?: continue
            if (isFinal(format)) continue
            val copy = libraryCopy(s) ?: continue
            val tried = "tried:" + s.videoId + "|" + copy.videoId
            if (prefs.getBoolean(tried, false)) continue
            // Not while it plays.
            if (com.music.bitchord.data.lyrics.CurrentLyrics.lines.value?.first == s.videoId) continue
            val url = WebDavConfig.fileUrlOf(copy.videoId) ?: continue
            val ext = java.net.URLDecoder.decode(url.substringAfterLast('/'), "UTF-8").substringAfterLast('.', "mp3").lowercase()
            val name = java.net.URLDecoder.decode(url.substringAfterLast('/'), "UTF-8").replace(Regex("""[\\/:*?"<>|]"""), " ")
            val tmp = java.io.File(work, name.ifBlank { "copy.$ext" })
            try {
                // 1. Fetched beside the current one.
                if (WebDavClient.download(url, user, pass, tmp).isFailure || tmp.length() <= 0) continue
                // 2. Really better.
                val kbps = bitrateOf(tmp)
                val lossless = ext in setOf("flac", "wav", "aiff", "aif", "alac")
                if (!lossless && (ext != "mp3" || (kbps ?: 0) < 315)) {
                    prefs.edit().putBoolean(tried, true).apply()
                    TrackLog.d(TAG, "download upgrade: '${s.title}' — the library's file is ${ext.uppercase()} ${kbps ?: "?"} kbps, not better; kept")
                    continue
                }
                // 3. The same recording, by ear.
                val ber = com.music.bitchord.playback.smart.AudioCheck.berBetween(ctx, android.net.Uri.parse(current), tmp)
                if (ber == null || ber > com.music.bitchord.playback.smart.AudioCheck.REPLACE_BELOW) {
                    prefs.edit().putBoolean(tried, true).apply()
                    TrackLog.d(TAG, "download upgrade: '${s.title}' — fingerprint ${ber?.let { "%.2f".format(it) } ?: "unreadable"}, under 80 % alike, not provably the same recording; kept")
                    continue
                }
                // 4. In place first, the old one deleted after.
                val newFormat = if (lossless) "${ext.uppercase()} · lossless (your library)" else "MP3 · ${kbps} kbps (your library)"
                if (Downloads.replaceWith(ctx, s.videoId, tmp, newFormat)) {
                    prefs.edit().putBoolean(tried, true).apply()
                    n++
                    TrackLog.d(TAG, "download upgraded: '${s.title}' ${format ?: "?"} → $newFormat (fingerprint ${"%.2f".format(ber)})")
                }
            } finally {
                tmp.delete()
            }
            delay(2_000)
        }
        if (n > 0) TrackLog.d(TAG, "library quality: $n downloads replaced by your library's copies")
    }

    /** The file's bitrate in kbps, from its header (or its size and length). */
    private fun bitrateOf(f: java.io.File): Int? = runCatching {
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(f.path)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.let { it / 1000 }
                ?: r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
                    ?.let { ms -> (f.length() * 8 / ms).toInt() }
        } finally {
            r.release()
        }
    }.getOrNull()

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

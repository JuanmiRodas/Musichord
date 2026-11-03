package com.music.bitchord.data

import android.content.Context
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BitChord Audio: the phone's running log, sent to a private GitHub repository
 * you own — so it can be read when something goes wrong without sharing files
 * by hand. Set up in Settings with the repository ("owner/name") and a token
 * that can only write to that repository.
 *
 * Every two minutes, when there is something new: this run's log goes to
 * `logs/<phone>/<day>/<time the app started>.log` (the same file, updated), and
 * each crash or exit report once to `logs/<phone>/reports/`. Also "Send now".
 */
object LogUpload {

    private const val PREFS = "bitchord_log_upload"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private var app: Context? = null
    private val started = Date()
    private var sessionSha: String? = null
    private var lastWritten = -1L

    private val _repo = MutableStateFlow("")
    val repo: StateFlow<String> = _repo.asStateFlow()

    /** What the last attempt said: null before any, else "ok …" or the error. */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private fun prefs() = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val token: String get() = prefs()?.getString("token", "").orEmpty()

    val configured: Boolean get() = _repo.value.contains('/') && token.isNotBlank()

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        _repo.value = prefs()?.getString("repo", "").orEmpty()
        scope.launch {
            delay(30_000)
            while (true) {
                runCatching { send(force = false) }
                delay(120_000)
            }
        }
    }

    fun configure(repo: String, token: String?) {
        val clean = repo.trim().removePrefix("https://github.com/").trim('/')
        prefs()?.edit()?.apply {
            putString("repo", clean)
            if (token != null) putString("token", token.trim())
        }?.apply()
        _repo.value = clean
        sessionSha = null
        lastWritten = -1L
        sendNow()
    }

    fun clear() {
        prefs()?.edit()?.clear()?.apply()
        _repo.value = ""
        _status.value = null
    }

    fun sendNow() {
        scope.launch { runCatching { send(force = true) }.onFailure { _status.value = it.message } }
    }

    private val device: String get() = (Build.MANUFACTURER + "-" + Build.MODEL).replace(Regex("[^A-Za-z0-9_-]"), "_")

    private suspend fun send(force: Boolean) = lock.withLock {
        if (!configured) return@withLock
        if (!force && TrackLog.written == lastWritten) return@withLock
        val written = TrackLog.written
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(started)
        val time = SimpleDateFormat("HH-mm-ss", Locale.ROOT).format(started)
        val text = CrashLog.sessionText()
        val path = "logs/$device/$day/$time.log"
        val sha = put(path, text.toByteArray(), "log $day $time", sessionSha ?: shaOf(path))
        if (sha != null) {
            sessionSha = sha
            lastWritten = written
        }
        // Reports, once each.
        val p = prefs()
        for (f in CrashLog.reportFileList()) {
            val key = "up:" + f.name
            if (p?.getBoolean(key, false) == true) continue
            val rPath = "logs/$device/reports/${f.name}"
            if (put(rPath, f.readBytes(), "report ${f.name}", shaOf(rPath)) != null) p?.edit()?.putBoolean(key, true)?.apply()
        }
        if (sha != null) {
            _status.value = "ok " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        }
    }

    private fun connection(path: String, method: String): HttpURLConnection {
        val url = URL("https://api.github.com/repos/${_repo.value}/contents/$path")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "BitChord-Audio")
        }
    }

    private fun shaOf(path: String): String? {
        val c = connection(path, "GET")
        return try {
            if (c.responseCode != 200) null
            else JSONObject(c.inputStream.bufferedReader().readText()).optString("sha").ifEmpty { null }
        } finally {
            c.disconnect()
        }
    }

    /** Writes [bytes] to [path]; the new file's sha, or null (status says why). */
    private fun put(path: String, bytes: ByteArray, message: String, sha: String?): String? {
        fun attempt(withSha: String?): Pair<Int, String> {
            val body = JSONObject()
                .put("message", message)
                .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .apply { if (withSha != null) put("sha", withSha) }
                .toString()
            val c = connection(path, "PUT")
            return try {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                code to text
            } finally {
                c.disconnect()
            }
        }
        var (code, text) = attempt(sha)
        // The file changed under us (or the sha was stale): read it and retry once.
        if (code == 409 || code == 422) {
            val fresh = shaOf(path)
            val retry = attempt(fresh)
            code = retry.first
            text = retry.second
        }
        if (code !in 200..299) {
            _status.value = "GitHub $code: " + runCatching { JSONObject(text).optString("message") }.getOrDefault(text.take(120))
            return null
        }
        return runCatching { JSONObject(text).getJSONObject("content").getString("sha") }.getOrNull()
    }

    @Suppress("unused")
    private fun File.safeName() = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

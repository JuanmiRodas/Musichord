package com.music.bitchord.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BitChord Audio: a log that is always running, and a report for every time
 * the app died — to find out why it crashes.
 *
 *  - **The running log**: the app's own log lines (sources, playback, casting,
 *    lyrics…) written to `session.log` every 20 seconds; the previous run's is
 *    kept as `session-previous.log`, so whatever led up to a crash survives it.
 *  - **Crashes in the app's code**: caught as they happen and written to a
 *    report with the error, where it happened, what was casting, and the log.
 *  - **Everything else Android ended the app for** — a crash in native code, an
 *    "app not responding", being killed for memory — read from Android's own
 *    record of how the app last exited (Android 11+) at the next start, with
 *    Android's trace when it has one, plus the previous run's log.
 *
 * Reports are kept on the phone (the last 20) and copied to pCloud
 * (`.bitchord-audio/logs/`); Settings can share them.
 */
object CrashLog {

    private const val PREFS = "bitchord_crash_log"
    private const val KEEP = 20

    private var dir: File? = null
    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)

    private val _reports = MutableStateFlow(0)

    /** How many reports are on the phone. */
    val reports: StateFlow<Int> = _reports.asStateFlow()

    /** Extra lines for a report, from the app (what was casting, the build). */
    @Volatile
    var context: () -> List<String> = { emptyList() }

    fun init(context: Context) {
        if (dir != null) return
        app = context.applicationContext
        val d = File(context.filesDir, "logs").apply { mkdirs() }
        dir = d
        // The last run's log becomes "previous"; this run starts its own.
        val current = File(d, "session.log")
        if (current.isFile) current.renameTo(File(d, "session-previous.log"))

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { writeCrash(thread, error) }
            previous?.uncaughtException(thread, error)
        }
        countReports()
        scope.launch {
            runCatching { readExitReasons(context) }
            launch {
                // Once the settings (and pCloud) are in; then this run's log
                // every five minutes when it has grown.
                delay(60_000)
                runCatching { upload() }
                runCatching { syncNotes() }
                var sent = -1L
                while (true) {
                    if (TrackLog.written != sent) {
                        sent = TrackLog.written
                        runCatching { uploadSession() }
                    }
                    delay(300_000)
                }
            }
            var seen = -1L
            while (true) {
                if (TrackLog.written != seen) {
                    seen = TrackLog.written
                    runCatching { File(d, "session.log").writeText(header() + TrackLog.recent()) }
                }
                delay(20_000)
            }
        }
    }

    private fun header(): String = buildString {
        val pm = app?.packageManager
        val version = runCatching { pm?.getPackageInfo(app!!.packageName, 0)?.versionName }.getOrNull()
        append("BitChord Audio ").append(version ?: "?").append('\n')
        append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(") · ")
            .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("Written ").append(Date()).append('\n')
        runCatching { append(MemoryWatch.line()).append('\n') }
        runCatching { context() }.getOrDefault(emptyList()).forEach { append(it).append('\n') }
        append("\n")
    }

    private fun writeCrash(thread: Thread, error: Throwable) {
        val d = dir ?: return
        val text = buildString {
            append("CRASH in thread ").append(thread.name).append("\n\n")
            append(header())
            append(error.stackTraceToString()).append("\n\n— log —\n")
            append(TrackLog.recent())
        }
        File(d, "crash-${stamp.format(Date())}.txt").writeText(text)
    }

    /** Android's record of how the app ended, since the last look. */
    private fun readExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val d = dir ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong("lastExit", 0L)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            .filter { it.timestamp > last }
        val previousLog = File(d, "session-previous.log").takeIf { it.isFile }?.readText().orEmpty()
        val worth = setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        )
        for (e in exits.filter { it.reason in worth }) {
            val trace = runCatching { e.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
            val text = buildString {
                append("EXIT: ").append(reasonName(e.reason)).append('\n')
                append("When: ").append(Date(e.timestamp)).append('\n')
                append("Process: ").append(e.processName).append(" · importance ").append(e.importance)
                    .append(" · memory ").append(e.pss / 1024).append(" MB\n")
                e.description?.let { append("Android says: ").append(it).append('\n') }
                append('\n')
                if (!trace.isNullOrBlank()) append("— trace —\n").append(trace.take(400_000)).append("\n\n")
                append("— the run's log —\n").append(previousLog)
            }
            File(d, "exit-${stamp.format(Date(e.timestamp))}-${reasonName(e.reason)}.txt").writeText(text)
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("lastExit", it).apply() }
        countReports()
    }

    private fun reasonName(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
        ApplicationExitInfo.REASON_ANR -> "not-responding"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive-resources"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "start-failure"
        else -> "reason-$r"
    }

    /** BitChord Audio: this run's log, with the phone and app header. */
    fun sessionText(): String = header() + TrackLog.recent()

    /** The crash and exit reports on the phone, newest first. */
    fun reportFileList(): List<File> = reportFiles()

    private fun reportFiles(): List<File> =
        dir?.listFiles { f -> f.name.startsWith("crash-") || f.name.startsWith("exit-") }
            ?.sortedByDescending { it.lastModified() }.orEmpty()

    private fun countReports() {
        val files = reportFiles()
        files.drop(KEEP).forEach { it.delete() }
        _reports.value = files.size.coerceAtMost(KEEP)
    }

    /** Reports not yet in pCloud go there (`.bitchord-audio/logs/`). */
    private suspend fun upload() {
        val base = AppSettings.webdavUrl.value
        if (!WebDavConfig.isConfigured(base)) return
        val prefs = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        val remote = WebDavConfig.normalizeUrl(base).trimEnd('/') + "/.bitchord-audio/logs/"
        val u = AppSettings.webdavUsername.value
        val p = AppSettings.webdavPassword.value
        val todo = reportFiles().filterNot { prefs.getBoolean("up:" + it.name, false) }
        if (todo.isEmpty()) return
        WebDavClient.ensureCollection(WebDavConfig.normalizeUrl(base).trimEnd('/') + "/.bitchord-audio/", u, p)
        WebDavClient.ensureCollection(remote, u, p)
        for (f in todo) {
            val ok = f.inputStream().use { WebDavClient.putFile(remote + f.name, it, f.length(), "text/plain", u, p, overwrite = true) }.isSuccess
            if (ok) prefs.edit().putBoolean("up:" + f.name, true).apply()
        }
    }

    private val sessionName = "session-${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())}.log"

    /**
     * BitChord Audio: the notes kept with the app's code (`docs/ON_HOLD.md` —
     * plans on hold, set-up steps) copied to your pCloud `.bitchord-audio/`
     * folder as "README-on-hold.md", whenever they change.
     */
    private suspend fun syncNotes() {
        val base = AppSettings.webdavUrl.value
        if (!WebDavConfig.isConfigured(base)) return
        val text = com.music.bitchord.data.Http.client.newCall(
            okhttp3.Request.Builder().url("https://raw.githubusercontent.com/JuanmiRodas/Musichord/main/docs/ON_HOLD.md").build(),
        ).execute().use { r -> if (r.isSuccessful) r.body?.string() else null } ?: return
        val prefs = app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        val hash = text.hashCode()
        if (prefs.getInt("notesHash", 0) == hash) return
        val dir = WebDavConfig.normalizeUrl(base).trimEnd('/') + "/.bitchord-audio/"
        val u = AppSettings.webdavUsername.value
        val p = AppSettings.webdavPassword.value
        WebDavClient.ensureCollection(dir, u, p)
        val bytes = text.toByteArray()
        val ok = WebDavClient.putFile(dir + "README-on-hold.md", bytes.inputStream(), bytes.size.toLong(), "text/markdown", u, p, overwrite = true).isSuccess
        if (ok) prefs.edit().putInt("notesHash", hash).apply()
    }

    /** This run's log to pCloud (`.bitchord-audio/logs/`), the same file updated. */
    private suspend fun uploadSession() {
        val base = AppSettings.webdavUrl.value
        if (!WebDavConfig.isConfigured(base)) return
        val remote = WebDavConfig.normalizeUrl(base).trimEnd('/') + "/.bitchord-audio/logs/"
        val u = AppSettings.webdavUsername.value
        val p = AppSettings.webdavPassword.value
        WebDavClient.ensureCollection(WebDavConfig.normalizeUrl(base).trimEnd('/') + "/.bitchord-audio/", u, p)
        WebDavClient.ensureCollection(remote, u, p)
        val bytes = sessionText().toByteArray()
        WebDavClient.putFile(remote + sessionName, bytes.inputStream(), bytes.size.toLong(), "text/plain", u, p, overwrite = true)
    }

    /** Shares the reports and the logs (this run's and the last). */
    fun share(context: Context) {
        val d = dir ?: return
        runCatching { File(d, "session.log").writeText(header() + TrackLog.recent()) }
        val out = File(context.cacheDir, "shared/logs").apply { deleteRecursively(); mkdirs() }
        val files = (reportFiles().take(5) + listOfNotNull(
            File(d, "session.log").takeIf { it.isFile },
            File(d, "session-previous.log").takeIf { it.isFile },
        )).map { f -> File(out, f.name).also { f.copyTo(it, overwrite = true) } }
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("text/plain")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .putExtra(Intent.EXTRA_SUBJECT, "BitChord Audio logs")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "BitChord Audio logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

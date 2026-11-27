package com.music.bitchord.playback.smart

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import com.music.bitchord.data.Http
import com.music.bitchord.data.LikedMusicCache
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceMap
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.data.webdav.WebDavRepository
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * BitChord Audio: "is this file really that song?" answered by listening, as Shazam
 * does — before a file of yours (pCloud) or a download is taken as a YouTube Music
 * song for good (its tags filled, its link kept).
 *
 * Both are heard for their first minute or so and turned into an audio fingerprint
 * (Haitsma–Kalker: 32 bits every 23 ms, whether the energy in each of 33 bands rises or
 * falls against its neighbour). The same recording — whatever its encoding, loudness or
 * a second of lead-in — gives the same bits; another performance of the song (a live
 * take, a re-recording) gives about half of them wrong, as an unrelated song does.
 * More precise than a Shazam lookup, which names the song but can take a live take
 * for the studio one.
 *
 * Runs in the background on Wi-Fi, a pair at a time; verdicts are kept (and synced
 * with your other settings). A link heard to be another recording is undone.
 */
object AudioCheck {

    private const val TAG = "BitChord"
    const val FILE = "audio_checks.json"
    private const val RATE = 5512
    private const val FRAME = 2048
    private const val HOP = 128
    private const val SECONDS = 75.0

    enum class Verdict { SAME, DIFFERENT, UNKNOWN }

    data class Progress(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val same: Int = 0,
        val different: Int = 0,
        val unsure: Int = 0,
        val current: String? = null,
        /** Why it is not listening right now: see [Wait]. */
        val waiting: Wait? = null,
        val paused: Boolean = false,
        val lastRun: Long = 0L,
    )

    /** BitChord Audio: what the run is waiting for. */
    sealed interface Wait {
        data object Wifi : Wait
        data class Network(val names: Set<String>) : Wait
        data class Hours(val from: Int, val to: Int) : Wait
        data object Charging : Wait
        data object Paused : Wait
        /** You are using the app (or YouTube is limiting requests): it carries on afterwards. */
        data object InUse : Wait
    }

    /** When the check may run: any Wi-Fi by default; or the [wifiNames] given, between [fromHour] and [toHour], charging. */
    data class Schedule(
        val on: Boolean = false,
        val wifiNames: Set<String> = emptySet(),
        val fromHour: Int = 1,
        val toHour: Int = 7,
        val chargingOnly: Boolean = false,
    )

    /** BitChord Audio: one link heard — what, against which copy, the verdict, and how different it sounded. */
    data class Heard(val title: String, val copy: String, val verdict: Verdict, val ber: Double?, val at: Long)

    private val _history = MutableStateFlow<List<Heard>>(emptyList())
    /** What has been heard, newest first (the last 2000). */
    val history: StateFlow<List<Heard>> = _history.asStateFlow()
    private val _pending = MutableStateFlow<List<String>>(emptyList())
    /** What this pass still has to hear, in order. */
    val pending: StateFlow<List<String>> = _pending.asStateFlow()
    @Volatile private var historyFile: File? = null
    @Volatile private var lastBer: Double? = null

    private fun loadHistory() {
        val text = runCatching { historyFile?.takeIf { it.isFile }?.readText() }.getOrNull() ?: return
        runCatching {
            val a = org.json.JSONArray(text)
            _history.value = (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                Heard(o.optString("t"), o.optString("c"), runCatching { Verdict.valueOf(o.optString("v")) }.getOrDefault(Verdict.UNKNOWN),
                    o.optDouble("b").takeIf { !it.isNaN() }, o.optLong("at"))
            }
        }
    }

    private fun saveHistory() {
        val a = org.json.JSONArray()
        _history.value.forEach { h ->
            a.put(JSONObject().put("t", h.title).put("c", h.copy).put("v", h.verdict.name).put("at", h.at).apply { h.ber?.let { put("b", it) } })
        }
        runCatching { historyFile?.writeText(a.toString()) }
    }

    /** A readable name for [copy]: the pCloud file's path in the library, or "Download". */
    private fun copyName(copy: String): String =
        if (copy.startsWith("download:")) "download"
        else WebDavConfig.fileUrlOf(copy)?.let { u -> runCatching { java.net.URLDecoder.decode(u.substringAfterLast('/'), "UTF-8") }.getOrNull() } ?: copy

    private val _schedule = MutableStateFlow(Schedule())
    val schedule: StateFlow<Schedule> = _schedule.asStateFlow()
    private var prefs: android.content.SharedPreferences? = null

    fun setSchedule(s: Schedule) {
        _schedule.value = s
        prefs?.edit()?.apply {
            putBoolean("on", s.on); putStringSet("wifi", s.wifiNames)
            putInt("from", s.fromHour); putInt("to", s.toHour); putBoolean("charging", s.chargingOnly)
        }?.apply()
        app?.let { AudioCheckWorker.schedule(it) }
    }

    fun setPaused(paused: Boolean) {
        prefs?.edit()?.putBoolean("paused", paused)?.apply()
        _progress.value = _progress.value.copy(paused = paused)
        if (!paused) start(now = true)
    }

    /** The Wi-Fi network the phone is on, by name; null when not on Wi-Fi or the name is hidden (no location permission). */
    @Suppress("DEPRECATION")
    fun currentWifi(context: Context): String? = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wm.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" && it != "0x" }
    }.getOrNull()

    private fun charging(context: Context): Boolean = runCatching {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        bm.isCharging
    }.getOrDefault(false)

    /** BitChord Audio: the same conditions, for other night work (the library assistant). */
    fun waitFor(context: Context): Wait? = waitingFor(context, force = false)

    /** Null when it may listen now; else what it waits for. */
    private fun waitingFor(context: Context, force: Boolean): Wait? {
        if (prefs?.getBoolean("paused", false) == true) return Wait.Paused
        if (!wifi()) return Wait.Wifi
        // Never in battery saver, schedule or not.
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        if (pm?.isPowerSaveMode == true && !charging(context)) return Wait.Charging
        val sc = _schedule.value
        if (force) return null
        if (!sc.on) return if (com.music.bitchord.data.BackgroundHours.now()) null else Wait.Hours(sc.fromHour, sc.toHour)
        if (sc.wifiNames.isNotEmpty() && currentWifi(context)?.let { it in sc.wifiNames } != true) return Wait.Network(sc.wifiNames)
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val inHours = if (sc.fromHour == sc.toHour) true else if (sc.fromHour < sc.toHour) h in sc.fromHour until sc.toHour else h >= sc.fromHour || h < sc.toHour
        if (!inHours) return Wait.Hours(sc.fromHour, sc.toHour)
        if (sc.chargingOnly && !charging(context)) return Wait.Charging
        // BitChord Audio: night work steps aside while you use the app, or while YouTube is limiting.
        if (com.music.bitchord.data.BackgroundHours.inUse() || com.music.bitchord.data.YouTubeLimit.active()) return Wait.InUse
        return null
    }

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val verdicts = ConcurrentHashMap<String, Pair<Verdict, Long>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    @Volatile private var store: File? = null
    @Volatile private var cache: File? = null
    @Volatile private var app: Context? = null

    private fun key(songId: String, copy: String) = "$songId|$copy"

    fun verdict(songId: String, copy: String): Verdict? = verdicts[key(songId, copy)]?.first

    /** Heard to be the same recording. */
    fun confirmed(songId: String, copy: String) = verdict(songId, copy) == Verdict.SAME

    /** Heard to be another recording: never linked again. */
    fun rejected(songId: String, copy: String) = verdict(songId, copy) == Verdict.DIFFERENT

    fun init(context: Context) {
        app = context.applicationContext
        store = File(context.filesDir, FILE)
        prefs = context.applicationContext.getSharedPreferences("bitchord_audio_check", Context.MODE_PRIVATE).also { p ->
            _schedule.value = Schedule(
                // BitChord Audio: by default only while charging (any hour, any Wi-Fi) — listening
                // through thousands of links is too much work for a phone on its battery.
                on = p.getBoolean("on", true),
                wifiNames = p.getStringSet("wifi", emptySet()).orEmpty().toSet(),
                fromHour = p.getInt("from", 0),
                toHour = p.getInt("to", 0),
                chargingOnly = p.getBoolean("charging", true),
            )
            // BitChord Audio: nothing heavy by day — the hours move to the night once.
            if (!p.getBoolean("night_v1", false)) {
                _schedule.value = _schedule.value.copy(on = true, fromHour = 1, toHour = 7)
                p.edit().putBoolean("night_v1", true).putBoolean("on", true).putInt("from", 1).putInt("to", 7).apply()
            }
            _progress.value = _progress.value.copy(paused = p.getBoolean("paused", false), lastRun = p.getLong("last", 0L))
        }
        AudioCheckWorker.schedule(context.applicationContext)
        cache = File(context.cacheDir, "audio-check").apply { mkdirs() }
        historyFile = File(context.filesDir, "audio_checks_history.json")
        scope.launch { load(); loadHistory() }
    }

    /** Read again (after a restore from pCloud). */
    fun load() {
        val text = runCatching { store?.takeIf { it.isFile }?.readText() }.getOrNull() ?: return
        runCatching {
            val o = JSONObject(text)
            o.keys().forEach { k ->
                val v = o.getString(k).split(':')
                val verdict = runCatching { Verdict.valueOf(v[0]) }.getOrNull() ?: return@forEach
                verdicts[k] = verdict to (v.getOrNull(1)?.toLongOrNull() ?: 0L)
            }
        }
        refreshCounts()
    }

    private fun save() {
        val o = JSONObject()
        verdicts.forEach { (k, v) -> o.put(k, "${v.first.name}:${v.second}") }
        runCatching { store?.writeText(o.toString()) }
        app?.let { com.music.bitchord.data.sync.SettingsSync.changed(it) }
    }

    private fun refreshCounts() {
        val all = verdicts.values.map { it.first }
        _progress.value = _progress.value.copy(
            same = all.count { it == Verdict.SAME },
            different = all.count { it == Verdict.DIFFERENT },
            unsure = all.count { it == Verdict.UNKNOWN },
        )
    }

    private fun wifi() = AppSettings.meteredConnection.value == false

    /**
     * Listens through every link of your library — liked songs ↔ pCloud files, and
     * downloads that came from another source than YouTube — a pair at a time, quietly.
     */
    /** Listening now, outside the schedule's hours and Wi-Fi (still on Wi-Fi), until this pass ends. */
    @Volatile private var forced = false

    fun runNow() {
        forced = true
        prefs?.edit()?.putBoolean("paused", false)?.apply()
        _progress.value = _progress.value.copy(paused = false)
        start(now = true)
    }

    fun start(now: Boolean = false) {
        if (job?.isActive == true) return
        val context = app ?: return
        job = scope.launch {
            if (!now) delay(120_000)
            val week = 7L * 24 * 3600 * 1000
            val now = System.currentTimeMillis()
            fun due(k: String) = verdicts[k]?.let { (v, at) -> v == Verdict.UNKNOWN && now - at > week } ?: true
            val liked = LikedMusicCache.likedSongs.value.associateBy { it.videoId }
            val pairs = ArrayList<Triple<String, String, String>>() // song, copy id, title
            SourceMap.fileLinks().forEach { (song, file) ->
                if (due(key(song, file))) pairs += Triple(song, file, liked[song]?.title ?: song)
            }
            Downloads.savedMetadataSongs().forEach { m ->
                val uri = m.localUri ?: return@forEach
                if (WebDavConfig.isWebDavId(m.videoId) || m.isVideo) return@forEach
                val copy = "download:$uri"
                if (due(key(m.videoId, copy))) pairs += Triple(m.videoId, copy, m.title)
            }
            _progress.value = _progress.value.copy(running = true, done = 0, total = pairs.size)
            _pending.value = pairs.map { it.third }
            TrackLog.d(TAG, "audio check: ${pairs.size} links to listen to")
            for ((i, p) in pairs.withIndex()) {
                while (true) {
                    val w = waitingFor(context, force = forced)
                    _progress.value = _progress.value.copy(waiting = w, current = if (w == null) _progress.value.current else null)
                    if (w == null) break
                    delay(30_000)
                }
                val (song, copy, title) = p
                _progress.value = _progress.value.copy(current = title)
                val verdict = runCatching { check(context, song, copy) }
                    .onFailure { TrackLog.w(TAG, "audio check: '$title' failed: ${it.message}") }
                    .getOrDefault(Verdict.UNKNOWN)
                verdicts[key(song, copy)] = verdict to System.currentTimeMillis()
                _history.value = (listOf(Heard(title, copyName(copy), verdict, lastBer, System.currentTimeMillis())) + _history.value).take(2000)
                _pending.value = pairs.drop(i + 1).map { it.third }
                lastBer = null
                saveHistory()
                if (verdict == Verdict.DIFFERENT && !copy.startsWith("download:")) {
                    SourceMap.forget(song)
                    TrackLog.d(TAG, "audio check: '$title' — your file is another recording; unlinked")
                } else if (verdict == Verdict.DIFFERENT) {
                    TrackLog.d(TAG, "audio check: '$title' — the download is another recording")
                }
                save()
                refreshCounts()
                _progress.value = _progress.value.copy(done = i + 1)
                delay(2_000)
            }
            save()
            val last = System.currentTimeMillis()
            prefs?.edit()?.putLong("last", last)?.apply()
            forced = false
            _progress.value = _progress.value.copy(running = false, current = null, waiting = null, lastRun = last)
            // What was heard to be the same can now take its details.
            com.music.bitchord.data.covers.MetadataFill.start(context)
            TrackLog.d(TAG, "audio check: done — ${_progress.value.same} same, ${_progress.value.different} other recordings, ${_progress.value.unsure} unsure")
        }
    }

    /** For the background job: runs (or joins) a pass, waiting at most [maxMs]. */
    suspend fun runFor(maxMs: Long) {
        val context = app ?: return
        if (waitingFor(context, force = false) != null) return
        start(now = true)
        kotlinx.coroutines.withTimeoutOrNull(maxMs) { job?.join() }
    }

    // ── Listening ──

    private suspend fun check(context: Context, songId: String, copy: String): Verdict {
        val dir = cache ?: return Verdict.UNKNOWN
        val ytFile = File(dir, "yt.audio")
        val fileCopy = File(dir, "copy.audio")
        try {
            val got = com.music.bitchord.data.innertube.InnerTubeXResolver.extract(songId, 64) ?: return Verdict.UNKNOWN
            if (!fetch(got.url, got.headers, ytFile, 2L shl 20)) return Verdict.UNKNOWN
            val songPrint = print(FileSource(ytFile)) ?: return Verdict.UNKNOWN
            val copyPrint = if (copy.startsWith("download:")) {
                val uri = Uri.parse(copy.removePrefix("download:"))
                val src = LocalAudioSource.open(context.contentResolver, uri) ?: return Verdict.UNKNOWN
                print(src)
            } else {
                val url = WebDavConfig.fileUrlOf(copy) ?: return Verdict.UNKNOWN
                val auth = WebDavConfig.basicAuthHeader(AppSettings.webdavUsername.value, AppSettings.webdavPassword.value)
                if (!fetch(url, auth?.let { mapOf("Authorization" to it) }.orEmpty(), fileCopy, 12L shl 20)) return Verdict.UNKNOWN
                print(FileSource(fileCopy))
            } ?: return Verdict.UNKNOWN
            val ber = bestBer(songPrint, copyPrint) ?: return Verdict.UNKNOWN
            lastBer = ber
            val title = WebDavRepository.library().firstOrNull { it.videoId == copy }?.title ?: songId
            TrackLog.d(TAG, "audio check: '$title' bit error ${"%.2f".format(ber)}")
            return when {
                ber < 0.33 -> Verdict.SAME
                ber > 0.42 -> Verdict.DIFFERENT
                else -> Verdict.UNKNOWN
            }
        } finally {
            ytFile.delete()
            fileCopy.delete()
        }
    }

    private fun fetch(url: String, headers: Map<String, String>, to: File, cap: Long): Boolean {
        val chunk = 1L shl 20
        var at = 0L
        to.outputStream().use { out ->
            while (at < cap) {
                val req = Request.Builder().url(url)
                    .apply { headers.forEach { (k, v) -> header(k, v) } }
                    .header("Range", "bytes=$at-${at + chunk - 1}")
                    .build()
                val got = Http.client.newCall(req).execute().use { r ->
                    if (r.code !in 200..299) return at > 0
                    val bytes = r.body?.bytes() ?: return at > 0
                    out.write(bytes)
                    bytes.size
                }
                at += got
                if (got < chunk) break
            }
        }
        return at > 0
    }

    private class FileSource(file: File) : MediaDataSource() {
        private val raf = RandomAccessFile(file, "r")
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= raf.length()) return -1
            raf.seek(position)
            return raf.read(buffer, offset, size)
        }
        override fun getSize(): Long = raf.length()
        override fun close() = raf.close()
    }

    // ── The fingerprint ──

    /** BitChord Audio: [source]'s fingerprint (its first ~75 s), for [SameRecording] too. */
    internal fun printOf(source: MediaDataSource): IntArray? = print(source)

    private fun print(source: MediaDataSource): IntArray? = try {
        val pcm = AudioDecoder.decodeDownsampled(source, 0.0, SECONDS, RATE)?.first
        pcm?.takeIf { it.samples.size > it.sampleRate * 20 }?.let { fingerprint(it.samples, it.sampleRate) }
    } finally {
        runCatching { source.close() }
    }

    /**
     * BitChord Audio: how different [download] and [file] sound (the lowest bit error over
     * every shift, as the link check measures it): under 0.33 the same recording, over
     * 0.42 another one. Null when either can't be read.
     */
    internal fun berBetween(context: Context, download: Uri, file: File): Double? {
        val a = LocalAudioSource.open(context.contentResolver, download)?.let { print(it) } ?: return null
        val b = print(FileSource(file)) ?: return null
        return bestBer(a, b)
    }

    const val SAME_BELOW = 0.33

    /**
     * BitChord Audio: stricter, for replacing one file with another — at least 80 % of the
     * bits alike. In the checks made so far the same recording lands at 0.00–0.20 and
     * another one at 0.45–0.50, so this is the gap between them.
     */
    const val REPLACE_BELOW = 0.20
    const val OTHER_ABOVE = 0.42

    /** 32 bits a frame: the sign of each band's energy step, against the frame before. */
    internal fun fingerprint(samples: FloatArray, rate: Double): IntArray {
        val window = FloatArray(FRAME) { (0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1))).toFloat() }
        val edges = IntArray(34) { b ->
            val hz = 300.0 * Math.pow(2000.0 / 300.0, b / 33.0)
            (hz * FRAME / rate).toInt().coerceIn(1, FRAME / 2 - 1)
        }
        val frames = (samples.size - FRAME) / HOP
        if (frames < 2) return IntArray(0)
        val re = FloatArray(FRAME)
        val im = FloatArray(FRAME)
        var prev = DoubleArray(33)
        val out = IntArray(frames - 1)
        for (f in 0 until frames) {
            val at = f * HOP
            for (i in 0 until FRAME) { re[i] = samples[at + i] * window[i]; im[i] = 0f }
            fft(re, im)
            val e = DoubleArray(33) { b ->
                var s = 0.0
                for (k in edges[b] until max(edges[b] + 1, edges[b + 1])) s += (re[k] * re[k] + im[k] * im[k]).toDouble()
                ln(s + 1e-9)
            }
            if (f > 0) {
                var bits = 0
                for (b in 0 until 32) {
                    val d = (e[b] - e[b + 1]) - (prev[b] - prev[b + 1])
                    if (d > 0) bits = bits or (1 shl b)
                }
                out[f - 1] = bits
            }
            prev = e
        }
        return out
    }

    /** The lowest share of differing bits over every shift up to ±15 s. */
    internal fun bestBer(a: IntArray, b: IntArray): Double? {
        val maxLag = (15.0 * RATE / HOP).toInt()
        val minOverlap = (20.0 * RATE / HOP).toInt()
        var best: Double? = null
        for (lag in -maxLag..maxLag) {
            val startA = max(0, lag)
            val startB = max(0, -lag)
            val n = min(a.size - startA, b.size - startB)
            if (n < minOverlap) continue
            var wrong = 0L
            for (i in 0 until n) wrong += Integer.bitCount(a[startA + i] xor b[startB + i])
            val ber = wrong / (32.0 * n)
            if (best == null || ber < best) best = ber
        }
        return best
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                val half = len / 2
                for (k in 0 until half) {
                    val xr = re[i + k + half] * cr - im[i + k + half] * ci
                    val xi = re[i + k + half] * ci + im[i + k + half] * cr
                    re[i + k + half] = re[i + k] - xr
                    im[i + k + half] = im[i + k] - xi
                    re[i + k] += xr
                    im[i + k] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}


/**
 * BitChord Audio: the listening check when the app is closed — every hour, on an unmetered
 * network (and charging when asked); it listens only inside the schedule's hours and on its
 * Wi-Fi, for up to 9 minutes a time, carrying on where it left off.
 */
class AudioCheckWorker(context: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AudioCheck.runFor(9 * 60_000L)
        return Result.success()
    }

    companion object {
        private const val NAME = "bitchord-audio-check"

        fun schedule(context: Context) {
            val sc = AudioCheck.schedule.value
            val wm = androidx.work.WorkManager.getInstance(context)
            if (!sc.on) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
                .setRequiresCharging(sc.chargingOnly)
                .build()
            val req = androidx.work.PeriodicWorkRequestBuilder<AudioCheckWorker>(1, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, androidx.work.ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}

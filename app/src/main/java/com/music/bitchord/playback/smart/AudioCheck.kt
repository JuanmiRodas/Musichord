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
    )

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
        cache = File(context.cacheDir, "audio-check").apply { mkdirs() }
        scope.launch { load() }
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
    fun start() {
        if (job?.isActive == true) return
        val context = app ?: return
        job = scope.launch {
            delay(120_000)
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
            TrackLog.d(TAG, "audio check: ${pairs.size} links to listen to")
            for ((i, p) in pairs.withIndex()) {
                while (!wifi()) {
                    _progress.value = _progress.value.copy(current = null)
                    delay(60_000)
                }
                val (song, copy, title) = p
                _progress.value = _progress.value.copy(current = title)
                val verdict = runCatching { check(context, song, copy) }
                    .onFailure { TrackLog.w(TAG, "audio check: '$title' failed: ${it.message}") }
                    .getOrDefault(Verdict.UNKNOWN)
                verdicts[key(song, copy)] = verdict to System.currentTimeMillis()
                if (verdict == Verdict.DIFFERENT && !copy.startsWith("download:")) {
                    SourceMap.forget(song)
                    TrackLog.d(TAG, "audio check: '$title' — your file is another recording; unlinked")
                } else if (verdict == Verdict.DIFFERENT) {
                    TrackLog.d(TAG, "audio check: '$title' — the download is another recording")
                }
                if (i % 10 == 9) save()
                refreshCounts()
                _progress.value = _progress.value.copy(done = i + 1)
                delay(2_000)
            }
            save()
            _progress.value = _progress.value.copy(running = false, current = null)
            // What was heard to be the same can now take its details.
            com.music.bitchord.data.covers.MetadataFill.start(context)
            TrackLog.d(TAG, "audio check: done — ${_progress.value.same} same, ${_progress.value.different} other recordings, ${_progress.value.unsure} unsure")
        }
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

    private fun print(source: MediaDataSource): IntArray? = try {
        val pcm = AudioDecoder.decodeDownsampled(source, 0.0, SECONDS, RATE)?.first
        pcm?.takeIf { it.samples.size > it.sampleRate * 20 }?.let { fingerprint(it.samples, it.sampleRate) }
    } finally {
        runCatching { source.close() }
    }

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

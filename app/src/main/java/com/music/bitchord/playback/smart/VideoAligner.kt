package com.music.bitchord.playback.smart

import android.content.Context
import android.media.MediaDataSource
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * BitChord Audio: video mode's sync for a video that isn't the same recording
 * as the song — a live performance, a remaster, a music video cut with its
 * own pauses.
 *
 * The song (as playing, from the cache or the device) and the video's own
 * sound are each turned into a chromagram — how much of each of the twelve
 * notes sounds, two and a half times a second — and the two are aligned by dynamic
 * time warping: the cheapest path through both, free to start and end
 * anywhere in the video, slower or faster in places. The path is the
 * timeline: for every moment of the song, the moment of the video playing
 * the same music. Harmony survives a different band, room and tempo, which
 * is why this finds a live take where matching loudness can't.
 *
 * A path whose notes don't really agree (another song, a medley) is
 * discarded: the picture then follows the song's clock as before.
 */
object VideoAligner {
    private const val TAG = "VideoMode"
    private const val RATE = 11025
    private const val FFT = 4096
    private const val HOP_SEC = 0.4
    private const val MAX_SECONDS = 720.0
    /** Mean agreement (centred-chroma cosine) along the path below which it isn't the same music. */
    private const val MIN_AGREEMENT = 0.28

    /** song ms → video ms, sampled every [HOP_SEC], and linear in between. */
    class Timeline(internal val songMs: LongArray, internal val videoMs: LongArray) {
        var sections = 0
        var cuts = 0
        fun describe() = "$sections section(s), $cuts cut(s), starts at +${if (songMs.isEmpty()) 0 else (videoMs[0] - songMs[0]) / 1000.0}s"
        fun map(audioMs: Long): Long {
            if (songMs.isEmpty()) return audioMs
            if (audioMs <= songMs[0]) return videoMs[0] - (songMs[0] - audioMs)
            val last = songMs.size - 1
            if (audioMs >= songMs[last]) return videoMs[last] + (audioMs - songMs[last])
            var lo = 0
            var hi = last
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (songMs[mid] <= audioMs) lo = mid else hi = mid
            }
            val f = (audioMs - songMs[lo]).toDouble() / (songMs[hi] - songMs[lo]).coerceAtLeast(1)
            return (videoMs[lo] + f * (videoMs[hi] - videoMs[lo])).toLong()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val results = ConcurrentHashMap<String, Timeline>()
    /** Pairs being aligned, or settled (aligned, or not the same music). */
    private val settled = ConcurrentHashMap.newKeySet<String>()
    /** When a pair last couldn't be tried (no audio for the song yet); asked again after a while. */
    private val waiting = ConcurrentHashMap<String, Long>()

    fun timeline(mediaId: String, videoKey: String): Timeline? = results["$mediaId|$videoKey"]

    // ── Kept: on the device, and (see CloudBackup) in pCloud ──

    const val FILE = "video-sync-v2.json"
    @Volatile private var store: File? = null

    /** Reads the syncs found before; each is measured once, ever. */
    fun init(context: Context) {
        val f = File(context.filesDir, FILE)
        store = f
        scope.launch(Dispatchers.IO) { load(f) }
    }

    /** (Again) after a restore from the backup. */
    fun reload(context: Context) {
        scope.launch(Dispatchers.IO) { load(File(context.filesDir, FILE)) }
    }

    private fun load(f: File) {
        val text = runCatching { f.takeIf { it.isFile }?.readText() }.getOrNull() ?: return
        runCatching {
            val o = org.json.JSONObject(text)
            o.keys().forEach { k ->
                val e = o.getJSONObject(k)
                val a = e.getJSONArray("s")
                val b = e.getJSONArray("v")
                val n = min(a.length(), b.length())
                results[k] = Timeline(LongArray(n) { a.getLong(it) }, LongArray(n) { b.getLong(it) })
                settled += k
            }
        }
        TrackLog.d(TAG, "video sync: ${results.size} kept")
    }

    @Synchronized
    private fun save() {
        val f = store ?: return
        val o = org.json.JSONObject()
        results.forEach { (k, t) ->
            // Every second is plenty to draw the line through.
            val step = max(1, (1000 / (HOP_SEC * 1000)).toInt())
            val idx = (t.songMs.indices step step).toList()
            o.put(k, org.json.JSONObject().apply {
                put("s", org.json.JSONArray(idx.map { t.songMs[it] }))
                put("v", org.json.JSONArray(idx.map { t.videoMs[it] }))
            })
        }
        runCatching { f.writeText(o.toString()) }
    }

    /**
     * Aligns [song] with the video whose sound is at [audioUrl], once per
     * pair, in the background. The song is read from the device or the cache
     * when it's there, else fetched through [songSound] — a pCloud file isn't
     * cached the way a stream is.
     */
    fun request(
        context: Context,
        song: Song,
        videoKey: String,
        audioUrl: String,
        headers: Map<String, String>,
        songSound: (suspend () -> Pair<String, Map<String, String>>?)? = null,
    ) {
        val key = "${song.videoId}|$videoKey"
        waiting[key]?.let { if (System.currentTimeMillis() - it < 30_000) return }
        if (!settled.add(key)) return
        waiting.remove(key)
        val app = context.applicationContext
        scope.launch {
            val started = System.currentTimeMillis()
            val outcome = runCatching { align(app, song, audioUrl, headers, songSound) }
                .onFailure { TrackLog.w(TAG, "video sync for '${song.title}' failed: ${it.message}") }
                .getOrNull()
            when (outcome) {
                is Outcome.Aligned -> {
                    results[key] = outcome.timeline
                    runCatching { save() }
                    TrackLog.d(TAG, "video sync for '${song.title}': aligned in ${System.currentTimeMillis() - started}ms")
                }
                Outcome.NoAudio, null -> {
                    // Not settled: asked again when the picture next loads, or a little later.
                    settled.remove(key)
                    waiting[key] = System.currentTimeMillis()
                }
                Outcome.Different -> Unit
            }
        }
    }

    private sealed interface Outcome {
        class Aligned(val timeline: Timeline) : Outcome
        data object NoAudio : Outcome
        data object Different : Outcome
    }

    private suspend fun align(
        context: Context,
        song: Song,
        audioUrl: String,
        headers: Map<String, String>,
        songSound: (suspend () -> Pair<String, Map<String, String>>?)?,
    ): Outcome {
        val songPcm = VersionAudioAligner.openDataSource(context, song)?.let { source ->
            try {
                AudioDecoder.decodeDownsampled(source, 0.0, MAX_SECONDS, RATE)
            } finally {
                runCatching { source.close() }
            }
        }?.takeIf { it.first.samples.size > RATE * 30 } ?: run {
            // Not on the device (a pCloud file streams past the cache): the
            // same song's own release, fetched small.
            val (url, h) = songSound?.invoke() ?: run {
                TrackLog.d(TAG, "video sync for '${song.title}': no audio of the song to compare yet")
                return Outcome.NoAudio
            }
            val f = File(context.cacheDir, "video-sync-song-${url.hashCode().toUInt()}.audio")
            try {
                if (!download(url, h, f)) return Outcome.NoAudio
                val src = FileSource(f)
                try {
                    AudioDecoder.decodeDownsampled(src, 0.0, MAX_SECONDS, RATE)
                } finally {
                    runCatching { src.close() }
                }
            } finally {
                f.delete()
            }
        } ?: return Outcome.NoAudio
        val file = File(context.cacheDir, "video-sync-${audioUrl.hashCode().toUInt()}.audio")
        try {
            if (!download(audioUrl, headers, file)) return Outcome.NoAudio
            val videoSource = FileSource(file)
            val videoPcm = try {
                AudioDecoder.decodeDownsampled(videoSource, 0.0, MAX_SECONDS, RATE)
            } finally {
                runCatching { videoSource.close() }
            } ?: return Outcome.NoAudio

            val a = chroma(songPcm.first.samples, songPcm.first.sampleRate)
            val b0 = chroma(videoPcm.first.samples, videoPcm.first.sampleRate)
            if (a.size < 50 || b0.size < 50) return Outcome.NoAudio
            // A live take is often sung lower (or higher): the video's notes are
            // turned to the song's key first.
            val shift = keyShift(a, b0)
            val b = if (shift == 0) b0 else Array(b0.size) { i -> FloatArray(12) { k -> b0[i][(k + shift) % 12] } }
            val songStart = songPcm.second
            val videoStart = videoPcm.second
            val path = dtw(a, b) ?: return Outcome.Different
            val agreement = path.mean
            TrackLog.d(
                TAG,
                "video sync for '${song.title}': agreement ${"%.2f".format(agreement)} over ${a.size}×${b.size} frames" +
                    (if (shift != 0) ", key shifted $shift" else ""),
            )
            if (agreement < MIN_AGREEMENT) return Outcome.Different
            val timeline = timelineOf(path, a.size, songStart, videoStart)
            TrackLog.d(TAG, "video sync for '${song.title}': ${timeline.describe()}")
            return Outcome.Aligned(timeline)
        } finally {
            file.delete()
        }
    }

    // ── The video's sound, fetched whole in bounded ranges (googlevideo throttles open-ended ones) ──

    private fun download(url: String, headers: Map<String, String>, to: File): Boolean {
        val chunk = 1L shl 20
        var at = 0L
        to.outputStream().use { out ->
            while (at < 40L * chunk) {
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

    // ── Chroma ──

    /** Twelve-note chroma every [HOP_SEC], centred and unit length (a silent frame is zero). */
    private fun chroma(samples: FloatArray, sampleRate: Double): Array<FloatArray> {
        // Down to ~11 kHz by averaging: plenty for the notes, and a quarter of the work.
        val step = max(1, (sampleRate / RATE).toInt())
        val rate = sampleRate / step
        val n = samples.size / step
        val x = FloatArray(n) { i ->
            var s = 0f
            val base = i * step
            for (k in 0 until step) s += samples[base + k]
            s / step
        }
        val hop = (rate * HOP_SEC).roundToInt().coerceAtLeast(1)
        val frames = ((n - FFT) / hop).coerceAtLeast(0)
        val window = FloatArray(FFT) { (0.5 - 0.5 * cos(2 * PI * it / (FFT - 1))).toFloat() }
        // Which note each FFT bin belongs to, 55 Hz – 4 kHz.
        val binNote = IntArray(FFT / 2) { k ->
            val f = k * rate / FFT
            if (f < 55.0 || f > 4000.0) -1 else (((12 * ln(f / 440.0) / ln(2.0)).roundToInt() % 12 + 12 + 9) % 12)
        }
        val re = FloatArray(FFT)
        val im = FloatArray(FFT)
        return Array(frames) { fIdx ->
            val start = fIdx * hop
            for (i in 0 until FFT) {
                re[i] = x[start + i] * window[i]
                im[i] = 0f
            }
            fft(re, im)
            val c = FloatArray(12)
            for (k in 1 until FFT / 2) {
                val note = binNote[k]
                if (note >= 0) c[note] += sqrt(re[k] * re[k] + im[k] * im[k])
            }
            // Log-compressed, and each frame scaled to its own total: a
            // frame's balance of notes, not its loudness.
            var total = 0f
            for (i in 0 until 12) {
                c[i] = ln(1f + 10f * c[i])
                total += c[i]
            }
            if (total > 1e-6f) for (i in 0 until 12) c[i] /= total
            c
        }.let(::smoothed)
    }

    /**
     * Each frame averaged with its neighbours (about two seconds) and then
     * centred to unit length: the harmony of a passage rather than of an
     * instant, which is what a different performance keeps — the CENS idea.
     */
    private fun smoothed(raw: Array<FloatArray>): Array<FloatArray> {
        val w = 2
        return Array(raw.size) { i ->
            val c = FloatArray(12)
            val lo = max(0, i - w)
            val hi = min(raw.size - 1, i + w)
            for (j in lo..hi) for (k in 0 until 12) c[k] += raw[j][k]
            var mean = 0f
            for (k in 0 until 12) mean += c[k]
            mean /= 12
            var norm = 0f
            for (k in 0 until 12) {
                c[k] -= mean
                norm += c[k] * c[k]
            }
            norm = sqrt(norm)
            if (norm > 1e-6f) for (k in 0 until 12) c[k] /= norm else c.fill(0f)
            c
        }
    }

    /** The rotation of [b]'s notes that best matches [a]'s overall harmony. */
    private fun keyShift(a: Array<FloatArray>, b: Array<FloatArray>): Int {
        fun profile(x: Array<FloatArray>) = FloatArray(12) { k -> x.sumOf { it[k].toDouble() }.toFloat() / x.size }
        val pa = profile(a)
        val pb = profile(b)
        return (0 until 12).maxByOrNull { s -> (0 until 12).sumOf { k -> (pa[k] * pb[(k + s) % 12]).toDouble() } } ?: 0
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
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
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    // ── Dynamic time warping, free start and end in the video ──

    /** For each song frame, its video frame; and how well the notes agreed along the way. */
    private class Path(val video: IntArray, val agree: FloatArray, val mean: Double)

    private fun dtw(a: Array<FloatArray>, b: Array<FloatArray>): Path? {
        val n = a.size
        val m = b.size
        if (n.toLong() * m > 4_000_000L) return null
        fun cost(i: Int, j: Int): Float {
            var dot = 0f
            val x = a[i]
            val y = b[j]
            for (k in 0 until 12) dot += x[k] * y[k]
            return 1f - dot
        }
        // Steps (1,1), (1,2), (2,1): a path may run up to twice as fast or slow,
        // never stand still on one side — which is what keeps it honest.
        val d = Array(n) { FloatArray(m) { Float.POSITIVE_INFINITY } }
        val back = Array(n) { ByteArray(m) }
        for (j in 0 until m) d[0][j] = cost(0, j)
        for (i in 1 until n) {
            val row = d[i]
            for (j in 1 until m) {
                val c = cost(i, j)
                var best = d[i - 1][j - 1] + c
                var dir: Byte = 0
                if (j >= 2) {
                    val v = d[i - 1][j - 2] + c * 1.5f
                    if (v < best) { best = v; dir = 1 }
                }
                if (i >= 2) {
                    val v = d[i - 2][j - 1] + c * 1.5f
                    if (v < best) { best = v; dir = 2 }
                }
                row[j] = best
                back[i][j] = dir
            }
        }
        var j = (0 until m).minByOrNull { d[n - 1][it] } ?: return null
        if (d[n - 1][j].isInfinite()) return null
        val map = IntArray(n)
        val agree = FloatArray(n)
        var i = n - 1
        var agreement = 0.0
        var steps = 0
        while (i >= 0 && j >= 0) {
            map[i] = j
            agree[i] = 1f - cost(i, j)
            agreement += 1.0 - cost(i, j)
            steps++
            if (i == 0) break
            when (back[i][j].toInt()) {
                0 -> { i -= 1; j -= 1 }
                1 -> { i -= 1; j -= 2 }
                else -> {
                    map[i - 1] = j
                    agree[i - 1] = 1f - cost(i - 1, j)
                    i -= 2; j -= 1
                }
            }
            if (j < 0) return null
        }
        return Path(map, agree, agreement / steps.coerceAtLeast(1))
    }

    /**
     * The path as a timeline that holds still. A warping path wobbles by a
     * frame or two all along — followed literally, the picture would be
     * re-placed every few seconds. So the path is read as *offsets* (video
     * minus song), and:
     *  - stretches where the notes don't really agree (a solo played
     *    differently, crowd noise) don't get a say: they keep the offset of
     *    the nearest stretch that does;
     *  - the offset is smoothed over ~10 s, then cut into sections that each
     *    keep one offset while it stays within ±0.8 s of where it began;
     *  - neighbouring sections close together (a live band speeding up)
     *    are joined by a ramp the picture follows by playing a little faster
     *    or slower; only a real cut — a skit, a break, an extended intro —
     *    is a jump, and the picture fades through it.
     */
    private fun timelineOf(path: Path, frames: Int, songStartSec: Double, videoStartSec: Double): Timeline {
        val hopMs = HOP_SEC * 1000
        val raw = IntArray(frames) { path.video[it] - it }
        // Local agreement, over ~10 s.
        val win = (10.0 / HOP_SEC).toInt()
        val local = FloatArray(frames) { i ->
            val lo = max(0, i - win / 2)
            val hi = min(frames - 1, i + win / 2)
            var sum = 0f
            for (k in lo..hi) sum += path.agree[k]
            sum / (hi - lo + 1)
        }
        val trusted = BooleanArray(frames) { local[it] >= max(MIN_AGREEMENT.toFloat(), (path.mean * 0.8).toFloat()) }
        val offset = IntArray(frames)
        if (trusted.none { it }) {
            raw.copyInto(offset)
        } else {
            // Untrusted frames take the nearest trusted frame's offset.
            var last = -1
            val nextTrusted = IntArray(frames) { -1 }
            var nt = -1
            for (i in frames - 1 downTo 0) {
                if (trusted[i]) nt = i
                nextTrusted[i] = nt
            }
            for (i in 0 until frames) {
                if (trusted[i]) {
                    last = i
                    offset[i] = raw[i]
                } else {
                    val n = nextTrusted[i]
                    val pick = when {
                        last < 0 -> n
                        n < 0 -> last
                        i - last <= n - i -> last
                        else -> n
                    }
                    offset[i] = raw[pick]
                }
            }
        }
        // Running median over ~10 s.
        val smooth = IntArray(frames) { i ->
            val lo = max(0, i - win / 2)
            val hi = min(frames - 1, i + win / 2)
            offset.copyOfRange(lo, hi + 1).sorted()[(hi - lo) / 2]
        }
        // Sections of one offset.
        val tolerance = max(1, (0.8 / HOP_SEC).toInt())
        data class Section(val start: Int, var end: Int, var value: Int)
        val sections = ArrayList<Section>()
        var startAt = 0
        while (startAt < frames) {
            val anchor = smooth[startAt]
            var end = startAt
            while (end + 1 < frames && abs(smooth[end + 1] - anchor) <= tolerance) end++
            val median = smooth.copyOfRange(startAt, end + 1).sorted()[(end - startAt) / 2]
            sections += Section(startAt, end, median)
            startAt = end + 1
        }
        // Too-short sections (under ~8 s) are folded into a neighbour.
        val minLen = (8.0 / HOP_SEC).toInt()
        var k = 0
        while (sections.size > 1 && k < sections.size) {
            val sct = sections[k]
            if (sct.end - sct.start + 1 < minLen) {
                val into = if (k > 0) k - 1 else k + 1
                val other = sections[into]
                if (into < k) other.end = sct.end else sections[into] = Section(sct.start, other.end, other.value)
                sections.removeAt(k)
                k = max(0, k - 1)
            } else k++
        }
        // Offsets per frame: flat within a section; between close sections a
        // ramp over the last ~6 s; a real cut stays a step.
        val ramp = (6.0 / HOP_SEC).toInt()
        val jump = (1.5 / HOP_SEC).toInt()
        val final = DoubleArray(frames)
        for ((index, sct) in sections.withIndex()) {
            for (f in sct.start..sct.end) final[f] = sct.value.toDouble()
            val next = sections.getOrNull(index + 1) ?: continue
            if (abs(next.value - sct.value) > jump) continue
            val from = max(sct.start, sct.end - ramp + 1)
            for (f in from..sct.end) {
                val t = (f - from + 1).toDouble() / (sct.end - from + 2)
                final[f] = sct.value + t * (next.value - sct.value)
            }
        }
        jumps = sections.zipWithNext().count { (x, y) -> abs(y.value - x.value) > jump }
        val songMs = LongArray(frames) { ((songStartSec * 1000) + it * hopMs).toLong() }
        val videoMs = LongArray(frames) { ((videoStartSec * 1000) + (it + final[it]) * hopMs).toLong() }
        return Timeline(songMs, videoMs).also { it.sections = sections.size; it.cuts = jumps }
    }

    @Volatile private var jumps = 0

}

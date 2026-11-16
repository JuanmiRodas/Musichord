package com.music.bitchord.playback.smart

import android.content.Context
import android.media.MediaDataSource
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.max
import kotlin.math.sqrt

/**
 * BitChord Audio: is another source's copy the song that is playing — the
 * same recording — and what is it really worth?
 *
 * Asked before a playing song is swapped for a "better" copy found by name
 * (JioSaavn, SoundCloud, bilibili, an addon): a title and an artist can agree
 * while the music doesn't (another band's "Grip", a music-box cover filed
 * under the singer, a live take). Both openings are listened to: the song's
 * own bytes already on the phone, and the candidate's first megabytes. Their
 * notes (12-tone chroma, every 0.4 s) are lined up at the best offset within
 * a few seconds and compared frame by frame; only a close match on most of
 * the opening, in the same key, is the same recording.
 *
 * The candidate's real bitrate is read off its size and length too, since a
 * file named "_320" can hold 96 kbps.
 */
object SameRecording {
    private const val TAG = "BitChord"

    /** Seconds of each opening compared. */
    private const val SECONDS = 45.0

    /** Frames of slack either way (0.4 s each): an intro trimmed or padded by up to ~8 s. */
    private const val MAX_LAG = 20

    /** Mean similarity of the lined-up frames for "the same recording". */
    private const val SAME = 0.82

    /** Below this it is surely not (another song, a cover, a live take). */
    private const val DIFFERENT = 0.70

    /** Bytes of the candidate fetched: ~45 s even at 320 kbps, and its header. */
    private const val FETCH_BYTES = 2L shl 20

    sealed interface Verdict {
        val kbps: Int?
        data class Same(val score: Double, override val kbps: Int?) : Verdict
        data class Different(val score: Double, override val kbps: Int?) : Verdict
        data class Unknown(val why: String, override val kbps: Int?) : Verdict
    }

    suspend fun check(
        context: Context,
        playing: Song,
        url: String,
        headers: Map<String, String>,
        durationSec: Int?,
    ): Verdict = withContext(Dispatchers.IO) {
        withTimeoutOrNull(25_000) { compare(context, playing, url, headers, durationSec) }
            ?: Verdict.Unknown("took too long", null)
    }

    private fun compare(context: Context, playing: Song, url: String, headers: Map<String, String>, durationSec: Int?): Verdict {
        val file = File(context.cacheDir, "same-recording-${url.hashCode().toUInt()}.audio")
        try {
            val total = fetch(url, headers, file) ?: return Verdict.Unknown("candidate unreachable", null)
            val kbps = durationSec?.takeIf { it > 0 && total > 0 }?.let { (total * 8 / it / 1000).toInt() }
            val mine = VersionAudioAligner.openDataSource(context, playing)
                ?.let { src -> decode(src) }
                ?: return Verdict.Unknown("the playing song's audio isn't on the phone yet", kbps)
            val theirs = decode(FileSource(file)) ?: return Verdict.Unknown("candidate didn't decode", kbps)
            val a = VideoAligner.chroma(mine.first.samples, mine.first.sampleRate)
            val b = VideoAligner.chroma(theirs.first.samples, theirs.first.sampleRate)
            if (a.size < 40 || b.size < 40) return Verdict.Unknown("too little audio to compare", kbps)
            if (VideoAligner.keyShift(a, b) != 0) {
                return Verdict.Different(0.0, kbps).also { TrackLog.d(TAG, "same-recording: in another key") }
            }
            val score = bestLag(a, b)
            TrackLog.d(TAG, "same-recording: '${playing.title}' vs candidate scored ${"%.2f".format(score)}" +
                (kbps?.let { ", really ~${it}kbps" } ?: ""))
            return when {
                score >= SAME -> Verdict.Same(score, kbps)
                score < DIFFERENT -> Verdict.Different(score, kbps)
                else -> Verdict.Unknown("too close to call (${"%.2f".format(score)})", kbps)
            }
        } catch (e: Exception) {
            return Verdict.Unknown(e.message ?: e.javaClass.simpleName, null)
        } finally {
            file.delete()
        }
    }

    private fun decode(source: MediaDataSource): Pair<AudioDecoder.Pcm, Double>? = try {
        AudioDecoder.decodeDownsampled(source, 0.0, SECONDS, VideoAligner.RATE)
    } catch (e: Exception) {
        null
    } finally {
        runCatching { source.close() }
    }

    /** Mean cosine of the frames at the best offset, over the frames both have. */
    private fun bestLag(a: Array<FloatArray>, b: Array<FloatArray>): Double {
        var best = 0.0
        for (lag in -MAX_LAG..MAX_LAG) {
            var sum = 0.0
            var n = 0
            for (i in a.indices) {
                val j = i + lag
                if (j < 0 || j >= b.size) continue
                val x = a[i]
                val y = b[j]
                var dot = 0.0
                var nx = 0.0
                var ny = 0.0
                for (k in 0 until 12) {
                    dot += x[k] * y[k]; nx += x[k] * x[k]; ny += y[k] * y[k]
                }
                // Silence on both sides says nothing either way.
                if (nx < 1e-9 || ny < 1e-9) continue
                sum += dot / sqrt(nx * ny)
                n++
            }
            if (n >= max(30, minOf(a.size, b.size) / 2)) best = max(best, sum / n)
        }
        return best
    }

    /** The candidate's first [FETCH_BYTES] to [to]; its full size, from Content-Range. */
    private fun fetch(url: String, headers: Map<String, String>, to: File): Long? {
        val req = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=0-${FETCH_BYTES - 1}")
            .build()
        return Http.client.newCall(req).execute().use { r ->
            if (r.code !in 200..299) return null
            val body = r.body ?: return null
            to.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var got = 0L
                val input = body.byteStream()
                while (got < FETCH_BYTES) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                }
            }
            r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: body.contentLength().takeIf { it > 0 && r.code == 200 }
                ?: -1L
        }
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
}

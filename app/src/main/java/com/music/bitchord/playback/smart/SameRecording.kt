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

/**
 * BitChord Audio: is another source's copy the song that is playing — the
 * same recording — and what is it really worth?
 *
 * Asked before a playing song is swapped for a "better" copy found by name
 * (JioSaavn, SoundCloud, bilibili, an addon): a title and an artist can agree
 * while the music doesn't (another band's "Grip", a music-box cover filed
 * under the singer, a live take). Both openings are heard with the link
 * check's fingerprint ([AudioCheck]): the same recording, whatever its
 * encoding, gives the same bits; a live take or a re-recording gives about
 * half of them wrong. It is only taken with at least 80 % of the bits alike.
 * (Notes — chroma — are kept for lining up videos, whose sound is often
 * another mix of the song: there a looser likeness is the point.)
 *
 * The candidate's real bitrate is read off its size and length too, since a
 * file named "_320" can hold 96 kbps.
 */
object SameRecording {
    private const val TAG = "BitChord"

    /** Bytes of the candidate fetched: ~45 s even of a lossless file, and its header. */
    private const val FETCH_BYTES = 6L shl 20

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
        withTimeoutOrNull(40_000) { compare(context, playing, url, headers, durationSec) }
            ?: Verdict.Unknown("took too long", null)
    }

    private fun compare(context: Context, playing: Song, url: String, headers: Map<String, String>, durationSec: Int?): Verdict {
        val file = File(context.cacheDir, "same-recording-${url.hashCode().toUInt()}.audio")
        try {
            val total = fetch(url, headers, file) ?: return Verdict.Unknown("candidate unreachable", null)
            val kbps = durationSec?.takeIf { it > 0 && total > 0 }?.let { (total * 8 / it / 1000).toInt() }
            val mine = VersionAudioAligner.openDataSource(context, playing)
                ?.let { src -> AudioCheck.printOf(src) }
                ?: return Verdict.Unknown("the playing song's audio isn't on the phone yet", kbps)
            val theirs = AudioCheck.printOf(FileSource(file)) ?: return Verdict.Unknown("candidate didn't decode", kbps)
            val ber = AudioCheck.bestBer(mine, theirs) ?: return Verdict.Unknown("too little audio to compare", kbps)
            val alike = 1.0 - ber
            TrackLog.d(TAG, "same-recording: '${playing.title}' vs candidate ${"%.0f".format(alike * 100)} % alike (bit error ${"%.2f".format(ber)})" +
                (kbps?.let { ", really ~${it}kbps" } ?: ""))
            return when {
                ber <= AudioCheck.REPLACE_BELOW -> Verdict.Same(alike, kbps)
                ber > AudioCheck.OTHER_ABOVE -> Verdict.Different(alike, kbps)
                else -> Verdict.Unknown("too close to call (${"%.0f".format(alike * 100)} % alike)", kbps).also {
                    TrackLog.w(TAG, "same-recording: '${playing.title}' not replaced — under 80 % alike")
                }
            }
        } catch (e: Exception) {
            return Verdict.Unknown(e.message ?: e.javaClass.simpleName, null)
        } finally {
            file.delete()
        }
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

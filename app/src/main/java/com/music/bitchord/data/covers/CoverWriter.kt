package com.music.bitchord.data.covers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.remote.EmbeddedArt
import com.music.bitchord.data.remote.RemoteArtworkStore
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * BitChord Audio: a chosen cover saved into your pCloud files themselves, so
 * any player (and a reinstall) shows it.
 *
 * The file is downloaded, its tag rewritten with [CoverTags] (MP3 and FLAC;
 * other formats are left alone and reported), checked — the new cover reads
 * back and the audio after the tag is the same length — then uploaded beside
 * the original under a temporary name and moved over it, so a cut connection
 * never leaves half a file in its place.
 */
object CoverWriter {

    private const val TAG = "BitChord"

    /** Long side of the cover written into files. */
    private const val MAX_SIDE = 1200

    class Image(val bytes: ByteArray, val mime: String, val width: Int, val height: Int)

    /** The picture at [url], as a JPEG at most [MAX_SIDE] pixels on a side (a PNG kept when small). */
    suspend fun fetch(url: String): Image? = withContext(Dispatchers.IO) {
        val raw = runCatching {
            if (url.startsWith("file:")) {
                File(java.net.URI(url)).readBytes()
            } else {
                Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.bytes() else null
                }
            }
        }.getOrNull() ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0) return@withContext null
        val isJpeg = raw.size > 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xD8.toByte()
        if (isJpeg && maxOf(bounds.outWidth, bounds.outHeight) <= MAX_SIDE) {
            return@withContext Image(raw, "image/jpeg", bounds.outWidth, bounds.outHeight)
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withContext null
        val scale = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        sized.compress(Bitmap.CompressFormat.JPEG, 92, out)
        Image(out.toByteArray(), "image/jpeg", sized.width, sized.height)
    }

    sealed interface Outcome {
        data object Saved : Outcome
        data class Skipped(val why: String) : Outcome
        data class Failed(val why: String) : Outcome
    }

    /** Writes [image] into the pCloud file [file] as its front cover. */
    suspend fun saveInto(context: Context, file: Song, image: Image): Outcome = withContext(Dispatchers.IO) {
        val url = WebDavConfig.fileUrlOf(file.videoId) ?: return@withContext Outcome.Skipped("not a pCloud file")
        val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
        val kind = CoverTags.kindOf(ext)
        if (kind == CoverTags.Kind.Unsupported) return@withContext Outcome.Skipped("$ext files aren't written to (MP3 and FLAC only)")
        val u = AppSettings.webdavUsername.value
        val p = AppSettings.webdavPassword.value
        val dir = File(context.cacheDir, "cover-write").apply { mkdirs() }
        val original = File(dir, "in.$ext")
        val rewritten = File(dir, "out.$ext")
        try {
            val size = WebDavClient.download(url, u, p, original).getOrElse { return@withContext Outcome.Failed(it.message ?: "download failed") }
            val rewrite = RandomAccessFile(original, "r").use { raf ->
                fun read(off: Long, len: Int): ByteArray {
                    if (off >= raf.length()) return ByteArray(0)
                    val n = minOf(len.toLong(), raf.length() - off).toInt()
                    val b = ByteArray(n)
                    raf.seek(off); raf.readFully(b)
                    return b
                }
                when (kind) {
                    CoverTags.Kind.Mp3 -> {
                        val first = read(0, 10)
                        val len = CoverTags.headLength(first, kind) ?: 0L
                        if (len > 64L * 1024 * 1024) return@withContext Outcome.Skipped("tag too large")
                        CoverTags.mp3(read(0, maxOf(len, 10L).toInt()), image.bytes, image.mime)
                    }
                    CoverTags.Kind.Flac -> {
                        val end = CoverTags.flacMetadataEnd(::read) ?: return@withContext Outcome.Skipped("not a FLAC file")
                        if (end > 64L * 1024 * 1024) return@withContext Outcome.Skipped("tag too large")
                        CoverTags.flac(read(0, end.toInt()), image.bytes, image.mime, image.width, image.height)
                    }
                    CoverTags.Kind.Unsupported -> null
                }
            } ?: return@withContext Outcome.Skipped("its tag can't be rewritten safely")

            rewritten.outputStream().buffered().use { out ->
                out.write(rewrite.head)
                original.inputStream().use { input ->
                    var skip = rewrite.replaces
                    while (skip > 0) { val n = input.skip(skip); if (n <= 0) break; skip -= n }
                    input.copyTo(out, 64 * 1024)
                }
            }
            // Checked before anything is uploaded.
            val expected = size - rewrite.replaces + rewrite.head.size
            if (rewritten.length() != expected) return@withContext Outcome.Failed("size check failed")
            val readBack = RandomAccessFile(rewritten, "r").use { raf ->
                EmbeddedArt.extract(object : EmbeddedArt.Reader {
                    override val size: Long get() = raf.length()
                    override fun read(offset: Long, length: Int): ByteArray {
                        if (offset >= raf.length()) return ByteArray(0)
                        val n = minOf(length.toLong(), raf.length() - offset).toInt()
                        val b = ByteArray(n); raf.seek(offset); raf.readFully(b); return b
                    }
                })
            }
            if (readBack?.bytes?.contentEquals(image.bytes) != true) return@withContext Outcome.Failed("cover didn't read back")

            val temp = url.substringBeforeLast('/') + "/.bitchord-cover-" + url.substringAfterLast('/')
            val mime = if (kind == CoverTags.Kind.Mp3) "audio/mpeg" else "audio/flac"
            rewritten.inputStream().use { input ->
                WebDavClient.putFile(temp, input, rewritten.length(), mime, u, p, overwrite = true)
            }.getOrElse { return@withContext Outcome.Failed(it.message ?: "upload failed") }
            WebDavClient.move(temp, url, u, p).getOrElse {
                WebDavClient.delete(temp, u, p)
                return@withContext Outcome.Failed(it.message ?: "replace failed")
            }
            RemoteArtworkStore.replace(context, file.videoId, image.bytes, image.mime)
            TrackLog.d(TAG, "cover written into ${url.substringAfterLast('/')}")
            Outcome.Saved
        } finally {
            original.delete()
            rewritten.delete()
        }
    }
}

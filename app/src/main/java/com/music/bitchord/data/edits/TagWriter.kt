package com.music.bitchord.data.edits

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.covers.CoverTags
import com.music.bitchord.data.covers.CoverWriter
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * BitChord Audio: edited song details written into the files themselves — a
 * pCloud file, or a download on the phone — so any player shows them.
 *
 * As with covers ([CoverWriter]): MP3 and FLAC only, the tag rewritten with
 * [CoverTags] and everything else in the file kept; checked before anything
 * replaces the original (the audio after the tag is the same length), and a
 * pCloud file replaced by an upload under a temporary name moved over it.
 */
object TagWriter {

    private const val TAG = "BitChord"

    private fun rewrite(file: File, ext: String, text: CoverTags.Text): CoverTags.Rewrite? {
        val kind = CoverTags.kindOf(ext)
        return RandomAccessFile(file, "r").use { raf ->
            fun read(off: Long, len: Int): ByteArray {
                if (off >= raf.length()) return ByteArray(0)
                val n = minOf(len.toLong(), raf.length() - off).toInt()
                val b = ByteArray(n)
                raf.seek(off); raf.readFully(b)
                return b
            }
            when (kind) {
                CoverTags.Kind.Mp3 -> {
                    val len = CoverTags.headLength(read(0, 10), kind) ?: 0L
                    if (len > 64L * 1024 * 1024) null else CoverTags.mp3Text(read(0, maxOf(len, 10L).toInt()), text)
                }
                CoverTags.Kind.Flac -> {
                    val end = CoverTags.flacMetadataEnd(::read) ?: return@use null
                    if (end > 64L * 1024 * 1024) null else CoverTags.flacText(read(0, end.toInt()), text)
                }
                CoverTags.Kind.Unsupported -> null
            }
        }
    }

    /** [original] with its tag rewritten into [out]; false when it couldn't be (checked). */
    private fun build(original: File, out: File, ext: String, text: CoverTags.Text): Boolean {
        val r = rewrite(original, ext, text) ?: return false
        out.outputStream().buffered().use { o ->
            o.write(r.head)
            original.inputStream().use { input ->
                var skip = r.replaces
                while (skip > 0) { val n = input.skip(skip); if (n <= 0) break; skip -= n }
                input.copyTo(o, 64 * 1024)
            }
        }
        return out.length() == original.length() - r.replaces + r.head.size
    }

    /** Into the pCloud file with id [fileId]. */
    suspend fun intoPcloud(context: Context, fileId: String, text: CoverTags.Text): CoverWriter.Outcome = withContext(Dispatchers.IO) {
        val url = WebDavConfig.fileUrlOf(fileId) ?: return@withContext CoverWriter.Outcome.Skipped("not a pCloud file")
        val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
        if (CoverTags.kindOf(ext) == CoverTags.Kind.Unsupported) {
            return@withContext CoverWriter.Outcome.Skipped("$ext files aren't written to (MP3 and FLAC only)")
        }
        val u = AppSettings.webdavUsername.value
        val p = AppSettings.webdavPassword.value
        val dir = File(context.cacheDir, "tag-write").apply { mkdirs() }
        val original = File(dir, "in.$ext")
        val rewritten = File(dir, "out.$ext")
        try {
            WebDavClient.download(url, u, p, original).getOrElse { return@withContext CoverWriter.Outcome.Failed(it.message ?: "download failed") }
            if (!build(original, rewritten, ext, text)) return@withContext CoverWriter.Outcome.Skipped("its tag can't be rewritten safely")
            val temp = url.substringBeforeLast('/') + "/.bitchord-tags-" + url.substringAfterLast('/')
            val mime = if (ext == "mp3") "audio/mpeg" else "audio/flac"
            rewritten.inputStream().use { input ->
                WebDavClient.putFile(temp, input, rewritten.length(), mime, u, p, overwrite = true)
            }.getOrElse { return@withContext CoverWriter.Outcome.Failed(it.message ?: "upload failed") }
            WebDavClient.move(temp, url, u, p).getOrElse {
                WebDavClient.delete(temp, u, p)
                return@withContext CoverWriter.Outcome.Failed(it.message ?: "replace failed")
            }
            TrackLog.d(TAG, "details written into ${url.substringAfterLast('/')}")
            CoverWriter.Outcome.Saved
        } finally {
            original.delete()
            rewritten.delete()
        }
    }

    /** Into a downloaded file on the phone ([uri]: the app's own MediaStore entry or a file). */
    suspend fun intoDownload(context: Context, uri: String, text: CoverTags.Text): CoverWriter.Outcome = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        val name = (parsed.lastPathSegment ?: "").lowercase()
        val ext = runCatching {
            if (parsed.scheme == "content") {
                context.contentResolver.query(parsed, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            } else null
        }.getOrNull()?.substringAfterLast('.', "")?.lowercase() ?: name.substringAfterLast('.', "")
        if (CoverTags.kindOf(ext) == CoverTags.Kind.Unsupported) {
            return@withContext CoverWriter.Outcome.Skipped("$ext files aren't written to (MP3 and FLAC only)")
        }
        val dir = File(context.cacheDir, "tag-write").apply { mkdirs() }
        val original = File(dir, "local-in.$ext")
        val rewritten = File(dir, "local-out.$ext")
        try {
            val input = if (parsed.scheme == "file") File(parsed.path!!).inputStream() else context.contentResolver.openInputStream(parsed)
            input?.use { i -> original.outputStream().use { i.copyTo(it) } } ?: return@withContext CoverWriter.Outcome.Failed("can't read the file")
            if (!build(original, rewritten, ext, text)) return@withContext CoverWriter.Outcome.Skipped("its tag can't be rewritten safely")
            val output = if (parsed.scheme == "file") File(parsed.path!!).outputStream() else context.contentResolver.openOutputStream(parsed, "wt")
            output?.use { o -> rewritten.inputStream().use { it.copyTo(o) } } ?: return@withContext CoverWriter.Outcome.Failed("can't write the file")
            TrackLog.d(TAG, "details written into a download")
            CoverWriter.Outcome.Saved
        } catch (t: Throwable) {
            CoverWriter.Outcome.Failed(t.message ?: "error")
        } finally {
            original.delete()
            rewritten.delete()
        }
    }
}

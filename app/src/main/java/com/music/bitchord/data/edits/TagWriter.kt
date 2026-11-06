package com.music.bitchord.data.edits

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.covers.CoverTags
import com.music.bitchord.data.covers.CoverWriter
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * BitChord Audio: details, lyrics and covers written into the files
 * themselves — a pCloud file, or a download on the phone — so any player
 * shows them.
 *
 * Uses TagLib (the C++ tag library, as MusicBeat does), so every format is
 * written: MP3, FLAC, M4A/AAC/ALAC, Opus, OGG Vorbis, WAV, AIFF, WMA. The tag
 * is changed on a copy on the phone; a pCloud file is then replaced by an
 * upload under a temporary name moved over it, a download rewritten in place.
 */
object TagWriter {

    private const val TAG = "BitChord"

    /** What to change: null leaves a field as it is, empty removes it. */
    data class Change(
        val text: CoverTags.Text = CoverTags.Text(),
        val discNumber: String? = null,
        /** Synced (LRC) or plain lyrics. */
        val lyrics: String? = null,
        val cover: CoverWriter.Image? = null,
    ) {
        val isEmpty: Boolean get() = text.isEmpty && discNumber == null && lyrics == null && cover == null
    }

    private val AUDIO = setOf("mp3", "flac", "m4a", "mp4", "aac", "alac", "opus", "ogg", "oga", "wav", "aif", "aiff", "wma", "ape", "wv", "mpc", "dsf")

    fun canWrite(ext: String) = ext.lowercase() in AUDIO

    /** [change] applied to the file open as [fd] (read-write). */
    private fun apply(fd: ParcelFileDescriptor, change: Change): Boolean {
        val current = TagLib.getMetadata(fd.dup().detachFd(), false)?.propertyMap ?: hashMapOf()
        val map = HashMap<String, Array<String>>(current)
        fun put(key: String, value: String?) {
            if (value == null) return
            val v = value.trim()
            map.keys.filter { it.equals(key, ignoreCase = true) }.forEach { map.remove(it) }
            if (v.isNotEmpty()) map[key] = arrayOf(v)
        }
        val t = change.text
        put("TITLE", t.title)
        put("ARTIST", t.artist)
        put("ALBUM", t.album)
        put("ALBUMARTIST", t.albumArtist)
        put("DATE", t.year)
        put("TRACKNUMBER", t.track)
        put("GENRE", t.genre)
        put("DISCNUMBER", change.discNumber)
        put("LYRICS", change.lyrics)
        var ok = true
        if (!(t.isEmpty && change.discNumber == null && change.lyrics == null)) {
            ok = TagLib.savePropertyMap(fd.dup().detachFd(), HashMap(map.filterValues { it.isNotEmpty() }))
        }
        change.cover?.let { img ->
            ok = TagLib.savePictures(
                fd.dup().detachFd(),
                arrayOf(Picture(data = img.bytes, description = "Front Cover", pictureType = "Front Cover", mimeType = img.mime)),
            ) && ok
        }
        return ok
    }

    /** [change] written into a local [file] (a copy being prepared). */
    private fun applyToFile(file: File, change: Change): Boolean =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use { apply(it, change) }

    /** Into the pCloud file with id [fileId]. */
    suspend fun intoPcloud(context: Context, fileId: String, change: Change): CoverWriter.Outcome = withContext(Dispatchers.IO) {
        val url = WebDavConfig.fileUrlOf(fileId) ?: return@withContext CoverWriter.Outcome.Skipped("not a pCloud file")
        val ext = url.substringAfterLast('.', "").substringBefore('?').lowercase()
        if (!canWrite(ext)) return@withContext CoverWriter.Outcome.Skipped("$ext isn't an audio format that holds tags")
        val u = AppSettings.webdavUsername.value
        val p = AppSettings.webdavPassword.value
        val dir = File(context.cacheDir, "tag-write").apply { mkdirs() }
        val work = File(dir, "pcloud-${System.nanoTime()}.$ext")
        try {
            val size = WebDavClient.download(url, u, p, work).getOrElse { return@withContext CoverWriter.Outcome.Failed(it.message ?: "download failed") }
            if (!applyToFile(work, change)) return@withContext CoverWriter.Outcome.Skipped("its tag couldn't be written")
            // The audio itself must still be there: the new file can't be much smaller.
            if (work.length() < size / 2) return@withContext CoverWriter.Outcome.Failed("size check failed")
            val temp = url.substringBeforeLast('/') + "/.bitchord-tags-" + url.substringAfterLast('/')
            work.inputStream().use { input ->
                WebDavClient.putFile(temp, input, work.length(), mimeOf(ext), u, p, overwrite = true)
            }.getOrElse { return@withContext CoverWriter.Outcome.Failed(it.message ?: "upload failed") }
            WebDavClient.move(temp, url, u, p).getOrElse {
                WebDavClient.delete(temp, u, p)
                return@withContext CoverWriter.Outcome.Failed(it.message ?: "replace failed")
            }
            TrackLog.d(TAG, "tags written into ${url.substringAfterLast('/')}")
            CoverWriter.Outcome.Saved
        } catch (t: Throwable) {
            CoverWriter.Outcome.Failed(t.message ?: "error")
        } finally {
            work.delete()
        }
    }

    /** Into a downloaded file on the phone ([uri]: the app's own MediaStore entry or a file). */
    suspend fun intoDownload(context: Context, uri: String, change: Change): CoverWriter.Outcome = withContext(Dispatchers.IO) {
        val parsed = Uri.parse(uri)
        val ext = runCatching {
            if (parsed.scheme == "content") {
                context.contentResolver.query(parsed, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            } else parsed.lastPathSegment
        }.getOrNull()?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext.isNotEmpty() && !canWrite(ext)) return@withContext CoverWriter.Outcome.Skipped("$ext isn't an audio format that holds tags")
        try {
            val fd = if (parsed.scheme == "file") {
                ParcelFileDescriptor.open(File(parsed.path!!), ParcelFileDescriptor.MODE_READ_WRITE)
            } else {
                context.contentResolver.openFileDescriptor(parsed, "rw")
            } ?: return@withContext CoverWriter.Outcome.Failed("can't open the file")
            val ok = fd.use { apply(it, change) }
            if (!ok) return@withContext CoverWriter.Outcome.Skipped("its tag couldn't be written")
            TrackLog.d(TAG, "tags written into a download")
            CoverWriter.Outcome.Saved
        } catch (t: Throwable) {
            CoverWriter.Outcome.Failed(t.message ?: "error")
        }
    }

    /** Older entry points: text only. */
    suspend fun intoPcloud(context: Context, fileId: String, text: CoverTags.Text) = intoPcloud(context, fileId, Change(text = text))
    suspend fun intoDownload(context: Context, uri: String, text: CoverTags.Text) = intoDownload(context, uri, Change(text = text))

    private fun mimeOf(ext: String) = when (ext) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "mp4", "aac", "alac" -> "audio/mp4"
        "opus", "ogg", "oga" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }
}

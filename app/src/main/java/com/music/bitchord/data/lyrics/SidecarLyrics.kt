package com.music.bitchord.data.lyrics

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * BitChord Audio: a `.lrc` file kept beside a track is that track's lyrics.
 *
 * `Song Name.flac` and `Song Name.lrc` in the same folder belong together, the
 * way every desktop player pairs them. Those lyrics were chosen by whoever
 * filed them, for this exact recording, so they come before anything a lyrics
 * service would match by title — and they work offline.
 *
 * Covers files on the phone (by path) and in the WebDAV/pCloud library (whose
 * listing registers each pair — [registerRemote]). A remote track whose listing
 * hasn't been seen this session is tried by the same naming rule.
 */
object SidecarLyrics {

    private const val MAX_BYTES = 1024 * 1024

    /** Remote track id → its `.lrc` URL, from the latest library listing. */
    private val remote = ConcurrentHashMap<String, String>()

    /** Remote track ids known to have no `.lrc`, so they aren't asked again. */
    private val remoteMisses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun registerRemote(songId: String, lrcUrl: String) {
        remote[songId] = lrcUrl
        remoteMisses -= songId
    }

    suspend fun forSong(
        context: Context,
        videoId: String,
        localUri: String?,
        localPath: String?,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val raw = when {
            WebDavConfig.isWebDavId(videoId) -> remoteText(videoId)
            else -> localText(context, localUri, localPath)
        } ?: return@withContext null
        LrcLib.parseLrc(raw).takeIf { lines -> lines.any { it.text.isNotBlank() } }?.withBackgroundVocals()
    }

    private suspend fun remoteText(videoId: String): String? {
        if (videoId in remoteMisses) return null
        val fileUrl = WebDavConfig.fileUrlOf(videoId) ?: return null
        val lrcUrl = remote[videoId] ?: (fileUrl.substringBeforeLast('.') + ".lrc")
        val text = WebDavClient.readText(
            lrcUrl,
            AppSettings.webdavUsername.value,
            AppSettings.webdavPassword.value,
            MAX_BYTES,
        ).getOrNull()?.takeIf { it.isNotBlank() && '[' in it }
        if (text == null) remoteMisses += videoId
        return text
    }

    private fun localText(context: Context, localUri: String?, localPath: String?): String? {
        val audio = localPath?.let(::File)?.takeIf { it.isFile }
            ?: localUri?.let { pathOf(context, it) }?.let(::File)?.takeIf { it.isFile }
            ?: return null
        val base = audio.name.substringBeforeLast('.')
        val dir = audio.parentFile ?: return null
        val lrc = File(dir, "$base.lrc").takeIf { it.isFile }
            ?: dir.listFiles { f -> f.isFile && f.name.equals("$base.lrc", ignoreCase = true) }?.firstOrNull()
            ?: return null
        if (lrc.length() !in 1..MAX_BYTES.toLong()) return null
        return runCatching { lrc.readText().removePrefix("﻿") }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** A `file://` path, or the file behind a MediaStore `content://` id. */
    private fun pathOf(context: Context, uriString: String): String? = runCatching {
        val uri = Uri.parse(uriString)
        when (uri.scheme) {
            "file" -> uri.path
            "content" -> context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            else -> null
        }
    }.getOrNull()
}

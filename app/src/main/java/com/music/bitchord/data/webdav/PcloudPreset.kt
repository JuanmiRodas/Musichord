package com.music.bitchord.data.webdav

import java.net.URI
import java.text.Normalizer
import java.util.Locale
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * BitChord Audio: pCloud, set up as the WebDAV library it already is.
 *
 * pCloud serves every account over WebDAV with the account's own email and
 * password, on one host per data region — accounts made in Europe live on
 * [EU_HOST], the rest on [US_HOST]. So "connect pCloud" is the WebDAV library
 * pointed at the right host and folder, and everything the WebDAV library
 * does — subfolders, covers, tags, `.lrc` lyrics, the saved copy — applies.
 *
 * Note for accounts with two-step verification: pCloud's WebDAV does not take
 * the second step, so the account password alone has to be enough.
 */
object PcloudPreset {

    const val EU_HOST = "ewebdav.pcloud.com"
    const val US_HOST = "webdav.pcloud.com"

    /** The region tried first; EU accounts are the expected case here. */
    val hosts = listOf(EU_HOST, US_HOST)

    fun isPcloud(url: String): Boolean =
        runCatching { URI(url.trim()).host?.lowercase() in hosts }.getOrDefault(false)

    /** `https://host/Music/Albums/` for the folder "Music/Albums" (blank is the whole drive). */
    fun urlFor(host: String, folder: String): String {
        val segments = folder.trim().trim('/').split('/').filter { it.isNotBlank() }
        val path = segments.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return if (path.isEmpty()) "https://$host/" else "https://$host/$path/"
    }

    /** The folder a pCloud WebDAV URL points at, as typed: "Music/Albums". */
    fun folderOf(url: String): String = runCatching {
        URLDecoder.decode(URI(url.trim()).rawPath.orEmpty().trim('/'), "UTF-8")
    }.getOrDefault("")

    fun hostOf(url: String): String? =
        runCatching { URI(url.trim()).host?.lowercase() }.getOrNull()?.takeIf { it in hosts }

    /** A folder name compared as letters and digits: accents however stored, spaces, case. */
    private fun nameKey(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /**
     * The real address of [folder] on [host], found one level at a time.
     *
     * A folder typed on the phone and the same folder made on a Mac can differ
     * in ways that don't show: macOS stores "Música" with the accent as a
     * separate character, and pCloud Backup names a computer folder its own
     * way ("MacBook Air de Juanmi" for "MacBookAirdeJuanmi"). Each level is
     * listed and the child whose name matches as letters and digits is taken,
     * so whatever was typed lands on the folder that is there.
     */
    suspend fun resolve(host: String, folder: String, username: String, password: String): Result<String> {
        val root = "https://$host/"
        WebDavClient.testConnection(root, username, password).onFailure { return Result.failure(it) }
        var url = root
        for (segment in folder.trim().trim('/').split('/').filter { it.isNotBlank() }) {
            val want = nameKey(segment)
            val children = WebDavClient.listFolders(url, username, password).getOrElse { return Result.failure(it) }
            val hit = children.firstOrNull { nameKey(it.displayName.ifBlank { lastName(it.url) }) == want }
                ?: children.firstOrNull { nameKey(lastName(it.url)) == want }
                ?: return Result.failure(
                    IllegalStateException(
                        "Folder \"$segment\" not found in /${folderOf(url)} — there: " +
                            children.joinToString(", ") { it.displayName.ifBlank { lastName(it.url) } }.take(300),
                    ),
                )
            url = hit.url.let { if (it.startsWith("http")) it else "https://$host" + (if (it.startsWith("/")) it else "/$it") }
                .let { if (it.endsWith("/")) it else "$it/" }
        }
        return Result.success(url)
    }

    private fun lastName(url: String): String =
        runCatching { URLDecoder.decode(url.trimEnd('/').substringAfterLast('/'), "UTF-8") }
            .getOrDefault(url.trimEnd('/').substringAfterLast('/'))
}

package com.music.bitchord.data.webdav

import java.net.URI
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
}

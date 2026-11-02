package com.music.bitchord.data.webdav

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.settings.AppSettings
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * BitChord Audio: a direct, password-free link to one of your pCloud files —
 * for a Cast receiver, which can't send the WebDAV password. pCloud's own API
 * (`getfilelink`) hands out such links; they last a few hours, so each is kept
 * that long. The API is logged into with the same account as the WebDAV
 * library (an account with two-step login can't be; the phone then serves the
 * file itself, see `CastStreamServer`).
 */
object PcloudLinks {

    private const val TAG = "BitChord"
    private const val LINK_TTL_MS = 2 * 60 * 60 * 1000L

    private val http by lazy { Http.client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build() }
    private val links = ConcurrentHashMap<String, Pair<String, Long>>()

    @Volatile
    private var auth: String? = null

    /** Whether [url] is on pCloud's WebDAV server. */
    fun isPcloud(url: String): Boolean =
        runCatching { java.net.URI(url).host.orEmpty().endsWith("pcloud.com") }.getOrDefault(false)

    /** A direct link to the pCloud file at WebDAV [url], or null. Not on the main thread. */
    fun directUrl(url: String): String? {
        if (!isPcloud(url)) return null
        links[url]?.let { (link, at) -> if (System.currentTimeMillis() - at < LINK_TTL_MS) return link }
        val uri = java.net.URI(url)
        val api = if (uri.host.startsWith("e")) "https://eapi.pcloud.com" else "https://api.pcloud.com"
        val path = URLDecoder.decode(uri.rawPath.replace("+", "%2B"), "UTF-8")
        repeat(2) { attempt ->
            val token = auth ?: login(api) ?: return null
            val body = get("$api/getfilelink?auth=${enc(token)}&path=${enc(path)}") ?: return null
            val o = JSONObject(body)
            when (o.optInt("result", -1)) {
                0 -> {
                    val host = o.optJSONArray("hosts")?.optString(0)?.takeIf { it.isNotBlank() } ?: return null
                    val link = "https://$host${o.optString("path")}"
                    links[url] = link to System.currentTimeMillis()
                    return link
                }
                1000, 2000 -> { auth = null; if (attempt == 1) return null } // logged out: once more
                else -> {
                    Log.w(TAG, "pCloud link: ${o.optInt("result")} ${o.optString("error")}")
                    return null
                }
            }
        }
        return null
    }

    private fun login(api: String): String? {
        val u = AppSettings.webdavUsername.value
        val p = AppSettings.webdavPassword.value
        if (u.isBlank() || p.isBlank()) return null
        val body = get("$api/userinfo?getauth=1&logout=1&username=${enc(u)}&password=${enc(p)}") ?: return null
        val o = JSONObject(body)
        if (o.optInt("result", -1) != 0) {
            Log.w(TAG, "pCloud login: ${o.optInt("result")} ${o.optString("error")}")
            return null
        }
        return o.optString("auth").takeIf { it.isNotBlank() }?.also { auth = it }
    }

    private fun get(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

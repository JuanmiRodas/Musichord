package com.music.bitchord.data.video

import android.webkit.CookieManager
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * BitChord Audio: VK Video as a video source, through your own VK session — you sign in
 * on VK's own web page inside the app (two-step verification and all), and the app then
 * reads VK's mobile site (m.vk.com) with that session, as your browser would: its video
 * search, and a video's page for the MP4 it plays.
 *
 * A video is kept as "owner_video" (e.g. "-12345_456239017").
 */
object VkVideo {
    private const val TAG = "VkVideo"
    private const val MOBILE = "https://m.vk.com"
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"

    data class Hit(val id: String, val title: String, val author: String, val durationText: String?, val thumbnail: String?, val durationSec: Int)
    data class Stream(val url: String, val headers: Map<String, String>, val width: Int, val height: Int)

    /** The VK sign-in page is up (Settings › VK). */
    val loginOpen = MutableStateFlow(false)

    private val http by lazy { Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).followRedirects(true).build() }

    private fun cookies(): String =
        listOf("https://m.vk.com", "https://vk.com", "https://vkvideo.ru")
            .mapNotNull { runCatching { CookieManager.getInstance().getCookie(it) }.getOrNull() }
            .flatMap { it.split(";") }.map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.substringBefore('=') }
            .joinToString("; ")

    /** Signed in to VK on the in-app page. */
    fun signedIn(): Boolean = cookies().split("; ").any { it.startsWith("remixsid=") || it.startsWith("remixnsid=") }

    /** Signs the in-app VK page out (its cookies only). */
    fun signOut() {
        val jar = CookieManager.getInstance()
        for (url in listOf("https://m.vk.com", "https://vk.com", "https://login.vk.com", "https://id.vk.com", "https://vkvideo.ru")) {
            jar.getCookie(url)?.split(";")?.map { it.substringBefore("=").trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
                val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
                jar.setCookie(url, expired)
                jar.setCookie(url, "$expired; Domain=.vk.com")
            }
        }
        jar.flush()
    }

    private fun page(url: String): String? = runCatching {
        http.newCall(
            Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Cookie", cookies())
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .build(),
        ).execute().use { r ->
            val body = r.body?.string()
            if (!r.isSuccessful) { TrackLog.w(TAG, "VK: HTTP ${r.code} for ${r.request.url.encodedPath}"); null } else body
        }
    }.onFailure { TrackLog.w(TAG, "VK: ${it.message}") }.getOrNull()

    private fun unescape(s: String) = s.replace("\\/", "/").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace(Regex("<[^>]+>"), "").trim()

    private val LINK = Regex("""href="/video(-?\d+_\d+)[^"]*"""")
    private val DURATION = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?""")

    /** VK's video search for [query] (needs the sign-in; nothing without it). */
    suspend fun search(query: String): List<Hit> = withContext(Dispatchers.IO) {
        if (!signedIn()) return@withContext emptyList()
        val html = page("$MOBILE/video?q=" + URLEncoder.encode(query, "UTF-8") + "&section=search") ?: return@withContext emptyList()
        val seen = LinkedHashMap<String, Hit>()
        for (m in LINK.findAll(html)) {
            val id = m.groupValues[1]
            if (id in seen) continue
            // The card around the link: its title, duration and picture are near it.
            val around = html.substring(m.range.first, minOf(html.length, m.range.first + 2_500))
            val title = Regex("""(?:aria-label|title|alt)="([^"]{3,200})"""").find(around)?.groupValues?.get(1)?.let(::unescape)
                ?: Regex("""class="[^"]*[Tt]itle[^"]*"[^>]*>([\s\S]{1,300}?)</""").find(around)?.groupValues?.get(1)?.let(::unescape)
                ?: continue
            val thumb = Regex("""(https://[^"'\s)]+\.(?:jpg|jpeg|webp)[^"'\s)]*)""").find(around)?.groupValues?.get(1)?.let(::unescape)
            val dm = Regex("""class="[^"]*[Dd]uration[^"]*"[^>]*>\s*([\d:]+)""").find(around)?.groupValues?.get(1)
                ?.let { DURATION.find(it) }
            val sec = dm?.let { d ->
                val (a, b, c) = d.destructured
                if (c.isNotEmpty()) a.toInt() * 3600 + b.toInt() * 60 + c.toInt() else a.toInt() * 60 + b.toInt()
            } ?: 0
            seen[id] = Hit(id, title, "VK", dm?.value, thumb, sec)
            if (seen.size >= 25) break
        }
        if (seen.isEmpty()) TrackLog.w(TAG, "VK search \"$query\": nothing read from the page (${html.length} chars; signed in: ${signedIn()})")
        else TrackLog.d(TAG, "VK: ${seen.size} videos for \"$query\"")
        seen.values.toList()
    }

    private val SOURCE = Regex("""<source[^>]+src="([^"]+)"[^>]*>""")
    private val URL_N = Regex(""""url(\d{3,4})"\s*:\s*"([^"]+)"""")

    /** The MP4 of video [id], at most [maxHeight] tall, read off its page. */
    suspend fun stream(id: String, maxHeight: Int = 1080): Stream? = withContext(Dispatchers.IO) {
        val html = page("$MOBILE/video$id") ?: return@withContext null
        val found = LinkedHashMap<Int, String>()
        URL_N.findAll(html).forEach { m -> m.groupValues[1].toIntOrNull()?.let { h -> found.putIfAbsent(h, unescape(m.groupValues[2])) } }
        SOURCE.findAll(html).forEach { m ->
            val url = unescape(m.groupValues[1])
            val h = Regex("""\.(\d{3,4})\.mp4""").find(url)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""[?&]type=(\d)""").find(url)?.let { 0 } ?: 0
            if (url.startsWith("http")) found.putIfAbsent(h, url)
        }
        val best = found.keys.filter { it in 1..maxHeight }.maxOrNull()?.let { it to found[it]!! }
            ?: found.entries.firstOrNull()?.let { it.key to it.value }
        if (best == null) {
            TrackLog.w(TAG, "VK: no MP4 on the page of $id (${html.length} chars)")
            return@withContext null
        }
        val height = best.first.takeIf { it > 0 } ?: 720
        TrackLog.d(TAG, "VK: stream for $id (${height}p)")
        Stream(best.second, mapOf("User-Agent" to UA, "Referer" to "$MOBILE/", "Cookie" to cookies()), height * 16 / 9, height)
    }
}

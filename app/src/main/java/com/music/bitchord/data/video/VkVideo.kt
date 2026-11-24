package com.music.bitchord.data.video

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.music.bitchord.data.web.QuietWebChromeClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import kotlin.coroutines.resume
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
    data class Stream(val url: String, val headers: Map<String, String>, val width: Int, val height: Int, val hls: Boolean = false)

    /** The VK sign-in page is up (Settings › VK). */
    val loginOpen = MutableStateFlow(false)

    @Volatile private var appContext: Context? = null

    fun init(context: Context) { appContext = context.applicationContext }

    private val http by lazy { Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).followRedirects(true).build() }

    private fun cookies(): String =
        listOf("https://m.vk.com", "https://vk.com", "https://vk.ru", "https://m.vk.ru", "https://vkvideo.ru", "https://m.vkvideo.ru", "https://login.vk.com", "https://login.vk.ru")
            .mapNotNull { runCatching { CookieManager.getInstance().getCookie(it) }.getOrNull() }
            .flatMap { it.split(";") }.map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.substringBefore('=') }
            .joinToString("; ")

    /** Signed in to VK on the in-app page. */
    fun signedIn(): Boolean = cookies().split("; ").any { it.startsWith("remixsid=") || it.startsWith("remixnsid=") || it.startsWith("remixsid6=") }

    /** Signs the in-app VK page out (its cookies only). */
    fun signOut() {
        val jar = CookieManager.getInstance()
        for (url in listOf("https://m.vk.com", "https://vk.com", "https://login.vk.com", "https://id.vk.com", "https://vkvideo.ru", "https://m.vkvideo.ru", "https://vk.ru", "https://login.vk.ru", "https://id.vk.ru")) {
            jar.getCookie(url)?.split(";")?.map { it.substringBefore("=").trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
                val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
                jar.setCookie(url, expired)
                jar.setCookie(url, "$expired; Domain=.vk.com")
                jar.setCookie(url, "$expired; Domain=.vk.ru")
                jar.setCookie(url, "$expired; Domain=.vkvideo.ru")
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

    /**
     * VK's video search for [query]. VK Video now lives at vkvideo.ru, a page built by its
     * script, so the search is opened there in a hidden browser (with your VK session, if
     * any) and the result cards read once drawn; the old mobile site is the fallback.
     */
    suspend fun search(query: String): List<Hit> {
        val q = URLEncoder.encode(query, "UTF-8")
        val web = runCatching {
            inPage("https://vkvideo.ru/?q=$q", 20_000, SEARCH_JS, settle = 2) { raw ->
                val a = JSONArray(raw)
                (0 until a.length()).mapNotNull { i ->
                    val o = a.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val d = o.optString("d").takeIf { it.isNotBlank() }
                    Hit(id, unescape(o.optString("t")), o.optString("a").ifBlank { "VK" }, d,
                        o.optString("th").takeIf { it.startsWith("http") }, d?.let(::seconds) ?: 0)
                }.takeIf { it.isNotEmpty() }
            }
        }.onFailure { TrackLog.w(TAG, "VK search page: ${it.message}") }.getOrNull().orEmpty()
        if (web.isNotEmpty()) {
            TrackLog.d(TAG, "VK: ${web.size} videos for \"$query\" (vkvideo.ru)")
            return web
        }
        val old = mobileSearch(query)
        if (old.isEmpty()) TrackLog.w(TAG, "VK search \"$query\": nothing found (signed in: ${signedIn()})")
        return old
    }

    private fun seconds(text: String): Int = DURATION.find(text)?.let { d ->
        val (a, b, c) = d.destructured
        if (c.isNotEmpty()) a.toInt() * 3600 + b.toInt() * 60 + c.toInt() else a.toInt() * 60 + b.toInt()
    } ?: 0

    /** The old mobile site's search page, read as HTML. */
    private suspend fun mobileSearch(query: String): List<Hit> = withContext(Dispatchers.IO) {
        if (!signedIn()) return@withContext emptyList()
        val html = page("$MOBILE/video?q=" + URLEncoder.encode(query, "UTF-8") + "&section=search") ?: return@withContext emptyList()
        val seen = LinkedHashMap<String, Hit>()
        for (m in LINK.findAll(html)) {
            val id = m.groupValues[1]
            if (id in seen) continue
            val around = html.substring(m.range.first, minOf(html.length, m.range.first + 2_500))
            val title = Regex("""(?:aria-label|title|alt)="([^"]{3,200})"""").find(around)?.groupValues?.get(1)?.let(::unescape)
                ?: Regex("""class="[^"]*[Tt]itle[^"]*"[^>]*>([\s\S]{1,300}?)</""").find(around)?.groupValues?.get(1)?.let(::unescape)
                ?: continue
            val thumb = Regex("""(https://[^"'\s)]+\.(?:jpg|jpeg|webp)[^"'\s)]*)""").find(around)?.groupValues?.get(1)?.let(::unescape)
            val dm = Regex("""class="[^"]*[Dd]uration[^"]*"[^>]*>\s*([\d:]+)""").find(around)?.groupValues?.get(1)
                ?.let { DURATION.find(it) }
            seen[id] = Hit(id, title, "VK", dm?.value, thumb, dm?.value?.let(::seconds) ?: 0)
            if (seen.size >= 25) break
        }
        TrackLog.d(TAG, "VK: ${seen.size} videos for \"$query\" (m.vk.com, ${html.length} chars)")
        seen.values.toList()
    }

    /**
     * Opens [url] in a hidden browser that shares the VK sign-in, and every [POLL_MS] runs
     * [js] (which returns a JSON string, or null while not ready) until [parse] makes
     * something of it [settle] times running with the same answer, or [timeoutMs] passes.
     * The page's own requests are kept in [requests].
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun <T> inPage(
        url: String, timeoutMs: Long, js: String, settle: Int = 1,
        requests: MutableList<String>? = null, hook: String? = null, parse: (String) -> T?,
    ): T? {
        val ctx = appContext ?: return null
        return withContext<T?>(Dispatchers.Main) {
            var view: WebView? = null
            try {
                view = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Never plays anything itself: its sound would cut into the song's.
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.userAgentString = UA
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = QuietWebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(v: WebView, u: String?, favicon: android.graphics.Bitmap?) {
                            hook?.let { v.evaluateJavascript(it, null) }
                        }
                        override fun onPageFinished(v: WebView, u: String?) {
                            hook?.let { v.evaluateJavascript(it, null) }
                        }
                        override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                            requests?.let { synchronized(it) { if (it.size < 400) it += r.url.toString() } }
                            return null
                        }
                    }
                    loadUrl(url)
                }
                val page = view ?: return@withContext null
                var result: T? = null
                withTimeoutOrNull(timeoutMs) {
                    var last: T? = null
                    var same = 0
                    while (result == null) {
                        delay(POLL_MS)
                        val raw = eval(page, js) ?: continue
                        val got = runCatching { parse(raw) }.getOrNull() ?: continue
                        if (got == last) same++ else { last = got; same = 1 }
                        if (same >= settle) result = got
                    }
                }
                result
            } finally {
                runCatching { view?.stopLoading(); view?.destroy() }
            }
        }
    }

    /** [js]'s string result, decoded; null for none. */
    private suspend fun eval(view: WebView, js: String): String? = suspendCancellableCoroutine { c ->
        view.evaluateJavascript(js) { raw ->
            val s = if (raw == null || raw == "null" || raw == "undefined") null
            else runCatching { JSONArray("[$raw]").optString(0).takeIf { it.isNotEmpty() && it != "null" } }.getOrNull()
            if (c.isActive) c.resume(s)
        }
    }

    private const val POLL_MS = 900L

    /** The result cards drawn on vkvideo.ru: id, title, picture, duration, channel. */
    private val SEARCH_JS = """
        (function(){
          var BADGE=/^(?:4K|UHD|FHD|QHD|HD|SD|\d{3,4}p)?\s*(?:\d{1,2}:)?\d{1,2}:\d{2}\s*/i;
          function clean(t){return (t||'').replace(/\s+/g,' ').trim();}
          function meta(t){return !t||t.length<2||/^(?:4K|UHD|FHD|QHD|HD|SD|\d{3,4}p)$/i.test(t)||/^(?:\d{1,2}:)?\d{1,2}:\d{2}$/.test(t)||
            /просмотр|views|назад|\bago\b|^\d[\d\s.,KkMмтыс]*$/i.test(t);}
          function idOf(a){var m=(a.getAttribute('href')||'').match(/video(-?\d+_\d+)/);return m?m[1]:null;}
          var ids=[],info={};
          var as=document.querySelectorAll('a[href*="video"]');
          for(var i=0;i<as.length;i++){
            var a=as[i];var id=idOf(a);if(!id)continue;
            if(!info[id]){if(ids.length>=25)continue;info[id]={ts:[],th:'',d:'',card:null};ids.push(id);}
            var o=info[id];
            var raw=clean(a.getAttribute('aria-label')||a.getAttribute('title')||a.textContent);
            var dm=raw.match(/(?:\d{1,2}:)?\d{1,2}:\d{2}/);if(dm&&!o.d)o.d=dm[0];
            var t=clean(raw.replace(BADGE,''));if(!meta(t))o.ts.push(t);
            if(!o.th){var img=a.querySelector('img');if(img)o.th=img.currentSrc||img.src||'';}
            if(!o.th){var bg=a.querySelector('[style*="background-image"]');var st=bg?bg.getAttribute('style'):(a.getAttribute('style')||'');var mm=(st||'').match(/url\(["']?([^"')]+)/);if(mm)o.th=mm[1];}
            var c=a;
            for(var k=0;k<7&&c.parentElement;k++){
              var up=c.parentElement;var links=up.querySelectorAll('a[href*="video"]');var other=false;
              for(var j=0;j<links.length;j++){var x=idOf(links[j]);if(x&&x!==id){other=true;break;}}
              if(other)break;c=up;
            }
            if(!o.card||o.card.contains(c))o.card=c;
          }
          var out=[];
          for(var n=0;n<ids.length;n++){
            var o=info[ids[n]];var lines=[];
            if(o.card){lines=(o.card.innerText||'').split('\n').map(function(l){return clean(l.replace(BADGE,''));}).filter(function(l){return !meta(l);});}
            var title=o.ts.sort(function(p,q){return q.length-p.length;})[0]||lines[0]||'';
            if(!title)continue;
            var ch='';
            if(o.card){var ce=o.card.querySelector('[class*="owner"],[class*="Owner"],[class*="author"],[class*="Author"],[class*="channel"],[class*="Channel"],[class*="group"],[class*="Group"]');if(ce)ch=clean(ce.textContent);}
            if(!ch||ch===title){for(var l=0;l<lines.length;l++){if(lines[l]!==title&&title.indexOf(lines[l])<0){ch=lines[l];break;}}}
            if(!o.th&&o.card){var im=o.card.querySelector('img');if(im)o.th=im.currentSrc||im.src||'';}
            out.push({id:ids[n],t:title,th:o.th,d:o.d,a:ch});
          }
          return out.length?JSON.stringify(out):null;
        })()
    """.trimIndent()

    private val SOURCE = Regex("""<source[^>]+src="([^"]+)"[^>]*>""")
    private val URL_N = Regex(""""url(\d{3,4})"\s*:\s*"([^"]+)"""")

    /**
     * The picture of video [id], at most [maxHeight] tall: one of its progressive MP4s (they
     * carry picture and sound together) read from its page or from the player's own answers in
     * a hidden browser; else its HLS playlist. Never a stream sniffed off the network: those
     * were DASH pieces — sometimes the sound alone, which played with no picture.
     */
    suspend fun stream(id: String, maxHeight: Int = 1080): Stream? {
        val found = LinkedHashMap<Int, String>()
        var hls: String? = null
        withContext(Dispatchers.IO) {
            for (u in listOf("$MOBILE/video$id", "https://vkvideo.ru/video$id")) {
                page(u)?.let { collect(it, found); if (hls == null) hls = hlsOf(it) }
                if (found.isNotEmpty()) break
            }
        }
        if (found.isEmpty()) {
            val requests = mutableListOf<String>()
            runCatching {
                inPage("https://vkvideo.ru/video$id", 20_000, STREAM_JS, requests = requests, hook = STREAM_HOOK) { raw ->
                    val map = LinkedHashMap<Int, String>().also { collect(raw, it) }
                    if (map.isEmpty()) hlsOf(raw)?.let { hls = it }
                    map.takeIf { it.isNotEmpty() || hls != null }
                }
            }.getOrNull()?.let { found.putAll(it) }
            if (found.isEmpty() && hls == null) synchronized(requests) {
                hls = requests.firstOrNull { it.contains(".m3u8") }
            }
        }
        val headers = mapOf("User-Agent" to UA, "Referer" to "https://vkvideo.ru/", "Cookie" to cookies())
        val best = found.keys.filter { it in 1..maxHeight }.maxOrNull()?.let { it to found[it]!! }
            ?: found.entries.minByOrNull { it.key }?.let { it.key to it.value }
        if (best == null) {
            val playlist = hls ?: run {
                TrackLog.w(TAG, "VK: no picture for $id (signed in: ${signedIn()})")
                return null
            }
            TrackLog.d(TAG, "VK: HLS for $id")
            return Stream(playlist, headers, 1280, 720, hls = true)
        }
        val height = best.first.takeIf { it > 0 } ?: 720
        TrackLog.d(TAG, "VK: MP4 for $id (${height}p)")
        return Stream(best.second, headers, height * 16 / 9, height)
    }

    private val HLS = Regex(""""hls(?:_ondemand|_fmp4)?"\s*:\s*"([^"]+)"""")

    private fun hlsOf(text: String): String? = HLS.find(text)?.groupValues?.get(1)?.let(::unescape)?.takeIf { it.startsWith("http") }

    /** The "url720": "…" MP4s and <source> tags in [text], by height. */
    private fun collect(text: String, found: MutableMap<Int, String>) {
        URL_N.findAll(text).forEach { m -> m.groupValues[1].toIntOrNull()?.let { h -> found.putIfAbsent(h, unescape(m.groupValues[2])) } }
        SOURCE.findAll(text).forEach { m ->
            val url = unescape(m.groupValues[1])
            val h = Regex("""\.(\d{3,4})\.mp4""").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            if (url.startsWith("http")) found.putIfAbsent(h, url)
        }
    }

    /** Keeps every API answer the page gets that names MP4s, for [STREAM_JS] to read. */
    private val STREAM_HOOK = """
        (function(){
          if(window.__bcVk)return;window.__bcVk=[];
          var keep=function(t){try{if(t&&/"(?:url\d{3,4}|hls)"\s*:/.test(t))window.__bcVk.push(t.slice(0,200000));}catch(e){}};
          try{var pl=HTMLMediaElement.prototype.play;HTMLMediaElement.prototype.play=function(){this.muted=true;this.volume=0;return pl.apply(this,arguments);};}catch(e){}
          var f=window.fetch;if(f){window.fetch=function(){var r=f.apply(this,arguments);try{r.then(function(x){x.clone().text().then(keep).catch(function(){});}).catch(function(){});}catch(e){}return r;};}
          var o=XMLHttpRequest.prototype.open,s=XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.send=function(){var x=this;try{x.addEventListener('load',function(){try{keep(x.responseText);}catch(e){}});}catch(e){}return s.apply(this,arguments);};
        })()
    """.trimIndent()

    /** What the drawn video page holds: kept API answers, its HTML, a <video>'s own file. */
    private val STREAM_JS = """
        (function(){
          var v=document.querySelector('video');if(v){try{v.muted=true;v.pause();}catch(e){}}
          var parts=(window.__bcVk||[]).slice();
          var h=document.documentElement.innerHTML;if(/"(?:url\d{3,4}|hls)"\s*:/.test(h))parts.push(h);
          return parts.length?parts.join('\n'):null;
        })()
    """.trimIndent()
}

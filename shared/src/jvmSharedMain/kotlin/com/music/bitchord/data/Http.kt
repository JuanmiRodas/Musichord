package com.music.bitchord.data

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One OkHttp client for the whole app.
 *
 * This matters: googlevideo binds a stream URL to the connection context of
 * the `player` request that minted it. If Innertube and ExoPlayer used
 * separate HTTP stacks they could resolve to different addresses (v4 vs v6)
 * and the media fetch would come back 403. Sharing the client keeps DNS,
 * address family and connection pooling identical for both.
 *
 * That sharing has a cost the defaults don't budget for. Every request the
 * app makes — [Innertube]'s player/browse calls, NewPipe's own signature and
 * `next`-endpoint fetches, [ChunkedDataSource][com.music.bitchord.playback.ChunkedDataSource]
 * and [AudioCache][com.music.bitchord.playback.AudioCache]'s multi-megabyte
 * chunk downloads, and every [StreamResolver] probe — funnels through this
 * one client, and OkHttp's stock [Dispatcher] allows only 5 requests in
 * flight to a single host at a time. A track streaming while its successor
 * pre-caches is two or three of those requests already; a resolve running
 * alongside them queues behind whichever is occupying the rest — invisibly,
 * since a request stuck in OkHttp's queue and a slow server both just look
 * like a request that took several extra seconds. Sized well past anything
 * this app actually drives concurrently, so the queue is never the reason a
 * request was slow.
 */
object Http {

    // ---- Temporary data-usage instrumentation --------------------------
    //
    // Added to answer, with real numbers instead of code-reading, which of
    // this app's network callers actually account for the reported data
    // usage (issue #48): every request funnels through this one client, so
    // a network interceptor here sees every byte the app receives,
    // regardless of which subsystem asked for it. Categorised by host —
    // googlevideo.com is YouTube's own audio bytes (and StreamResolver's
    // probes, distinguishable by their small actual read against a 2MB
    // Range ask), a canvas provider's CDN is motion artwork, youtube.com /
    // youtubei is API/extraction traffic. Grep logcat for "BCDataUsage"
    // during a play session; each line is one response with its actual
    // transferred bytes and the running total for its host.
    //
    // Left in place rather than deleted — the investigation this served is
    // not the last one this app will need — but off by default: flip to
    // true only while actively measuring. Wrapping every response body in a
    // counting source and writing a log line per request is not free, and
    // paying that on every install for a question already answered is the
    // wrong trade.
    private const val USAGE_LOGGING_ENABLED = false
    private const val USAGE_TAG = "BCDataUsage"
    private val usageTotals = ConcurrentHashMap<String, AtomicLong>()

    private class CountingSource(source: Source, private val onClose: (Long) -> Unit) :
        ForwardingSource(source) {
        private var bytes = 0L
        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(sink, byteCount)
            if (read > 0) bytes += read
            return read
        }
        override fun close() {
            super.close()
            onClose(bytes)
        }
    }

    private class CountedBody(
        private val delegate: ResponseBody,
        private val counted: BufferedSource,
    ) : ResponseBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun source() = counted
    }

    private val usageInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val body = response.body
        if (body == null) {
            response
        } else {
            val host = request.url.host
            val range = request.header("Range")
            val counting = CountingSource(body.source()) { bytes ->
                val total = usageTotals.computeIfAbsent(host) { AtomicLong() }.addAndGet(bytes)
                DebugLog.d(
                    USAGE_TAG,
                    "$host ${request.method} ${request.url.encodedPath} " +
                        "range=$range status=${response.code} bytes=$bytes total[$host]=$total",
                )
            }.buffer()
            response.newBuilder().body(CountedBody(body, counting)).build()
        }
    }
    // ---- End temporary instrumentation ----------------------------------

    /**
     * Adds the WebDAV Basic credential to requests aimed at the configured
     * server, so both PROPFIND listings and ExoPlayer's media fetches (which
     * share this client via OkHttpDataSource) authenticate the same way.
     *
     * Read off [com.music.bitchord.data.webdav.WebDavAuth] rather than prefs
     * so the network layer never touches storage: AppSettings publishes here.
     */
    private val webDavInterceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        if (request.header("Authorization") == null &&
            com.music.bitchord.data.webdav.WebDavAuth.shouldAuthorize(request.url.host)
        ) {
            val header = com.music.bitchord.data.webdav.WebDavAuth.authHeader
            if (!header.isNullOrBlank()) {
                return@Interceptor proceedWebDav(chain, request.newBuilder().header("Authorization", header).build())
            }
        }
        if (com.music.bitchord.data.webdav.WebDavAuth.shouldAuthorize(request.url.host) ||
            com.music.bitchord.data.webdav.PcloudApiBridge.isPcloudHost(request.url.host)
        ) {
            return@Interceptor proceedWebDav(chain, request)
        }
        chain.proceed(request)
    }

    /** BitChord Audio: path prefixes known to live under the other Unicode form. */
    private val renamedPrefixes = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * BitChord Audio: a WebDAV request whose path has accents asked for in the
     * other Unicode form when the server says 404.
     *
     * A Mac writes "Música" decomposed (u + combining accent, `Mu%CC%81sica`);
     * the same folder can be served precomposed (`M%C3%BAsica`) once it has
     * been touched from elsewhere. Every file then answered 404 to the
     * addresses the library had listed — the pCloud library "broke". The
     * answer is remembered per folder, so only the first file pays the miss.
     */
    private fun proceedWebDav(chain: okhttp3.Interceptor.Chain, original: okhttp3.Request): okhttp3.Response {
        val bridge = com.music.bitchord.data.webdav.PcloudApiBridge
        // BitChord Audio: signed in through pCloud's own page — the API answers everything.
        if (bridge.isPcloudHost(original.url.host) && bridge.signedInWithPcloud()) {
            bridge.answer(chain, original)?.let { return it }
        }
        bridge.blocked(original)?.let { return it }
        // BitChord Audio: pCloud's WebDAV refusing everything — its API answers instead.
        if (bridge.isPcloudHost(original.url.host) && bridge.webdavDown()) {
            bridge.answer(chain, original)?.let { return it }
        }
        val response = proceedWebDavDirect(chain, original)
        if (bridge.isPcloudHost(original.url.host) && bridge.refusal(response.code)) {
            // The refusal is read out and closed first: OkHttp lets an interceptor make a new
            // request only once the previous response is closed.
            val type = response.body?.contentType()
            val bytes = runCatching { response.body?.bytes() }.getOrNull() ?: ByteArray(0)
            response.close()
            val refused = response.newBuilder().body(with(okhttp3.ResponseBody.Companion) { bytes.toResponseBody(type) }).build()
            val viaApi = runCatching { bridge.answer(chain, original) }.getOrNull()
            if (viaApi != null && viaApi.code != 404) {
                bridge.markWebdavDown()
                return viaApi
            }
            viaApi?.close()
            return refused
        }
        return response
    }

    private fun proceedWebDavDirect(chain: okhttp3.Interceptor.Chain, original: okhttp3.Request): okhttp3.Response {
        var request = original
        val url = request.url.toString()
        renamedPrefixes.entries.firstOrNull { url.startsWith(it.key) }?.let { (from, to) ->
            request = request.newBuilder().url(to + url.removePrefix(from)).build()
        }
        val response = chain.proceed(request)
        if (response.code != 404) return response
        val path = request.url.encodedPath
        val decoded = runCatching { java.net.URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return response
        if (decoded.all { it.code < 128 }) return response
        for (form in listOf(java.text.Normalizer.Form.NFC, java.text.Normalizer.Form.NFD)) {
            val other = java.text.Normalizer.normalize(decoded, form)
            if (other == decoded) continue
            val otherUrl = request.url.newBuilder().encodedPath("/").apply {
                other.trim('/').split('/').forEach { addPathSegment(it) }
                if (decoded.endsWith("/")) addPathSegment("")
            }.build()
            response.close()
            val retry = chain.proceed(request.newBuilder().url(otherUrl).build())
            if (retry.code != 404) {
                // Remembered from the first folder that differs, so the rest of the library
                // under it goes straight to the right form.
                val a = request.url.encodedPathSegments
                val b = otherUrl.encodedPathSegments
                val at = a.indices.firstOrNull { it < b.size && a[it] != b[it] }
                if (at != null && at < a.size - 1) {
                    val base = request.url.scheme + "://" + request.url.host + (if (request.url.port != okhttp3.HttpUrl.defaultPort(request.url.scheme)) ":" + request.url.port else "")
                    renamedPrefixes[base + "/" + a.take(at + 1).joinToString("/") + "/"] =
                        base + "/" + b.take(at + 1).joinToString("/") + "/"
                }
            }
            return retry
        }
        return response
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .addInterceptor(webDavInterceptor)
        .apply { if (USAGE_LOGGING_ENABLED) addNetworkInterceptor(usageInterceptor) }
        .build()
}

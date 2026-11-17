package com.music.bitchord.data.webdav

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Base64

/**
 * BitChord Audio: pCloud's own HTTP API standing in for its WebDAV, when the
 * WebDAV server stops answering (404 even for the drive's root, as it did
 * for this library on 17 Nov 2026, or WebDAV turned off for an account).
 *
 * The rest of the app keeps speaking WebDAV to `ewebdav.`/`webdav.pcloud.com`
 * — addresses, file ids, links to files all stay as they were. Under that,
 * a refused request is answered here instead:
 *  - PROPFIND (depth 0 or 1) from `listfolder`, written back as the
 *    multistatus XML a WebDAV server would have sent;
 *  - GET/HEAD from `getfilelink`: the file's own download address on
 *    pCloud's storage servers, fetched with the caller's Range header.
 * Writes (lyrics, tags) are not bridged and fail as before.
 *
 * Signs in with the same email and password (the Basic credential the app
 * already sends), on the API host of the same region (`eapi.` for EU,
 * `api.` for US), and keeps the token for the session.
 */
object PcloudApiBridge {

    private val json = Json { ignoreUnknownKeys = true }

    /** WebDAV refused recently: requests go straight to the API for a while. */
    @Volatile private var webdavDownUntil = 0L

    @Volatile private var token: String? = null
    @Volatile private var preferredApi: String? = null
    private val refusedUntil = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String?>>()
    @Volatile private var tokenFor: String? = null

    @Volatile var lastError: String? = null
        private set

    fun isPcloudHost(host: String): Boolean =
        host.equals("webdav.pcloud.com", true) || host.equals("ewebdav.pcloud.com", true)

    private fun apiHost(webdavHost: String): String =
        if (webdavHost.startsWith("e", ignoreCase = true)) "eapi.pcloud.com" else "api.pcloud.com"

    fun webdavDown(): Boolean = System.currentTimeMillis() < webdavDownUntil

    fun markWebdavDown() {
        webdavDownUntil = System.currentTimeMillis() + 10 * 60_000L
    }

    /** Whether a WebDAV answer is one the API should be asked about instead. */
    fun refusal(code: Int): Boolean = code == 404 || code == 403 || code == 401 || code == 405 || code in 500..599

    /**
     * A request with the email and password pCloud just refused: answered 401 here, without
     * reaching pCloud — each WebDAV request is a sign-in attempt to pCloud too, and the phone
     * playing through a library kept its address blocked. A changed password goes through.
     */
    fun blocked(request: Request): Response? {
        if (!isPcloudHost(request.url.host)) return null
        val credentials = credentials(request.header("Authorization")) ?: return null
        val now = System.currentTimeMillis()
        val hit = refusedUntil.entries.firstOrNull { (key, value) ->
            key.endsWith("|" + credentials.first + "|" + credentials.second.hashCode()) && now < value.first
        } ?: return null
        lastError = hit.value.second
        return synthetic(request, 401, "Unauthorized", "", "text/plain", null)
    }

    /** The bridged answer to [request], or null when the API can't give one either. */
    fun answer(chain: Interceptor.Chain, request: Request): Response? {
        if (!isPcloudHost(request.url.host)) return null
        val credentials = credentials(request.header("Authorization")) ?: return null
        val method = request.method.uppercase()
        if (method != "PROPFIND" && method != "GET" && method != "HEAD") return null
        lastError = null
        // The region's API host first, then the other (an account lives in one only).
        var api = preferredApi ?: apiHost(request.url.host)
        var auth = auth(chain, api, credentials)
        if (auth == null && lastError?.contains("(2321") == true) {
            val other = if (api.startsWith("e")) "api.pcloud.com" else "eapi.pcloud.com"
            auth(chain, other, credentials)?.let { api = other; auth = it; preferredApi = other }
        }
        val signed = auth ?: return null
        val path = pathOf(request)
        return runCatching {
            when (method) {
                "PROPFIND" -> propfind(chain, request, api, signed, path)
                else -> download(chain, request, api, signed, path)
            }
        }.onFailure { lastError = it.message }.getOrNull()
    }

    // ---- sign-in ---------------------------------------------------------------------

    private fun credentials(header: String?): Pair<String, String>? {
        val h = header ?: return null
        if (!h.startsWith("Basic ", ignoreCase = true)) return null
        val raw = runCatching { String(Base64.getDecoder().decode(h.substring(6).trim()), Charsets.UTF_8) }.getOrNull() ?: return null
        val at = raw.indexOf(':')
        if (at <= 0) return null
        return raw.substring(0, at) to raw.substring(at + 1)
    }

    private fun auth(chain: Interceptor.Chain, api: String, credentials: Pair<String, String>): String? {
        val key = api + "|" + credentials.first + "|" + credentials.second.hashCode()
        token?.takeIf { tokenFor == key }?.let { return it }
        // A refused sign-in is not asked again with the same email and password for a
        // while: every file request used to try again, and pCloud then blocks the
        // phone's address for too many attempts (4000).
        refusedUntil[key]?.let { (until, why) ->
            if (System.currentTimeMillis() < until) {
                lastError = why
                return null
            }
        }
        val url = "https://$api/userinfo?getauth=1&username=" + enc(credentials.first) + "&password=" + enc(credentials.second)
        val result = call(chain, url) ?: return null
        val code = result["result"]?.jsonPrimitive?.intOrNull ?: -1
        if (code != 0) {
            lastError = "pCloud sign-in refused (${code}: ${result["error"]?.jsonPrimitive?.content})"
            refusedUntil[key] = (System.currentTimeMillis() + if (code == 4000) 60 * 60_000L else 15 * 60_000L) to lastError
            return null
        }
        val t = result["auth"]?.jsonPrimitive?.content ?: return null
        token = t
        tokenFor = key
        return t
    }

    private fun call(chain: Interceptor.Chain, url: String): JsonObject? {
        val response = chain.proceed(Request.Builder().url(url).header("Accept", "application/json").build())
        return response.use { r ->
            val text = r.body?.string() ?: return null
            runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        }
    }

    /** A token that went stale: dropped, so the next request signs in again. */
    private fun checkToken(result: JsonObject) {
        val code = result["result"]?.jsonPrimitive?.intOrNull ?: return
        if (code == 1000 || code == 2000 || code == 2094 || code == 2095) token = null
    }

    // ---- paths -------------------------------------------------------------------------

    private fun pathOf(request: Request): String {
        val decoded = runCatching { URLDecoder.decode(request.url.encodedPath.replace("+", "%2B"), "UTF-8") }
            .getOrDefault(request.url.encodedPath)
        val trimmed = decoded.trimEnd('/')
        return if (trimmed.isEmpty()) "/" else trimmed
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** The path as given, then in the other Unicode form (a Mac's "Música" vs the precomposed one). */
    private fun forms(path: String): List<String> =
        listOf(path, Normalizer.normalize(path, Normalizer.Form.NFC), Normalizer.normalize(path, Normalizer.Form.NFD)).distinct()

    // ---- PROPFIND ------------------------------------------------------------------------

    private fun propfind(chain: Interceptor.Chain, request: Request, api: String, auth: String, path: String): Response? {
        val depth = request.header("Depth") ?: "1"
        var listed: JsonObject? = null
        for (p in forms(path)) {
            val result = call(chain, "https://$api/listfolder?auth=$auth&path=" + enc(p)) ?: continue
            checkToken(result)
            if (result["result"]?.jsonPrimitive?.intOrNull == 0) {
                listed = result["metadata"]?.jsonObject
                break
            }
            lastError = "pCloud listfolder $p: ${result["error"]?.jsonPrimitive?.content}"
        }
        val folder = listed ?: return notFound(request)
        val base = request.url.newBuilder().encodedPath("/").build().toString().trimEnd('/')
        val selfHref = request.url.encodedPath.let { if (it.endsWith("/")) it else "$it/" }
        val xml = StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n")
        entry(xml, selfHref, folder)
        if (depth != "0") {
            val contents = folder["contents"] as? JsonArray ?: JsonArray(emptyList())
            for (child in contents) {
                val o = child.jsonObject
                val name = o["name"]?.jsonPrimitive?.content ?: continue
                val isFolder = o["isfolder"]?.jsonPrimitive?.booleanOrNull == true
                val href = selfHref + enc(name) + if (isFolder) "/" else ""
                entry(xml, href, o)
            }
        }
        xml.append("</D:multistatus>\n")
        com.music.bitchord.data.TrackLog.d("BitChord", "pCloud: WebDAV refused $path; listed it through pCloud's API instead")
        return synthetic(request, 207, "Multi-Status", xml.toString(), "application/xml; charset=utf-8", base)
    }

    private fun entry(xml: StringBuilder, href: String, o: JsonObject) {
        val name = o["name"]?.jsonPrimitive?.content.orEmpty()
        val isFolder = o["isfolder"]?.jsonPrimitive?.booleanOrNull == true
        xml.append("<D:response><D:href>").append(esc(href)).append("</D:href><D:propstat><D:prop>")
        xml.append("<D:displayname>").append(esc(name)).append("</D:displayname>")
        if (isFolder) {
            xml.append("<D:resourcetype><D:collection/></D:resourcetype>")
        } else {
            xml.append("<D:resourcetype/>")
            o["size"]?.jsonPrimitive?.longOrNull?.let { xml.append("<D:getcontentlength>").append(it).append("</D:getcontentlength>") }
            o["contenttype"]?.jsonPrimitive?.content?.let { xml.append("<D:getcontenttype>").append(esc(it)).append("</D:getcontenttype>") }
        }
        o["modified"]?.jsonPrimitive?.content?.let { xml.append("<D:getlastmodified>").append(esc(it)).append("</D:getlastmodified>") }
        xml.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>\n")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ---- GET / HEAD -----------------------------------------------------------------------

    private fun download(chain: Interceptor.Chain, request: Request, api: String, auth: String, path: String): Response? {
        for (p in forms(path)) {
            val result = call(chain, "https://$api/getfilelink?auth=$auth&forcedownload=1&path=" + enc(p)) ?: continue
            checkToken(result)
            if (result["result"]?.jsonPrimitive?.intOrNull != 0) {
                lastError = "pCloud getfilelink $p: ${result["error"]?.jsonPrimitive?.content}"
                continue
            }
            val hosts = result["hosts"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: continue
            val filePath = result["path"]?.jsonPrimitive?.content ?: continue
            val host = hosts.firstOrNull() ?: continue
            val direct = Request.Builder().url("https://$host$filePath").method(request.method, null).apply {
                request.header("Range")?.let { header("Range", it) }
                request.header("If-Range")?.let { header("If-Range", it) }
            }.build()
            val real = chain.proceed(direct)
            // Handed back as the answer to the original address, so caches and the player
            // keep keying it by the WebDAV URL they asked for.
            return real.newBuilder().request(request).build()
        }
        return notFound(request)
    }

    private fun notFound(request: Request): Response =
        synthetic(request, 404, "Not Found", "", "text/plain", null)

    private fun synthetic(request: Request, code: Int, message: String, body: String, type: String, @Suppress("UNUSED_PARAMETER") base: String?): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .body(body.toResponseBody(type.toMediaType()))
            .build()
}

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

    /**
     * A token from pCloud's own sign-in page (OAuth, which takes the emailed code a
     * password sign-in can't), and the API host it belongs to. When set, every pCloud
     * request goes through the API with it — no password involved at all.
     */
    @Volatile var oauthToken: String? = null
    @Volatile var oauthApi: String? = null

    fun signedInWithPcloud(): Boolean = !oauthToken.isNullOrBlank()
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
        val method = request.method.uppercase()
        if (method != "PROPFIND" && method != "GET" && method != "HEAD") return null
        oauthToken?.takeIf { it.isNotBlank() }?.let { bearer ->
            lastError = null
            val api = oauthApi ?: apiHost(request.url.host)
            val path = pathOf(request)
            return runCatching {
                when (method) {
                    "PROPFIND" -> propfind(chain, request, api, "", path, bearer)
                    else -> download(chain, request, api, "", path, bearer)
                }
            }.onFailure { lastError = it.message }.getOrNull()
        }
        val credentials = credentials(request.header("Authorization")) ?: return null
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

    /** Set by the app: keeps a session from [finishLogin] (stored encrypted, "auth:<token>"). */
    @Volatile var onToken: ((token: String, apiHost: String) -> Unit)? = null

    /** The challenge pCloud answered the last [startLogin] with: its token and API host. */
    @Volatile private var pendingTfa: Pair<String, String>? = null

    sealed interface Login {
        object Done : Login
        /** pCloud has sent a code (by email, or SMS/app for 2FA accounts): give it to [finishLogin]. */
        data class NeedsCode(val tfaType: Int?) : Login
    }

    private fun post(client: okhttp3.OkHttpClient, api: String, method: String, params: Map<String, String>): JsonObject? {
        val body = okhttp3.FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        return runCatching {
            client.newCall(Request.Builder().url("https://$api/$method").post(body).build()).execute().use { r ->
                json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
            }
        }.getOrNull()
    }

    private fun deviceParams() = mapOf(
        "deviceid" to "bitchord-audio-android",
        "device" to "BitChord Audio",
        "os" to "4",
        // A year, renewed by use; revocable from pcloud.com → Settings → Devices.
        "authexpire" to (365 * 24 * 3600).toString(),
        "authinactiveexpire" to (90 * 24 * 3600).toString(),
    )

    private fun keep(api: String, result: JsonObject): Boolean {
        val t = result["auth"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return false
        val host = result["apiserver"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: api
        preferredApi = host
        refusedUntil.clear()
        pendingTfa = null
        oauthToken = "auth:$t"
        oauthApi = host
        onToken?.invoke("auth:$t", host)
        return true
    }

    /**
     * Signing in the way pCloud's own apps do since it stopped taking a bare
     * email and password from the API (April 2026): `login` with a device, and
     * when pCloud wants a code it sends one and answers with a token for it —
     * [finishLogin] trades token and code for a session. The session is kept
     * (encrypted, by the app), so this happens once.
     */
    fun startLogin(client: okhttp3.OkHttpClient, username: String, password: String): Result<Login> {
        var last = "pCloud sign-in failed"
        for (api in listOf(preferredApi ?: "eapi.pcloud.com", "eapi.pcloud.com", "api.pcloud.com").distinct()) {
            val result = post(client, api, "login", mapOf("getauth" to "1", "username" to username, "password" to password) + deviceParams())
                ?: continue
            val code = result["result"]?.jsonPrimitive?.intOrNull ?: -1
            if (code == 0 && keep(api, result)) return Result.success(Login.Done)
            val token = result["token"]?.jsonPrimitive?.content
            if (!token.isNullOrBlank()) {
                pendingTfa = token to (result["apiserver"]?.jsonPrimitive?.content ?: api)
                return Result.success(Login.NeedsCode(result["tfatype"]?.jsonPrimitive?.intOrNull))
            }
            last = "pCloud sign-in refused ($code: ${result["error"]?.jsonPrimitive?.content})"
            if (code == 2321) continue
            if (code != 2000 && code != 1022) break
        }
        lastError = last
        return Result.failure(IllegalStateException(last))
    }

    /** The code pCloud sent, for the challenge [startLogin] got (started again when there is none). */
    fun finishLogin(client: okhttp3.OkHttpClient, username: String, password: String, code: String): Result<Login> {
        val (token, api) = pendingTfa ?: run {
            val started = startLogin(client, username, password).getOrElse { return Result.failure(it) }
            if (started == Login.Done) return Result.success(Login.Done)
            pendingTfa ?: return Result.failure(IllegalStateException("pCloud sent no code"))
        }
        val result = post(client, api, "tfa_login", mapOf("getauth" to "1", "token" to token, "code" to code.trim(), "trustdevice" to "1") + deviceParams())
            ?: return Result.failure(IllegalStateException("pCloud didn't answer"))
        val c = result["result"]?.jsonPrimitive?.intOrNull ?: -1
        if (c == 0 && keep(api, result)) return Result.success(Login.Done)
        val why = "pCloud refused the code ($c: ${result["error"]?.jsonPrimitive?.content})"
        lastError = why
        return Result.failure(IllegalStateException(why))
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
            if (code == 1022) lastError = "pCloud asks for the code it emails you (1022): write it in the code field and tap Test"
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

    private fun authParam(auth: String, bearer: String?) = when {
        bearer == null -> "auth=$auth"
        bearer.startsWith("auth:") -> "auth=" + enc(bearer.removePrefix("auth:"))
        else -> "access_token=" + enc(bearer)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** The path as given, then in the other Unicode form (a Mac's "Música" vs the precomposed one). */
    private fun forms(path: String): List<String> =
        listOf(path, Normalizer.normalize(path, Normalizer.Form.NFC), Normalizer.normalize(path, Normalizer.Form.NFD)).distinct()

    // ---- PROPFIND ------------------------------------------------------------------------

    private fun propfind(chain: Interceptor.Chain, request: Request, api: String, auth: String, path: String, bearer: String? = null): Response? {
        val depth = request.header("Depth") ?: "1"
        var listed: JsonObject? = null
        for (p in forms(path)) {
            val result = call(chain, "https://$api/listfolder?" + authParam(auth, bearer) + "&path=" + enc(p)) ?: continue
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

    private fun download(chain: Interceptor.Chain, request: Request, api: String, auth: String, path: String, bearer: String? = null): Response? {
        for (p in forms(path)) {
            val result = call(chain, "https://$api/getfilelink?" + authParam(auth, bearer) + "&forcedownload=1&path=" + enc(p)) ?: continue
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

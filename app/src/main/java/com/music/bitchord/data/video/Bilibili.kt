package com.music.bitchord.data.video

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * BitChord Audio: bilibili videos for video mode — searched only when asked
 * (the video search's "Also search bilibili"), and played as the plain MP4
 * its HTML5 player gets, at what bilibili serves without an account.
 */
object Bilibili {
    private const val TAG = "VideoMode"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Hit(val bvid: String, val title: String, val author: String, val durationText: String?, val thumbnail: String?)

    /** [audioUrl]: set when the picture is a DASH video track with its sound apart. */
    data class Stream(val url: String, val headers: Map<String, String>, val width: Int, val height: Int, val audioUrl: String? = null)

    /** bilibili's search wants a visitor cookie; it hands one out for the asking. */
    @Volatile private var buvid: String? = null

    private fun visitor(): String? {
        buvid?.let { return it }
        val body = get("https://api.bilibili.com/x/frontend/finger/spi", cookie = false) ?: return null
        val b3 = runCatching { (obj(obj(json.parseToJsonElement(body))?.get("data"))?.get("b_3"))?.jsonPrimitive?.content }.getOrNull()
        buvid = b3
        return b3
    }

    private fun get(url: String, cookie: Boolean = true): String? = runCatching {
        val builder = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://www.bilibili.com/")
        if (cookie) visitor()?.let { builder.header("Cookie", "buvid3=$it") }
        Http.client.newCall(builder.build()).execute().use { r ->
            if (!r.isSuccessful) {
                TrackLog.w(TAG, "bilibili HTTP ${r.code} for ${url.substringBefore('?').takeLast(40)}")
                null
            } else r.body?.string()
        }
    }.onFailure { TrackLog.w(TAG, "bilibili failed: ${it.message}") }.getOrNull()

    suspend fun search(query: String): List<Hit> = withContext(Dispatchers.IO) {
        val url = "https://api.bilibili.com/x/web-interface/search/type".toHttpUrl().newBuilder()
            .addQueryParameter("search_type", "video")
            .addQueryParameter("keyword", query)
            .addQueryParameter("page", "1")
            .build().toString()
        val body = get(url) ?: return@withContext emptyList()
        val list = runCatching {
            obj(obj(json.parseToJsonElement(body))?.get("data"))?.get("result") as? JsonArray
        }.getOrNull().orEmpty()
        list.mapNotNull { e ->
            val o = obj(e) ?: return@mapNotNull null
            val bvid = str(o["bvid"]) ?: return@mapNotNull null
            Hit(
                bvid = bvid,
                title = str(o["title"]).orEmpty().replace(Regex("<[^>]+>"), "")
                    .replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'"),
                author = str(o["author"]).orEmpty(),
                // "4:20", sometimes "0:4:20".
                durationText = str(o["duration"])?.split(':')?.mapNotNull { it.toIntOrNull() }
                    ?.fold(0) { acc, p -> acc * 60 + p }?.takeIf { it > 0 }?.let { "%d:%02d".format(it / 60, it % 60) },
                thumbnail = str(o["pic"])?.let { if (it.startsWith("//")) "https:$it" else it },
            )
        }.also { TrackLog.d(TAG, "bilibili: ${it.size} videos for \"$query\"") }
    }

    /**
     * The picture for [bvid]'s first part: the plain MP4 when bilibili gives one; else, through
     * its signed (WBI) player API, the MP4 or the DASH video and sound tracks to play together.
     * Why nothing came back is logged (bilibili's own code and message: region locks, risk control).
     */
    suspend fun stream(bvid: String): Stream? = withContext(Dispatchers.IO) {
        val view = get("https://api.bilibili.com/x/web-interface/view?bvid=$bvid") ?: return@withContext null
        val root = runCatching { obj(json.parseToJsonElement(view)) }.getOrNull()
        val data = obj(root?.get("data")) ?: run {
            TrackLog.w(TAG, "bilibili: no details for $bvid (${str(root?.get("code"))} ${str(root?.get("message"))})")
            return@withContext null
        }
        val cid = data["cid"]?.jsonPrimitive?.longOrNull ?: return@withContext null
        val dim = obj(data["dimension"])
        var w = dim?.get("width")?.jsonPrimitive?.intOrNull ?: 16
        var h = dim?.get("height")?.jsonPrimitive?.intOrNull ?: 9
        if (dim?.get("rotate")?.jsonPrimitive?.intOrNull == 1) { val t = w; w = h; h = t }
        val headers = mapOf("Referer" to "https://www.bilibili.com/", "User-Agent" to UA)
        var why = ""
        fun durlOf(body: String?): String? {
            val o = runCatching { obj(json.parseToJsonElement(body ?: return null)) }.getOrNull() ?: return null
            val d = obj(o["data"])
            if (d == null) { why = "${str(o["code"])} ${str(o["message"])}"; return null }
            return str(obj((d["durl"] as? JsonArray)?.firstOrNull())?.get("url"))
        }
        // 1. The HTML5 player's MP4.
        durlOf(get("https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=80&fnval=1&fnver=0&fourk=0&platform=html5&high_quality=1"))?.let {
            TrackLog.d(TAG, "bilibili: MP4 for $bvid (${w}x$h)")
            return@withContext Stream(it, headers, w, h)
        }
        // 2. The signed API: MP4 first, then DASH.
        signed(mapOf("bvid" to bvid, "cid" to cid.toString(), "qn" to "80", "fnval" to "1", "fnver" to "0", "fourk" to "0"))
            ?.let { durlOf(get("https://api.bilibili.com/x/player/wbi/playurl?$it")) }
            ?.let {
                TrackLog.d(TAG, "bilibili: MP4 (signed) for $bvid (${w}x$h)")
                return@withContext Stream(it, headers, w, h)
            }
        val dashBody = signed(mapOf("bvid" to bvid, "cid" to cid.toString(), "qn" to "80", "fnval" to "16", "fnver" to "0", "fourk" to "0"))
            ?.let { get("https://api.bilibili.com/x/player/wbi/playurl?$it") }
        val dashRoot = runCatching { obj(json.parseToJsonElement(dashBody ?: "")) }.getOrNull()
        val dash = obj(obj(dashRoot?.get("data"))?.get("dash"))
        if (dash == null) {
            if (dashRoot != null) why = "${str(dashRoot["code"])} ${str(dashRoot["message"])}"
            TrackLog.w(TAG, "bilibili: no picture for $bvid — ${why.ifBlank { "no answer" }}")
            return@withContext null
        }
        fun urlOf(t: JsonObject) = str(t["base_url"]) ?: str(t["baseUrl"]) ?: (t["backup_url"] as? JsonArray)?.firstOrNull()?.let { str(it) }
        val videos = (dash["video"] as? JsonArray).orEmpty().mapNotNull { obj(it) }
        // H.264 plays everywhere; the tallest up to 1080p.
        val video = videos.filter { (it["codecid"]?.jsonPrimitive?.intOrNull ?: 7) == 7 && (it["height"]?.jsonPrimitive?.intOrNull ?: 0) <= 1080 }
            .maxByOrNull { it["height"]?.jsonPrimitive?.intOrNull ?: 0 }
            ?: videos.maxByOrNull { it["height"]?.jsonPrimitive?.intOrNull ?: 0 }
        val audio = (dash["audio"] as? JsonArray).orEmpty().mapNotNull { obj(it) }
            .maxByOrNull { it["bandwidth"]?.jsonPrimitive?.longOrNull ?: 0L }
        val vUrl = video?.let(::urlOf) ?: run {
            TrackLog.w(TAG, "bilibili: no video track for $bvid")
            return@withContext null
        }
        val vw = video["width"]?.jsonPrimitive?.intOrNull ?: w
        val vh = video["height"]?.jsonPrimitive?.intOrNull ?: h
        TrackLog.d(TAG, "bilibili: DASH for $bvid (${vw}x$vh)")
        Stream(vUrl, headers, vw, vh, audio?.let(::urlOf))
    }

    // ── WBI: bilibili's signature on its newer player API ──

    private val MIXIN = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
    )
    @Volatile private var mixinKey: Pair<String, Long>? = null

    private fun mixin(): String? {
        mixinKey?.takeIf { System.currentTimeMillis() - it.second < 6 * 3600_000L }?.let { return it.first }
        val nav = get("https://api.bilibili.com/x/web-interface/nav") ?: return null
        val img = runCatching { obj(obj(obj(json.parseToJsonElement(nav))?.get("data"))?.get("wbi_img")) }.getOrNull() ?: return null
        fun key(u: String?) = u?.substringAfterLast('/')?.substringBefore('.')
        val raw = (key(str(img["img_url"])) ?: return null) + (key(str(img["sub_url"])) ?: return null)
        val mixed = MIXIN.filter { it < raw.length }.map { raw[it] }.joinToString("").take(32)
        mixinKey = mixed to System.currentTimeMillis()
        return mixed
    }

    /** [params] with wts and w_rid added, as a query string. */
    private fun signed(params: Map<String, String>): String? {
        val key = mixin() ?: return null
        val all = (params + ("wts" to (System.currentTimeMillis() / 1000).toString())).toSortedMap()
        fun enc(v: String) = java.net.URLEncoder.encode(v.filterNot { it in "!'()*" }, "UTF-8").replace("+", "%20")
        val query = all.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val rid = java.security.MessageDigest.getInstance("MD5").digest((query + key).toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$query&w_rid=$rid"
    }

    /** BitChord Audio: the best audio-only rendition of [bvid] — an fMP4 AAC file, served without an account. */
    data class Audio(val url: String, val headers: Map<String, String>, val kbps: Int, val durationSec: Int?)

    suspend fun audio(bvid: String): Audio? = withContext(Dispatchers.IO) {
        val view = get("https://api.bilibili.com/x/web-interface/view?bvid=$bvid") ?: return@withContext null
        val data = runCatching { obj(obj(json.parseToJsonElement(view))?.get("data")) }.getOrNull() ?: return@withContext null
        val cid = data["cid"]?.jsonPrimitive?.longOrNull ?: return@withContext null
        val length = data["duration"]?.jsonPrimitive?.intOrNull
        val play = get("https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&fnval=16&fnver=0&fourk=0")
            ?: return@withContext null
        val dash = runCatching { obj(obj(obj(json.parseToJsonElement(play))?.get("data"))?.get("dash")) }.getOrNull()
            ?: return@withContext null
        val tracks = (dash["audio"] as? JsonArray).orEmpty().mapNotNull { obj(it) }
        val best = tracks.maxByOrNull { it["bandwidth"]?.jsonPrimitive?.longOrNull ?: 0L } ?: return@withContext null
        val url = str(best["base_url"]) ?: str(best["baseUrl"])
            ?: (best["backup_url"] as? JsonArray)?.firstOrNull()?.let { str(it) }
            ?: return@withContext null
        val kbps = ((best["bandwidth"]?.jsonPrimitive?.longOrNull ?: 0L) / 1000).toInt()
        TrackLog.d(TAG, "bilibili: audio for $bvid at ~${kbps}kbps")
        Audio(url, mapOf("Referer" to "https://www.bilibili.com/", "User-Agent" to UA), kbps, length)
    }

    private fun obj(e: JsonElement?): JsonObject? = e as? JsonObject
    private fun str(e: JsonElement?): String? = runCatching { e?.jsonPrimitive?.content }.getOrNull()?.takeIf { it != "null" }
}

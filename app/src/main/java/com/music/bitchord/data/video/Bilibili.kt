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

    data class Stream(val url: String, val headers: Map<String, String>, val width: Int, val height: Int)

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

    /** The MP4 for [bvid]'s first part. */
    suspend fun stream(bvid: String): Stream? = withContext(Dispatchers.IO) {
        val view = get("https://api.bilibili.com/x/web-interface/view?bvid=$bvid") ?: return@withContext null
        val data = runCatching { obj(obj(json.parseToJsonElement(view))?.get("data")) }.getOrNull() ?: return@withContext null
        val cid = data["cid"]?.jsonPrimitive?.longOrNull ?: return@withContext null
        val dim = obj(data["dimension"])
        var w = dim?.get("width")?.jsonPrimitive?.intOrNull ?: 16
        var h = dim?.get("height")?.jsonPrimitive?.intOrNull ?: 9
        if (dim?.get("rotate")?.jsonPrimitive?.intOrNull == 1) { val t = w; w = h; h = t }
        val play = get(
            "https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=80&fnval=1&fnver=0&fourk=0&platform=html5&high_quality=1",
        ) ?: return@withContext null
        val url = runCatching {
            val durl = obj(obj(json.parseToJsonElement(play))?.get("data"))?.get("durl") as? JsonArray
            str(obj(durl?.firstOrNull())?.get("url"))
        }.getOrNull() ?: return@withContext null
        TrackLog.d(TAG, "bilibili: stream for $bvid (${w}x$h)")
        Stream(url, mapOf("Referer" to "https://www.bilibili.com/", "User-Agent" to UA), w, h)
    }

    private fun obj(e: JsonElement?): JsonObject? = e as? JsonObject
    private fun str(e: JsonElement?): String? = runCatching { e?.jsonPrimitive?.content }.getOrNull()?.takeIf { it != "null" }
}

package com.music.bitchord.data.lyrics

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * BitChord Audio: Microsoft's translator as Edge uses it — a short-lived token from
 * edge.microsoft.com, then the lines sent as a list (no markers to come back mangled).
 * The fallback when Google's free endpoint rate-limits (429).
 */
object MicrosoftTranslator {
    private val client by lazy { Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L

    private fun freshToken(): String? {
        val now = System.currentTimeMillis()
        token?.takeIf { now - tokenAt < 8 * 60_000L }?.let { return it }
        return runCatching {
            client.newCall(
                Request.Builder().url("https://edge.microsoft.com/translate/auth")
                    .header("User-Agent", UA).build(),
            ).execute().use { r -> if (r.isSuccessful) r.body?.string()?.trim()?.takeIf { it.count { c -> c == '.' } == 2 } else null }
        }.getOrNull()?.also { token = it; tokenAt = now }
    }

    /** Microsoft's names for a few languages that differ from the BCP-47 tags the app keeps. */
    private fun msLanguage(tag: String): String {
        val t = tag.trim()
        return when (t.lowercase()) {
            "zh-cn", "zh-sg", "zh-hans", "zh" -> "zh-Hans"
            "zh-tw", "zh-hk", "zh-mo", "zh-hant" -> "zh-Hant"
            "iw" -> "he"
            "tl", "fil" -> "fil"
            "pt-br" -> "pt"
            "pt-pt" -> "pt-pt"
            else -> t.substringBefore('-').substringBefore('_')
        }
    }

    /** [texts] translated, and the language they were found in; null if it couldn't. */
    suspend fun translate(texts: List<String>, target: String, from: String? = null): Pair<List<String>, String>? = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext null
        val out = ArrayList<String>(texts.size)
        val sources = HashMap<String, Int>()
        for (chunk in texts.chunked(90)) {
            var answer = ask(chunk, target, from)
            if (answer == null) { token = null; answer = ask(chunk, target, from) }
            if (answer == null) return@withContext null
            out += answer.map { it.first }
            answer.forEach { (_, lang) -> if (lang.isNotEmpty()) sources.merge(lang, 1, Int::plus) }
        }
        out to (sources.maxByOrNull { it.value }?.key ?: from.orEmpty())
    }

    private fun ask(texts: List<String>, target: String, from: String?): List<Pair<String, String>>? {
        val auth = freshToken() ?: run { TrackLog.w("LyricsTranslation", "Microsoft translator: no token"); return null }
        val body = buildJsonArray { texts.forEach { add(buildJsonObject { put("Text", JsonPrimitive(it)) }) } }.toString()
        val url = buildString {
            append("https://api-edge.cognitivetranslator.com/translate?api-version=3.0&to=").append(msLanguage(target))
            if (!from.isNullOrBlank()) append("&from=").append(msLanguage(from))
        }
        return runCatching {
            client.newCall(
                Request.Builder().url(url)
                    .header("Authorization", "Bearer $auth")
                    .header("User-Agent", UA)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build(),
            ).execute().use { r ->
                val text = r.body?.string()
                if (!r.isSuccessful || text == null) {
                    TrackLog.w("LyricsTranslation", "Microsoft translator: HTTP ${r.code}")
                    return@use null
                }
                json.parseToJsonElement(text).jsonArray.map { item ->
                    val o = item.jsonObject
                    val t = (o["translations"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                    val lang = o["detectedLanguage"]?.jsonObject?.get("language")?.jsonPrimitive?.contentOrNull.orEmpty()
                    t to lang
                }.takeIf { it.size == texts.size }
            }
        }.onFailure { TrackLog.w("LyricsTranslation", "Microsoft translator failed: ${it.message}") }.getOrNull()
    }

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0"
}

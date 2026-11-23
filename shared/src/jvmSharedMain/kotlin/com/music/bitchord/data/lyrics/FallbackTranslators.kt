package com.music.bitchord.data.lyrics

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * BitChord Audio: more ways to translate lyrics when Google's usual endpoint says
 * "too many requests" (429) — its Chrome-extension endpoint on clients5.google.com
 * (a quota of its own), then Lingva (Google through other servers), then MyMemory.
 * Lines are sent joined by line breaks and kept only if they come back as many.
 */
object FallbackTranslators {
    private val client by lazy { Http.client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }
    private val json = Json { ignoreUnknownKeys = true }
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Mobile Safari/537.36"
    private val LINGVA = listOf("https://lingva.ml", "https://lingva.lunar.icu", "https://translate.plausibility.cloud")

    private fun get(url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else { TrackLog.w("LyricsTranslation", "fallback ${r.request.url.host}: HTTP ${r.code}"); null }
        }
    }.onFailure { TrackLog.w("LyricsTranslation", "fallback failed: ${it.message}") }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun base(tag: String) = when (tag.lowercase()) {
        "zh-cn", "zh-hans" -> "zh-CN"
        "zh-tw", "zh-hant" -> "zh-TW"
        else -> tag.substringBefore('-')
    }

    /** [texts] translated into [target] and the language found, or null. */
    suspend fun translate(texts: List<String>, target: String): Pair<List<String>, String>? = withContext(Dispatchers.IO) {
        val tl = base(target)
        clients5(texts, tl)?.let { return@withContext it }
        lingva(texts, tl)?.let { return@withContext it }
        myMemory(texts, tl)
    }

    private fun split(joined: String, n: Int): List<String>? =
        joined.split('\n').map { it.trim() }.let { lines -> lines.takeIf { it.size == n && it.none(String::isBlank) } }

    private fun clients5(texts: List<String>, tl: String): Pair<List<String>, String>? {
        val out = ArrayList<String>()
        var lang = ""
        for (chunk in chunks(texts, 1500)) {
            val q = chunk.joinToString("&q=") { enc(it) }
            val body = get("https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=$tl&q=$q") ?: return null
            val arr = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return null
            if (arr.size != chunk.size) return null
            arr.forEach { e ->
                when (e) {
                    is JsonArray -> { out += e.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty(); if (lang.isEmpty()) lang = e.getOrNull(1)?.jsonPrimitive?.contentOrNull.orEmpty() }
                    is JsonPrimitive -> out += e.contentOrNull.orEmpty()
                    else -> return null
                }
            }
        }
        return out.takeIf { it.size == texts.size && it.none(String::isBlank) }?.let { it to lang }
    }

    private fun lingva(texts: List<String>, tl: String): Pair<List<String>, String>? {
        for (host in LINGVA) {
            val out = ArrayList<String>()
            var lang = ""
            var ok = true
            for (chunk in chunks(texts, 900)) {
                val body = get("$host/api/v1/auto/$tl/${enc(chunk.joinToString("\n")).replace("+", "%20")}") ?: run { ok = false; null } ?: break
                val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: run { ok = false; break }
                val parts = split(o["translation"]?.jsonPrimitive?.contentOrNull.orEmpty(), chunk.size) ?: run { ok = false; break }
                out += parts
                if (lang.isEmpty()) lang = o["info"]?.jsonObject?.get("detectedSource")?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            if (ok && out.size == texts.size) return out to lang
        }
        return null
    }

    private fun myMemory(texts: List<String>, tl: String): Pair<List<String>, String>? {
        val out = ArrayList<String>()
        for (chunk in chunks(texts, 450)) {
            val body = get("https://api.mymemory.translated.net/get?q=${enc(chunk.joinToString("\n"))}&langpair=Autodetect|$tl") ?: return null
            val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            val text = o["responseData"]?.jsonObject?.get("translatedText")?.jsonPrimitive?.contentOrNull ?: return null
            out += split(text, chunk.size) ?: return null
        }
        return out.takeIf { it.size == texts.size }?.let { it to "" }
    }

    /** [texts] in runs of at most [max] characters. */
    private fun chunks(texts: List<String>, max: Int): List<List<String>> {
        val result = mutableListOf<List<String>>()
        var cur = mutableListOf<String>()
        var len = 0
        for (t in texts) {
            if (cur.isNotEmpty() && len + t.length + 1 > max) { result += cur; cur = mutableListOf(); len = 0 }
            cur += t; len += t.length + 1
        }
        if (cur.isNotEmpty()) result += cur
        return result
    }
}

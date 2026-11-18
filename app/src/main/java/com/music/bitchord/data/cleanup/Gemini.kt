package com.music.bitchord.data.cleanup

import android.content.Context
import android.util.Base64
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * BitChord Audio: Google's Gemini, with your own free key (aistudio.google.com), as the
 * library assistant: it reads what a folder of yours holds — file names, tags, lengths,
 * the cover — plus what YouTube Music offers and what you typed, searches the web
 * (Google Search grounding), and answers which release it is.
 *
 * The key stays on this phone (not in the synced settings).
 */
object Gemini {

    private const val TAG = "BitChord"
    private const val MODEL = "gemini-2.5-flash"
    private val _key = MutableStateFlow("")
    val key: StateFlow<String> = _key
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("bitchord_assistant", Context.MODE_PRIVATE)
        _key.value = prefs?.getString("gemini_key", "").orEmpty()
    }

    fun setKey(k: String) {
        _key.value = k.trim()
        prefs?.edit()?.putString("gemini_key", k.trim())?.apply()
    }

    val ready get() = _key.value.isNotBlank()

    private val client by lazy {
        Http.client.newBuilder().readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build()
    }

    /**
     * Asks with [prompt] (and [image], the folder's cover, when there is one); the JSON
     * object in the answer, or null. Searches the web when it needs to.
     */
    suspend fun ask(prompt: String, image: ByteArray?, imageMime: String?): JSONObject? = withContext(Dispatchers.IO) {
        if (!ready) return@withContext null
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (image != null) {
            parts.put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", imageMime ?: "image/jpeg")
                .put("data", Base64.encodeToString(image, Base64.NO_WRAP))))
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put("generationConfig", JSONObject().put("temperature", 0.2))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .header("x-goog-api-key", _key.value)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.code !in 200..299) {
                    TrackLog.w(TAG, "assistant: Gemini answered ${r.code}: ${text.take(300)}")
                    return@runCatching null
                }
                val answer = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                    ?.let { p -> (0 until p.length()).joinToString("") { p.optJSONObject(it)?.optString("text").orEmpty() } }
                    .orEmpty()
                jsonIn(answer)
            }
        }.onFailure { TrackLog.w(TAG, "assistant: Gemini failed: ${it.message}") }.getOrNull()
    }

    /** The outermost {…} of [text] (an answer may wrap it in prose or a code fence). */
    private fun jsonIn(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }
}

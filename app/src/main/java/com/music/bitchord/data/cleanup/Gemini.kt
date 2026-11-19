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
    /**
     * BitChord Audio: the model is not fixed — Google retires them (gemini-2.5-flash is no
     * longer open to new keys). The newest stable "Flash" your key can use is picked from
     * Google's own list, and picked again when the one in use goes away.
     */
    private val FALLBACK = listOf("gemini-flash-latest", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3-flash-preview")
    @Volatile private var model: String? = null

    private fun version(name: String): Double =
        Regex("""gemini-(\d+(?:\.\d+)?)""").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    /** The newest stable Flash model this key can generate with, from Google's list. */
    private fun pickModel(): String {
        model?.let { return it }
        val listed = runCatching {
            val req = Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
                .header("x-goog-api-key", _key.value).build()
            client.newCall(req).execute().use { r ->
                val o = JSONObject(r.body?.string().orEmpty())
                val a = o.optJSONArray("models") ?: JSONArray()
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                    .filter { m ->
                        val methods = m.optJSONArray("supportedGenerationMethods")
                        methods != null && (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                    }
                    .map { it.optString("name").removePrefix("models/") }
            }
        }.getOrDefault(emptyList())
        val flash = listed.filter { n ->
            Regex("""^gemini-\d+(\.\d+)?-flash$""").matches(n)
        }.sortedByDescending(::version)
        val preview = listed.filter { n -> Regex("""^gemini-\d+(\.\d+)?-flash-preview$""").matches(n) }.sortedByDescending(::version)
        val chosen = flash.firstOrNull() ?: listed.firstOrNull { it == "gemini-flash-latest" } ?: preview.firstOrNull() ?: FALLBACK.first()
        TrackLog.d(TAG, "assistant: using $chosen (of ${listed.size} models offered)")
        model = chosen
        return chosen
    }
    private val _key = MutableStateFlow("")
    val key: StateFlow<String> = _key
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("bitchord_assistant", Context.MODE_PRIVATE)
        _key.value = prefs?.getString("gemini_key", "").orEmpty()
    }

    fun setKey(k: String) {
        _key.value = k.trim()
        model = null
        prefs?.edit()?.putString("gemini_key", k.trim())?.apply()
    }

    val ready get() = _key.value.isNotBlank()

    /** BitChord Audio: how the last request went — null when it answered, else why not (shown in the assistant). */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError
    private val _lastOk = MutableStateFlow(0L)
    val lastOk: StateFlow<Long> = _lastOk

    /** A tiny request to check the key: null when it works, else the reason. */
    suspend fun test(): String? {
        val o = ask("Reply with the JSON object {\"ok\": true}", null, null, search = false)
        return if (o != null) null else _lastError.value ?: "sin respuesta"
    }

    private fun why(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
        return when (code) {
            400 -> if (msg.contains("API key", true)) "la clave no es válida" else "petición rechazada: ${msg.take(120)}"
            401, 403 -> "la clave no tiene permiso (${msg.take(80)})"
            429 -> "límite del plan gratuito alcanzado por ahora; se reintenta más tarde"
            in 500..599 -> "Gemini no responde ahora ($code)"
            else -> "error $code: ${msg.take(120)}"
        }
    }

    private val client by lazy {
        Http.client.newBuilder().readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build()
    }

    /**
     * Asks with [prompt] (and [image], the folder's cover, when there is one); the JSON
     * object in the answer, or null. Searches the web when it needs to.
     */
    suspend fun ask(prompt: String, image: ByteArray?, imageMime: String?, search: Boolean = true): JSONObject? {
        val first = askOnce(prompt, image, imageMime, search)
        // A retired model (404): once more with the one picked in its place.
        if (first == null && model == null) return askOnce(prompt, image, imageMime, search)
        return first
    }

    private suspend fun askOnce(prompt: String, image: ByteArray?, imageMime: String?, search: Boolean): JSONObject? = withContext(Dispatchers.IO) {
        if (!ready) return@withContext null
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (image != null) {
            parts.put(JSONObject().put("inline_data", JSONObject()
                .put("mime_type", imageMime ?: "image/jpeg")
                .put("data", Base64.encodeToString(image, Base64.NO_WRAP))))
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .apply { if (search) put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject()))) }
            .put("generationConfig", JSONObject().put("temperature", 0.2))
        val name = pickModel()
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$name:generateContent")
            .header("x-goog-api-key", _key.value)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                // The model went away (retired, not offered to this key): another one, next time.
                if (r.code == 404) model = null
                if (r.code !in 200..299) {
                    TrackLog.w(TAG, "assistant: Gemini answered ${r.code}: ${text.take(300)}")
                    _lastError.value = why(r.code, text)
                    return@runCatching null
                }
                _lastError.value = null
                _lastOk.value = System.currentTimeMillis()
                val answer = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                    ?.let { p -> (0 until p.length()).joinToString("") { p.optJSONObject(it)?.optString("text").orEmpty() } }
                    .orEmpty()
                jsonIn(answer)
            }
        }.onFailure {
            TrackLog.w(TAG, "assistant: Gemini failed: ${it.message}")
            _lastError.value = "sin conexión con Gemini (${it.message?.take(80)})"
        }.getOrNull()
    }

    /** The outermost {…} of [text] (an answer may wrap it in prose or a code fence). */
    private fun jsonIn(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }
}

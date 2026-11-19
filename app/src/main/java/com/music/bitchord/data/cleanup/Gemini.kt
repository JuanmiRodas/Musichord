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
     * BitChord Audio: the model is not fixed — Google retires them and the newest ones have
     * no free allowance. The models your key can use are read from Google's list and tried
     * in order: stable Flash (newest first), then Flash-Lite (the most generous free
     * allowance), then the rest. One that answers "quota" (429) or "gone" (404) is set
     * aside for a few hours and the next is tried, at once.
     */
    private val FALLBACK = listOf("gemini-flash-latest", "gemini-flash-lite-latest", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite")
    @Volatile private var ranked: List<String>? = null
    private val restingUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Models that answered without the web search tool only (the search has its own allowance). */
    private val noSearch = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile var model: String? = null
        private set

    private fun version(name: String): Double =
        Regex("""gemini-(\d+(?:\.\d+)?)""").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    private fun models(): List<String> {
        ranked?.let { return it }
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
        fun matching(re: String) = listed.filter { Regex(re).matches(it) }.sortedByDescending(::version)
        val order = (
            matching("""^gemini-\d+(\.\d+)?-flash$""") +
                listOf("gemini-flash-latest").filter { it in listed } +
                matching("""^gemini-\d+(\.\d+)?-flash-lite$""") +
                listOf("gemini-flash-lite-latest").filter { it in listed } +
                matching("""^gemini-\d+(\.\d+)?-flash(-lite)?-preview(-[\d-]+)?$""")
            ).distinct().ifEmpty { FALLBACK }
        TrackLog.d(TAG, "assistant: models to try — ${order.take(8).joinToString()} (of ${listed.size} offered)")
        ranked = order
        return order
    }

    private fun usable(): List<String> {
        val now = System.currentTimeMillis()
        return models().filter { (restingUntil[it] ?: 0L) < now }
    }
    private val _key = MutableStateFlow("")
    val key: StateFlow<String> = _key
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        OtherBrains.init(context)
        prefs = context.applicationContext.getSharedPreferences("bitchord_assistant", Context.MODE_PRIVATE)
        _key.value = prefs?.getString("gemini_key", "").orEmpty()
    }

    fun setKey(k: String) {
        _key.value = k.trim()
        ranked = null
        restingUntil.clear()
        noSearch.clear()
        prefs?.edit()?.putString("gemini_key", k.trim())?.apply()
    }

    /** Gemini's own key. */
    val hasKey get() = _key.value.isNotBlank()

    /** Some assistant to ask: Gemini, or one of your other services. */
    val ready get() = hasKey || OtherBrains.ready

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
            429 -> "los modelos gratuitos de esta clave no tienen cuota ahora mismo; se reintenta más tarde"
            in 500..599 -> "Gemini no responde ahora ($code)"
            else -> "error $code: ${msg.take(120)}"
        }
    }

    private val client by lazy {
        Http.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).build()
    }

    /**
     * Asks with [prompt] (and [image], the folder's cover, when there is one); the JSON
     * object in the answer, or null. Searches the web when it needs to.
     */
    /** BitChord Audio: what the assistant is doing right now ("Asking gemini-3.6-flash…"), for the screen. */
    private val _activity = MutableStateFlow<String?>(null)
    val activity: StateFlow<String?> = _activity
    fun activity(text: String?) { _activity.value = text }

    /** What a request ended with: the answer, "try another model", or a plain failure. */
    private sealed interface Outcome {
        data class Answer(val o: JSONObject?) : Outcome
        data class Quota(val withSearch: Boolean) : Outcome
        data object Gone : Outcome
        /** Overloaded or down for now (5xx, a timeout): the next model. */
        data object Busy : Outcome
        data object Failed : Outcome
    }

    suspend fun ask(prompt: String, image: ByteArray?, imageMime: String?, search: Boolean = true): JSONObject? = withContext(Dispatchers.IO) {
        if (!ready) return@withContext null
        for (name in (if (hasKey) usable().take(6) else emptyList())) {
            var withSearch = search && name !in noSearch
            repeat(2) {
                _activity.value = "Preguntando a $name" + if (withSearch) " (con búsqueda web)…" else "…"
                when (val out = askOnce(name, prompt, image, imageMime, withSearch)) {
                    is Outcome.Answer -> {
                        _activity.value = null
                        model = name
                        OtherBrains.used("Gemini · $name")
                        return@withContext out.o
                    }
                    is Outcome.Quota -> {
                        // The web search's own allowance first: the same model without it.
                        if (out.withSearch) { noSearch += name; withSearch = false }
                        else { restingUntil[name] = System.currentTimeMillis() + 3 * 3_600_000L; return@repeat }
                    }
                    Outcome.Gone -> { restingUntil[name] = Long.MAX_VALUE; return@repeat }
                    Outcome.Busy -> { restingUntil[name] = System.currentTimeMillis() + 10 * 60_000L; return@repeat }
                    // A key Gemini refuses: no point trying its other models.
                    Outcome.Failed -> return@withContext OtherBrains.ask(prompt)
                }
            }
        }
        // Gemini has nothing left: your other services, if you added them.
        if (OtherBrains.ready) _activity.value = "Gemini no responde; probando tus otros servicios…"
        OtherBrains.ask(prompt).also { _activity.value = null }?.let {
            _lastError.value = null
            _lastOk.value = System.currentTimeMillis()
            return@withContext it
        }
        if (_lastError.value == null) _lastError.value = "ningún modelo disponible ahora mismo"
        null
    }

    private fun askOnce(name: String, prompt: String, image: ByteArray?, imageMime: String?, search: Boolean): Outcome {
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
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$name:generateContent")
            .header("x-goog-api-key", _key.value)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                when {
                    r.code == 429 -> {
                        TrackLog.w(TAG, "assistant: $name${if (search) " + search" else ""} — no allowance left (429); trying another way")
                        _lastError.value = why(429, text)
                        Outcome.Quota(search)
                    }
                    r.code == 404 || (r.code == 400 && search && text.contains("tool", true)) -> {
                        TrackLog.w(TAG, "assistant: $name not usable (${r.code}): ${text.take(160)}")
                        if (r.code == 400) Outcome.Quota(true) else Outcome.Gone
                    }
                    r.code in 500..599 -> {
                        TrackLog.w(TAG, "assistant: $name busy (${r.code}); trying the next model")
                        _lastError.value = why(r.code, text)
                        Outcome.Busy
                    }
                    r.code !in 200..299 -> {
                        TrackLog.w(TAG, "assistant: Gemini answered ${r.code}: ${text.take(300)}")
                        _lastError.value = why(r.code, text)
                        Outcome.Failed
                    }
                    else -> {
                        _lastError.value = null
                        _lastOk.value = System.currentTimeMillis()
                        val answer = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                            ?.optJSONObject("content")?.optJSONArray("parts")
                            ?.let { p -> (0 until p.length()).joinToString("") { p.optJSONObject(it)?.optString("text").orEmpty() } }
                            .orEmpty()
                        Outcome.Answer(jsonIn(answer))
                    }
                }
            }
        }.getOrElse {
            TrackLog.w(TAG, "assistant: $name failed: ${it.message}")
            _lastError.value = "sin conexión con Gemini (${it.message?.take(80)})"
            Outcome.Busy
        }
    }

    /** The outermost {…} of [text] (an answer may wrap it in prose or a code fence). */
    private fun jsonIn(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }
}

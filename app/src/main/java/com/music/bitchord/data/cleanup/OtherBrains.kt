package com.music.bitchord.data.cleanup

import android.content.Context
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * BitChord Audio: other AI services for the library assistant, with your own keys, for
 * when Gemini has no allowance left: Experiential Labs (its free models — DeepSeek, GPT
 * Luna, Clef) and Mistral (the free Experiment plan). Both speak the OpenAI chat format.
 * Neither searches the web the way Gemini does: they answer from what they know and what
 * the app hands them (YouTube Music's releases, the files, your notes).
 *
 * The keys stay on this phone.
 */
object OtherBrains {

    private const val TAG = "BitChord"

    enum class Provider(val label: String, val base: String, val prefKey: String, val prefer: List<Regex>) {
        EXPERIENTIAL(
            "Experiential Labs", "https://api.experientiallabs.ai/v1", "experiential_key",
            // Its free core pool: DeepSeek V4.1 Flash, GPT-6 Luna, Clef — chat models, not the
            // OCR, reasoning-only or vision ones that share a name with them.
            listOf(Regex("(?i)deepseek[^/]*v4\\.?1[^/]*flash"), Regex("(?i)gpt-?6[^/]*luna|(^|/)luna"), Regex("(?i)(^|/)clef")),
        ),
        MISTRAL(
            "Mistral", "https://api.mistral.ai/v1", "mistral_key",
            listOf(Regex("^mistral-large-latest$"), Regex("^mistral-medium-latest$"), Regex("^mistral-small-latest$")),
        ),
    }

    private val keys = Provider.entries.associateWith { MutableStateFlow("") }
    fun key(p: Provider): StateFlow<String> = keys.getValue(p)
    private var prefs: android.content.SharedPreferences? = null
    private val models = java.util.concurrent.ConcurrentHashMap<Provider, String>()
    private val resting = java.util.concurrent.ConcurrentHashMap<Provider, Long>()

    /** Which service gave the last answer ("Gemini · gemini-3.5-flash", "Mistral · …"). */
    private val _lastUsed = MutableStateFlow<String?>(null)
    val lastUsed: StateFlow<String?> = _lastUsed
    fun used(label: String) { _lastUsed.value = label }

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("bitchord_assistant", Context.MODE_PRIVATE)
        Provider.entries.forEach {
            keys.getValue(it).value = prefs?.getString(it.prefKey, "").orEmpty()
            chosen.getValue(it).value = prefs?.getString(it.prefKey + "_model", null)
        }
    }

    fun setKey(p: Provider, k: String) {
        keys.getValue(p).value = k.trim()
        prefs?.edit()?.putString(p.prefKey, k.trim())?.apply()
        models.remove(p)
        resting.remove(p)
    }

    val ready: Boolean get() = Provider.entries.any { keys.getValue(it).value.isNotBlank() }

    private val client by lazy {
        Http.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).build()
    }

    /** The free models (by name): Experiential's free core pool; for Mistral every model is in the free plan. */
    fun isFree(p: Provider, id: String) = p == Provider.MISTRAL || (p.prefer.any { it.containsMatchIn(id) } && !NOT_CHAT.containsMatchIn(id))

    /** Models that are not for a chat answer (reading images, embeddings, speech…). */
    private val NOT_CHAT = Regex("(?i)ocr|embed|tts|whisper|image|vision|moderation|audio|transcri|guard|rerank|-vl\\b")

    /** The models your key can use, for picking one by hand. */
    fun listModels(p: Provider): List<String> = runCatching {
        val req = Request.Builder().url("${p.base}/models").header("Authorization", "Bearer ${keys.getValue(p).value}").build()
        client.newCall(req).execute().use { r ->
            val a = JSONObject(r.body?.string().orEmpty()).optJSONArray("data") ?: JSONArray()
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id") }.filter { it.isNotBlank() }
                .filterNot { Regex("(?i)embed|tts|whisper|image|moderation|ocr|audio|transcri").containsMatchIn(it) }
        }
    }.getOrDefault(emptyList())

    private val chosen = Provider.entries.associateWith { MutableStateFlow<String?>(null) }
    fun chosenModel(p: Provider): StateFlow<String?> = chosen.getValue(p)

    fun setModel(p: Provider, id: String?) {
        chosen.getValue(p).value = id
        prefs?.edit()?.putString(p.prefKey + "_model", id)?.apply()
        models.remove(p)
    }

    /** A tiny request to [p]: null when it answers, else why not. */
    fun test(p: Provider): String? {
        resting.remove(p)
        val o = askOne(p, "Reply with the JSON object {\"ok\": true}")
        return if (o != null) null else (lastError[p] ?: "sin respuesta")
    }

    private val lastError = java.util.concurrent.ConcurrentHashMap<Provider, String>()

    private fun modelFor(p: Provider): String? {
        chosen.getValue(p).value?.let { return it }
        models[p]?.let { return it }
        val ids = runCatching {
            val req = Request.Builder().url("${p.base}/models").header("Authorization", "Bearer ${keys.getValue(p).value}").build()
            client.newCall(req).execute().use { r ->
                val a = JSONObject(r.body?.string().orEmpty()).optJSONArray("data") ?: JSONArray()
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id") }.filter { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
        val chat = ids.filterNot { NOT_CHAT.containsMatchIn(it) }
        val chosen = p.prefer.firstNotNullOfOrNull { re -> chat.firstOrNull { re.containsMatchIn(it) } }
            // Not a free one found by name: a quick chat model, never a slow reasoning one first.
            ?: chat.firstOrNull { Regex("(?i)deepseek.*(chat|v\\d)").containsMatchIn(it) && !Regex("(?i)r1|reason").containsMatchIn(it) }
            ?: chat.firstOrNull()
            ?: return null
        TrackLog.d(TAG, "assistant: ${p.label} — using $chosen (of ${ids.size} models)")
        models[p] = chosen
        return chosen
    }

    /** The first of your other services that answers [prompt]; the JSON object in it, or null. */
    fun ask(prompt: String): JSONObject? {
        val now = System.currentTimeMillis()
        for (p in Provider.entries) {
            if ((resting[p] ?: 0L) > now) continue
            askOne(p, prompt)?.let { return it }
        }
        return null
    }

    private fun askOne(p: Provider, prompt: String): JSONObject? {
        val now = System.currentTimeMillis()
        run {
            val key = keys.getValue(p).value
            if (key.isBlank()) return null
            val model = modelFor(p) ?: run { lastError[p] = "la clave no da acceso a ningún modelo (¿es correcta?)"; return null }
            Gemini.activity("Preguntando a ${p.label} · $model…")
            val body = JSONObject()
                .put("model", model)
                .put("temperature", 0.2)
                // Ask for JSON outright where the service supports it.
                .put("response_format", JSONObject().put("type", "json_object"))
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content",
                    prompt + "\nYou cannot browse the web here: answer from what you know and the data above; lower your confidence when unsure.")))
            val req = Request.Builder().url("${p.base}/chat/completions")
                .header("Authorization", "Bearer $key")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val answer = runCatching {
                client.newCall(req).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (r.code == 429) {
                        TrackLog.w(TAG, "assistant: ${p.label} — no allowance left (429)")
                        lastError[p] = "sin cuota ahora mismo (429)"
                        resting[p] = now + 3_600_000L
                        return@use null
                    }
                    // A service that refuses "response_format": once more without it.
                    if (r.code == 400 && text.contains("response_format", true)) {
                        body.remove("response_format")
                        val retry = Request.Builder().url("${p.base}/chat/completions").header("Authorization", "Bearer $key")
                            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                        return@use client.newCall(retry).execute().use { r2 ->
                            if (r2.code in 200..299) JSONObject(r2.body?.string().orEmpty()).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content") else null
                        }
                    }
                    if (r.code == 404 || r.code == 400) models.remove(p)
                    if (r.code !in 200..299) {
                        TrackLog.w(TAG, "assistant: ${p.label} answered ${r.code}: ${text.take(200)}")
                        lastError[p] = when (r.code) {
                            401, 403 -> "la clave no es válida o no tiene permiso"
                            else -> "error ${r.code}: " + (runCatching { JSONObject(text).optJSONObject("error")?.optString("message") ?: JSONObject(text).optString("message") }.getOrNull() ?: text).take(120)
                        }
                        return@use null
                    }
                    JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                }
            }.onFailure { TrackLog.w(TAG, "assistant: ${p.label} failed: ${it.message}"); lastError[p] = "sin conexión (${it.message?.take(60)})" }.getOrNull()
            val o = answer?.let(::jsonIn)
            if (o != null) {
                lastError.remove(p)
                used("${p.label} · $model")
                return o
            }
            if (answer != null) lastError[p] = "respondió, pero no en el formato pedido"
        }
        return null
    }

    /**
     * The JSON object in an answer: past a reasoning model's "<think>…</think>", out of a
     * code fence, the last whole {…} that parses.
     */
    internal fun jsonIn(raw: String): JSONObject? {
        val text = raw.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("```(?:json)?"), "")
        runCatching { return JSONObject(text.trim()) }
        val end = text.lastIndexOf('}')
        if (end < 0) return null
        var start = text.lastIndexOf('{', end)
        while (start >= 0) {
            runCatching { return JSONObject(text.substring(start, end + 1)) }
            start = text.lastIndexOf('{', start - 1)
        }
        return null
    }
}

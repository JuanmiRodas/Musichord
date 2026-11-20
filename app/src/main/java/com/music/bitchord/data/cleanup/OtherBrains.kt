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
            // Its free models (the "Free" tab): Jev, Clef, GPT-6 Luna, and the ":free" variants
            // (gemini-4-neon:free). DeepSeek V4.1 Flash is NOT free — it was charged.
            listOf(Regex("(?i)(^|/)gpt-?6-?luna(:free)?$"), Regex("(?i)(^|/)clef(:free)?$"), Regex("(?i)(^|/)jev(:free)?$"), Regex("(?i):free$")),
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
        catalogue.remove(p)
        modelResting.keys.removeIf { it.startsWith(p.name + "|") }
    }

    val ready: Boolean get() = Provider.entries.any { keys.getValue(it).value.isNotBlank() }

    private val client by lazy {
        Http.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).build()
    }

    /** The free models (by name): Experiential's free core pool; for Mistral every model is in the free plan. */
    /**
     * BitChord Audio: free to use — Mistral's free plan covers its models; on Experiential
     * Labs only a model the service prices at zero, or one marked ":free" or named in its
     * free tier. A model with a price is never free, whatever its name.
     */
    fun isFree(p: Provider, id: String): Boolean {
        if (p == Provider.MISTRAL) return true
        if (NOT_CHAT.containsMatchIn(id)) return false
        priced[id]?.let { return !it }
        return p.prefer.any { it.containsMatchIn(id) }
    }

    /** Model id → whether the service charges for it (from its own list, when it says). */
    private val priced = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun cost(o: JSONObject): Boolean? {
        val pricing = o.optJSONObject("pricing") ?: return null
        val nums = listOf("prompt", "completion", "input", "output", "request").mapNotNull { k ->
            pricing.opt(k)?.toString()?.toDoubleOrNull()
        }
        return if (nums.isEmpty()) null else nums.any { it > 0.0 }
    }

    private fun fetchIds(p: Provider): List<String> = runCatching {
        val req = Request.Builder().url("${p.base}/models").header("Authorization", "Bearer ${keys.getValue(p).value}").build()
        client.newCall(req).execute().use { r ->
            val a = JSONObject(r.body?.string().orEmpty()).optJSONArray("data") ?: JSONArray()
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                cost(o)?.let { priced[id] = it }
                id
            }
        }
    }.getOrDefault(emptyList())

    /** Models that are not for a chat answer (reading images, embeddings, speech…). */
    private val NOT_CHAT = Regex("(?i)ocr|embed|tts|whisper|image|vision|moderation|audio|transcri|guard|rerank|-vl\\b")

    /** The models your key can use, for picking one by hand. */
    /** The free models your key can use, for picking one by hand (paid ones are never offered). */
    fun listModels(p: Provider): List<String> =
        fetchIds(p).also { if (it.isNotEmpty()) catalogue[p] = it }.filter { isFree(p, it) }

    private val chosen = Provider.entries.associateWith { MutableStateFlow<String?>(null) }
    fun chosenModel(p: Provider): StateFlow<String?> = chosen.getValue(p)

    fun setModel(p: Provider, id: String?) {
        if (id != null && !isFree(p, id)) return
        chosen.getValue(p).value = id
        prefs?.edit()?.putString(p.prefKey + "_model", id)?.apply()
        models.remove(p)
        modelResting.keys.removeIf { it.startsWith(p.name + "|") }
    }

    /** A tiny request to [p]: null when it answers, else why not. */
    fun test(p: Provider): String? {
        modelResting.keys.removeIf { it.startsWith(p.name + "|") }
        val o = askOne(p, "Reply with the JSON object {\"ok\": true}")
        return if (o != null) null else (lastError[p] ?: "sin respuesta")
    }

    private val lastError = java.util.concurrent.ConcurrentHashMap<Provider, String>()

    /** The first of your other services that answers [prompt]; the JSON object in it, or null. */
    fun ask(prompt: String): JSONObject? {
        for (p in Provider.entries) askOne(p, prompt)?.let { return it }
        return null
    }

    /** Models resting after "no allowance" or "not usable": "provider|model" → until. */
    private val modelResting = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val catalogue = java.util.concurrent.ConcurrentHashMap<Provider, List<String>>()

    /**
     * BitChord Audio: the models to try, in order — the one you picked, else every free chat
     * model the key offers (each has its own allowance: when one runs out, the next is
     * asked), never one that only reads images, speaks or embeds. Read from the service's own
     * list once per run.
     */
    private fun modelsFor(p: Provider): List<String> {
        val ids = catalogue[p] ?: fetchIds(p).also { if (it.isNotEmpty()) catalogue[p] = it }
        // The one you picked — only while it is free.
        chosen.getValue(p).value?.takeIf { isFree(p, it) }?.let { return listOf(it) }
        val chat = ids.filterNot { NOT_CHAT.containsMatchIn(it) }
        val order = if (p == Provider.MISTRAL) {
            p.prefer.flatMap { re -> chat.filter { re.containsMatchIn(it) } }.distinct().ifEmpty { chat.take(3) }
        } else {
            // Never a paid model: the free ones only, best known first.
            val free = chat.filter { isFree(p, it) }
            p.prefer.flatMap { re -> free.filter { re.containsMatchIn(it) } }.distinct() + free
        }.distinct()
        if (order.isEmpty() && ids.isNotEmpty()) {
            TrackLog.w(TAG, "assistant: ${p.label} — no free model among ${ids.size}: ${ids.filter { it.contains("free", true) || it.contains("luna", true) || it.contains("clef", true) || it.contains("jev", true) }.joinToString().ifBlank { ids.take(40).joinToString() }}")
        }
        if (models[p] != order.joinToString()) {
            models[p] = order.joinToString()
            TrackLog.d(TAG, "assistant: ${p.label} — models to try: ${order.joinToString().ifBlank { "none" }} (of ${ids.size})")
        }
        return order
    }

    private fun askOne(p: Provider, prompt: String): JSONObject? {
        val key = keys.getValue(p).value
        if (key.isBlank()) return null
        val all = modelsFor(p)
        if (all.isEmpty()) { lastError[p] = "ningún modelo gratuito disponible con esta clave"; return null }
        val now = System.currentTimeMillis()
        val ready = all.filter { (modelResting["${p.name}|$it"] ?: 0L) < now }
        if (ready.isEmpty()) {
            val back = all.minOf { modelResting["${p.name}|$it"] ?: 0L }
            lastError[p] = "sus modelos gratuitos no tienen cuota ahora mismo; vuelve a probar sobre las " +
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(back))
            return null
        }
        for (model in ready) {
            Gemini.activity("Preguntando a ${p.label} · $model…")
            val body = JSONObject()
                .put("model", model)
                .put("temperature", 0.2)
                // Ask for JSON outright where the service supports it.
                .put("response_format", JSONObject().put("type", "json_object"))
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content",
                    prompt + "\nYou cannot browse the web here: answer from what you know and the data above; lower your confidence when unsure.")))
            fun call(b: JSONObject) = client.newCall(
                Request.Builder().url("${p.base}/chat/completions").header("Authorization", "Bearer $key")
                    .post(b.toString().toRequestBody("application/json".toMediaType())).build(),
            ).execute()
            val answer = runCatching {
                var r = call(body)
                var text = r.use { it.body?.string().orEmpty() }
                // A service that refuses "response_format": once more without it.
                if (r.code == 400 && text.contains("response_format", true)) {
                    body.remove("response_format")
                    r = call(body)
                    text = r.use { it.body?.string().orEmpty() }
                }
                when {
                    r.code == 429 -> {
                        TrackLog.w(TAG, "assistant: ${p.label} · $model — no allowance left (429); the next free model")
                        modelResting["${p.name}|$model"] = System.currentTimeMillis() + 3_600_000L
                        lastError[p] = "sin cuota en $model"
                        null
                    }
                    r.code == 404 || r.code == 400 || r.code == 422 -> {
                        TrackLog.w(TAG, "assistant: ${p.label} · $model not usable (${r.code}): ${text.take(160)}")
                        modelResting["${p.name}|$model"] = System.currentTimeMillis() + 24 * 3_600_000L
                        lastError[p] = "$model no se puede usar (${r.code})"
                        null
                    }
                    r.code == 401 || r.code == 403 -> {
                        lastError[p] = "la clave no es válida o no tiene permiso"
                        return null
                    }
                    r.code !in 200..299 -> {
                        TrackLog.w(TAG, "assistant: ${p.label} · $model answered ${r.code}: ${text.take(200)}")
                        modelResting["${p.name}|$model"] = System.currentTimeMillis() + 10 * 60_000L
                        lastError[p] = "error ${r.code} en $model"
                        null
                    }
                    else -> JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                }
            }.onFailure {
                TrackLog.w(TAG, "assistant: ${p.label} · $model failed: ${it.message}")
                modelResting["${p.name}|$model"] = System.currentTimeMillis() + 10 * 60_000L
                lastError[p] = "sin conexión (${it.message?.take(60)})"
            }.getOrNull()
            val o = answer?.let(::jsonIn)
            if (o != null) {
                lastError.remove(p)
                used("${p.label} · $model")
                return o
            }
            if (answer != null) lastError[p] = "$model respondió, pero no en el formato pedido"
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

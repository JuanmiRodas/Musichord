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
            listOf(Regex("(?i)deepseek"), Regex("(?i)luna"), Regex("(?i)clef")),
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
        Provider.entries.forEach { keys.getValue(it).value = prefs?.getString(it.prefKey, "").orEmpty() }
    }

    fun setKey(p: Provider, k: String) {
        keys.getValue(p).value = k.trim()
        prefs?.edit()?.putString(p.prefKey, k.trim())?.apply()
        models.remove(p)
        resting.remove(p)
    }

    val ready: Boolean get() = Provider.entries.any { keys.getValue(it).value.isNotBlank() }

    private val client by lazy {
        Http.client.newBuilder().readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS).build()
    }

    private fun modelFor(p: Provider): String? {
        models[p]?.let { return it }
        val ids = runCatching {
            val req = Request.Builder().url("${p.base}/models").header("Authorization", "Bearer ${keys.getValue(p).value}").build()
            client.newCall(req).execute().use { r ->
                val a = JSONObject(r.body?.string().orEmpty()).optJSONArray("data") ?: JSONArray()
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("id") }.filter { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
        val chosen = p.prefer.firstNotNullOfOrNull { re -> ids.firstOrNull { re.containsMatchIn(it) } }
            ?: ids.firstOrNull { !Regex("(?i)embed|tts|whisper|image|moderation|ocr|audio").containsMatchIn(it) }
            ?: return null
        TrackLog.d(TAG, "assistant: ${p.label} — using $chosen (of ${ids.size} models)")
        models[p] = chosen
        return chosen
    }

    /** The first of your other services that answers [prompt]; the JSON object in it, or null. */
    fun ask(prompt: String): JSONObject? {
        val now = System.currentTimeMillis()
        for (p in Provider.entries) {
            val key = keys.getValue(p).value
            if (key.isBlank() || (resting[p] ?: 0L) > now) continue
            val model = modelFor(p) ?: continue
            val body = JSONObject()
                .put("model", model)
                .put("temperature", 0.2)
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
                        resting[p] = now + 3_600_000L
                        return@use null
                    }
                    if (r.code == 404 || r.code == 400) models.remove(p)
                    if (r.code !in 200..299) {
                        TrackLog.w(TAG, "assistant: ${p.label} answered ${r.code}: ${text.take(200)}")
                        return@use null
                    }
                    JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                }
            }.onFailure { TrackLog.w(TAG, "assistant: ${p.label} failed: ${it.message}") }.getOrNull()
            val o = answer?.let(::jsonIn)
            if (o != null) {
                used("${p.label} · $model")
                return o
            }
        }
        return null
    }

    private fun jsonIn(text: String): JSONObject? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }
}

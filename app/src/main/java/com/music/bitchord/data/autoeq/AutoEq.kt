package com.music.bitchord.data.autoeq

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * BitChord Audio: headphone correction from the AutoEQ database
 * (jaakkopasanen/AutoEq, MIT), ported from LastWave (Clash-Projects,
 * GPL-3.0). Pick your headphones once per output — your earbuds, the car,
 * a wired DAC — and their lab-measured parametric EQ runs on top of the
 * equaliser whenever that output plays.
 */

enum class PeqType { PEAKING, LOW_SHELF, HIGH_SHELF }

@Serializable
data class PeqFilter(val type: PeqType, val frequencyHz: Double, val gainDb: Double, val q: Double)

@Serializable
data class PeqSettings(val preampDb: Double, val filters: List<PeqFilter>) {

    /** Normalised biquad coefficients (b0, b1, b2, a1, a2) per filter, at [sampleRate]. */
    fun coefficients(sampleRate: Int): Array<DoubleArray> = filters.map { coefficientsOf(it, sampleRate.toDouble()) }.toTypedArray()

    companion object {
        const val MAX_FILTERS = 16

        /** RBJ audio-EQ-cookbook biquads. */
        fun coefficientsOf(filter: PeqFilter, sampleRate: Double): DoubleArray {
            val f = filter.frequencyHz.coerceIn(10.0, sampleRate * 0.49)
            val w = 2.0 * PI * f / sampleRate
            val cs = cos(w)
            val alpha = sin(w) / (2.0 * max(filter.q, 0.05))
            val a = 10.0.pow(filter.gainDb / 40.0)
            val b0: Double; val b1: Double; val b2: Double
            val a0: Double; val a1: Double; val a2: Double
            when (filter.type) {
                PeqType.LOW_SHELF -> {
                    val k = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) - (a - 1) * cs + k)
                    b1 = 2 * a * ((a - 1) - (a + 1) * cs)
                    b2 = a * ((a + 1) - (a - 1) * cs - k)
                    a0 = (a + 1) + (a - 1) * cs + k
                    a1 = -2 * ((a - 1) + (a + 1) * cs)
                    a2 = (a + 1) + (a - 1) * cs - k
                }
                PeqType.HIGH_SHELF -> {
                    val k = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) + (a - 1) * cs + k)
                    b1 = -2 * a * ((a - 1) + (a + 1) * cs)
                    b2 = a * ((a + 1) + (a - 1) * cs - k)
                    a0 = (a + 1) - (a - 1) * cs + k
                    a1 = 2 * ((a - 1) - (a + 1) * cs)
                    a2 = (a + 1) - (a - 1) * cs - k
                }
                PeqType.PEAKING -> {
                    b0 = 1 + alpha * a
                    b1 = -2 * cs
                    b2 = 1 - alpha * a
                    a0 = 1 + alpha / a
                    a1 = -2 * cs
                    a2 = 1 - alpha / a
                }
            }
            return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }

        private val PREAMP = Regex("""^\s*Preamp:\s*([-+]?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
        private val FILTER = Regex(
            """^\s*Filter\s*\d*\s*:\s*(ON|OFF)\s+([A-Z]+)\s+Fc\s+([-+]?\d+(?:\.\d+)?)\s*Hz""" +
                """(?:\s+Gain\s+([-+]?\d+(?:\.\d+)?)\s*dB)?(?:\s+Q\s+([-+]?\d+(?:\.\d+)?))?""",
            RegexOption.IGNORE_CASE,
        )

        /** AutoEQ's "ParametricEQ.txt" (Equalizer APO format). */
        fun parse(text: String): PeqSettings? {
            var preamp = 0.0
            val filters = mutableListOf<PeqFilter>()
            for (line in text.lineSequence()) {
                PREAMP.find(line)?.let { preamp = it.groupValues[1].toDouble() }
                val m = FILTER.find(line) ?: continue
                if (!m.groupValues[1].equals("ON", true)) continue
                val type = when (m.groupValues[2].uppercase()) {
                    "PK", "PEQ", "PEAKING" -> PeqType.PEAKING
                    "LSC", "LS", "LSQ", "LOWSHELF" -> PeqType.LOW_SHELF
                    "HSC", "HS", "HSQ", "HIGHSHELF" -> PeqType.HIGH_SHELF
                    else -> continue
                }
                val fc = m.groupValues[3].toDoubleOrNull() ?: continue
                filters += PeqFilter(type, fc, m.groupValues[4].toDoubleOrNull() ?: 0.0, m.groupValues[5].toDoubleOrNull() ?: 0.7071)
                if (filters.size >= MAX_FILTERS) break
            }
            return if (filters.isEmpty()) null else PeqSettings(preamp.coerceAtMost(0.0), filters)
        }
    }
}

/** One headphone in the AutoEQ database (results/INDEX.md). */
@Serializable
data class AutoEqEntry(val name: String, val source: String, val rig: String? = null, val encodedPath: String) {
    val folderName: String
        get() = URLDecoder.decode(encodedPath.substringAfterLast('/').replace("+", "%2B"), "UTF-8")
    val subtitle: String get() = if (rig.isNullOrBlank()) source else "$source · $rig"
}

object AutoEq {

    private const val TAG = "BitChord"
    private const val BASE_URL = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"
    private const val INDEX_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
    private val LINE = Regex("""^- \[(.+)]\(\./(.+)\) by (.+?)(?: on (.+))?\s*$""")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Choice(val entry: AutoEqEntry, val settings: PeqSettings)

    private var prefs: SharedPreferences? = null
    private var root: File? = null
    private var audio: AudioManager? = null
    private val mutex = Mutex()
    @Volatile private var cachedEntries: List<AutoEqEntry>? = null

    /** The output playing now, as a stable key and a name to show. */
    data class Output(val key: String, val name: String)

    private val _output = MutableStateFlow(Output("speaker", "Phone speaker"))
    val output: StateFlow<Output> = _output.asStateFlow()

    private val _active = MutableStateFlow<Choice?>(null)

    /** The correction for the output playing now, or null. Read by the playback service. */
    val active: StateFlow<Choice?> = _active.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("bitchord_autoeq", Context.MODE_PRIVATE)
        root = File(context.filesDir, "autoeq").apply { mkdirs() }
        audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        refreshOutput()
        // Headphones plugged in or out, Bluetooth connecting: the correction follows.
        audio?.registerAudioDeviceCallback(object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshOutput()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshOutput()
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    /** Re-reads which output is playing (call on device changes). */
    fun refreshOutput() {
        val am = audio ?: return
        val outs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.getOrDefault(emptyList())
        val preferred = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )
        val device = preferred.firstNotNullOfOrNull { t -> outs.firstOrNull { it.type == t } }
        _output.value = if (device == null) {
            Output("speaker", "Phone speaker")
        } else {
            val name = device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Headphones"
            Output("${device.type}:$name", name)
        }
        _active.value = choiceFor(_output.value.key)
    }

    private fun choiceFor(key: String): Choice? =
        prefs?.getString("out:$key", null)?.let { runCatching { json.decodeFromString(Choice.serializer(), it) }.getOrNull() }

    /** [entry]'s correction, from now on whenever the current output plays. */
    suspend fun choose(entry: AutoEqEntry): Result<Choice> = runCatching {
        val settings = profile(entry)
        val choice = Choice(entry, settings)
        val key = _output.value.key
        prefs?.edit()?.putString("out:$key", json.encodeToString(Choice.serializer(), choice))?.apply()
        _active.value = choice
        TrackLog.d(TAG, "AutoEQ: ${entry.name} (${settings.filters.size} filters) for ${_output.value.name}")
        choice
    }

    fun clearForOutput() {
        prefs?.edit()?.remove("out:${_output.value.key}")?.apply()
        _active.value = null
    }

    suspend fun search(query: String, limit: Int = 100): List<AutoEqEntry> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return entries().filter { e ->
            val hay = "${e.name} ${e.source} ${e.rig.orEmpty()}".lowercase()
            words.all { it in hay }
        }.take(limit)
    }

    private suspend fun entries(): List<AutoEqEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dir = root ?: throw IOException("AutoEQ not ready")
            val file = File(dir, "INDEX.md")
            val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > INDEX_MAX_AGE_MS
            if (stale) {
                runCatching { download("$BASE_URL/INDEX.md") }
                    .onSuccess { body -> if (parseIndex(body).size > 100) { file.writeText(body); cachedEntries = null } }
                    .onFailure { if (!file.exists()) throw it }
            }
            cachedEntries ?: parseIndex(file.readText()).also { cachedEntries = it }
        }
    }

    private fun parseIndex(markdown: String): List<AutoEqEntry> = markdown.lineSequence().mapNotNull { line ->
        val m = LINE.find(line.trim()) ?: return@mapNotNull null
        AutoEqEntry(m.groupValues[1].trim(), m.groupValues[3].trim(), m.groupValues[4].trim().ifBlank { null }, m.groupValues[2].trim())
    }.toList()

    private suspend fun profile(entry: AutoEqEntry): PeqSettings = withContext(Dispatchers.IO) {
        val dir = File(root ?: throw IOException("AutoEQ not ready"), "profiles").apply { mkdirs() }
        val key = MessageDigest.getInstance("SHA-1").digest(entry.encodedPath.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        val cache = File(dir, "$key.txt")
        val text = if (cache.exists() && cache.length() > 0) cache.readText() else {
            val name = URLEncoder.encode("${entry.folderName} ParametricEQ.txt", "UTF-8").replace("+", "%20")
            download("$BASE_URL/${entry.encodedPath}/$name").also { cache.writeText(it) }
        }
        PeqSettings.parse(text) ?: throw IOException("This profile has no filters")
    }

    private fun download(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "BitChordAudio-AutoEQ").build()
        Http.client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.string() ?: throw IOException("Empty answer")
        }
    }
}

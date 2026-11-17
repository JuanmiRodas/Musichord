package com.music.bitchord.playback.tv

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricOffsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale

/**
 * BitChord Audio: the LG TV app (webos/) as somewhere to play.
 *
 * The TV runs the whole player by itself — it searches, streams and shows
 * lyrics with no phone at all. This is the phone's side of the link when one
 * is wanted: find TVs on the Wi-Fi (a UDP broadcast the TV's service answers),
 * pair once with the code the TV shows, then send it the queue, transport,
 * the lyrics picked here and the video to show, and follow what it is playing.
 *
 * HTTP is spoken over a plain socket on purpose: the app forbids cleartext
 * traffic everywhere, and a TV on the home network has no certificate to
 * offer. Keeping that one exception to these few requests to a paired TV's
 * own address is narrower than relaxing the policy for the whole app.
 */
object TvLink {

    data class Tv(val host: String, val port: Int, val name: String, val token: String? = null) {
        val id: String get() = "$host:$port"
    }

    data class NowOnTv(
        val videoId: String,
        val title: String,
        val artist: String,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val volume: Float,
        /** elapsedRealtime when it arrived. */
        val at: Long,
    )

    data class State(
        val found: List<Tv> = emptyList(),
        val saved: List<Tv> = emptyList(),
        val searching: Boolean = false,
        /** The TV the music is on, when it is. */
        val connected: Tv? = null,
        val connectingId: String? = null,
        val now: NowOnTv? = null,
        val error: String? = null,
        /** Whether the TV shows the song's video rather than the cover. */
        val videoShown: Boolean = false,
    )

    private const val PREFS = "bitchord_tv_link"
    private const val DISCOVERY_PORT = 47616
    private const val DEFAULT_PORT = 47615

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var prefs: SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var events: Job? = null
    private var discovery: Job? = null

    @Volatile internal var speaker: TvSpeaker? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.update { it.copy(saved = readSaved()) }
    }

    // ---- finding and pairing ------------------------------------------------------

    /** Looks for TVs for as long as the picker is open; returns how to stop. */
    fun discover(): () -> Unit {
        discovery?.cancel()
        discovery = scope.launch {
            _state.update { it.copy(searching = true) }
            try {
                while (isActive) {
                    val found = broadcast()
                    _state.update { s -> s.copy(found = found.filter { f -> s.saved.none { it.id == f.id } }) }
                    delay(4_000)
                }
            } finally {
                _state.update { it.copy(searching = false) }
            }
        }
        return { discovery?.cancel(); discovery = null }
    }

    private fun broadcast(): List<Tv> {
        val out = LinkedHashMap<String, Tv>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 700
                val ask = "BITCHORD?".toByteArray()
                for (target in broadcastAddresses()) {
                    runCatching { socket.send(DatagramPacket(ask, ask.size, target, DISCOVERY_PORT)) }
                }
                val buffer = ByteArray(2048)
                val until = System.currentTimeMillis() + 1_500
                while (System.currentTimeMillis() < until) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val json = runCatching { JSONObject(String(packet.data, 0, packet.length)) }.getOrNull() ?: continue
                    if (json.optString("app") != "bitchord-tv") continue
                    val host = packet.address.hostAddress ?: continue
                    val tv = Tv(host, json.optInt("port", DEFAULT_PORT), json.optString("name", "LG TV"))
                    out[tv.id] = tv
                }
            }
        }
        return out.values.toList()
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
                .forEach { if (it !in list) list += it }
        }
        return list
    }

    /** Pairs with [tv] using the code on its screen; true when it took. */
    fun pair(tv: Tv, code: String, onDone: (Boolean) -> Unit) {
        scope.launch {
            val phone = (Build.MODEL ?: "Phone").take(40)
            val body = JSONObject().put("pin", code.trim()).put("phone", phone).toString()
            val reply = runCatching { request(tv, "POST", "/remote/pair", body, token = null) }.getOrNull()
            val token = reply?.takeIf { it.first == 200 }?.let { runCatching { JSONObject(it.second).optString("token") }.getOrNull() }
            if (token.isNullOrEmpty()) {
                main.post { onDone(false) }
                return@launch
            }
            val name = runCatching { JSONObject(reply.second).optString("name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: tv.name
            val paired = tv.copy(token = token, name = name)
            save(paired)
            main.post { onDone(true) }
        }
    }

    /** A TV typed in by address, for networks that block broadcasts. */
    fun addByAddress(address: String, onDone: (Tv?) -> Unit) {
        scope.launch {
            val host = address.substringBefore(':').trim()
            val port = address.substringAfter(':', "").toIntOrNull() ?: DEFAULT_PORT
            val probe = Tv(host, port, host)
            val info = runCatching { request(probe, "GET", "/remote/info", null, token = null) }.getOrNull()
            val tv = info?.takeIf { it.first == 200 }?.let { r ->
                runCatching { JSONObject(r.second) }.getOrNull()?.takeIf { it.optString("app") == "bitchord-tv" }
                    ?.let { probe.copy(name = it.optString("name", host)) }
            }
            if (tv != null) _state.update { s -> s.copy(found = (s.found.filter { it.id != tv.id }) + tv) }
            main.post { onDone(tv) }
        }
    }

    fun forget(tv: Tv) {
        if (_state.value.connected?.id == tv.id) disconnect(resumeHere = false)
        val left = readSaved().filter { it.id != tv.id }
        writeSaved(left)
        _state.update { it.copy(saved = left) }
    }

    // ---- the music on the TV --------------------------------------------------------

    /** Moves the phone's queue to [tv]. */
    fun connect(tv: Tv) {
        val speaker = speaker ?: return
        if (tv.token == null) return
        _state.update { it.copy(connectingId = tv.id, error = null) }
        scope.launch {
            val ok = runCatching { request(tv, "GET", "/remote/state", null, tv.token).first }.getOrNull()
            main.post {
                _state.update { it.copy(connectingId = null) }
                when (ok) {
                    200 -> {
                        _state.update { it.copy(connected = tv, now = null, videoShown = false) }
                        listen(tv)
                        speaker.start()
                    }
                    401 -> {
                        forget(tv)
                        _state.update { it.copy(error = "unpaired") }
                    }
                    else -> _state.update { it.copy(error = "unreachable") }
                }
            }
        }
    }

    /** The music comes back to the phone. */
    fun disconnect(resumeHere: Boolean) {
        speaker?.stop(resumeHere)
        events?.cancel()
        events = null
        _state.update { it.copy(connected = null, now = null, videoShown = false) }
    }

    /** Shows the playing song's video on the TV, or back to the cover. */
    fun showVideo(show: Boolean) {
        val now = _state.value.now
        val id = now?.videoId ?: speaker?.currentId() ?: return
        _state.update { it.copy(videoShown = show) }
        if (show) send(JSONObject().put("type", "video").put("videoId", id).put("title", now?.title).put("artist", now?.artist))
        else speaker?.reload()
    }

    internal fun send(command: JSONObject, onFailed: (() -> Unit)? = null) {
        val tv = _state.value.connected ?: return
        scope.launch {
            val code = runCatching { request(tv, "POST", "/remote/command", command.toString(), tv.token).first }.getOrNull()
            if (code != 200) main.post { onFailed?.invoke() }
        }
    }

    private fun listen(tv: Tv) {
        events?.cancel()
        events = scope.launch {
            var failures = 0
            while (isActive && _state.value.connected?.id == tv.id) {
                val opened = runCatching { stream(tv, "/remote/events") { onEvent(it) } }.isSuccess
                failures = if (opened) 0 else failures + 1
                if (failures >= 5) {
                    main.post { if (_state.value.connected?.id == tv.id) { _state.update { it.copy(error = "unreachable") }; disconnect(resumeHere = false) } }
                    return@launch
                }
                delay(2_000)
            }
        }
    }

    private fun onEvent(data: String) {
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return
        val playing = json.optJSONObject("nowPlaying")
        val now = playing?.let {
            NowOnTv(
                videoId = it.optString("videoId"),
                title = it.optString("title"),
                artist = it.optString("artist"),
                playing = json.optBoolean("playing"),
                positionMs = (json.optDouble("positionSec", 0.0) * 1000).toLong(),
                durationMs = (json.optDouble("durationSec", 0.0) * 1000).toLong(),
                volume = json.optDouble("volume", 1.0).toFloat(),
                at = android.os.SystemClock.elapsedRealtime(),
            )
        }
        main.post {
            _state.update { it.copy(now = now) }
            speaker?.onTvState(now, json.optString("status"))
        }
    }

    // ---- lyrics picked on the phone ---------------------------------------------------

    private var lyricsFor: String? = null
    private var lyricsLines: List<LyricLine>? = null
    private var offsetWatch: Job? = null
    private var sentLyrics: String? = null

    /** The lyrics the phone shows for [videoId]: the TV gets the same, with the same shift. */
    fun offerLyrics(videoId: String?, lines: List<LyricLine>?) {
        if (videoId == null) return
        main.post {
            if (videoId != lyricsFor) {
                offsetWatch?.cancel()
                // A nudge on the phone moves the TV's lyrics too.
                offsetWatch = scope.launch { LyricOffsets.flow(videoId).collect { main.post { pushLyrics() } } }
            }
            lyricsFor = videoId
            lyricsLines = lines
            pushLyrics()
        }
    }

    /** Sent again when the TV moves to the song the lyrics belong to. */
    internal fun pushLyrics() {
        val tv = _state.value.connected ?: return
        val id = lyricsFor ?: return
        val lines = lyricsLines?.takeIf { it.isNotEmpty() } ?: return
        val offset = LyricOffsets.flow(id).value
        val lrc = enhancedLrc(lines)
        val key = "${tv.id}|$id|$offset|${lrc.hashCode()}"
        if (key == sentLyrics) return
        sentLyrics = key
        send(JSONObject().put("type", "lyrics").put("videoId", id).put("lrc", lrc).put("offsetMs", offset))
    }

    /** Forgets what was sent, so the next [pushLyrics] goes out even if unchanged (a new TV queue). */
    internal fun lyricsResend() {
        sentLyrics = null
        pushLyrics()
    }

    private fun stamp(ms: Long): String {
        val t = ms.coerceAtLeast(0L)
        return String.format(Locale.ROOT, "%02d:%02d.%02d", t / 60_000, t / 1000 % 60, t / 10 % 100)
    }

    /** Lines as LRC, word-timed (`<mm:ss.xx>`) where the phone has word timing. */
    private fun enhancedLrc(lines: List<LyricLine>): String = buildString {
        lines.forEach { line ->
            append('[').append(stamp(line.timeMs)).append(']')
            if (line.words.isNotEmpty()) {
                line.words.forEach { w -> append('<').append(stamp(w.startMs)).append('>').append(w.text) }
                line.words.last().let { append('<').append(stamp(it.endMs)).append('>') }
            } else {
                append(line.text)
            }
            append('\n')
        }
    }

    // ---- storage ---------------------------------------------------------------------

    private fun readSaved(): List<Tv> {
        val raw = prefs?.getString("tvs", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Tv(o.getString("host"), o.optInt("port", DEFAULT_PORT), o.optString("name", "LG TV"), o.optString("token").ifEmpty { null })
            }
        }.getOrDefault(emptyList())
    }

    private fun writeSaved(list: List<Tv>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("host", it.host).put("port", it.port).put("name", it.name).put("token", it.token)) }
        prefs?.edit()?.putString("tvs", array.toString())?.apply()
    }

    private fun save(tv: Tv) {
        val list = readSaved().filter { it.id != tv.id } + tv
        writeSaved(list)
        _state.update { s -> s.copy(saved = list, found = s.found.filter { it.id != tv.id }) }
    }

    // ---- HTTP/1.0 over a socket ----------------------------------------------------------

    private fun open(tv: Tv, method: String, path: String, body: String?, token: String?, readTimeoutMs: Int): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(tv.host, tv.port), 3_000)
        socket.soTimeout = readTimeoutMs
        val bytes = body?.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append(method).append(' ').append(path).append(" HTTP/1.0\r\n")
            append("Host: ").append(tv.host).append(':').append(tv.port).append("\r\n")
            if (token != null) append("X-BitChord-Token: ").append(token).append("\r\n")
            if (bytes != null) {
                append("Content-Type: application/json\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (bytes != null) out.write(bytes)
        out.flush()
        return socket
    }

    /** (status, body). */
    private fun request(tv: Tv, method: String, path: String, body: String?, token: String?): Pair<Int, String> =
        open(tv, method, path, body, token, 5_000).use { socket ->
            val text = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val status = text.substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            status to text.substringAfter("\r\n\r\n", "")
        }

    /** Server-sent events: each `data:` line to [onData], until the TV closes the stream. */
    private fun stream(tv: Tv, path: String, onData: (String) -> Unit) {
        // The TV pings every 15 s, so 40 s of silence means it is gone.
        open(tv, "GET", path, null, tv.token, 40_000).use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val status = reader.readLine()?.split(' ')?.getOrNull(1)?.toIntOrNull()
            if (status != 200) throw IllegalStateException("events: $status")
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val data = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart())
                    line.isEmpty() && data.isNotEmpty() -> { onData(data.toString()); data.setLength(0) }
                }
            }
        }
    }
}

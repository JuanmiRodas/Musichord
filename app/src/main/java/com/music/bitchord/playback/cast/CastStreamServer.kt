package com.music.bitchord.playback.cast

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.playback.DirectSourceGate
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * BitChord Audio: the phone as the Cast receiver's file server, for the tracks
 * a receiver cannot fetch by itself — your pCloud files (behind a password),
 * JioSaavn and addon streams that want headers, files on the phone.
 *
 * The receiver is handed `http://<phone>:<port>/t/<token>/<name>`; each request
 * is answered by reading the track through the app's own stream chain (the
 * same one the phone's player uses, credentials and resolvers included), with
 * byte ranges so the receiver can seek. Only tokens handed out here are served,
 * and only on the local network. The Wi-Fi and the CPU are kept awake while
 * the receiver is reading, so the music doesn't stop when the screen goes off.
 */
class CastStreamServer(
    private val context: Context,
    private val factory: () -> DataSource.Factory?,
) {
    private class Entry(val uri: Uri, val mime: String, @Volatile var length: Long = C.LENGTH_UNSET.toLong())

    private val entries = ConcurrentHashMap<String, Entry>()
    private val byUri = ConcurrentHashMap<String, String>()

    @Volatile
    private var socket: ServerSocket? = null
    private val active = AtomicInteger(0)
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** The address the receiver reads [uri] at, or null when the phone has no local network address. */
    fun urlFor(uri: Uri, mime: String, name: String): String? {
        val host = lanAddress() ?: return null
        val server = ensureStarted() ?: return null
        val key = uri.toString()
        val token = byUri.getOrPut(key) { UUID.randomUUID().toString().replace("-", "") }
        entries.putIfAbsent(token, Entry(uri, mime))
        val safeName = URLEncoder.encode(name.ifBlank { "track" }, "UTF-8").replace("+", "%20")
        return "http://${host.hostAddress}:${server.localPort}/t/$token/$safeName"
    }

    @Synchronized
    private fun ensureStarted(): ServerSocket? {
        socket?.takeIf { !it.isClosed }?.let { return it }
        return runCatching {
            ServerSocket(0, 16).also { s ->
                socket = s
                Thread({ acceptLoop(s) }, "cast-stream-server").apply { isDaemon = true }.start()
                Log.d(TAG, "cast stream server on port ${s.localPort}")
            }
        }.onFailure { Log.w(TAG, "cast stream server failed to start", it) }.getOrNull()
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            Thread({ serve(client) }, "cast-stream").apply { isDaemon = true }.start()
        }
    }

    private fun serve(client: Socket) {
        hold()
        try {
            client.soTimeout = 30_000
            client.use { s ->
                val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = input.readLine() ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val path = parts.getOrNull(1).orEmpty()
                val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                val token = path.removePrefix("/t/").substringBefore('/')
                val entry = entries[token]
                if (!path.startsWith("/t/") || entry == null || (method != "GET" && method != "HEAD")) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }
                respond(entry, method == "HEAD", headers["range"], out)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "cast stream: ${t.message}")
        } finally {
            release()
        }
    }

    private fun respond(entry: Entry, head: Boolean, range: String?, out: BufferedOutputStream) {
        val factory = factory() ?: throw IllegalStateException("no stream chain")
        // The whole length, once: needed to answer ranges.
        if (entry.length == C.LENGTH_UNSET.toLong()) {
            val probe = factory.createDataSource()
            try {
                entry.length = DirectSourceGate.ungated { probe.open(DataSpec(entry.uri)) }
            } finally {
                runCatching { probe.close() }
            }
        }
        val total = entry.length
        var start = 0L
        var end = if (total > 0) total - 1 else -1L
        val ranged = range != null && range.startsWith("bytes=")
        if (ranged) {
            val spec = range!!.removePrefix("bytes=").substringBefore(',')
            val a = spec.substringBefore('-').trim()
            val b = spec.substringAfter('-', "").trim()
            if (a.isEmpty() && b.isNotEmpty() && total > 0) {
                start = maxOf(0L, total - b.toLong())
            } else {
                start = a.toLongOrNull() ?: 0L
                if (b.isNotEmpty()) end = b.toLong().let { if (total > 0) minOf(it, total - 1) else it }
            }
        }
        if (total > 0 && start >= total) {
            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        val length = if (end >= start && end >= 0) end - start + 1 else -1L
        val status = if (ranged && total > 0) "206 Partial Content" else "200 OK"
        val sb = StringBuilder("HTTP/1.1 $status\r\n")
            .append("Content-Type: ").append(entry.mime).append("\r\n")
            .append("Accept-Ranges: bytes\r\n")
            .append("Access-Control-Allow-Origin: *\r\n")
            .append("Connection: close\r\n")
        if (length >= 0) sb.append("Content-Length: ").append(length).append("\r\n")
        if (ranged && total > 0) sb.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (head) { out.flush(); return }

        val source = factory.createDataSource()
        try {
            DirectSourceGate.ungated {
                source.open(DataSpec.Builder().setUri(entry.uri).setPosition(start).setLength(if (length >= 0) length else C.LENGTH_UNSET.toLong()).build())
            }
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = source.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
            }
            out.flush()
        } finally {
            runCatching { source.close() }
        }
    }

    // ── Staying awake while the receiver reads ───────────────────────────

    @Synchronized
    private fun hold() {
        if (active.getAndIncrement() > 0) return
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = (wifiLock ?: wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "bitchord:cast")).apply {
                setReferenceCounted(false); acquire()
            }
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = (wakeLock ?: power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bitchord:cast")).apply {
                setReferenceCounted(false); acquire(10 * 60 * 1000L)
            }
        }
    }

    @Synchronized
    private fun release() {
        if (active.decrementAndGet() > 0) return
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    }

    /** The phone's address on the local network (Wi-Fi first). */
    private fun lanAddress(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    }.getOrNull()

    private companion object {
        const val TAG = "BitChord"
    }
}

package com.music.bitchord.data

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * BitChord Audio: how much memory the app holds, in the running log — the
 * crashes so far were the app running out of it, and this says how it got
 * there (steadily, or all at once, and with what loaded). When Android says
 * memory is short, the picture caches are let go of.
 */
object MemoryWatch {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    fun usedMb(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
    }

    fun maxMb(): Long = Runtime.getRuntime().maxMemory() / (1024 * 1024)

    fun line(): String {
        val native = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val library = runCatching { com.music.bitchord.data.webdav.WebDavRepository.library().size }.getOrDefault(-1)
        return "memory: ${usedMb()} of ${maxMb()} MB used (native $native MB) · library $library songs · " +
            "liked ${LikedMusicCache.likedSongs.value.size}"
    }

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        lastApp = app
        TrackLog.d("BitChord", line() + " · large heap: " + ((app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).largeMemoryClass) + " MB")
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                TrackLog.w("BitChord", "Android asks to free memory (level $level); ${line()}")
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) freeCaches(app)
            }
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                TrackLog.w("BitChord", "low memory; ${line()}")
                freeCaches(app)
            }
        })
        scope.launch {
            var last = 0L
            while (true) {
                delay(60_000)
                val used = usedMb()
                // Every minute while it grows fast or is high; else every ten.
                val high = used > maxMb() * 6 / 10
                if (high || used - last > 40 || System.currentTimeMillis() / 60_000 % 10 == 0L) {
                    TrackLog.d("BitChord", line())
                    last = used
                }
                if (used > maxMb() * 85 / 100) freeCaches(app)
                runCatching { cpu() }
            }
        }
    }

    // ── CPU: which threads keep the phone busy (and hot) ──

    private var lastTicks = HashMap<String, Long>()
    private var lastAt = 0L

    /** Ticks (1/100 s) each thread has run, by "tid name". */
    private fun ticks(): Map<String, Long> {
        val out = HashMap<String, Long>()
        java.io.File("/proc/self/task").listFiles()?.forEach { t ->
            val stat = runCatching { java.io.File(t, "stat").readText() }.getOrNull() ?: return@forEach
            val open = stat.indexOf('('); val close = stat.lastIndexOf(')')
            if (open < 0 || close < 0) return@forEach
            val name = stat.substring(open + 1, close)
            val f = stat.substring(close + 2).split(' ')
            // After ")": state(0) … utime(11) stime(12)
            val n = (f.getOrNull(11)?.toLongOrNull() ?: 0) + (f.getOrNull(12)?.toLongOrNull() ?: 0)
            out["${t.name} $name"] = n
        }
        return out
    }

    /** Once a minute: if the app used more than a tenth of a core, the busiest threads and what they run. */
    private fun cpu() {
        val now = System.currentTimeMillis()
        val cur = ticks()
        val prev = lastTicks; val since = now - lastAt
        lastTicks = HashMap(cur); lastAt = now
        if (prev.isEmpty() || since <= 0) return
        val delta = cur.mapNotNull { (k, v) -> prev[k]?.let { k to (v - it) } ?: (k to v) }.filter { it.second > 0 }
        val total = delta.sumOf { it.second } * 10 // ms
        val pct = total * 100 / since
        if (pct < 10) return
        val top = delta.sortedByDescending { it.second }.take(5)
        val stacks = runCatching { Thread.getAllStackTraces() }.getOrDefault(emptyMap())
        val lines = top.joinToString(" · ") { (k, v) ->
            val name = k.substringAfter(' ')
            val st = stacks.entries.firstOrNull { it.key.name.take(15) == name }?.value
            val where = st?.firstOrNull { it.className.startsWith("com.music.") }?.let { " @${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }.orEmpty()
            "$name ${v * 10 * 100 / since}%$where"
        }
        val screen = runCatching {
            (lastApp?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager)?.isInteractive
        }.getOrNull()
        TrackLog.w("BitChord", "cpu: $pct% of a core in the last ${since / 1000}s (screen ${if (screen == true) "on" else "off"}) — $lines")
        // What the busy app threads are running right now (sampled a few times).
        val seen = HashMap<String, Int>()
        repeat(5) {
            runCatching { Thread.getAllStackTraces() }.getOrDefault(emptyMap()).forEach { (t, st) ->
                if (t == Thread.currentThread() || t.state != Thread.State.RUNNABLE) return@forEach
                val app = st.filter { it.className.startsWith("com.music.") }.take(6)
                if (app.isEmpty()) return@forEach
                val k = app.joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                seen[k] = (seen[k] ?: 0) + 1
            }
            Thread.sleep(150)
        }
        seen.entries.sortedByDescending { it.value }.take(4).forEach { (k, n) -> TrackLog.w("BitChord", "cpu busy ($n/5): $k") }
    }

    @Volatile private var lastApp: Context? = null

    private fun freeCaches(context: Context) {
        runCatching { coil3.SingletonImageLoader.get(context).memoryCache?.clear() }
        System.gc()
        TrackLog.d("BitChord", "picture caches let go of; ${line()}")
    }
}

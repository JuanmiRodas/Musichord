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
            }
        }
    }

    private fun freeCaches(context: Context) {
        runCatching { coil3.SingletonImageLoader.get(context).memoryCache?.clear() }
        System.gc()
        TrackLog.d("BitChord", "picture caches let go of; ${line()}")
    }
}

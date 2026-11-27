package com.music.bitchord.data

import android.content.Context
import android.os.PowerManager
import com.music.bitchord.playback.smart.AudioCheck
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * BitChord Audio: the hours heavy background work may run — listening to links, writing
 * tags and covers into files, sweeping lyrics, mapping song families: at night only (the
 * hours set in the library check's screen, 01:00–07:00 by default). During the day the
 * app does what is asked of it and nothing more.
 *
 * Even at night it steps aside while you are using the app (open, screen on), so a
 * search or a video isn't kept waiting behind it, and while YouTube is limiting
 * requests ([YouTubeLimit]). It carries on once you leave the app or the screen goes off.
 */
object BackgroundHours {

    private const val TAG = "BackgroundHours"

    @Volatile private var appOpen = false
    @Volatile private var power: PowerManager? = null
    @Volatile private var saidInUse = false

    /** Called once at start, with the app's context. */
    fun init(context: Context) {
        power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        runCatching {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) { appOpen = true }
                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { appOpen = false }
            })
        }
    }

    /** Whether you are in the app right now: open on a screen that is on (not the screen-off music view). */
    fun inUse(): Boolean = appOpen && power?.isInteractive != false

    /** The night hours, by the clock alone. */
    fun hours(): Boolean {
        val s = AudioCheck.schedule.value
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            s.fromHour == s.toHour -> true
            s.fromHour < s.toHour -> h in s.fromHour until s.toHour
            else -> h >= s.fromHour || h < s.toHour
        }
    }

    fun now(): Boolean {
        if (!hours()) return false
        if (inUse()) {
            if (!saidInUse) {
                saidInUse = true
                TrackLog.d(TAG, "night work paused while you use the app")
            }
            return false
        }
        if (saidInUse) {
            saidInUse = false
            TrackLog.d(TAG, "night work carries on")
        }
        return !YouTubeLimit.active()
    }

    /** Suspends until the night hours, with the app left alone. */
    suspend fun await() {
        while (!now()) delay(if (hours()) 60_000L else 5 * 60_000L)
    }
}

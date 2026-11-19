package com.music.bitchord.data

import com.music.bitchord.playback.smart.AudioCheck
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * BitChord Audio: the hours heavy background work may run — listening to links, writing
 * tags and covers into files, sweeping lyrics, mapping song families: at night only (the
 * hours set in the library check's screen, 01:00–07:00 by default). During the day the
 * app does what is asked of it and nothing more.
 */
object BackgroundHours {

    fun now(): Boolean {
        val s = AudioCheck.schedule.value
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            s.fromHour == s.toHour -> true
            s.fromHour < s.toHour -> h in s.fromHour until s.toHour
            else -> h >= s.fromHour || h < s.toHour
        }
    }

    /** Suspends until the night hours. */
    suspend fun await() {
        while (!now()) delay(5 * 60_000L)
    }
}

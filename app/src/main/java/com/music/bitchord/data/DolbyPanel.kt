package com.music.bitchord.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.music.bitchord.BuildConfig

/**
 * BitChord Audio: the phone's own Dolby Atmos sound settings.
 *
 * Not the in-app "Dolby Atmos" switch, which plays Atmos *versions* of songs
 * and needs a Dolby decoder (E-AC-3 JOC) most phones, OnePlus included, don't
 * have. The phone's Dolby Atmos is a sound effect applied on top of any song,
 * and it is switched on in the phone's settings, while music plays. On
 * OnePlus/OPPO it only acts on apps it recognises as music players, which is
 * what the BitChord Dolby edition is for.
 */
object DolbyPanel {

    /** Dolby's own settings apps, as phone makers ship them. */
    private val PACKAGES = listOf(
        "com.dolby.daxappui",
        "com.dolby.daxappUI",
        "com.dolby.ds1appUI",
        "com.oplus.dolby",
        "com.coloros.dolby",
        "com.motorola.dolby.dolbyui",
    )

    /** Whether this is the Dolby edition (the package OnePlus/OPPO enable Dolby for). */
    val isDolbyEdition: Boolean get() = BuildConfig.FLAVOR == "dolby"

    /** Opens the Dolby panel, else the phone's sound settings (Dolby sits there on OnePlus). */
    fun open(context: Context): Boolean {
        val pm = context.packageManager
        for (pkg in PACKAGES) {
            val launch = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() ?: continue
            if (start(context, launch)) return true
        }
        return start(context, Intent(Settings.ACTION_SOUND_SETTINGS))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

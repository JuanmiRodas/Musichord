package com.music.bitchord.aod

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.music.bitchord.R
import com.music.bitchord.data.TrackLog

/**
 * BitChord Audio: puts the always-on music screen ([AodActivity]) up when the
 * screen is switched off while music plays — in place of the phone's own
 * always-on display, which only shows Spotify's music.
 *
 * An app may not open a screen from the background, so it is opened the way an
 * alarm clock opens its screen: a silent notification carrying a full-screen
 * intent, which Android turns into the screen straight away (Android 14+ asks
 * you once to allow "full-screen notifications" for the app).
 */
object AodTrigger {

    private const val TAG = "BitChord"
    private const val CHANNEL = "aod"
    private const val NOTIFICATION_ID = 0xA0D

    private var registered = false
    @Volatile private var isPlaying: () -> Boolean = { false }

    /**
     * Listens for the screen going off; [isPlaying] says whether music is
     * playing then. A service that comes back replaces the question (the
     * listener itself is registered once, on the app).
     */
    fun attach(context: Context, isPlaying: () -> Boolean) {
        this.isPlaying = isPlaying
        if (registered) return
        registered = true
        val app = context.applicationContext
        AodSettings.init(app)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_SCREEN_OFF) return
                if (!AodSettings.enabled.value || AodActivity.showing) return
                if (!runCatching { isPlaying() }.getOrDefault(false)) return
                show(app)
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun canShowOverLock(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }

    /** Opens the setting that lets the app show its screen over the lock screen. */
    fun askPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(android.net.Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Turning it on: asks for what it needs — showing over the lock screen
     * (Android 14+) and, once, notification access for the icons.
     */
    fun askIfNeeded(context: Context) {
        if (!canShowOverLock(context)) {
            android.widget.Toast.makeText(context, R.string.aod_permission, android.widget.Toast.LENGTH_LONG).show()
            askPermission(context)
        } else {
            com.music.bitchord.playback.NotificationIcons.ask(context)
        }
    }

    private fun show(context: Context) {
        val intent = Intent(context, AodActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        // First the direct way (allowed when the app was just on screen)…
        val direct = runCatching { context.startActivity(intent) }.isSuccess
        // …and the alarm-clock way, which works from the background.
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.aod_title), NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(context.getString(R.string.aod_title))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(5_000)
            .setFullScreenIntent(pending, true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
        TrackLog.d(TAG, "aod: screen off while playing; showing the music screen (direct=$direct)")
    }

    fun clearNotification(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
    }
}

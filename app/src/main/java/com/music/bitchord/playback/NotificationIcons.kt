package com.music.bitchord.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BitChord Audio: the icons of the notifications you have, for the OLED lyrics
 * screen to show the way the status bar would — one per app, in the order they
 * came, ongoing ones (this app's player, a download) left out.
 *
 * Needs notification access (Settings → Notification access), asked for the
 * first time the OLED screen opens. Only the icons are kept; the notifications'
 * text is never read.
 */
class NotificationIconsService : NotificationListenerService() {

    override fun onListenerConnected() = publish()
    override fun onNotificationPosted(sbn: StatusBarNotification?) = publish()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = publish()

    override fun onListenerDisconnected() {
        NotificationIcons.set(emptyList())
    }

    private fun publish() {
        val list = runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter { !it.isOngoing && it.packageName != packageName && it.notification.smallIcon != null }
            .sortedBy { it.postTime }
            .distinctBy { it.packageName }
        val icons = list.mapNotNull { sbn ->
            runCatching {
                val context = runCatching { createPackageContext(sbn.packageName, 0) }.getOrDefault(this)
                iconFor(sbn.packageName, sbn.notification.smallIcon.loadDrawable(context))?.asImageBitmap()
            }.getOrNull()
        }
        NotificationIcons.set(icons.take(8))
    }

    /**
     * What to draw for one notification, ready to show as it is (no tint).
     *
     * A proper small icon is a silhouette — only its alpha means anything — so
     * it is painted white. Some apps (and the system, for some of them) hand
     * over a full-colour or fully opaque one instead: painted white that is just
     * a grey square, so for those the app's own icon is shown, in colour and
     * rounded, which is what the system does in its status bar too.
     */
    private fun iconFor(pkg: String, small: android.graphics.drawable.Drawable?): Bitmap? {
        val size = 48
        val raw = small?.toBitmap(size, size, Bitmap.Config.ARGB_8888)
        if (raw != null && isSilhouette(raw)) {
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(out)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = android.graphics.PorterDuffColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
            }
            canvas.drawBitmap(raw, 0f, 0f, paint)
            return out
        }
        val app = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull() ?: return null
        val colour = app.toBitmap(size, size, Bitmap.Config.ARGB_8888)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(colour, 0f, 0f, paint)
        return out
    }

    /**
     * True for a real silhouette: a fair part of it transparent. An icon opaque
     * almost edge to edge is a picture, not a shape (a tinted square otherwise).
     */
    private fun isSilhouette(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val opaque = pixels.count { (it ushr 24) > 0x40 }
        if (opaque == 0) return false
        return opaque < pixels.size * 0.8
    }
}

object NotificationIcons {
    private val _icons = MutableStateFlow<List<ImageBitmap>>(emptyList())
    val icons: StateFlow<List<ImageBitmap>> = _icons.asStateFlow()

    internal fun set(list: List<ImageBitmap>) { _icons.value = list }

    fun granted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    private const val PREFS = "bitchord_notification_icons"

    /**
     * Opens the notification-access setting, once (a second time only when
     * [force]); after that the OLED screen just shows no icons.
     */
    fun ask(context: Context, force: Boolean = false) {
        if (granted(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && prefs.getBoolean("asked", false)) return
        prefs.edit().putBoolean("asked", true).apply()
        val component = ComponentName(context, NotificationIconsService::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        android.widget.Toast.makeText(
            context,
            "Allow notification access to see your notification icons on the OLED screen",
            android.widget.Toast.LENGTH_LONG,
        ).show()
        runCatching { context.startActivity(intent) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}

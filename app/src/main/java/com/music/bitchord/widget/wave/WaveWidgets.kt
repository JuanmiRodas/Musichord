package com.music.bitchord.widget.wave

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.music.bitchord.MainActivity
import com.music.bitchord.widget.MediaWidgetActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * BitChord Audio: LastWave's "Wave" home-screen widgets (Clash-Projects,
 * GPL-3.0) — strip, glass and circle — drawn by its views and graphics,
 * fed by the same snapshot as our own widgets and driving the same actions.
 */

/** What every Wave widget draws. */
data class WaveWidgetState(
    val trackId: String = "",
    val title: String = "",
    val artist: String = "",
    val artworkUri: String? = null,
    val isPlaying: Boolean = false,
    val isLiked: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val updatedAtEpochMs: Long = 0L,
) {
    val hasTrack: Boolean get() = trackId.isNotBlank() && title.isNotBlank()

    fun progressAt(nowEpochMs: Long = System.currentTimeMillis()): Float {
        if (durationMs <= 0L) return 0f
        val elapsed = if (isPlaying && updatedAtEpochMs > 0L) (nowEpochMs - updatedAtEpochMs).coerceAtLeast(0L) else 0L
        return ((positionMs + elapsed).coerceIn(0L, durationMs).toDouble() / durationMs).toFloat().coerceIn(0f, 1f)
    }
}

object WaveWidgetActions {
    const val ACTION_TOGGLE = MediaWidgetActions.ACTION_TOGGLE
    const val ACTION_NEXT = MediaWidgetActions.ACTION_NEXT
    const val ACTION_PREVIOUS = MediaWidgetActions.ACTION_PREVIOUS
    const val ACTION_LIKE = MediaWidgetActions.ACTION_LIKE
    const val ACTION_SHUFFLE = MediaWidgetActions.ACTION_SHUFFLE

    @Suppress("UNUSED_PARAMETER")
    fun pending(context: Context, widgetId: Int, action: String, target: String? = null): PendingIntent =
        MediaWidgetActions.pendingIntent(context, action)

    fun openPending(context: Context, widgetId: Int): PendingIntent = PendingIntent.getActivity(
        context,
        0x5741 + widgetId,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(com.music.bitchord.playback.PlayerDeepLink.EXTRA_OPEN_PLAYER, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

abstract class WaveWidgetProvider : AppWidgetProvider() {
    abstract val kind: WaveWidgetKind

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        WaveWidgetUpdater.render(context, kind, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        WaveWidgetUpdater.render(context, kind, intArrayOf(appWidgetId))
    }
}

class WaveStripWidgetProvider : WaveWidgetProvider() { override val kind = WaveWidgetKind.STRIP }
class WaveGlassWidgetProvider : WaveWidgetProvider() { override val kind = WaveWidgetKind.GLASS }
class WaveCircularWidgetProvider : WaveWidgetProvider() { override val kind = WaveWidgetKind.CIRCULAR }

object WaveWidgetUpdater {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var state = WaveWidgetState()
    private var ticker: Job? = null

    private val providers = mapOf(
        WaveWidgetKind.STRIP to WaveStripWidgetProvider::class.java,
        WaveWidgetKind.GLASS to WaveGlassWidgetProvider::class.java,
        WaveWidgetKind.CIRCULAR to WaveCircularWidgetProvider::class.java,
    )

    /** Called by the playback service with every widget snapshot. */
    fun publish(context: Context, newState: WaveWidgetState) {
        val app = context.applicationContext
        state = newState
        renderAll(app)
        // Glass and circle draw progress: redrawn every few seconds while playing.
        ticker?.cancel()
        if (newState.isPlaying && newState.durationMs > 0) {
            ticker = scope.launch {
                while (isActive) {
                    delay(5_000)
                    renderAll(app, progressOnly = true)
                }
            }
        }
    }

    private fun ids(context: Context, kind: WaveWidgetKind): IntArray =
        runCatching {
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, providers.getValue(kind)))
        }.getOrDefault(IntArray(0))

    private fun renderAll(context: Context, progressOnly: Boolean = false) {
        WaveWidgetKind.entries.forEach { kind ->
            if (progressOnly && kind == WaveWidgetKind.STRIP) return@forEach
            val ids = ids(context, kind)
            if (ids.isNotEmpty()) render(context, kind, ids)
        }
        if (progressOnly && WaveWidgetKind.entries.none { it != WaveWidgetKind.STRIP && ids(context, it).isNotEmpty() }) {
            ticker?.cancel()
        }
    }

    fun render(context: Context, kind: WaveWidgetKind, ids: IntArray) {
        val app = context.applicationContext
        val current = state
        scope.launch {
            val art = runCatching {
                WaveWidgetArt.resolve(app, current.trackId, current.artworkUri, null)
            }.getOrDefault(WaveWidgetArt.Result(null, null, null))
            val manager = AppWidgetManager.getInstance(app)
            ids.forEach { id ->
                runCatching { manager.updateAppWidget(id, WaveWidgetViews.build(app, id, kind, current, art)) }
            }
        }
    }
}

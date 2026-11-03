package com.music.bitchord.aod

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * BitChord Audio: widgets from any app (weather, calendar, notes…) on the
 * always-on music screen, hosted the way a launcher hosts them.
 */
object AodWidgets {

    private const val HOST_ID = 0xA0D
    private var host: AppWidgetHost? = null

    fun host(context: Context): AppWidgetHost =
        host ?: AppWidgetHost(context.applicationContext, HOST_ID).also { host = it }

    fun providers(context: Context): List<AppWidgetProviderInfo> =
        AppWidgetManager.getInstance(context).installedProviders
            .sortedBy { it.loadLabel(context.packageManager).lowercase() }

    fun info(context: Context, id: Int): AppWidgetProviderInfo? =
        AppWidgetManager.getInstance(context).getAppWidgetInfo(id)

    fun remove(context: Context, id: Int) {
        runCatching { host(context).deleteAppWidgetId(id) }
        AodSettings.setWidgets(AodSettings.widgets.value - id)
    }

    /** Starts adding [provider]'s widget: allowed, configured, then saved. */
    fun add(context: Context, provider: ComponentName) {
        context.startActivity(
            Intent(context, AodWidgetPickerActivity::class.java)
                .putExtra(AodWidgetPickerActivity.EXTRA_PROVIDER, provider)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Asks Android for leave to show a widget (the "allow BitChord to create
 * widgets" prompt), opens the widget's own set-up screen when it has one, and
 * keeps it for the always-on screen.
 */
class AodWidgetPickerActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AodSettings.init(this)
        @Suppress("DEPRECATION")
        val provider = intent.getParcelableExtra<ComponentName>(EXTRA_PROVIDER) ?: return finish()
        widgetId = AodWidgets.host(this).allocateAppWidgetId()
        val manager = AppWidgetManager.getInstance(this)
        if (manager.bindAppWidgetIdIfAllowed(widgetId, provider)) {
            configure()
        } else {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider),
                REQUEST_BIND,
            )
        }
    }

    private fun configure() {
        val info = AodWidgets.info(this, widgetId)
        if (info?.configure != null) {
            runCatching {
                AodWidgets.host(this).startAppWidgetConfigureActivityForResult(this, widgetId, 0, REQUEST_CONFIGURE, null)
            }.onFailure { done(true) }
        } else {
            done(true)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_BIND -> if (resultCode == RESULT_OK) configure() else done(false)
            REQUEST_CONFIGURE -> done(resultCode == RESULT_OK)
        }
    }

    private fun done(ok: Boolean) {
        if (ok) {
            AodSettings.setWidgets(AodSettings.widgets.value + widgetId)
        } else {
            runCatching { AodWidgets.host(this).deleteAppWidgetId(widgetId) }
        }
        finish()
    }

    companion object {
        const val EXTRA_PROVIDER = "provider"
        private const val REQUEST_BIND = 1
        private const val REQUEST_CONFIGURE = 2
    }
}

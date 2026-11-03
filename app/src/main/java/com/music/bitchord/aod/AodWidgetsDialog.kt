package com.music.bitchord.aod

import android.appwidget.AppWidgetProviderInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.music.bitchord.R

/**
 * BitChord Audio: the widgets on the always-on music screen — the ones added
 * (tap ✕ to take one off) and every widget the installed apps offer, to add.
 */
@Composable
fun AodWidgetsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val added by AodSettings.widgets.collectAsState()
    val providers = remember { runCatching { AodWidgets.providers(context) }.getOrDefault(emptyList()) }
    val pm = context.packageManager

    @Composable
    fun ProviderLine(info: AppWidgetProviderInfo, trailing: @Composable () -> Unit, onClick: (() -> Unit)?) {
        val label = remember(info) { runCatching { info.loadLabel(pm) }.getOrNull() ?: info.provider.className }
        val app = remember(info) {
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString() }.getOrDefault("")
        }
        val icon = remember(info) {
            runCatching { info.loadIcon(context, 0)?.toBitmap(64, 64)?.asImageBitmap() }.getOrNull()
                ?: runCatching { pm.getApplicationIcon(info.provider.packageName).toBitmap(64, 64).asImageBitmap() }.getOrNull()
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(vertical = 8.dp),
        ) {
            if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (app.isNotBlank()) {
                    Text(app, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            trailing()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.aod_done)) } },
        title = { Text(stringResource(R.string.aod_widgets)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                if (added.isNotEmpty()) {
                    item { Text(stringResource(R.string.aod_widgets_added), style = MaterialTheme.typography.labelLarge) }
                    items(added, key = { "added$it" }) { id ->
                        val info = remember(id) { AodWidgets.info(context, id) }
                        if (info != null) {
                            ProviderLine(info, trailing = {
                                IconButton(onClick = { AodWidgets.remove(context, id) }) { Icon(Icons.Rounded.Close, null) }
                            }, onClick = null)
                        }
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                }
                item { Text(stringResource(R.string.aod_widgets_available), style = MaterialTheme.typography.labelLarge) }
                items(providers, key = { "p" + it.provider.flattenToString() }) { info ->
                    ProviderLine(info, trailing = {}, onClick = { AodWidgets.add(context, info.provider) })
                }
            }
        },
    )
}

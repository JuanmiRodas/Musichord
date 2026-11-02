package com.music.bitchord.ui.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.DownloadSkips

/** BitChord Audio: tick the sources downloads may come from; none ticked = any. */
@Composable
fun DownloadSourcesDialog(onDismiss: () -> Unit) {
    val chosen by AppSettings.downloadSources.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.download_only_from)) },
        text = {
            Column {
                Text(stringResource(R.string.download_only_from_message), style = MaterialTheme.typography.bodyMedium)
                DownloadSkips.Choice.entries.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            AppSettings.setDownloadSources(if (c.name in chosen) chosen - c.name else chosen + c.name)
                        }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = c.name in chosen, onCheckedChange = null)
                        Text(c.label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        dismissButton = {
            TextButton(onClick = { AppSettings.setDownloadSources(emptySet()) }) {
                Text(stringResource(R.string.download_only_from_any))
            }
        },
    )
}

/** BitChord Audio: the songs not downloaded because no allowed source had them, as a table. */
@Composable
fun DownloadSkipsDialog(onDismiss: () -> Unit) {
    val entries by DownloadSkips.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scroll = rememberScrollState()
    @Composable
    fun Cell(text: String, w: Int, bold: Boolean = false) = Text(
        text,
        modifier = Modifier.width(w.dp).padding(horizontal = 4.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.download_skips_title)) },
        text = {
            if (entries.isEmpty()) {
                Text(stringResource(R.string.download_skips_none))
            } else {
                Column(Modifier.horizontalScroll(scroll)) {
                    Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Cell("#", 36, true); Cell("Title", 170, true); Cell("Artist", 130, true)
                        Cell("Album", 150, true); Cell("Reason", 150, true)
                    }
                    LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        items(entries.withIndex().toList()) { (i, e) ->
                            Row(
                                Modifier.background(
                                    if (i % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                ),
                            ) {
                                Cell("${i + 1}", 36); Cell(e.title, 170); Cell(e.artist, 130)
                                Cell(e.album.orEmpty(), 150); Cell(e.reason, 150)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (entries.isNotEmpty()) {
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_SUBJECT, "BitChord — skipped downloads")
                            putExtra(Intent.EXTRA_TEXT, DownloadSkips.csv())
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    }) { Text(stringResource(R.string.download_skips_share)) }
                    TextButton(onClick = { DownloadSkips.clear() }) { Text(stringResource(R.string.download_skips_clear)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
            }
        },
    )
}

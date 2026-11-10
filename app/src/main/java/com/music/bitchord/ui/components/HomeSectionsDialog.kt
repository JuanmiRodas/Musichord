package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.HomeSections

/** BitChord Audio: Home's shelves — tick to show, arrows to order. */
@Composable
fun HomeSectionsDialog(onDismiss: () -> Unit) {
    val order by HomeSections.order.collectAsState()
    val seen by HomeSections.seen.collectAsState()
    val hidden by HomeSections.hidden.collectAsState()
    val mixCard by HomeSections.mixCard.collectAsState()
    val titles = remember(order, seen) { HomeSections.listed() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_sections_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = mixCard, onCheckedChange = HomeSections::setMixCard)
                        Text(stringResource(R.string.home_make_mix), modifier = Modifier.weight(1f))
                    }
                }
                if (titles.isEmpty()) {
                    item { Text(stringResource(R.string.home_sections_empty), style = MaterialTheme.typography.bodySmall) }
                }
                itemsIndexed(titles, key = { _, t -> t }) { i, t ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = t !in hidden, onCheckedChange = { HomeSections.setHidden(t, !it) })
                        Text(t, modifier = Modifier.weight(1f).padding(end = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(enabled = i > 0, onClick = { HomeSections.move(t, -1) }) { Text("↑") }
                        TextButton(enabled = i < titles.lastIndex, onClick = { HomeSections.move(t, 1) }) { Text("↓") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.aod_done)) } },
        dismissButton = { TextButton(onClick = { HomeSections.reset() }) { Text(stringResource(R.string.home_sections_reset)) } },
    )
}

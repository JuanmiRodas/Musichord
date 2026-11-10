package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.NoRecommend

/** BitChord Audio: the songs never to be suggested, each with an undo. */
@Composable
fun NoRecommendDialog(onDismiss: () -> Unit) {
    val entries by NoRecommend.entries.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.no_recommend_title)) },
        text = {
            if (entries.isEmpty()) {
                Text(stringResource(R.string.no_recommend_empty))
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(entries.sortedByDescending { it.addedAt }, key = { it.videoId }) { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    e.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { NoRecommend.remove(e.videoId) }) {
                                Text(stringResource(R.string.no_recommend_allow))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.aod_done)) } },
        dismissButton = if (entries.isNotEmpty()) {
            { TextButton(onClick = { NoRecommend.clear() }) { Text(stringResource(R.string.download_skips_clear)) } }
        } else null,
    )
}

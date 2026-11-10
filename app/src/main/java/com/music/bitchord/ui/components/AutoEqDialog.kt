package com.music.bitchord.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.autoeq.AutoEq
import com.music.bitchord.data.autoeq.AutoEqEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** BitChord Audio: pick the headphones on the current output (AutoEQ). */
@Composable
fun AutoEqDialog(onDismiss: () -> Unit) {
    val output by AutoEq.output.collectAsState()
    val active by AutoEq.active.collectAsState()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<AutoEqEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(300)
        busy = true
        error = null
        results = runCatching { AutoEq.search(query) }.onFailure { error = it.message }.getOrDefault(emptyList())
        busy = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.autoeq_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.autoeq_for_output, output.name) + "\n" +
                        (active?.let { stringResource(R.string.autoeq_active, it.entry.name, it.settings.filters.size) }
                            ?: stringResource(R.string.autoeq_none)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.autoeq_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) CircularProgressIndicator(Modifier.padding(8.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(results, key = { it.encodedPath }) { e ->
                        Column(
                            Modifier.fillMaxWidth().clickable {
                                scope.launch {
                                    busy = true
                                    AutoEq.choose(e).onFailure { error = it.message }.onSuccess { onDismiss() }
                                    busy = false
                                }
                            }.padding(vertical = 8.dp),
                        ) {
                            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(e.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.aod_done)) } },
        dismissButton = if (active != null) {
            { TextButton(onClick = { AutoEq.clearForOutput() }) { Text(stringResource(R.string.autoeq_off)) } }
        } else null,
    )
}

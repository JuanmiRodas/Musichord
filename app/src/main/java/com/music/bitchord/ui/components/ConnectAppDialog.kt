package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.playback.cast.CastConnect

/** Where our speaker app is published (GitHub Pages of the app's repository). */
const val CONNECT_RECEIVER_URL = "https://juanmirodas.github.io/Musichord/receiver/"

/**
 * BitChord Audio: our own speaker app's id (from the Google Cast developer
 * console) — with it set, casting goes to BitChord Connect, which holds the
 * whole queue on the speaker. Empty: Google's default player, as before.
 */
@Composable
fun ConnectAppDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var id by remember { mutableStateOf(CastConnect.appId(context).orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connect_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.connect_help), style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text(CONNECT_RECEIVER_URL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it.trim().uppercase().take(12) },
                    label = { Text(stringResource(R.string.connect_app_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.connect_restart), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                CastConnect.setAppId(context, id.ifBlank { null })
                android.widget.Toast.makeText(context, context.getString(R.string.connect_saved), android.widget.Toast.LENGTH_LONG).show()
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

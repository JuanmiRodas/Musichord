package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.LogUpload

/**
 * BitChord Audio: where the running log goes on GitHub — a private repository
 * and a token that can only write to it. The token is kept on the phone only;
 * left empty, the one already saved stays.
 */
@Composable
fun LogUploadDialog(onDismiss: () -> Unit) {
    val current by LogUpload.repo.collectAsState()
    val status by LogUpload.status.collectAsState()
    var repo by remember { mutableStateOf(current) }
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.log_upload_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.log_upload_help), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = repo,
                    onValueChange = { repo = it },
                    label = { Text(stringResource(R.string.log_upload_repo)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.log_upload_token)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (current.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { LogUpload.sendNow() }) { Text(stringResource(R.string.log_upload_send_now)) }
                        TextButton(onClick = { LogUpload.clear(); onDismiss() }) { Text(stringResource(R.string.log_upload_stop)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = repo.contains('/'),
                onClick = {
                    LogUpload.configure(repo, token.ifBlank { null })
                    onDismiss()
                },
            ) { Text(stringResource(R.string.aod_done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

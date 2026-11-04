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
    // BitChord Audio: your logs repository by default — only the token to give.
    var repo by remember { mutableStateOf(current.ifBlank { "JuanmiRodas/bitchord-logs" }) }
    var token by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current
    // A token copied on GitHub is picked up from the clipboard when coming back.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && token.isBlank()) {
                val clip = runCatching {
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                }.getOrNull()
                if (clip != null && (clip.startsWith("github_pat_") || clip.startsWith("ghp_"))) token = clip
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.log_upload_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.log_upload_help), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    val url = "https://github.com/settings/personal-access-tokens/new" +
                        "?name=BitChord+logs&description=BitChord+Audio+sends+its+log+here" +
                        "&target_name=" + repo.substringBefore('/').ifBlank { "JuanmiRodas" } +
                        "&expires_in=366&contents=write"
                    runCatching {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text(stringResource(R.string.log_upload_create_token)) }
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

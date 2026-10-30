package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.LibraryFolders
import com.music.bitchord.data.webdav.PcloudPreset

/**
 * BitChord Audio: the pCloud / WebDAV folders the app reads — named, each on
 * or off, for music, lyrics or both. Removing one only stops the app reading
 * it; nothing on the server is touched.
 */
@Composable
fun LibraryFoldersDialog(onDismiss: () -> Unit) {
    val folders by LibraryFolders.folders.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    if (adding) {
        AddFolderDialog(onDone = { adding = false })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_folders)) },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.library_folders_message), style = MaterialTheme.typography.bodyMedium)
                if (folders.isEmpty()) {
                    Text(stringResource(R.string.library_folders_none), style = MaterialTheme.typography.bodyMedium)
                }
                folders.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                kindLabel(f.kind) + " · " + displayPath(f.url),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = f.enabled, onCheckedChange = { LibraryFolders.setEnabled(f.id, it) })
                        IconButton(onClick = { LibraryFolders.remove(f.id) }) {
                            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.library_folder_remove))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        dismissButton = {
            TextButton(
                onClick = { adding = true },
                enabled = AppSettings.webdavUrl.value.isNotBlank(),
            ) { Text(stringResource(R.string.library_folder_add)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFolderDialog(onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(LibraryFolders.Kind.MUSIC) }
    val pcloud = PcloudPreset.isPcloud(AppSettings.webdavUrl.value)
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.library_folder_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.library_folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = {
                        Text(stringResource(if (pcloud) R.string.library_folder_path_pcloud else R.string.library_folder_url))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryFolders.Kind.entries.forEach { k ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(kindLabel(k)) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = path.isNotBlank(),
                onClick = {
                    val url = if (pcloud && !path.trim().startsWith("http")) {
                        val host = PcloudPreset.hostOf(AppSettings.webdavUrl.value) ?: PcloudPreset.EU_HOST
                        PcloudPreset.urlFor(host, path)
                    } else {
                        path.trim()
                    }
                    LibraryFolders.add(name, url, kind)
                    onDone()
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun kindLabel(kind: LibraryFolders.Kind): String = stringResource(
    when (kind) {
        LibraryFolders.Kind.MUSIC -> R.string.library_folder_music
        LibraryFolders.Kind.LYRICS -> R.string.library_folder_lyrics
        LibraryFolders.Kind.BOTH -> R.string.library_folder_both
    },
)

private fun displayPath(url: String): String =
    if (PcloudPreset.isPcloud(url)) "/" + PcloudPreset.folderOf(url) else url

package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.ServiceKeys
import com.music.bitchord.data.settings.AppSettings
import kotlinx.coroutines.launch

/**
 * BitChord Audio: Settings › Services (keys) — your own free keys for Last.fm, Discogs and
 * fanart.tv. Typed here, hidden like a password, kept encrypted on this phone; each can be
 * checked against its service and removed. Without a key the service is skipped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceKeysSheet(onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val lastKey by AppSettings.lastfmApiKey.collectAsStateWithLifecycle()
    val lastSecret by AppSettings.lastfmSecret.collectAsStateWithLifecycle()
    val discogs by AppSettings.discogsToken.collectAsStateWithLifecycle()
    val fanart by AppSettings.fanartKey.collectAsStateWithLifecycle()
    val logo by AppSettings.artistLogoInPlayer.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.service_keys_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.service_keys_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            ServiceBlock(
                name = "Last.fm",
                use = stringResource(R.string.service_keys_lastfm_use),
                fields = listOf(
                    Field(stringResource(R.string.service_keys_api_key), lastKey) { AppSettings.setLastfmApiKey(it.trim()); ServiceKeys.applyLastFm() },
                    Field(stringResource(R.string.service_keys_secret), lastSecret) { AppSettings.setLastfmSecret(it.trim()); ServiceKeys.applyLastFm() },
                ),
                test = { ServiceKeys.test(ServiceKeys.Service.LASTFM, lastKey, lastSecret) },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ServiceBlock(
                name = "Discogs",
                use = stringResource(R.string.service_keys_discogs_use),
                fields = listOf(Field(stringResource(R.string.service_keys_token), discogs) { AppSettings.setDiscogsToken(it) }),
                test = { ServiceKeys.test(ServiceKeys.Service.DISCOGS, discogs) },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ServiceBlock(
                name = "fanart.tv",
                use = stringResource(R.string.service_keys_fanart_use),
                fields = listOf(Field(stringResource(R.string.service_keys_api_key), fanart) { AppSettings.setFanartKey(it) }),
                test = { ServiceKeys.test(ServiceKeys.Service.FANART, fanart) },
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.service_keys_logo_title), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.service_keys_logo_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = logo, onCheckedChange = AppSettings::setArtistLogoInPlayer)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private class Field(val label: String, val value: String, val set: (String) -> Unit)

@Composable
private fun ServiceBlock(name: String, use: String, fields: List<Field>, test: suspend () -> ServiceKeys.Check) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<ServiceKeys.Check?>(null) }
    var checking by remember { mutableStateOf(false) }
    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(use, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    fields.forEach { f -> SecretField(f) { result = null } }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            enabled = !checking && fields.first().value.isNotBlank(),
            onClick = {
                checking = true
                scope.launch { result = test(); checking = false }
            },
        ) { Text(stringResource(if (checking) R.string.service_keys_checking else R.string.service_keys_check)) }
        TextButton(
            enabled = fields.any { it.value.isNotBlank() },
            onClick = { fields.forEach { it.set("") }; result = null },
        ) { Text(stringResource(R.string.service_keys_remove)) }
    }
    result?.let { r ->
        val ok = r == ServiceKeys.Check.Works
        Text(
            text = when (r) {
                ServiceKeys.Check.Works -> "✓ " + stringResource(R.string.service_keys_works)
                ServiceKeys.Check.Rejected -> "✗ " + stringResource(R.string.service_keys_rejected)
                ServiceKeys.Check.SecretRejected -> "✗ " + stringResource(R.string.service_keys_secret_rejected)
                ServiceKeys.Check.Missing -> "✗ " + stringResource(R.string.service_keys_missing)
                is ServiceKeys.Check.Offline -> stringResource(R.string.service_keys_offline)
                is ServiceKeys.Check.Odd -> "✗ " + stringResource(R.string.service_keys_odd, r.code)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) Color(0xFF34C759) else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SecretField(f: Field, onEdit: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = f.value,
        onValueChange = { f.set(it.trim()); onEdit() },
        label = { Text(f.label) },
        singleLine = true,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = null)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

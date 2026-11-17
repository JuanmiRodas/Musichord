package com.music.bitchord.ui.components

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.PcloudPreset

/**
 * BitChord Audio: signing in to pCloud through its own page (OAuth 2.0, token
 * flow), for accounts whose email and password pCloud won't take from an app —
 * the page asks for the emailed code like the website does.
 *
 * pCloud only signs in apps registered in its "My Apps": the listener
 * registers one, with [REDIRECT] as its redirect URI, and pastes its Client
 * ID here. The page runs in a WebView and the redirect is caught before it
 * loads, so the address never has to exist.
 */
object PcloudSignIn {
    const val REDIRECT = "https://localhost/bitchord-pcloud"

    fun authorizeUrl(clientId: String): String =
        "https://my.pcloud.com/oauth2/authorize?client_id=" + Uri.encode(clientId) +
            "&response_type=token&redirect_uri=" + Uri.encode(REDIRECT)

    /** The token and API host from the redirect's fragment, or null. */
    fun parse(url: String): Pair<String, String>? {
        if (!url.startsWith(REDIRECT)) return null
        val fragment = Uri.parse(url).encodedFragment ?: return null
        val params = fragment.split('&').mapNotNull {
            val k = it.substringBefore('=', "")
            if (k.isEmpty()) null else k to Uri.decode(it.substringAfter('=', ""))
        }.toMap()
        val token = params["access_token"]?.takeIf { it.isNotBlank() } ?: return null
        val host = params["hostname"]?.takeIf { it.isNotBlank() }
            ?: if (params["locationid"] == "2") "eapi.pcloud.com" else "api.pcloud.com"
        return token to host
    }

    /** Signed in: the token kept, and the library pointed at pCloud's WebDAV address for that region. */
    fun complete(token: String, apiHost: String) {
        AppSettings.setPcloudOAuth(token, apiHost)
        if (!PcloudPreset.isPcloud(AppSettings.webdavUrl.value)) {
            val host = if (apiHost.startsWith("e")) PcloudPreset.EU_HOST else PcloudPreset.US_HOST
            AppSettings.setWebDavUrl("https://$host/")
        }
    }
}

/** The settings row and its dialog. */
@Composable
fun PcloudSignInRow(row: @Composable (title: String, subtitle: String, onClick: () -> Unit) -> Unit) {
    val signedIn by AppSettings.pcloudSignedIn.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    row(
        stringResource(if (signedIn) R.string.pcloud_oauth_signed_in else R.string.pcloud_oauth_title),
        stringResource(R.string.pcloud_oauth_subtitle),
    ) { open = true }
    if (open) PcloudSignInDialog(signedIn = signedIn, onDismiss = { open = false })
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PcloudSignInDialog(signedIn: Boolean, onDismiss: () -> Unit) {
    val savedId by AppSettings.pcloudClientId.collectAsStateWithLifecycle()
    var clientId by remember { mutableStateOf(savedId) }
    var browsing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = !browsing)) {
        if (browsing) {
            AndroidView(
                modifier = Modifier.fillMaxSize().background(Color.White),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                handle(request.url.toString())

                            @Deprecated("Deprecated in Java")
                            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = handle(url)

                            private fun handle(url: String): Boolean {
                                if (!url.startsWith(PcloudSignIn.REDIRECT)) return false
                                val result = PcloudSignIn.parse(url)
                                if (result != null) {
                                    PcloudSignIn.complete(result.first, result.second)
                                    onDismiss()
                                } else {
                                    error = Uri.parse(url).let { it.getQueryParameter("error") ?: it.encodedFragment } ?: "?"
                                    browsing = false
                                }
                                return true
                            }
                        }
                        loadUrl(PcloudSignIn.authorizeUrl(clientId))
                    }
                },
            )
            return@Dialog
        }
        Column(
            Modifier
                .background(Color(0xFF1C1C1E), RoundedCornerShape(16.dp))
                .padding(20.dp),
        ) {
            Text(
                stringResource(R.string.pcloud_oauth_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
            Text(
                stringResource(R.string.pcloud_oauth_help, PcloudSignIn.REDIRECT),
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = Color.White.copy(alpha = 0.75f),
            )
            Box(
                Modifier
                    .padding(top = 14.dp)
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                if (clientId.isEmpty()) Text("Client ID", color = Color.White.copy(alpha = 0.4f), fontSize = 15.sp)
                BasicTextField(
                    value = clientId,
                    onValueChange = { clientId = it.trim() },
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(Color.White),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            error?.let {
                Text(stringResource(R.string.pcloud_oauth_failed, it), modifier = Modifier.padding(top = 8.dp), color = Color(0xFFFF6B63), fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                if (signedIn) {
                    Text(
                        stringResource(R.string.pcloud_oauth_sign_out),
                        modifier = Modifier.clickable { AppSettings.setPcloudOAuth(null, null); onDismiss() }.padding(8.dp),
                        color = Color(0xFFFF6B63),
                        fontSize = 15.sp,
                    )
                }
                Box(Modifier.weight(1f))
                Text(
                    stringResource(R.string.cancel),
                    modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                )
                Text(
                    stringResource(R.string.pcloud_oauth_open),
                    modifier = Modifier
                        .clickable(enabled = clientId.isNotBlank()) {
                            AppSettings.setPcloudClientId(clientId)
                            error = null
                            browsing = true
                        }
                        .padding(8.dp),
                    color = if (clientId.isNotBlank()) Color(0xFF64A8FF) else Color.White.copy(alpha = 0.3f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

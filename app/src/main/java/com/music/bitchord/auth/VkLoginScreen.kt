package com.music.bitchord.auth

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.music.bitchord.data.video.VkVideo
import com.music.bitchord.data.web.QuietWebChromeClient

/**
 * BitChord Audio: VK's own sign-in page (m.vk.com), in the app — you sign in yourself,
 * two-step code included; the app never sees the password. Done once VK's session cookie
 * is set and the page has moved on from the login.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VkLoginScreen(onConnected: () -> Unit, modifier: Modifier = Modifier) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = browserUserAgent(settings.userAgentString)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = QuietWebChromeClient()
                var sent = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        canGoBack = view.canGoBack()
                        CookieManager.getInstance().flush()
                        val onLogin = url?.contains("login") == true || url?.contains("id.vk.com") == true
                        if (sent || onLogin || !VkVideo.signedIn()) return
                        sent = true
                        onConnected()
                    }
                }
                webView = this
                loadUrl("https://m.vk.com/login")
            }
        },
        onRelease = { it.destroy() },
    )
    BackHandler(enabled = canGoBack) { webView?.goBack() }
}

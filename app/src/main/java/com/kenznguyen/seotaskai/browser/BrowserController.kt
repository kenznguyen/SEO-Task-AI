package com.kenznguyen.seotaskai.browser

import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URI

class BrowserController {
    fun configure(webView: WebView) {
        webView.webViewClient = WebViewClient()
        webView.settings.javaScriptEnabled = false
        webView.settings.domStorageEnabled = false
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
    }

    fun normalizeUrl(input: String): String {
        val value = input.trim()
        require(value.isNotEmpty()) { "URL is required" }
        val candidate = if ("://" in value) value else "https://$value"
        val uri = URI(candidate)
        val scheme = uri.scheme?.lowercase()
        require(scheme == "http" || scheme == "https") {
            "Only HTTP and HTTPS URLs are supported"
        }

        if (scheme == "http" && uri.host.equals("software.inilahkoran.id", ignoreCase = true)) {
            val port = if (uri.port == 80) -1 else uri.port
            return URI(
                "https",
                uri.userInfo,
                uri.host,
                port,
                uri.path,
                uri.query,
                uri.fragment
            ).toASCIIString()
        }

        return candidate
    }
}

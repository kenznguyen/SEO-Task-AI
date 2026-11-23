package com.kenznguyen.seotaskai.browser

import android.webkit.WebView
import android.webkit.WebViewClient

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
        val scheme = candidate.substringBefore("://").lowercase()
        require(scheme == "http" || scheme == "https") { "Only HTTP and HTTPS URLs are supported" }
        return candidate
    }
}

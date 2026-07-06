package com.denuoweb.hnsdane.ui

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

internal object BrowserWebViewHardening {
    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun applyTo(webView: WebView, allowJavaScript: Boolean) {
        webView.settings.apply {
            javaScriptEnabled = allowJavaScript
            domStorageEnabled = true
            loadsImagesAutomatically = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)

            if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                WebSettingsCompat.setSafeBrowsingEnabled(this, true)
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
                WebSettingsCompat.setWebAuthenticationSupport(
                    this,
                    WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER,
                )
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SPECULATIVE_LOADING)) {
                WebSettingsCompat.setSpeculativeLoadingStatus(
                    this,
                    WebSettingsCompat.SPECULATIVE_LOADING_DISABLED,
                )
            }
        }

        webView.removeJavascriptInterface("accessibility")
        webView.removeJavascriptInterface("accessibilityTraversal")
        webView.removeJavascriptInterface("searchBoxJavaBridge_")
    }
}

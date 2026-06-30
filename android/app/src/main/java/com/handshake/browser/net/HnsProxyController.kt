package com.handshake.browser.net

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.Locale

class HnsProxyController(
    private val context: Context,
) {
    fun applyLoopbackProxy(port: Int, hnsHost: String?, onComplete: (Boolean) -> Unit) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            onComplete(false)
            return
        }

        val reverseBypassSupported = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS)
        if (!canApplyLoopbackProxy(hnsHost, reverseBypassSupported)) {
            onComplete(false)
            return
        }

        val proxyConfig = loopbackProxyConfig(port, requireNotNull(hnsHost))

        ProxyController.getInstance().setProxyOverride(
            proxyConfig,
            ContextCompat.getMainExecutor(context),
        ) {
            onComplete(true)
        }
    }

    fun clear(onComplete: () -> Unit) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            onComplete()
            return
        }

        ProxyController.getInstance().clearProxyOverride(
            ContextCompat.getMainExecutor(context),
        ) {
            onComplete()
        }
    }
}

internal fun loopbackProxyConfig(
    port: Int,
    hnsHost: String,
): ProxyConfig {
    val builder = ProxyConfig.Builder()
        .addProxyRule("http://127.0.0.1:$port")

    val normalizedHost = hnsHost
        .trim()
        .trimEnd('.')
        .lowercase(Locale.US)
    require(normalizedHost.isNotBlank()) { "hnsHost must not be blank" }

    builder
        .addBypassRule(normalizedHost)
        .addBypassRule("*.$normalizedHost")
        .setReverseBypassEnabled(true)

    return builder.build()
}

internal fun canApplyLoopbackProxy(
    hnsHost: String?,
    reverseBypassSupported: Boolean,
): Boolean {
    val normalizedHost = hnsHost
        .orEmpty()
        .trim()
        .trimEnd('.')
    return reverseBypassSupported && normalizedHost.isNotBlank()
}

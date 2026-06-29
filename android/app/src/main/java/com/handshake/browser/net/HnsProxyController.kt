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

        val proxyConfig = loopbackProxyConfig(
            port = port,
            hnsHost = hnsHost,
            reverseBypassSupported = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE_REVERSE_BYPASS),
        )

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
    hnsHost: String?,
    reverseBypassSupported: Boolean,
): ProxyConfig {
    val builder = ProxyConfig.Builder()
        .addProxyRule("http://127.0.0.1:$port")

    val normalizedHost = hnsHost
        ?.trim()
        ?.trimEnd('.')
        ?.lowercase(Locale.US)
        ?.takeIf { it.isNotBlank() }

    if (normalizedHost != null && reverseBypassSupported) {
        builder
            .addBypassRule(normalizedHost)
            .addBypassRule("*.$normalizedHost")
            .setReverseBypassEnabled(true)
    }

    return builder.build()
}

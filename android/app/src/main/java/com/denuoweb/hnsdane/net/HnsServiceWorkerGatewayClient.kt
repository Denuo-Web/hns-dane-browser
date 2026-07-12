package com.denuoweb.hnsdane.net

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.ServiceWorkerClientCompat

class HnsServiceWorkerGatewayClient(
    private val interceptor: HnsWebViewGatewayInterceptor,
    private val enabled: () -> Boolean = { true },
) : ServiceWorkerClientCompat() {
    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
        if (enabled()) interceptor.interceptServiceWorker(request) else null
}

object DisabledServiceWorkerClient : ServiceWorkerClientCompat() {
    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = null
}

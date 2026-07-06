package com.denuoweb.hnsdane.net

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.ServiceWorkerClientCompat

class HnsServiceWorkerGatewayClient(
    private val interceptor: HnsWebViewGatewayInterceptor,
) : ServiceWorkerClientCompat() {
    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
        interceptor.intercept(request)
}

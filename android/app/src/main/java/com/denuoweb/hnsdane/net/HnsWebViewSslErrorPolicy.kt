package com.denuoweb.hnsdane.net

import android.net.http.SslError
import com.denuoweb.hnsdane.core.HnsHostPolicy
import java.net.URI
import java.util.Locale

object HnsWebViewSslErrorPolicy {
    fun canProceed(error: SslError): Boolean {
        val url = error.url ?: return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = eligiblePinnedLocalCertificateHost(uri) ?: return false
        val certificate = error.certificate?.getX509Certificate() ?: return false
        return HnsLocalCertificateRegistry.hasPinnedCertificate(host, certificate)
    }

    internal fun isEligiblePinnedLocalCertificateUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return eligiblePinnedLocalCertificateHost(uri) != null
    }

    private fun eligiblePinnedLocalCertificateHost(uri: URI): String? {
        if (uri.scheme?.lowercase(Locale.US) !in setOf("https", "wss")) {
            return null
        }
        val host = uri.httpAuthorityHost() ?: return null
        return host.takeIf { HnsHostPolicy.requiresHnsResolution(it) }
    }
}

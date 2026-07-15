package com.denuoweb.hnsdane.net

import com.denuoweb.hnsdane.core.HnsPageResolverPolicy
import com.denuoweb.hnsdane.core.HnsPageSecurityPath
import com.denuoweb.hnsdane.core.HnsPageTlsPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class KotlinFallbackBrowserProxyTest {
    @After
    fun clearCertificateRegistry() {
        HnsLocalCertificateRegistry.clear()
    }

    @Test
    fun startsImmutableScopedAuthenticatedFallbackAndSurfacesTypedStatus() {
        val serverFactory = FakeServerFactory()
        val proxy = requireNotNull(
            KotlinFallbackBrowserProxy.start(config(" Alpha. "), serverFactory),
        )

        assertEquals("alpha", proxy.scopeHost)
        assertEquals(41111, proxy.endpoint.port)
        assertEquals(0L, proxy.endpoint.nativeHandle)
        assertTrue(proxy.endpoint.instanceId.generation > 0L)
        assertEquals("alpha", serverFactory.config?.scopeHost)
        assertSame(serverFactory.authorization, proxy.endpoint.authorization)

        serverFactory.statusCallback?.invoke(
            "ALPHA.",
            200,
            HnsPageTlsPolicy.Dane,
            HnsPageResolverPolicy.HnsDohCompatibility,
            HnsPageSecurityPath.DaneAuthoritativeDoh,
            "sensitive trace",
        )
        val status = requireNotNull(proxy.takeMainFrameStatus("alpha"))
        assertEquals(200, status.statusCode)
        assertEquals(HnsPageTlsPolicy.Dane, status.tlsPolicy)
        assertEquals(HnsPageResolverPolicy.HnsDohCompatibility, status.resolverPolicy)
        assertEquals(HnsPageSecurityPath.DaneAuthoritativeDoh, status.securityPath)
        assertEquals("sensitive trace", status.resolutionTraceJson)
        assertFalse(status.toString().contains("sensitive trace"))
        assertNull(proxy.takeMainFrameStatus("alpha"))
    }

    @Test
    fun discardAndAggregateBoundsPreventStaleOrUnboundedFallbackStatus() {
        val serverFactory = FakeServerFactory()
        val proxy = requireNotNull(KotlinFallbackBrowserProxy.start(config("alpha"), serverFactory))
        val callback = requireNotNull(serverFactory.statusCallback)

        callback("alpha", 200, null, null, null, "old")
        proxy.discardMainFrameStatus("alpha")
        assertNull(proxy.takeMainFrameStatus("alpha"))

        repeat(10) { index ->
            callback("host$index.alpha", 200, null, null, null, "x".repeat(12 * 1024))
        }
        assertNull(proxy.takeMainFrameStatus("host0.alpha"))
        assertNull(proxy.takeMainFrameStatus("host1.alpha"))
        assertTrue((2 until 10).count { index -> proxy.takeMainFrameStatus("host$index.alpha") != null } <= 8)

        callback("alpha", 200, null, null, null, "x".repeat(64 * 1024 + 1))
        assertNull(requireNotNull(proxy.takeMainFrameStatus("alpha")).resolutionTraceJson)
    }

    @Test
    fun stopRevokesCertificateAndStatusBeforeIdempotentDestroy() {
        val serverFactory = FakeServerFactory()
        val proxy = requireNotNull(KotlinFallbackBrowserProxy.start(config("alpha"), serverFactory))
        val certificateDer = byteArrayOf(1, 2, 3, 4)
        HnsLocalCertificateRegistry.trustHostCertificate(
            "alpha",
            MessageDigest.getInstance("SHA-256").digest(certificateDer),
        )
        serverFactory.statusCallback?.invoke("alpha", 200, null, null, null, null)

        assertTrue(proxy.matchesLocalCertificate("alpha", certificateDer))
        proxy.requestStop()
        assertFalse(proxy.matchesLocalCertificate("alpha", certificateDer))
        assertNull(proxy.takeMainFrameStatus("alpha"))
        assertEquals(1, serverFactory.server.closeCalls)

        proxy.requestStop()
        proxy.joinAndDestroy()
        proxy.joinAndDestroy()
        assertEquals(1, serverFactory.server.closeCalls)
    }

    @Test
    fun failedOrMalformedServerStartIsClosedAndNotPublished() {
        val failed = FakeServerFactory().apply { server.startResult = false }
        assertNull(KotlinFallbackBrowserProxy.start(config("alpha"), failed))
        assertEquals(1, failed.server.closeCalls)

        val missingPort = FakeServerFactory().apply { server.port = null }
        assertNull(KotlinFallbackBrowserProxy.start(config("alpha"), missingPort))
        assertEquals(1, missingPort.server.closeCalls)
    }

    @Test
    fun defaultFactoryUsesFallbackOnlyWhenRustCannotStart() {
        val rustProxy = FakeProxy("alpha")
        val fallbackProxy = FakeProxy("alpha")
        var rustStarts = 0
        var fallbackStarts = 0
        val factory = DefaultLocalBrowserProxyFactory(
            rustFactory = LocalBrowserProxyFactory {
                rustStarts += 1
                rustProxy
            },
            fallbackFactory = LocalBrowserProxyFactory {
                fallbackStarts += 1
                fallbackProxy
            },
        )

        assertSame(rustProxy, factory.start(config("alpha")))
        assertEquals(1, rustStarts)
        assertEquals(0, fallbackStarts)

        val fallbackFactory = DefaultLocalBrowserProxyFactory(
            rustFactory = LocalBrowserProxyFactory { null },
            fallbackFactory = LocalBrowserProxyFactory {
                fallbackStarts += 1
                fallbackProxy
            },
        )
        assertSame(fallbackProxy, fallbackFactory.start(config("alpha")))
        assertEquals(1, fallbackStarts)
    }

    private fun config(host: String): RustBrowserProxyConfig =
        RustBrowserProxyConfig(
            dataDir = "/tmp/browser",
            network = "regtest",
            scopeHost = host,
            strictHnsMode = true,
            dohResolverUrl = "https://resolver.test/dns-query",
            statelessDaneCertificates = true,
        )

    private class FakeServerFactory : KotlinFallbackProxyServerFactory {
        val server = FakeServer()
        var config: RustBrowserProxyConfig? = null
        var authorization: LoopbackProxyAuthorization? = null
        var statusCallback: ((String, Int, HnsPageTlsPolicy?, HnsPageResolverPolicy?, HnsPageSecurityPath?, String?) -> Unit)? = null

        override fun create(
            config: RustBrowserProxyConfig,
            authorization: LoopbackProxyAuthorization,
            onStatus: (String, Int, HnsPageTlsPolicy?, HnsPageResolverPolicy?, HnsPageSecurityPath?, String?) -> Unit,
        ): KotlinFallbackProxyServer {
            this.config = config
            this.authorization = authorization
            statusCallback = onStatus
            return server
        }
    }

    private class FakeServer : KotlinFallbackProxyServer {
        var startResult = true
        var port: Int? = 41111
        var closeCalls = 0

        override fun start(): Boolean = startResult

        override fun boundPort(): Int? = port

        override fun close() {
            closeCalls += 1
        }
    }

    private class FakeProxy(
        override val scopeHost: String,
    ) : LocalBrowserProxy {
        override val endpoint = LocalBrowserProxyEndpoint(
            nativeHandle = 1,
            port = 41111,
            instanceId = LocalProxyInstanceId("session", 1),
            authorization = LoopbackProxyAuthorization.createForTest("realm", "user", "password"),
        )

        override fun matchesLocalCertificate(host: String, certificateDer: ByteArray): Boolean = false

        override fun takeMainFrameStatus(host: String): LocalBrowserProxyStatus? = null

        override fun discardMainFrameStatus(host: String) = Unit

        override fun requestStop() = Unit

        override fun joinAndDestroy() = Unit
    }
}

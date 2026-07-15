package com.denuoweb.hnsdane.net

import org.junit.Assert.assertEquals
import org.junit.Test

class HnsServiceWorkerGatewayClientTest {
    @Test
    fun admittedProxyRouteUsesSharedRuntimeGatewayBecauseWorkerCannotAcceptLocalTlsChallenge() {
        assertEquals(
            ServiceWorkerRouteAction.SharedRuntimeGateway,
            serviceWorkerRouteAction(BrowserProxyRoute.Proxy),
        )
    }

    @Test
    fun compatibilityAndUnclassifiedRoutesKeepExistingGatewayBehavior() {
        assertEquals(
            ServiceWorkerRouteAction.SharedRuntimeGateway,
            serviceWorkerRouteAction(BrowserProxyRoute.CompatibilityInterceptor),
        )
        assertEquals(
            ServiceWorkerRouteAction.SharedRuntimeGateway,
            serviceWorkerRouteAction(null),
        )
    }

    @Test
    fun transitionRouteFailsClosed() {
        assertEquals(
            ServiceWorkerRouteAction.Block,
            serviceWorkerRouteAction(BrowserProxyRoute.Block),
        )
    }

    @Test
    fun trailingDotHnsHostStillUsesTheCoordinatorScopeDecision() {
        val routedHosts = mutableListOf<String>()
        assertEquals(
            BrowserProxyRoute.Block,
            serviceWorkerProxyRoute("https", "otherhns.") { host ->
                routedHosts += host
                BrowserProxyRoute.Block
            },
        )
        assertEquals(listOf("otherhns."), routedHosts)

        assertEquals(
            BrowserProxyRoute.Proxy,
            serviceWorkerProxyRoute("https", "allowedhns.") { BrowserProxyRoute.Proxy },
        )
    }

    @Test
    fun missingRouteForNativeGatewayTargetFailsClosedInForegroundAndBackground() {
        assertEquals(
            ServiceWorkerRouteAction.Block,
            serviceWorkerRouteAction(
                route = null,
                enabled = true,
                scheme = "https",
                host = "otherhns.",
            ),
        )
        assertEquals(
            ServiceWorkerRouteAction.Block,
            serviceWorkerRouteAction(
                route = null,
                enabled = false,
                scheme = "https",
                host = "otherhns.",
            ),
        )
    }

    @Test
    fun icannDaneTestHostUsesTheCompatibilityGatewayRoute() {
        assertEquals(
            BrowserProxyRoute.CompatibilityInterceptor,
            serviceWorkerProxyRoute("https", "dane-test.denuoweb.com") { BrowserProxyRoute.Block },
        )
    }

    @Test
    fun suspendedSecuritySensitiveRoutesFailClosedWhileIcannRemainsDirect() {
        assertEquals(
            ServiceWorkerRouteAction.Block,
            serviceWorkerRouteAction(BrowserProxyRoute.Proxy, enabled = false),
        )
        assertEquals(
            ServiceWorkerRouteAction.Block,
            serviceWorkerRouteAction(BrowserProxyRoute.CompatibilityInterceptor, enabled = false),
        )
        assertEquals(
            ServiceWorkerRouteAction.Direct,
            serviceWorkerRouteAction(null, enabled = false),
        )
    }

    @Test
    fun destroyedClientBlocksHnsWithoutCapturingAnActivityAndLeavesIcannDirect() {
        assertEquals(
            ServiceWorkerRouteAction.Block,
            disabledServiceWorkerRouteAction("https", "shakeshift"),
        )
        assertEquals(
            ServiceWorkerRouteAction.Direct,
            disabledServiceWorkerRouteAction("https", "example.com"),
        )
        assertEquals(
            ServiceWorkerRouteAction.Direct,
            disabledServiceWorkerRouteAction("data", null),
        )
    }

    @Test
    fun newerActivityOwnsTheProcessClientAndOlderActivityCannotOverwriteOrDisableIt() {
        val gate = ServiceWorkerClientOwnershipGate()
        val first = gate.newOwner()
        val events = mutableListOf<String>()
        assertEquals(true, gate.install(first) { events += "first" })

        val second = gate.newOwner()
        assertEquals(true, gate.install(second) { events += "second" })
        assertEquals(false, gate.install(first) { events += "stale-install" })
        assertEquals(false, gate.disable(first) { events += "stale-disable" })
        assertEquals(true, gate.disable(second) { events += "disabled" })
        assertEquals(false, gate.install(first) { events += "stale-reclaim" })

        assertEquals(listOf("first", "second", "disabled"), events)
    }

    @Test
    fun constructingFutureOwnerDoesNotInvalidateTheInstalledClient() {
        val gate = ServiceWorkerClientOwnershipGate()
        val current = gate.newOwner()
        val events = mutableListOf<String>()
        gate.install(current) { events += "current" }

        gate.newOwner()
        assertEquals(true, gate.disable(current) { events += "disabled" })
        assertEquals(listOf("current", "disabled"), events)
    }
}

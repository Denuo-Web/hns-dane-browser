package com.handshake.browser.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HnsProxyControllerTest {
    @Test
    fun loopbackProxyConfigScopesProxyToCurrentHnsHostWhenReverseBypassIsSupported() {
        val config = loopbackProxyConfig(
            port = 12345,
            hnsHost = "Nathan.Woodburn.",
            reverseBypassSupported = true,
        )

        assertTrue(config.isReverseBypassEnabled)
        assertEquals(listOf("nathan.woodburn", "*.nathan.woodburn"), config.bypassRules)
        assertEquals("http://127.0.0.1:12345", config.proxyRules.single().url)
    }

    @Test
    fun loopbackProxyConfigFallsBackToPlainProxyWithoutReverseBypassSupport() {
        val config = loopbackProxyConfig(
            port = 12345,
            hnsHost = "nathan.woodburn",
            reverseBypassSupported = false,
        )

        assertFalse(config.isReverseBypassEnabled)
        assertTrue(config.bypassRules.isEmpty())
        assertEquals("http://127.0.0.1:12345", config.proxyRules.single().url)
    }
}

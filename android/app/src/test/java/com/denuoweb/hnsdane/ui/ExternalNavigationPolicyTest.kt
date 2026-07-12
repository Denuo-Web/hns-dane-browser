package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalNavigationPolicyTest {
    @Test
    fun externalSchemesRequireUserGesture() {
        assertTrue(canLaunchExternalNavigation("mailto", hasUserGesture = true))
        assertTrue(canLaunchExternalNavigation("TEL", hasUserGesture = true))
        assertFalse(canLaunchExternalNavigation("sms", hasUserGesture = false))
        assertFalse(canLaunchExternalNavigation("https", hasUserGesture = true))
        assertFalse(canLaunchExternalNavigation(null, hasUserGesture = true))
    }

    @Test
    fun directLoopbackRequestsAreBlockedFromWebContent() {
        for (host in listOf(
            "localhost",
            "app.localhost",
            "127.0.0.1",
            "127.9.8.7",
            "127.1",
            "2130706433",
            "0x7f000001",
            "0177.0.0.1",
            "0.0.0.0",
            "::",
            "::1",
            "0:0:0:0:0:0:0:1",
            "::127.0.0.1",
            "::ffff:127.0.0.1",
            "0:0:0:0:0:ffff:7f00:1",
        )) {
            assertTrue(isBlockedLoopbackHost(host))
        }
        assertFalse(isBlockedLoopbackHost("128.0.0.1"))
        assertFalse(isBlockedLoopbackHost("1.2.3.4"))
        assertFalse(isBlockedLoopbackHost("2001:db8::1"))
        assertFalse(isBlockedLoopbackHost("welcome"))
    }
}

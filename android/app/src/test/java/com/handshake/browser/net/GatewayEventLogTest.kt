package com.handshake.browser.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayEventLogTest {
    @Before
    fun clearGatewayEvents() {
        GatewayEventLog.clear()
    }

    @Test
    fun eventLogIsBoundedToRecentEvents() {
        repeat(30) { index ->
            GatewayEventLog.record("native_response", "host$index", 500 + index, "failure")
        }

        val events = GatewayEventLog.snapshot()
        assertEquals(25, events.size)
        assertEquals("host5", events.first().host)
        assertEquals("host29", events.last().host)
    }

    @Test
    fun eventLogSanitizesHostStageAndReason() {
        GatewayEventLog.record(
            "Native Response: /private?q=secret",
            "Welcome./private?q=secret",
            503,
            "HNS Resolution Unavailable /private?q=secret",
        )

        val event = GatewayEventLog.snapshot().single()
        assertEquals("native_response", event.stage)
        assertEquals("welcome", event.host)
        assertEquals("HNS_Resolution_Unavailable", event.reason)

        val text = GatewayEventLog.snapshotText()
        assertFalse(text.contains("/"))
        assertFalse(text.contains("?"))
        assertFalse(text.contains("="))
        assertFalse(text.contains("secret"))
        assertTrue(text.contains("welcome"))
    }
}

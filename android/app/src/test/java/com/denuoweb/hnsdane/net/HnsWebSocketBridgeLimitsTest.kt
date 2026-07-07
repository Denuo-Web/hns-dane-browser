package com.denuoweb.hnsdane.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HnsWebSocketBridgeLimitsTest {
    @Test
    fun tunnelOutputFailsOnceAndStopsBufferingOversizedHandshake() {
        val handshakes = mutableListOf<ByteArray>()
        val frames = mutableListOf<ByteArray>()
        val failures = mutableListOf<String>()
        val output = HnsWebSocketTunnelOutput(
            onHandshake = { handshakes += it },
            onFrameBytes = { bytes, offset, length -> frames += bytes.copyOfRange(offset, offset + length) },
            onFailure = { failures += it },
        )
        val chunk = ByteArray(HnsWebSocketLimits.MAX_HANDSHAKE_BYTES + 1) { 'A'.code.toByte() }

        output.write(chunk)
        output.write("HTTP/1.1 101 Switching Protocols\r\n\r\nignored".toByteArray())

        assertTrue(handshakes.isEmpty())
        assertTrue(frames.isEmpty())
        assertEquals(listOf("HNS WebSocket handshake response is too large"), failures)
    }

    @Test
    fun tunnelOutputForwardsFrameBytesAfterHandshake() {
        val handshakes = mutableListOf<String>()
        val frames = mutableListOf<String>()
        val output = HnsWebSocketTunnelOutput(
            onHandshake = { handshakes += it.toString(Charsets.ISO_8859_1) },
            onFrameBytes = { bytes, offset, length ->
                frames += bytes.copyOfRange(offset, offset + length).toString(Charsets.ISO_8859_1)
            },
            onFailure = { error -> error(error) },
        )

        output.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\nframe".toByteArray())

        assertEquals(listOf("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n"), handshakes)
        assertEquals(listOf("frame"), frames)
    }
}

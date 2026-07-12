package com.denuoweb.hnsdane.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.io.ByteArrayOutputStream

class HnsWebSocketHandshakePolicyTest {
    @Test
    fun validatesRfcHandshakeAndOfferedProtocol() {
        val response = response(
            "Upgrade: websocket",
            "Connection: keep-alive, Upgrade",
            "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
            "Sec-WebSocket-Protocol: chat",
        )

        assertEquals(
            "chat",
            HnsWebSocketHandshakePolicy.validate(
                response,
                requestKey = "dGhlIHNhbXBsZSBub25jZQ==",
                offeredProtocols = listOf("chat"),
            ),
        )
    }

    @Test
    fun rejectsWrongAcceptUnofferedProtocolAndExtensions() {
        assertThrows(IllegalArgumentException::class.java) {
            HnsWebSocketHandshakePolicy.validate(
                response(
                    "Upgrade: websocket",
                    "Connection: Upgrade",
                    "Sec-WebSocket-Accept: wrong",
                ),
                "dGhlIHNhbXBsZSBub25jZQ==",
                emptyList(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HnsWebSocketHandshakePolicy.validate(
                response(
                    "Upgrade: websocket",
                    "Connection: Upgrade",
                    "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
                    "Sec-WebSocket-Protocol: surprise",
                ),
                "dGhlIHNhbXBsZSBub25jZQ==",
                listOf("chat"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HnsWebSocketHandshakePolicy.validate(
                response(
                    "Upgrade: websocket",
                    "Connection: Upgrade",
                    "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
                    "Sec-WebSocket-Extensions: permessage-deflate",
                ),
                "dGhlIHNhbXBsZSBub25jZQ==",
                emptyList(),
            )
        }
    }

    @Test
    fun rejectsMalformedResponseHeaders() {
        assertThrows(IllegalArgumentException::class.java) {
            HnsWebSocketHandshakePolicy.validate(
                response(
                    "Upgrade: websocket",
                    "Connection: Upgrade",
                    "MalformedHeader",
                    "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
                ),
                "dGhlIHNhbXBsZSBub25jZQ==",
                emptyList(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            HnsWebSocketHandshakePolicy.validate(
                "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n".toByteArray(),
                "dGhlIHNhbXBsZSBub25jZQ==",
                emptyList(),
            )
        }
    }

    @Test
    fun closeEchoIsMaskedAndCarriesSamePayload() {
        val payload = HnsWebSocketFrameCodec.closePayload(1000, "done")
        val output = ByteArrayOutputStream()

        echoWebSocketClose(output, payload)

        val frame = output.toByteArray()
        assertEquals(0x88.toByte(), frame[0])
        assertEquals(0x80, frame[1].toInt() and 0x80)
        val length = frame[1].toInt() and 0x7f
        assertEquals(payload.size, length)
        val mask = frame.copyOfRange(2, 6)
        val decoded = frame.copyOfRange(6, 6 + length).mapIndexed { index, byte ->
            (byte.toInt() xor mask[index % 4].toInt()).toByte()
        }.toByteArray()
        org.junit.Assert.assertArrayEquals(payload, decoded)
    }

    private fun response(vararg headers: String): ByteArray =
        ("HTTP/1.1 101 Switching Protocols\r\n" + headers.joinToString("\r\n") + "\r\n\r\n")
            .toByteArray(StandardCharsets.ISO_8859_1)
}

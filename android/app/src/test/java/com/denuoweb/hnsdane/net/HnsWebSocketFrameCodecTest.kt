package com.denuoweb.hnsdane.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class HnsWebSocketFrameCodecTest {
    @Test
    fun parsesUnmaskedServerTextFrameAcrossChunks() {
        val frames = mutableListOf<HnsWebSocketFrame>()
        val parser = HnsWebSocketFrameParser { frames += it }

        parser.append(byteArrayOf(0x81.toByte()))
        parser.append(byteArrayOf(0x02, 'o'.code.toByte(), 'k'.code.toByte()))

        assertEquals(
            listOf(HnsWebSocketFrame(true, HnsWebSocketFrameCodec.OPCODE_TEXT, "ok".toByteArray())),
            frames,
        )
    }

    @Test
    fun encodesMaskedClientFrame() {
        val encoded = HnsWebSocketFrameCodec.encodeClientFrame(
            HnsWebSocketFrameCodec.OPCODE_TEXT,
            "hi".toByteArray(),
            mask = byteArrayOf(0, 0, 0, 0),
        )

        assertEquals(0x81.toByte(), encoded[0])
        assertEquals(0x82.toByte(), encoded[1])
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), encoded.copyOfRange(2, 6))
        assertEquals("hi", encoded.copyOfRange(6, encoded.size).toString(Charsets.UTF_8))
    }

    @Test
    fun parserUnmasksClientStyleFramesForTests() {
        val frames = mutableListOf<HnsWebSocketFrame>()
        val parser = HnsWebSocketFrameParser { frames += it }
        val encoded = HnsWebSocketFrameCodec.encodeClientFrame(
            HnsWebSocketFrameCodec.OPCODE_TEXT,
            "masked".toByteArray(),
            mask = byteArrayOf(1, 2, 3, 4),
        )

        parser.append(encoded)

        assertEquals(1, frames.size)
        assertEquals("masked", frames.single().payload.toString(Charsets.UTF_8))
    }

    @Test
    fun closePayloadRoundTripsCodeAndReason() {
        val payload = HnsWebSocketFrameCodec.closePayload(1000, "done")

        assertEquals(1000, HnsWebSocketFrameCodec.closeCode(payload))
        assertEquals("done", HnsWebSocketFrameCodec.closeReason(payload))
        assertTrue(payload.size > 2)
    }

    @Test
    fun parserRejectsOversizedFramePayload() {
        val parser = HnsWebSocketFrameParser(maxPayloadBytes = 4) {}
        val frame = serverFrame(
            fin = true,
            opcode = HnsWebSocketFrameCodec.OPCODE_TEXT,
            payload = "large".toByteArray(),
        )

        assertThrows(IOException::class.java) {
            parser.append(frame)
        }
    }

    @Test
    fun parserRejectsFragmentedControlFrame() {
        val parser = HnsWebSocketFrameParser {}
        val frame = serverFrame(
            fin = false,
            opcode = HnsWebSocketFrameCodec.OPCODE_PING,
            payload = "x".toByteArray(),
        )

        assertThrows(IOException::class.java) {
            parser.append(frame)
        }
    }

    @Test
    fun messageAssemblerRejectsOversizedFragmentedMessage() {
        val messages = mutableListOf<Pair<Int, ByteArray>>()
        val failures = mutableListOf<String>()
        val assembler = HnsWebSocketMessageAssembler(
            maxMessageBytes = 4,
            onMessage = { opcode, payload -> messages += opcode to payload },
            onFailure = { failures += it },
        )

        assembler.accept(HnsWebSocketFrame(false, HnsWebSocketFrameCodec.OPCODE_TEXT, "he".toByteArray()))
        assembler.accept(HnsWebSocketFrame(true, HnsWebSocketFrameCodec.OPCODE_CONTINUATION, "llo".toByteArray()))

        assertTrue(messages.isEmpty())
        assertEquals(listOf("websocket message is too large"), failures)
    }

    @Test
    fun messageAssemblerEmitsBoundedFragmentedMessage() {
        val messages = mutableListOf<Pair<Int, ByteArray>>()
        val failures = mutableListOf<String>()
        val assembler = HnsWebSocketMessageAssembler(
            maxMessageBytes = 8,
            onMessage = { opcode, payload -> messages += opcode to payload },
            onFailure = { failures += it },
        )

        assembler.accept(HnsWebSocketFrame(false, HnsWebSocketFrameCodec.OPCODE_TEXT, "he".toByteArray()))
        assembler.accept(HnsWebSocketFrame(true, HnsWebSocketFrameCodec.OPCODE_CONTINUATION, "llo".toByteArray()))

        assertTrue(failures.isEmpty())
        assertEquals(HnsWebSocketFrameCodec.OPCODE_TEXT, messages.single().first)
        assertEquals("hello", messages.single().second.toString(Charsets.UTF_8))
    }

    private fun serverFrame(fin: Boolean, opcode: Int, payload: ByteArray): ByteArray {
        require(payload.size < 126) { "test helper only supports short payloads" }
        return byteArrayOf(
            ((if (fin) 0x80 else 0) or opcode).toByte(),
            payload.size.toByte(),
        ) + payload
    }
}

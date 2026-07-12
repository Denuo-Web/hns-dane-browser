package com.denuoweb.hnsdane.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom

internal object HnsWebSocketLimits {
    const val MAX_ACTIVE_SESSIONS = 32
    const val MAX_PAGE_ID_CHARS = 128
    const val MAX_PROTOCOLS = 16
    const val MAX_PROTOCOL_CHARS = 128
    const val MAX_WEB_MESSAGE_CHARS = 3 * 1024 * 1024
    const val MAX_FRAME_PAYLOAD_BYTES = 2 * 1024 * 1024
    const val MAX_MESSAGE_BYTES = 2 * 1024 * 1024
    const val MAX_OUTBOUND_QUEUE_BYTES = 4 * 1024 * 1024
    const val MAX_OUTBOUND_QUEUE_FRAMES = 16
    const val MAX_HANDSHAKE_BYTES = 64 * 1024
    const val MAX_OUTBOUND_BINARY_BASE64_CHARS = ((MAX_MESSAGE_BYTES + 2) / 3) * 4
}

internal data class HnsWebSocketFrame(
    val fin: Boolean,
    val opcode: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HnsWebSocketFrame) return false
        return fin == other.fin && opcode == other.opcode && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = fin.hashCode()
        result = 31 * result + opcode
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

internal class HnsWebSocketFrameParser(
    private val maxPayloadBytes: Int = HnsWebSocketLimits.MAX_FRAME_PAYLOAD_BYTES,
    private val onFrame: (HnsWebSocketFrame) -> Unit,
) {
    private var buffer = ByteArray(0)

    @Throws(IOException::class)
    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (length <= 0) {
            return
        }
        if (offset < 0 || length < 0 || offset + length > bytes.size) {
            throw IOException("invalid frame buffer range")
        }
        val appended = ByteArray(buffer.size + length)
        buffer.copyInto(appended, 0)
        bytes.copyInto(appended, buffer.size, offset, offset + length)
        buffer = appended
        parseAvailableFrames()
    }

    @Throws(IOException::class)
    private fun parseAvailableFrames() {
        var cursor = 0
        while (true) {
            val available = buffer.size - cursor
            if (available < MIN_HEADER_BYTES) {
                break
            }

            val first = buffer[cursor].toInt() and 0xff
            val second = buffer[cursor + 1].toInt() and 0xff
            val fin = (first and FIN_BIT) != 0
            if ((first and RSV_MASK) != 0) {
                throw IOException("websocket frame uses unsupported reserved bits")
            }
            val opcode = first and OPCODE_MASK
            if (opcode !in VALID_OPCODES) {
                throw IOException("websocket frame opcode is unsupported")
            }
            val masked = (second and MASK_BIT) != 0
            if (masked) {
                throw IOException("websocket server frame must not be masked")
            }
            val lengthMarker = second and LENGTH_MASK
            var payloadLength = lengthMarker.toLong()
            var headerLength = MIN_HEADER_BYTES

            if (payloadLength == LENGTH_16_MARKER.toLong()) {
                if (available < headerLength + SHORT_LENGTH_BYTES) {
                    break
                }
                payloadLength = unsignedShort(cursor + headerLength).toLong()
                if (payloadLength < LENGTH_16_MARKER) {
                    throw IOException("websocket frame length is not minimally encoded")
                }
                headerLength += SHORT_LENGTH_BYTES
            } else if (payloadLength == LENGTH_64_MARKER.toLong()) {
                if (available < headerLength + LONG_LENGTH_BYTES) {
                    break
                }
                payloadLength = unsignedLong(cursor + headerLength)
                if (payloadLength <= 0xffffL) {
                    throw IOException("websocket frame length is not minimally encoded")
                }
                headerLength += LONG_LENGTH_BYTES
            }

            if (payloadLength < 0 || payloadLength > maxPayloadBytes) {
                throw IOException("websocket frame is too large")
            }
            if (opcode in CONTROL_OPCODES && (!fin || payloadLength > MAX_CONTROL_PAYLOAD_BYTES)) {
                throw IOException("websocket control frame is malformed")
            }
            val frameLength = headerLength + payloadLength.toInt()
            if (available < frameLength) {
                break
            }

            val payloadStart = cursor + headerLength
            val payload = buffer.copyOfRange(payloadStart, payloadStart + payloadLength.toInt())
            onFrame(HnsWebSocketFrame(fin, opcode, payload))
            cursor += frameLength
        }

        if (cursor > 0) {
            buffer = buffer.copyOfRange(cursor, buffer.size)
        }
    }

    private fun unsignedShort(offset: Int): Int =
        ((buffer[offset].toInt() and 0xff) shl 8) or (buffer[offset + 1].toInt() and 0xff)

    @Throws(IOException::class)
    private fun unsignedLong(offset: Int): Long {
        var value = 0L
        for (index in 0 until LONG_LENGTH_BYTES) {
            value = (value shl 8) or (buffer[offset + index].toLong() and 0xff)
        }
        if (value < 0) {
            throw IOException("websocket frame is too large")
        }
        return value
    }

    private companion object {
        const val MIN_HEADER_BYTES = 2
        const val SHORT_LENGTH_BYTES = 2
        const val LONG_LENGTH_BYTES = 8
        const val FIN_BIT = 0x80
        const val RSV_MASK = 0x70
        const val MASK_BIT = 0x80
        const val OPCODE_MASK = 0x0f
        const val LENGTH_MASK = 0x7f
        const val LENGTH_16_MARKER = 126
        const val LENGTH_64_MARKER = 127
        const val MAX_CONTROL_PAYLOAD_BYTES = 125
        val VALID_OPCODES = setOf(0x0, 0x1, 0x2, 0x8, 0x9, 0xA)
        val CONTROL_OPCODES = setOf(0x8, 0x9, 0xA)
    }
}

internal class HnsWebSocketMessageAssembler(
    private val maxMessageBytes: Int = HnsWebSocketLimits.MAX_MESSAGE_BYTES,
    private val onMessage: (Int, ByteArray) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private var continuationOpcode: Int? = null
    private var continuationPayload: ByteArrayOutputStream? = null
    private var continuationBytes: Int = 0

    fun accept(frame: HnsWebSocketFrame) {
        when (frame.opcode) {
            HnsWebSocketFrameCodec.OPCODE_TEXT,
            HnsWebSocketFrameCodec.OPCODE_BINARY,
            -> handleDataFrame(frame)
            HnsWebSocketFrameCodec.OPCODE_CONTINUATION -> handleContinuation(frame)
            else -> fail("websocket data frame opcode is unsupported")
        }
    }

    private fun handleDataFrame(frame: HnsWebSocketFrame) {
        if (continuationOpcode != null) {
            fail("websocket data frame arrived during a fragmented message")
            return
        }
        if (frame.payload.size > maxMessageBytes) {
            fail("websocket message is too large")
            return
        }
        if (!frame.fin) {
            if (continuationOpcode != null) {
                fail("websocket continuation is already active")
                return
            }
            continuationOpcode = frame.opcode
            continuationBytes = frame.payload.size
            continuationPayload = ByteArrayOutputStream(frame.payload.size).apply { write(frame.payload) }
            return
        }
        emitMessage(frame.opcode, frame.payload)
    }

    private fun handleContinuation(frame: HnsWebSocketFrame) {
        val opcode = continuationOpcode
        val payload = continuationPayload
        if (opcode == null || payload == null) {
            fail("websocket continuation frame has no active message")
            return
        }
        val nextSize = continuationBytes.toLong() + frame.payload.size.toLong()
        if (nextSize > maxMessageBytes) {
            fail("websocket message is too large")
            return
        }
        payload.write(frame.payload)
        continuationBytes = nextSize.toInt()
        if (frame.fin) {
            val message = payload.toByteArray()
            resetContinuation()
            emitMessage(opcode, message)
        }
    }

    private fun emitMessage(opcode: Int, payload: ByteArray) {
        if (opcode == HnsWebSocketFrameCodec.OPCODE_TEXT && !HnsWebSocketFrameCodec.isValidUtf8(payload)) {
            fail("websocket text message is not valid UTF-8")
            return
        }
        onMessage(opcode, payload)
    }

    private fun fail(reason: String) {
        resetContinuation()
        onFailure(reason)
    }

    private fun resetContinuation() {
        continuationOpcode = null
        continuationPayload = null
        continuationBytes = 0
    }
}

internal object HnsWebSocketFrameCodec {
    const val OPCODE_CONTINUATION = 0x0
    const val OPCODE_TEXT = 0x1
    const val OPCODE_BINARY = 0x2
    const val OPCODE_CLOSE = 0x8
    const val OPCODE_PING = 0x9
    const val OPCODE_PONG = 0xA

    private val secureRandom = SecureRandom()

    fun encodeClientFrame(opcode: Int, payload: ByteArray): ByteArray {
        val mask = ByteArray(MASK_BYTES)
        secureRandom.nextBytes(mask)
        return encodeClientFrame(opcode, payload, mask)
    }

    internal fun encodeClientFrame(opcode: Int, payload: ByteArray, mask: ByteArray): ByteArray {
        require(mask.size == MASK_BYTES) { "mask must be 4 bytes" }
        val output = ByteArrayOutputStream()
        output.write(FIN_BIT or (opcode and OPCODE_MASK))
        writeLength(output, payload.size, masked = true)
        output.write(mask)
        payload.forEachIndexed { index, byte ->
            output.write(byte.toInt() xor mask[index % MASK_BYTES].toInt())
        }
        return output.toByteArray()
    }

    fun closePayload(code: Int, reason: String): ByteArray {
        require(isValidCloseCode(code)) { "invalid websocket close code" }
        val reasonBytes = encodeUtf8(reason)
        require(reasonBytes.size <= MAX_CLOSE_REASON_BYTES) { "websocket close reason is too long" }
        val payload = ByteArray(STATUS_CODE_BYTES + reasonBytes.size)
        payload[0] = ((code ushr 8) and 0xff).toByte()
        payload[1] = (code and 0xff).toByte()
        reasonBytes.copyInto(payload, STATUS_CODE_BYTES)
        return payload
    }

    fun textPayload(value: String): ByteArray = encodeUtf8(value)

    fun closeCode(payload: ByteArray): Int? {
        if (payload.size < STATUS_CODE_BYTES) {
            return null
        }
        return ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
    }

    fun closeReason(payload: ByteArray): String {
        if (payload.size <= STATUS_CODE_BYTES) {
            return ""
        }
        return decodeUtf8(payload.copyOfRange(STATUS_CODE_BYTES, payload.size))
    }

    fun validateClosePayload(payload: ByteArray) {
        require(payload.size != 1) { "websocket close payload is malformed" }
        if (payload.isEmpty()) {
            return
        }
        require(payload.size <= MAX_CONTROL_PAYLOAD_BYTES) { "websocket close payload is too large" }
        require(isValidCloseCode(requireNotNull(closeCode(payload)))) { "invalid websocket close code" }
        closeReason(payload)
    }

    fun isValidCloseCode(code: Int): Boolean =
        code in setOf(1000, 1001, 1002, 1003, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1014) ||
            code in 3000..4999

    fun isValidUtf8(bytes: ByteArray): Boolean =
        runCatching { decodeUtf8(bytes) }.isSuccess

    private fun encodeUtf8(value: String): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        return encoded.toByteArray()
    }

    private fun decodeUtf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun ByteBuffer.toByteArray(): ByteArray =
        ByteArray(remaining()).also { get(it) }

    private fun writeLength(output: ByteArrayOutputStream, length: Int, masked: Boolean) {
        val maskBit = if (masked) MASK_BIT else 0
        when {
            length < LENGTH_16_MARKER -> output.write(maskBit or length)
            length <= 0xffff -> {
                output.write(maskBit or LENGTH_16_MARKER)
                output.write((length ushr 8) and 0xff)
                output.write(length and 0xff)
            }
            else -> {
                output.write(maskBit or LENGTH_64_MARKER)
                for (shift in 56 downTo 0 step 8) {
                    output.write((length.toLong() ushr shift).toInt() and 0xff)
                }
            }
        }
    }

    private const val FIN_BIT = 0x80
    private const val MASK_BIT = 0x80
    private const val OPCODE_MASK = 0x0f
    private const val MASK_BYTES = 4
    private const val STATUS_CODE_BYTES = 2
    private const val MAX_CONTROL_PAYLOAD_BYTES = 125
    private const val MAX_CLOSE_REASON_BYTES = MAX_CONTROL_PAYLOAD_BYTES - STATUS_CODE_BYTES
    private const val LENGTH_16_MARKER = 126
    private const val LENGTH_64_MARKER = 127
}

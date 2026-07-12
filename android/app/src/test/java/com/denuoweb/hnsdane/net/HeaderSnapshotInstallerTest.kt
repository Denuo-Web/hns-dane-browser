package com.denuoweb.hnsdane.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class HeaderSnapshotInstallerTest {
    @Test
    fun exactSnapshotSizeIsCopied() {
        val bytes = ByteArray(32) { it.toByte() }
        val output = ByteArrayOutputStream()

        copySnapshotExactly(ByteArrayInputStream(bytes), output, bytes.size.toLong())

        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun truncatedAndOversizedSnapshotsFailClosed() {
        assertThrows(IOException::class.java) {
            copySnapshotExactly(ByteArrayInputStream(ByteArray(3)), ByteArrayOutputStream(), 4)
        }
        assertThrows(IOException::class.java) {
            copySnapshotExactly(ByteArrayInputStream(ByteArray(5)), ByteArrayOutputStream(), 4)
        }
    }
}

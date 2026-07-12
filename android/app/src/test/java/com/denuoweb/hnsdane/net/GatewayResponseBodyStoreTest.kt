package com.denuoweb.hnsdane.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class GatewayResponseBodyStoreTest {
    @Test
    fun pruningRemovesStaleAndOldestFilesWithinBudgets() {
        val directory = createTempDirectory("gateway-body-prune-test").toFile()
        val stale = directory.resolve("hns-gateway-stale.body").apply {
            writeBytes(ByteArray(2))
            setLastModified(1_000L)
        }
        val older = directory.resolve("hns-gateway-older.body").apply {
            writeBytes(ByteArray(4))
            setLastModified(9_000L)
        }
        val newest = directory.resolve("hns-gateway-newest.body").apply {
            writeBytes(ByteArray(4))
            setLastModified(10_000L)
        }

        GatewayResponseBodyStore.pruneDirectory(
            directory = directory,
            nowMillis = 10_000L,
            maxAgeMillis = 5_000L,
            maxFiles = 2,
            maxTotalBytes = 4,
        )

        assertFalse(stale.exists())
        assertFalse(older.exists())
        assertTrue(newest.exists())
        directory.deleteRecursively()
    }

    @Test
    fun pruningDoesNotDeleteCurrentProtectedFile() {
        val directory = createTempDirectory("gateway-body-protected-test").toFile()
        val current = directory.resolve("hns-gateway-current.body").apply {
            writeBytes(ByteArray(4))
            setLastModified(1_000L)
        }

        GatewayResponseBodyStore.pruneDirectory(
            directory = directory,
            nowMillis = 10_000L,
            maxAgeMillis = 1,
            maxFiles = 0,
            maxTotalBytes = 0,
            protectedFile = current,
        )

        assertTrue(current.exists())
        directory.deleteRecursively()
    }

    @Test
    fun activeLeaseSurvivesPruningAndIsDeletedOnStreamClose() {
        val dataDir = createTempDirectory("gateway-body-active-test").toFile()
        val body = requireNotNull(GatewayResponseBodyStore.create(dataDir.absolutePath)).apply {
            writeText("payload")
            setLastModified(1_000L)
        }

        GatewayResponseBodyStore.pruneDirectory(
            directory = body.parentFile,
            nowMillis = 10_000L,
            maxAgeMillis = 1,
            maxFiles = 0,
            maxTotalBytes = 0,
        )
        assertTrue(body.exists())

        GatewayResponseBodyStore.openReleasing(body).use { input ->
            assertTrue(input.read() >= 0)
        }
        assertFalse(body.exists())
        dataDir.deleteRecursively()
    }

    @Test
    fun externallyRemovedLeasesDoNotExhaustTheProcessBudget() {
        val dataDir = createTempDirectory("gateway-body-missing-lease-test").toFile()

        repeat(80) {
            val body = requireNotNull(GatewayResponseBodyStore.create(dataDir.absolutePath))
            assertTrue(body.delete())
        }

        val finalBody = GatewayResponseBodyStore.create(dataDir.absolutePath)
        assertNotNull(finalBody)
        requireNotNull(finalBody).let(GatewayResponseBodyStore::release)
        dataDir.deleteRecursively()
    }
}

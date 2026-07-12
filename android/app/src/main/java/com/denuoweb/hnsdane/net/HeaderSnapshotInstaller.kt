package com.denuoweb.hnsdane.net

import android.content.Context
import com.denuoweb.hnsdane.ui.HnsResolutionPreferences
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

class BundledHeaderSyncBridge(
    context: Context,
    private val delegate: HnsSyncBridge = NativeBridge,
) : HnsSyncBridge {
    private val appContext = context.applicationContext

    override fun syncOnce(dataDir: String): String =
        syncOnce(dataDir, HnsResolutionPreferences.handshakeNetworkId(appContext))

    override fun syncOnce(dataDir: String, network: String): String {
        HeaderSnapshotInstaller.installIfNeeded(appContext, dataDir, network)
        return delegate.syncOnce(dataDir, network)
    }
}

object HeaderSnapshotInstaller {
    const val SNAPSHOT_HEIGHT: Long = 300_000L

    private const val ASSET_NAME = "hns_headers_300000.snapshot.gzip"
    private const val TEMP_NAME = "hns-headers-300000.snapshot"
    private const val PREFS_NAME = "hns_header_snapshot"
    private const val KEY_DISABLE_BUNDLED_SNAPSHOT = "disable_bundled_snapshot"
    internal const val EXPECTED_SNAPSHOT_BYTES = 70_800_287L

    fun disableBundledSnapshot(context: Context, network: String = MAINNET) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(disabledKey(network), true)
            .apply()
    }

    fun installIfNeeded(
        context: Context,
        dataDir: String = context.filesDir.absolutePath,
        network: String = HnsResolutionPreferences.handshakeNetworkId(context),
    ): String? {
        if (network != MAINNET || !NativeBridge.isLoaded || bundledSnapshotDisabled(context, network)) {
            return null
        }
        val progress = HnsSyncProgress.fromJson(NativeBridge.syncStatus(dataDir, network))
        if ((progress.bestHeight ?: 0L) >= SNAPSHOT_HEIGHT) {
            return null
        }

        val tempDir = File(context.cacheDir, "hns-header-snapshot")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            return null
        }
        val tempFile = File(tempDir, TEMP_NAME)
        return runCatching {
            context.assets.open(ASSET_NAME).use { asset ->
                GZIPInputStream(asset).use { gzip ->
                    FileOutputStream(tempFile).use { output ->
                        copySnapshotExactly(gzip, output, EXPECTED_SNAPSHOT_BYTES)
                    }
                }
            }
            NativeBridge.installHeaderSnapshot(dataDir, tempFile.absolutePath, network)
        }.also {
            tempFile.delete()
        }.getOrNull()
    }

    private fun bundledSnapshotDisabled(context: Context, network: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(disabledKey(network), false) ||
            (network == MAINNET && prefs.getBoolean(KEY_DISABLE_BUNDLED_SNAPSHOT, false))
    }

    private fun disabledKey(network: String): String =
        "${KEY_DISABLE_BUNDLED_SNAPSHOT}_${network.lowercase()}"

    private const val MAINNET = "mainnet"
}

@Throws(IOException::class)
internal fun copySnapshotExactly(
    input: InputStream,
    output: OutputStream,
    expectedBytes: Long,
) {
    require(expectedBytes >= 0) { "expectedBytes must not be negative" }
    val buffer = ByteArray(16 * 1024)
    var copied = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) {
            break
        }
        if (read == 0) {
            continue
        }
        val nextSize = copied + read
        if (nextSize > expectedBytes) {
            throw IOException("bundled HNS header snapshot exceeds expected size")
        }
        output.write(buffer, 0, read)
        copied = nextSize
    }
    if (copied != expectedBytes) {
        throw IOException("bundled HNS header snapshot has unexpected size")
    }
}

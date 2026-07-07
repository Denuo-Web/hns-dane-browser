package com.denuoweb.hnsdane.net

import android.content.Context
import com.denuoweb.hnsdane.ui.HnsResolutionPreferences
import java.io.File
import java.io.FileOutputStream
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

        val tempDir = File(context.cacheDir, "hns-header-snapshot").apply {
            if (!exists()) {
                mkdirs()
            }
        }
        val tempFile = File(tempDir, TEMP_NAME)
        return runCatching {
            context.assets.open(ASSET_NAME).use { asset ->
                GZIPInputStream(asset).use { gzip ->
                    FileOutputStream(tempFile).use { output ->
                        gzip.copyTo(output)
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

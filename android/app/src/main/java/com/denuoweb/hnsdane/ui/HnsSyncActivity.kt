package com.denuoweb.hnsdane.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.denuoweb.hnsdane.R
import com.denuoweb.hnsdane.net.HeaderSnapshotInstaller
import com.denuoweb.hnsdane.net.NativeBridge
import com.denuoweb.hnsdane.net.ProcessHnsSyncSingleFlight
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class HnsSyncActivity : ComponentActivity() {
    private lateinit var syncStatus: TextView
    private lateinit var headerResyncStatus: TextView
    private var syncRunInProgress = false
    private var activePoller: AtomicBoolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        syncStatus = preferenceSummary(
            text = NativeBridge.syncStatus(
                filesDir.absolutePath,
                HnsResolutionPreferences.handshakeNetworkId(this),
            ),
            selectable = true,
            maxLines = Int.MAX_VALUE,
            bold = true,
        )
        headerResyncStatus = preferenceSummary(getString(R.string.settings_header_resync_ready))

        setSecondaryScreen(getString(R.string.screen_hns_sync)) {
            addView(screenSection(getString(R.string.row_hns_sync)) {
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_sync_status),
                    summaryView = syncStatus,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_run_sync_now),
                    summary = getString(R.string.row_run_sync_now_summary),
                    actionLabel = getString(R.string.action_run),
                ) {
                    runSyncNow()
                })
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_resync_headers_from_peers),
                    summaryView = headerResyncStatus,
                    actionLabel = getString(R.string.action_reset),
                    destructive = true,
                ) {
                    confirmHeaderPeerResync()
                })
            })
        }
    }

    override fun onStop() {
        activePoller?.set(false)
        activePoller = null
        super.onStop()
    }

    override fun onDestroy() {
        activePoller?.set(false)
        super.onDestroy()
    }

    private fun runSyncNow() {
        if (syncRunInProgress) {
            Toast.makeText(this, getString(R.string.sync_already_running), Toast.LENGTH_SHORT).show()
            return
        }

        syncRunInProgress = true
        syncStatus.text = getString(R.string.common_running)

        val running = AtomicBoolean(true)
        activePoller = running
        val network = HnsResolutionPreferences.handshakeNetworkId(this)
        val dataDir = filesDir.absolutePath
        thread(name = "hns-sync-status-poll") {
            while (running.get()) {
                Thread.sleep(SYNC_STATUS_POLL_MS)
                if (!running.get()) {
                    break
                }
                val status = NativeBridge.syncStatus(dataDir, network)
                runOnUiThread {
                    if (running.get()) {
                        syncStatus.text = getString(R.string.common_running_status, status)
                    }
                }
            }
        }
        thread(name = "hns-sync-now") {
            val status = ProcessHnsSyncSingleFlight.tryRun {
                NativeBridge.syncOnce(dataDir, network)
            }
            running.set(false)
            runOnUiThread {
                syncRunInProgress = false
                if (isDestroyed || activePoller !== running) {
                    return@runOnUiThread
                }
                if (status == null) {
                    syncStatus.text = NativeBridge.syncStatus(dataDir, network)
                    Toast.makeText(this, getString(R.string.sync_already_running), Toast.LENGTH_SHORT).show()
                } else {
                    syncStatus.text = status
                }
            }
        }
    }

    private fun confirmHeaderPeerResync() {
        val network = HnsResolutionPreferences.handshakeNetwork(this)
        val networkName = network.displayName(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_header_resync_title)
            .setMessage(getString(R.string.settings_header_resync_message, networkName))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_reset) { _, _ ->
                resetHeadersFromPeers()
            }
            .show()
    }

    private fun resetHeadersFromPeers() {
        if (syncRunInProgress) {
            Toast.makeText(this, getString(R.string.sync_already_running), Toast.LENGTH_SHORT).show()
            return
        }

        val network = HnsResolutionPreferences.handshakeNetwork(this)
        val networkName = network.displayName(this)
        val appContext = applicationContext
        val dataDir = filesDir.absolutePath
        syncRunInProgress = true
        headerResyncStatus.text = getString(R.string.common_running)
        thread(name = "hns-header-reset") {
            val result = ProcessHnsSyncSingleFlight.tryRun {
                if (network == HandshakeNetwork.Mainnet) {
                    HeaderSnapshotInstaller.disableBundledSnapshot(appContext, network.id)
                }
                NativeBridge.resetHeadersFromPeers(dataDir, network.id)
            }
            val status = result?.let { runCatching { JSONObject(it).optString("status") }.getOrDefault("") }
            runOnUiThread {
                syncRunInProgress = false
                if (isDestroyed) {
                    return@runOnUiThread
                }
                when (status) {
                    null -> Toast.makeText(this, getString(R.string.sync_already_running), Toast.LENGTH_SHORT).show()
                    "headers_reset" -> {
                        HnsSyncUiPreferences.setProgressVisible(this, true)
                        headerResyncStatus.text = getString(R.string.settings_header_resync_started_status, networkName)
                        Toast.makeText(this, getString(R.string.settings_header_resync_started), Toast.LENGTH_SHORT).show()
                        runSyncNow()
                    }
                    else -> {
                        headerResyncStatus.text = getString(R.string.settings_header_resync_failed_status)
                        Toast.makeText(this, getString(R.string.settings_header_resync_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private companion object {
        const val SYNC_STATUS_POLL_MS = 2_000L
    }
}

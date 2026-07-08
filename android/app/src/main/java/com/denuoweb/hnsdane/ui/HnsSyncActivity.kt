package com.denuoweb.hnsdane.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.denuoweb.hnsdane.R
import com.denuoweb.hnsdane.net.HnsSyncForegroundService
import com.denuoweb.hnsdane.net.NativeBridge
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class HnsSyncActivity : ComponentActivity() {
    private lateinit var syncStatus: TextView
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
            })
        }
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
        HnsSyncForegroundService.start(this)
        syncStatus.text = getString(R.string.common_running)

        val running = AtomicBoolean(true)
        activePoller = running
        val network = HnsResolutionPreferences.handshakeNetworkId(this)
        thread(name = "hns-sync-status-poll") {
            while (running.get()) {
                Thread.sleep(SYNC_STATUS_POLL_MS)
                val status = NativeBridge.syncStatus(filesDir.absolutePath, network)
                runOnUiThread {
                    if (running.get()) {
                        syncStatus.text = getString(R.string.common_running_status, status)
                    }
                }
            }
        }
        thread(name = "hns-sync-now") {
            val status = NativeBridge.syncOnce(filesDir.absolutePath, network)
            running.set(false)
            runOnUiThread {
                syncStatus.text = status
                syncRunInProgress = false
            }
        }
    }

    private companion object {
        const val SYNC_STATUS_POLL_MS = 2_000L
    }
}

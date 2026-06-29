package com.handshake.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.webkit.WebViewFeature
import com.handshake.browser.BuildConfig
import com.handshake.browser.net.GatewayEventLog
import com.handshake.browser.net.HnsSyncForegroundService
import com.handshake.browser.net.NativeBridge
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GatewayEventLog.configureAppStorage(filesDir)
        HnsSyncForegroundService.start(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 32, 32, 32)
            applySystemBarPadding()
        }

        var latestSyncStatus = NativeBridge.syncStatus(filesDir.absolutePath)
        val syncStatus = row("Sync status", latestSyncStatus)

        root.addView(row("Build", buildLabel()))
        root.addView(row("Rust core", NativeBridge.version()))
        root.addView(row("Rust diagnostics", NativeBridge.diagnostics()))
        root.addView(syncStatus)
        root.addView(Button(this).apply {
            text = "Run sync now"
            setOnClickListener {
                HnsSyncForegroundService.start(this@DiagnosticsActivity)
                isEnabled = false
                syncStatus.text = "Sync status: running"
                val running = AtomicBoolean(true)
                thread(name = "hns-sync-status-poll") {
                    while (running.get()) {
                        Thread.sleep(SYNC_STATUS_POLL_MS)
                        val status = NativeBridge.syncStatus(filesDir.absolutePath)
                        runOnUiThread {
                            if (running.get()) {
                                latestSyncStatus = status
                                syncStatus.text = "Sync status: running $status"
                            }
                        }
                    }
                }
                thread(name = "hns-sync-now") {
                    val status = NativeBridge.syncOnce(filesDir.absolutePath)
                    running.set(false)
                    runOnUiThread {
                        latestSyncStatus = status
                        syncStatus.text = "Sync status: $status"
                        isEnabled = true
                    }
                }
            }
        })
        root.addView(row("Proxy override", WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE).toString()))
        root.addView(row(
            "Third-party cookies blocked",
            BrowserCookiePreferences.blockThirdPartyCookies(this).toString(),
        ))
        root.addView(row("Recent gateway events", GatewayEventLog.snapshotText()))
        root.addView(Button(this).apply {
            text = "Copy diagnostic bundle"
            setOnClickListener {
                copyDiagnosticBundle(latestSyncStatus)
            }
        })
        root.addView(Button(this).apply {
            text = "Share diagnostic bundle"
            setOnClickListener {
                shareDiagnosticBundle(latestSyncStatus)
            }
        })

        setContentView(
            ScrollView(this).apply {
                addView(root)
            },
        )
    }

    private fun row(label: String, value: String): TextView =
        TextView(this).apply {
            text = "$label: $value"
            textSize = 16f
            setTextIsSelectable(true)
            setPadding(0, 10, 0, 10)
        }

    private fun copyDiagnosticBundle(syncStatus: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("HNS Browser diagnostic bundle", diagnosticBundle(syncStatus)))
        Toast.makeText(this, "Diagnostic bundle copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareDiagnosticBundle(syncStatus: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_SUBJECT, "HNS Browser diagnostic bundle")
            putExtra(Intent.EXTRA_TEXT, diagnosticBundle(syncStatus))
        }
        startActivity(Intent.createChooser(sendIntent, "Share diagnostic bundle"))
    }

    private fun diagnosticBundle(syncStatus: String): String =
        DiagnosticReport.markdown(
            buildLabel = buildLabel(),
            rustCore = NativeBridge.version(),
            rustDiagnostics = NativeBridge.diagnostics(),
            syncStatus = syncStatus,
            proxyOverrideSupported = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE),
            thirdPartyCookiesBlocked = BrowserCookiePreferences.blockThirdPartyCookies(this),
            gatewayEvents = GatewayEventLog.snapshotText(),
        )

    private fun buildLabel(): String {
        val channel = if (BuildConfig.DEBUG) "debug demo" else "release"
        return "$channel ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    }

    private companion object {
        const val SYNC_STATUS_POLL_MS = 2_000L
    }
}

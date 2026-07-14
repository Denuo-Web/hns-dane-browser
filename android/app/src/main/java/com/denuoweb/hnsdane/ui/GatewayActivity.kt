package com.denuoweb.hnsdane.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.denuoweb.hnsdane.R
import com.denuoweb.hnsdane.net.GatewayEvent
import com.denuoweb.hnsdane.net.GatewayEventLog

class GatewayActivity : ComponentActivity() {
    private lateinit var eventsSummary: TextView

    private val url: String
        get() = intent.getStringExtra(EXTRA_URL).orEmpty()

    private val traceJson: String
        get() = intent.getStringExtra(EXTRA_TRACE_JSON).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GatewayEventLog.configureAppStorage(filesDir)

        setSecondaryScreen(
            title = getString(R.string.screen_gateway),
            onSwipeLeft = {
                openAdjacentHnsDiagnostic(HnsDiagnosticTool.Gateway, forward = true, url, traceJson)
            },
            onSwipeRight = {
                openAdjacentHnsDiagnostic(HnsDiagnosticTool.Gateway, forward = false, url, traceJson)
            },
        ) {
            addView(hnsDiagnosticTabs(HnsDiagnosticTool.Gateway, url, traceJson))
            addView(screenSection(getString(R.string.section_recent_gateway_events)) {
                eventsSummary = fieldReportText(gatewaySummary())
                addView(eventsSummary)
            })
            addView(screenSection(getString(R.string.section_export)) {
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_copy_gateway_events),
                    summary = getString(R.string.row_copy_gateway_events_summary),
                    actionLabel = getString(R.string.action_copy),
                ) {
                    copyGatewayEvents()
                })
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_clear_gateway_events),
                    summary = getString(R.string.row_clear_gateway_events_summary),
                    actionLabel = getString(R.string.action_clear),
                    destructive = true,
                ) {
                    clearGatewayEvents()
                })
            })
        }
    }

    private fun gatewaySummary(): String {
        val events = GatewayEventLog.snapshot()
        if (events.isEmpty()) {
            return getString(R.string.gateway_empty)
        }
        return events.joinToString(separator = "\n\n") { event ->
            eventSummary(event)
        }
    }

    private fun eventSummary(event: GatewayEvent): String =
        buildString {
            appendLine(getString(R.string.gateway_event_timestamp, event.timestampMillis.toString()))
            appendLine(getString(R.string.gateway_event_stage, event.stage))
            appendLine(getString(R.string.gateway_event_host, event.host))
            appendLine(getString(R.string.gateway_event_status, event.status.toString()))
            append(getString(R.string.gateway_event_reason, event.reason))
        }

    private fun copyGatewayEvents() {
        val events = GatewayEventLog.snapshotText()
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.gateway_clip_label), events))
        Toast.makeText(this, getString(R.string.gateway_copied), Toast.LENGTH_SHORT).show()
    }

    private fun clearGatewayEvents() {
        val cleared = GatewayEventLog.clear()
        eventsSummary.text = gatewaySummary()
        val message = if (cleared) R.string.gateway_cleared else R.string.gateway_clear_failed
        Toast.makeText(this, getString(message), Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_URL = "com.denuoweb.hnsdane.GATEWAY_URL"
        const val EXTRA_TRACE_JSON = "com.denuoweb.hnsdane.GATEWAY_TRACE_JSON"
    }
}

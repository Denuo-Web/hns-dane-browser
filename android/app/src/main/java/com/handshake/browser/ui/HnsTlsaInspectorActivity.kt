package com.handshake.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject

class HnsTlsaInspectorActivity : ComponentActivity() {
    private val url: String
        get() = intent.getStringExtra(EXTRA_URL).orEmpty()

    private val traceJson: String
        get() = intent.getStringExtra(EXTRA_TRACE_JSON).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 32, 32, 32)
            applySystemBarPadding()
            addView(heading("TLSA / DANE Inspector"))
            addView(bodyText(friendlySummary()))
            addView(actionButton("Copy JSON") {
                copy("HNS TLSA inspector JSON", rawJson())
            })
            addView(actionButton("Copy Markdown") {
                copy("HNS TLSA inspector Markdown", markdownReport())
            })
            addView(subheading("Raw Export"))
            addView(bodyText(rawJson()))
        }

        setContentView(
            ScrollView(this).apply {
                addView(root)
            },
        )
    }

    private fun friendlySummary(): String {
        val trace = parsedTrace()
            ?: return "No resolver trace is available. Load an HTTPS HNS page first."
        val tls = trace.optJSONObject("tls")
            ?: return "No HTTPS TLSA/DANE trace is available for this page."
        val dane = tls.optJSONObject("dane")
        val certificate = tls.optJSONObject("certificate")
        return buildString {
            appendLine("URL: ${url.ifBlank { trace.optString("url", "unknown") }}")
            appendLine("Host: ${trace.optString("host", "unknown")}")
            appendLine("TLS mode: ${tls.optString("mode", "unknown")}")
            appendLine("TLSA owner: ${tls.optString("tlsaOwner", "unknown")}")
            appendLine("TLSA found: ${if (tls.optBoolean("tlsaFound", false)) "yes" else "no"}")
            appendLine("DNSSEC secure: ${tls.optString("dnssecSecure", "unknown")}")
            appendLine("DANE decision: ${dane?.optString("decision", "unknown") ?: "unknown"}")
            appendLine("Matched usage: ${dane?.optString("matchedUsage", "none") ?: "none"}")
            appendLine("Certificate match: ${dane?.optString("certificateMatch", "unknown") ?: "unknown"}")
            appendLine("WebPKI fallback: ${if (dane?.optBoolean("webPkiFallback", false) == true) "yes" else "no"}")
            appendLine("WebPKI status: ${certificate?.optString("webPkiStatus", "unknown") ?: "unknown"}")
            appendLine("Certificate SHA-256: ${certificate?.optString("endEntitySha256", "unknown") ?: "unknown"}")
            appendLine("SPKI SHA-256: ${certificate?.optString("spkiSha256", "unknown") ?: "unknown"}")
            appendLine("Intermediate certs: ${certificate?.optString("intermediateCount", "unknown") ?: "unknown"}")
            appendLine()
            appendLine("TLSA records:")
            appendLine(recordsText(tls.optJSONArray("records")))
            appendLine()
            appendLine("SPKI DER:")
            appendLine(certificate?.optString("spkiDerHex")?.takeIf { it.isNotBlank() } ?: "unavailable")
        }
    }

    private fun recordsText(records: JSONArray?): String =
        if (records == null || records.length() == 0) {
            "none"
        } else {
            (0 until records.length()).joinToString("\n") { index ->
                val record = records.optJSONObject(index)
                "- usage=${record?.optString("usage", "unknown") ?: "unknown"}, " +
                    "selector=${record?.optString("selector", "unknown") ?: "unknown"}, " +
                    "matching=${record?.optString("matching", "unknown") ?: "unknown"}, " +
                    "association=${record?.optString("associationDataHex", "unknown") ?: "unknown"}"
            }
        }

    private fun markdownReport(): String =
        "# HNS TLSA / DANE Report\n\n```\n${rawJson()}\n```\n"

    private fun rawJson(): String =
        traceJson.ifBlank { """{"error":"no_hns_tlsa_trace_available"}""" }

    private fun parsedTrace(): JSONObject? =
        runCatching { JSONObject(traceJson) }.getOrNull()

    private fun copy(label: String, value: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun heading(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 24f
            setPadding(0, 0, 0, 14)
        }

    private fun subheading(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, 18, 0, 8)
        }

    private fun bodyText(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, 0, 0, 12)
        }

    private fun actionButton(text: String, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setAllCaps(false)
            setOnClickListener { action() }
        }

    companion object {
        const val EXTRA_URL = "com.handshake.browser.HNS_TLSA_URL"
        const val EXTRA_TRACE_JSON = "com.handshake.browser.HNS_TLSA_TRACE_JSON"
    }
}

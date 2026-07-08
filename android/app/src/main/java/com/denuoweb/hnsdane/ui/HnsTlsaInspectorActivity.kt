package com.denuoweb.hnsdane.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.denuoweb.hnsdane.R
import org.json.JSONArray
import org.json.JSONObject

class HnsTlsaInspectorActivity : ComponentActivity() {
    private val url: String
        get() = intent.getStringExtra(EXTRA_URL).orEmpty()

    private val traceJson: String
        get() = intent.getStringExtra(EXTRA_TRACE_JSON).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setSecondaryScreen(
            title = getString(R.string.screen_tlsa_dane_inspector),
            onSwipeLeft = {
                openAdjacentHnsDiagnostic(HnsDiagnosticTool.TlsaInspector, forward = true, url, traceJson)
            },
            onSwipeRight = {
                openAdjacentHnsDiagnostic(HnsDiagnosticTool.TlsaInspector, forward = false, url, traceJson)
            },
        ) {
            addView(hnsDiagnosticTabs(HnsDiagnosticTool.TlsaInspector, url, traceJson))
            addView(screenSection(getString(R.string.section_summary)) {
                addView(fieldReportText(friendlySummary()))
            })
            addView(screenSection(getString(R.string.section_export)) {
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_copy_json),
                    summary = getString(R.string.export_tlsa_dane_json_summary),
                    actionLabel = getString(R.string.action_copy),
                ) {
                    copy(getString(R.string.copy_label_tlsa_json), rawJson())
                })
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_copy_markdown),
                    summary = getString(R.string.row_copy_markdown_summary),
                    actionLabel = getString(R.string.action_copy),
                ) {
                    copy(getString(R.string.copy_label_tlsa_markdown), markdownReport())
                })
            })
            addView(screenSection(getString(R.string.section_raw_export)) {
                addView(reportText(rawJson(), monospace = true))
            })
        }
    }

    private fun friendlySummary(): String {
        val trace = parsedTrace()
            ?: return getString(R.string.trace_no_tlsa_resolver_trace)
        val tls = trace.optJSONObject("tls")
            ?: return getString(R.string.trace_no_tlsa_trace)
        val dane = tls.optJSONObject("dane")
        val certificate = tls.optJSONObject("certificate")
        return buildString {
            appendLine(getString(R.string.trace_field_url, url.ifBlank { trace.optString("url", getString(R.string.common_unknown)) }))
            appendLine(getString(R.string.trace_field_host, trace.optString("host", getString(R.string.common_unknown))))
            appendLine(getString(R.string.trace_field_tls_mode, LocalizedTraceText.tlsMode(this@HnsTlsaInspectorActivity, tls)))
            appendLine(getString(R.string.trace_field_tlsa_owner, tls.optString("tlsaOwner", getString(R.string.common_unknown))))
            appendLine(getString(R.string.trace_field_tlsa_status, LocalizedTraceText.tlsaStatus(this@HnsTlsaInspectorActivity, tls)))
            appendLine(getString(R.string.trace_field_tlsa_found, LocalizedTraceText.tlsaFound(this@HnsTlsaInspectorActivity, tls)))
            appendLine(getString(R.string.trace_field_tlsa_source, LocalizedTraceText.tlsaSource(this@HnsTlsaInspectorActivity, tls)))
            appendLine(getString(R.string.trace_field_dnssec_secure, LocalizedTraceText.dnssecSecure(this@HnsTlsaInspectorActivity, tls)))
            appendLine(getString(R.string.trace_field_dane_decision, LocalizedTraceText.daneDecision(this@HnsTlsaInspectorActivity, tls)))
            appendLine(getString(R.string.trace_field_matched_usage, LocalizedTraceText.valueOrNone(this@HnsTlsaInspectorActivity, dane?.optString("matchedUsage"))))
            appendLine(getString(R.string.trace_field_certificate_match, LocalizedTraceText.valueOrUnknown(this@HnsTlsaInspectorActivity, dane?.optString("certificateMatch"))))
            appendLine(getString(R.string.trace_field_webpki_fallback, LocalizedTraceText.yesNo(this@HnsTlsaInspectorActivity, dane?.optBoolean("webPkiFallback", false) == true)))
            appendLine(getString(R.string.trace_field_webpki_status, LocalizedTraceText.valueOrUnknown(this@HnsTlsaInspectorActivity, certificate?.optString("webPkiStatus"))))
            appendLine(getString(R.string.trace_field_certificate_sha256, LocalizedTraceText.valueOrUnknown(this@HnsTlsaInspectorActivity, certificate?.optString("endEntitySha256"))))
            appendLine(getString(R.string.trace_field_spki_sha256, LocalizedTraceText.valueOrUnknown(this@HnsTlsaInspectorActivity, certificate?.optString("spkiSha256"))))
            appendLine(getString(R.string.trace_field_intermediate_certs, LocalizedTraceText.valueOrUnknown(this@HnsTlsaInspectorActivity, certificate?.optString("intermediateCount"))))
            appendLine()
            appendLine(getString(R.string.trace_tlsa_records))
            appendLine(recordsText(tls.optJSONArray("records")))
            appendLine()
            appendLine(getString(R.string.trace_spki_der))
            appendLine(certificate?.optString("spkiDerHex")?.takeIf { it.isNotBlank() } ?: getString(R.string.common_unavailable))
        }
    }

    private fun recordsText(records: JSONArray?): String =
        if (records == null || records.length() == 0) {
            getString(R.string.common_none)
        } else {
            (0 until records.length()).joinToString("\n") { index ->
                val record = records.optJSONObject(index)
                getString(
                    R.string.trace_tlsa_record_line,
                    record?.optString("usage", getString(R.string.common_unknown)) ?: getString(R.string.common_unknown),
                    record?.optString("selector", getString(R.string.common_unknown)) ?: getString(R.string.common_unknown),
                    record?.optString("matching", getString(R.string.common_unknown)) ?: getString(R.string.common_unknown),
                    record?.optString("associationDataHex", getString(R.string.common_unknown))
                        ?: getString(R.string.common_unknown),
                )
            }
        }

    private fun markdownReport(): String =
        "${getString(R.string.trace_markdown_tlsa_title)}\n\n```\n${rawJson()}\n```\n"

    private fun rawJson(): String =
        traceJson.ifBlank { """{"error":"no_hns_tlsa_trace_available"}""" }

    private fun parsedTrace(): JSONObject? =
        runCatching { JSONObject(traceJson) }.getOrNull()

    private fun copy(label: String, value: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, getString(R.string.common_copied), Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_URL = "com.denuoweb.hnsdane.HNS_TLSA_URL"
        const val EXTRA_TRACE_JSON = "com.denuoweb.hnsdane.HNS_TLSA_TRACE_JSON"
    }
}

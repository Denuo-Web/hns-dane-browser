package com.denuoweb.hnsdane.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.denuoweb.hnsdane.R
import org.json.JSONObject

class HnsResolverTraceActivity : ComponentActivity() {
    private val url: String
        get() = intent.getStringExtra(EXTRA_URL).orEmpty()

    private val traceJson: String
        get() = intent.getStringExtra(EXTRA_TRACE_JSON).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setSecondaryScreen(
            title = getString(R.string.screen_resolver_trace),
            onSwipeLeft = {
                openAdjacentHnsDiagnostic(HnsDiagnosticTool.ResolverTrace, forward = true, url, traceJson)
            },
            onSwipeRight = {
                openAdjacentHnsDiagnostic(HnsDiagnosticTool.ResolverTrace, forward = false, url, traceJson)
            },
        ) {
            addView(hnsDiagnosticTabs(HnsDiagnosticTool.ResolverTrace, url, traceJson))
            addView(screenSection(getString(R.string.section_summary)) {
                addView(fieldReportText(friendlySummary()))
            })
            addView(screenSection(getString(R.string.section_export)) {
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_copy_json),
                    summary = getString(R.string.export_resolver_trace_json_summary),
                    actionLabel = getString(R.string.action_copy),
                ) {
                    copy(getString(R.string.copy_label_resolver_trace_json), rawJson())
                })
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_copy_markdown),
                    summary = getString(R.string.row_copy_markdown_summary),
                    actionLabel = getString(R.string.action_copy),
                ) {
                    copy(getString(R.string.copy_label_resolver_trace_markdown), markdownReport())
                })
            })
            addView(screenSection(getString(R.string.section_raw_export)) {
                addView(reportText(rawJson(), monospace = true))
            })
        }
    }

    private fun friendlySummary(): String {
        val trace = parsedTrace()
            ?: return getString(R.string.trace_no_resolver_trace)
        if (HnsResolutionTraceFormat.isIcann(trace)) {
            return icannSummary(trace)
        }
        val fallback = trace.optJSONObject("fallback")
        val authoritativeDns = trace.optJSONObject("authoritativeDns")
        val tls = trace.optJSONObject("tls")
        return buildString {
            appendLine(getString(R.string.trace_field_url, url.ifBlank { trace.optString("url", getString(R.string.common_unknown)) }))
            appendLine(getString(R.string.trace_field_host, trace.optString("host", getString(R.string.common_unknown))))
            appendLine(getString(R.string.trace_field_root, trace.optString("root", getString(R.string.common_unknown))))
            appendLine(getString(R.string.trace_field_mode, trace.optString("mode", getString(R.string.common_unknown))))
            appendLine(getString(R.string.trace_field_hns_proof, trace.optString("hnsProof", getString(R.string.common_unknown))))
            appendLine(getString(R.string.trace_field_local_best_height, nullableTraceValue(trace, "localBestHeight")))
            appendLine(getString(R.string.trace_field_target_height, nullableTraceValue(trace, "targetHeight")))
            appendLine(getString(R.string.trace_field_estimated_target_height, nullableTraceValue(trace, "estimatedTargetHeight")))
            appendLine(getString(R.string.trace_field_local_chain_stale, nullableTraceValue(trace, "localChainStale")))
            appendLine(getString(R.string.trace_field_delegation, LocalizedTraceText.yesNo(this@HnsResolverTraceActivity, trace.optBoolean("delegation", false))))
            appendLine(getString(R.string.trace_field_resolution_source, LocalizedTraceText.resolutionSource(this@HnsResolverTraceActivity, trace)))
            appendLine(getString(R.string.trace_field_resource_records, LocalizedTraceText.jsonArrayText(this@HnsResolverTraceActivity, trace.optJSONArray("resourceRecords"))))
            appendLine(getString(R.string.trace_field_nameserver_candidates, LocalizedTraceText.jsonArrayText(this@HnsResolverTraceActivity, trace.optJSONArray("nameserverCandidates"))))
            appendLine(getString(R.string.trace_field_authoritative_udp53, authoritativeDns?.optString("udp53") ?: getString(R.string.common_unknown)))
            appendLine(getString(R.string.trace_field_authoritative_tcp53, authoritativeDns?.optString("tcp53") ?: getString(R.string.common_unknown)))
            appendLine(getString(R.string.trace_field_authoritative_doh, authoritativeDns?.optString("doh") ?: getString(R.string.common_unknown)))
            appendLine(getString(R.string.trace_field_resolver_attempts, dnsAttemptsSummary(trace)))
            appendLine(getString(R.string.trace_field_dnssec, trace.optString("dnssec", getString(R.string.common_unknown))))
            appendLine(getString(R.string.trace_field_origin_address, trace.optString("originAddress", getString(R.string.common_unknown))))
            appendLine(getString(R.string.trace_field_tlsa_owner, LocalizedTraceText.valueOrNone(this@HnsResolverTraceActivity, tls?.optString("tlsaOwner"))))
            appendLine(getString(R.string.trace_field_tlsa_status, LocalizedTraceText.tlsaStatus(this@HnsResolverTraceActivity, tls)))
            appendLine(getString(R.string.trace_field_tlsa_source, LocalizedTraceText.tlsaSource(this@HnsResolverTraceActivity, tls)))
            appendLine(getString(R.string.trace_field_dane, LocalizedTraceText.daneDecision(this@HnsResolverTraceActivity, tls)))
            appendLine(getString(R.string.trace_field_doh_fallback, LocalizedTraceText.yesNo(this@HnsResolverTraceActivity, fallback?.optBoolean("used", false) == true)))
            appendLine(getString(R.string.trace_field_fallback_reason, LocalizedTraceText.valueOrNone(this@HnsResolverTraceActivity, fallback?.optString("reason"))))
            appendLine(getString(R.string.trace_field_final_error, LocalizedTraceText.valueOrNone(this@HnsResolverTraceActivity, trace.optString("finalError"))))
            appendLine()
            appendLine(getString(R.string.trace_suggested_fix))
            appendLine(suggestedFix(trace))
        }
    }

    private fun icannSummary(trace: JSONObject): String {
        val fallback = trace.optJSONObject("fallback")
        val tls = trace.optJSONObject("tls")
        return buildString {
            appendLine(getString(R.string.trace_field_url, url.ifBlank { trace.optString("url", getString(R.string.common_unknown)) }))
            appendLine(getString(R.string.trace_field_host, trace.optString("host", getString(R.string.common_unknown))))
            appendLine(getString(R.string.trace_field_namespace, LocalizedTraceText.namespace(this@HnsResolverTraceActivity, trace)))
            appendLine(getString(R.string.trace_field_mode, trace.optString("mode", getString(R.string.common_unknown))))
            appendLine(getString(R.string.trace_field_dnssec, trace.optString("dnssec", getString(R.string.common_unknown))))
            appendLine(getString(R.string.trace_field_resolution_source, LocalizedTraceText.resolutionSource(this@HnsResolverTraceActivity, trace)))
            appendLine(getString(R.string.trace_field_resource_records, LocalizedTraceText.jsonArrayText(this@HnsResolverTraceActivity, trace.optJSONArray("resourceRecords"))))
            appendLine(getString(R.string.trace_field_resolver_attempts, dnsAttemptsSummary(trace)))
            appendLine(getString(R.string.trace_field_origin_address, trace.optString("originAddress", getString(R.string.common_unknown))))
            appendLine(getString(R.string.trace_field_tlsa_owner, LocalizedTraceText.valueOrNone(this@HnsResolverTraceActivity, tls?.optString("tlsaOwner"))))
            appendLine(getString(R.string.trace_field_tlsa_status, LocalizedTraceText.tlsaStatus(this@HnsResolverTraceActivity, tls)))
            appendLine(getString(R.string.trace_field_tlsa_source, LocalizedTraceText.tlsaSource(this@HnsResolverTraceActivity, tls)))
            appendLine(getString(R.string.trace_field_dane, LocalizedTraceText.daneDecision(this@HnsResolverTraceActivity, tls)))
            appendLine(getString(R.string.trace_field_doh_fallback, LocalizedTraceText.yesNo(this@HnsResolverTraceActivity, fallback?.optBoolean("used", false) == true)))
            appendLine(getString(R.string.trace_field_final_error, LocalizedTraceText.valueOrNone(this@HnsResolverTraceActivity, trace.optString("finalError"))))
            appendLine()
            appendLine(getString(R.string.trace_suggested_fix))
            appendLine(suggestedFix(trace))
        }
    }

    private fun nullableTraceValue(trace: JSONObject, key: String): String =
        LocalizedTraceText.nullableValue(this, trace, key)

    private fun dnsAttemptsSummary(trace: JSONObject): String {
        val attempts = trace.optJSONArray("dnsAttempts") ?: return getString(R.string.common_none)
        if (attempts.length() == 0) {
            return getString(R.string.common_none)
        }
        return (0 until attempts.length()).joinToString(" | ") { index ->
            val attempt = attempts.optJSONObject(index)
            val protocol = LocalizedTraceText.attemptProtocolLabel(
                this,
                attempt?.optString("protocol")?.takeIf { it.isNotBlank() } ?: getString(R.string.common_unknown),
            )
            val server = attempt?.optString("server")?.takeIf { it.isNotBlank() } ?: getString(R.string.common_unknown)
            val status = attempt?.optString("status")?.takeIf { it.isNotBlank() } ?: getString(R.string.common_unknown)
            val elapsed = attempt
                ?.takeIf { it.has("elapsedMs") }
                ?.optLong("elapsedMs")
                ?.let { "${it}ms" }
                ?: getString(R.string.common_unknown)
            getString(R.string.trace_attempt_summary, protocol, server, status, elapsed)
        }
    }

    private fun suggestedFix(trace: JSONObject): String {
        if (HnsResolutionTraceFormat.isIcann(trace)) {
            return suggestedIcannFix(trace)
        }
        val hnsProof = trace.optString("hnsProof")
        val authoritativeDns = trace.optJSONObject("authoritativeDns")
        val udp53 = authoritativeDns?.optString("udp53").orEmpty()
        val tcp53 = authoritativeDns?.optString("tcp53").orEmpty()
        val doh = authoritativeDns?.optString("doh").orEmpty()
        val dnssec = trace.optString("dnssec")
        val fallback = trace.optJSONObject("fallback")
        val nameserverCandidates = trace.optJSONArray("nameserverCandidates")
        val tls = trace.optJSONObject("tls")
        val tlsaBlockedBy = LocalizedTraceText.tlsaBlockedBy(tls)
        return when {
            hnsProof == "stale" || trace.optBoolean("localChainStale", false) ->
                getString(R.string.trace_fix_hns_stale)
            hnsProof == "unavailable" || hnsProof == "unknown" ->
                getString(R.string.trace_fix_hns_unavailable)
            nameserverCandidates == null || nameserverCandidates.length() == 0 ->
                getString(R.string.trace_fix_add_delegation)
            udp53 in setOf("timeout", "transport_error") &&
                tcp53 in setOf("timeout", "transport_error", "not_attempted") &&
                doh == "ok" ->
                getString(R.string.trace_fix_doh_answered)
            udp53 in setOf("timeout", "transport_error") && tcp53 in setOf("timeout", "transport_error", "not_attempted") && doh.isBlank() ->
                getString(R.string.trace_fix_nameserver_unreliable_with_doh)
            udp53 in setOf("timeout", "transport_error") && tcp53 in setOf("timeout", "transport_error", "not_attempted") ->
                getString(R.string.trace_fix_nameserver_unreliable)
            dnssec == "bogus" ->
                getString(R.string.trace_fix_dnssec_bogus)
            tlsaBlockedBy in setOf("delegated_dnssec_validation_failed", "insecure_resolution") ->
                getString(R.string.trace_fix_dnssec_before_tlsa)
            originCertificateExpired(trace, tls) ->
                getString(R.string.trace_fix_renew_cert)
            trace.optString("originAddress") == "missing" ->
                getString(R.string.trace_fix_origin_address_missing)
            fallback?.optBoolean("used", false) == true ->
                getString(R.string.trace_fix_compat_fallback_used)
            else ->
                getString(R.string.trace_fix_no_obvious_hns)
        }
    }

    private fun suggestedIcannFix(trace: JSONObject): String {
        val tls = trace.optJSONObject("tls")
        val tlsaBlockedBy = LocalizedTraceText.tlsaBlockedBy(tls)
        return when {
            trace.optString("dnssec") == "bogus" ->
                getString(R.string.trace_fix_icann_dnssec_bogus)
            originCertificateExpired(trace, tls) ->
                getString(R.string.trace_fix_renew_cert)
            trace.optString("originAddress") == "missing" ->
                getString(R.string.trace_fix_icann_origin_address_missing)
            tlsaBlockedBy in setOf("delegated_dnssec_validation_failed", "insecure_resolution") ->
                getString(R.string.trace_fix_icann_dnssec_before_tlsa)
            HnsTlsaTraceFormat.daneDecision(tls) == "verified" ->
                getString(R.string.trace_fix_icann_working)
            else ->
                getString(R.string.trace_fix_no_obvious_icann)
        }
    }

    private fun originCertificateExpired(trace: JSONObject, tls: JSONObject?): Boolean {
        if (LocalizedTraceText.tlsaBlockedBy(tls) == "origin_certificate_expired") {
            return true
        }
        val finalError = trace.optString("finalError", "").lowercase()
        return finalError.contains("certificate expired") ||
            finalError.contains("certificate has expired") ||
            finalError.contains("cert has expired") ||
            finalError.contains("not valid after")
    }

    private fun markdownReport(): String =
        if (HnsResolutionTraceFormat.isIcann(parsedTrace())) {
            "${getString(R.string.trace_markdown_icann_title)}\n\n```\n${rawJson()}\n```\n"
        } else {
            "${getString(R.string.trace_markdown_hns_title)}\n\n```\n${rawJson()}\n```\n"
        }

    private fun rawJson(): String =
        traceJson.ifBlank { """{"error":"no_hns_resolver_trace_available"}""" }

    private fun parsedTrace(): JSONObject? =
        runCatching { JSONObject(traceJson) }.getOrNull()

    private fun copy(label: String, value: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, getString(R.string.common_copied), Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_URL = "com.denuoweb.hnsdane.HNS_TRACE_URL"
        const val EXTRA_TRACE_JSON = "com.denuoweb.hnsdane.HNS_TRACE_JSON"
    }
}

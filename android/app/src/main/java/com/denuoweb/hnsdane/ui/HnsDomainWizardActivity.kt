package com.denuoweb.hnsdane.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.denuoweb.hnsdane.R
import com.denuoweb.hnsdane.net.NativeBridge
import org.json.JSONArray
import org.json.JSONObject

class HnsDomainWizardActivity : ComponentActivity() {
    private lateinit var input: EditText
    private lateinit var output: TextView
    private var lastReport: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        input = EditText(this).apply {
            hint = getString(R.string.wizard_input_hint)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    analyze()
                    true
                } else {
                    false
                }
            }
        }

        output = reportText(getString(R.string.wizard_intro))

        setSecondaryScreen(getString(R.string.screen_hns_domain_setup)) {
            addView(screenSection(getString(R.string.section_domain)) {
                addView(input, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.wizard_analyze_domain),
                    summary = getString(R.string.wizard_analyze_summary),
                    actionLabel = getString(R.string.action_analyze),
                ) {
                    analyze()
                })
                addScreenRow(preferenceRow(
                    title = getString(R.string.wizard_copy_report),
                    summary = getString(R.string.wizard_copy_summary),
                    actionLabel = getString(R.string.action_copy),
                ) {
                    copy(lastReport.ifBlank { output.text.toString() })
                })
            })
            addView(screenSection(getString(R.string.section_report)) {
                addView(output)
            })
        }
    }

    private fun analyze() {
        val host = normalizeHost(input.text.toString())
        if (host.isBlank()) {
            output.text = getString(R.string.wizard_invalid_name)
            return
        }
        val proofJson = NativeBridge.hnsProofDetails(
            filesDir.absolutePath,
            host,
            HnsResolutionPreferences.handshakeNetworkId(this),
        )
        lastReport = reportFor(host, proofJson)
        output.text = lastReport
    }

    private fun reportFor(host: String, proofJson: String): String {
        val proof = runCatching { JSONObject(proofJson) }.getOrNull()
            ?: return getString(R.string.wizard_parse_error, proofJson)
        val name = proof.optString("name", host.substringAfterLast("."))
        val status = proof.optString("proofStatus", getString(R.string.common_unknown))
        val cacheStatus = proof.optString("cacheStatus", getString(R.string.common_unknown))
        val recordTypes = proof.optJSONArray("recordTypes")
        val records = proof.optJSONArray("resourceRecords")
        val hasNs = hasRecordType(recordTypes, "NS")
        val hasAddress = hasRecordType(recordTypes, "A") || hasRecordType(recordTypes, "AAAA")
        val hasDs = hasRecordType(recordTypes, "DS")
        val hasTxt = hasRecordType(recordTypes, "TXT")

        return buildString {
            appendLine(getString(R.string.wizard_report_title))
            appendLine()
            appendLine(getString(R.string.wizard_field_host, host))
            appendLine(getString(R.string.wizard_field_root, name))
            appendLine(getString(R.string.wizard_field_proof_status, status))
            appendLine(getString(R.string.wizard_field_cache_status, cacheStatus))
            appendLine(getString(R.string.wizard_field_records, arrayText(recordTypes)))
            appendLine()
            appendLine(getString(R.string.wizard_current_problem))
            appendLine(problemText(status, cacheStatus, hasNs, hasAddress, hasDs))
            appendLine()
            appendLine(getString(R.string.wizard_suggested_fix))
            appendLine(suggestedFix(name, status, hasNs, hasAddress, hasDs))
            appendLine()
            appendLine(getString(R.string.wizard_checklist_title))
            appendLine(getString(R.string.wizard_checklist_resource))
            appendLine(getString(R.string.wizard_checklist_dns, name))
            appendLine(getString(R.string.wizard_checklist_dnssec))
            appendLine(getString(R.string.wizard_checklist_tlsa, host))
            appendLine()
            appendLine(getString(R.string.wizard_decoded_records))
            appendLine(recordsText(records))
            if (hasTxt) {
                appendLine()
                appendLine(getString(R.string.wizard_txt_warning))
            }
            appendLine()
            appendLine(getString(R.string.wizard_raw_json))
            appendLine(proofJson)
        }
    }

    private fun problemText(
        status: String,
        cacheStatus: String,
        hasNs: Boolean,
        hasAddress: Boolean,
        hasDs: Boolean,
    ): String = when {
        status == "unavailable" ->
            getString(R.string.wizard_problem_unavailable, cacheStatus)
        status == "not_found" ->
            getString(R.string.wizard_problem_not_found)
        status != "verified" ->
            getString(R.string.wizard_problem_not_verified, status)
        !hasNs && !hasAddress ->
            getString(R.string.wizard_problem_no_data)
        hasNs && !hasAddress ->
            getString(R.string.wizard_problem_no_address)
        hasAddress && !hasDs ->
            getString(R.string.wizard_problem_no_ds)
        else ->
            getString(R.string.wizard_problem_usable)
    }

    private fun suggestedFix(
        name: String,
        status: String,
        hasNs: Boolean,
        hasAddress: Boolean,
        hasDs: Boolean,
    ): String = when {
        status == "unavailable" ->
            getString(R.string.wizard_fix_unavailable)
        status == "not_found" ->
            getString(R.string.wizard_fix_not_found, name)
        !hasNs && !hasAddress ->
            getString(R.string.wizard_fix_add_records, name)
        hasNs && !hasAddress ->
            getString(R.string.wizard_fix_add_glue, name)
        !hasDs ->
            getString(R.string.wizard_fix_add_dane, name)
        else ->
            getString(R.string.wizard_fix_verify_webserver)
    }

    private fun normalizeHost(value: String): String =
        value
            .trim()
            .substringAfter("://", value.trim())
            .substringBefore("/")
            .substringBefore("?")
            .substringBefore("#")
            .substringBefore(":")
            .trimEnd('.')
            .lowercase()

    private fun hasRecordType(recordTypes: JSONArray?, expected: String): Boolean =
        recordTypes != null && (0 until recordTypes.length()).any { index ->
            recordTypes.optString(index).equals(expected, ignoreCase = true)
        }

    private fun arrayText(array: JSONArray?): String =
        if (array == null || array.length() == 0) {
            getString(R.string.common_none)
        } else {
            (0 until array.length()).joinToString(", ") { index -> array.optString(index) }
        }

    private fun recordsText(records: JSONArray?): String =
        if (records == null || records.length() == 0) {
            getString(R.string.common_none)
        } else {
            (0 until records.length()).joinToString("\n") { index ->
                val record = records.optJSONObject(index)
                getString(
                    R.string.wizard_record_line,
                    record?.optString("type", getString(R.string.common_unknown)) ?: getString(R.string.common_unknown),
                    record?.optString("name", getString(R.string.common_unknown)) ?: getString(R.string.common_unknown),
                    record?.optString("rdataHex", getString(R.string.common_unknown)) ?: getString(R.string.common_unknown),
                )
            }
        }

    private fun copy(value: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.wizard_clip_label), value))
        Toast.makeText(this, getString(R.string.common_copied), Toast.LENGTH_SHORT).show()
    }
}

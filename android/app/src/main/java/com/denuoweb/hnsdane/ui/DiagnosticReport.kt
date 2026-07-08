package com.denuoweb.hnsdane.ui

import android.content.Context
import com.denuoweb.hnsdane.R
import java.time.Instant

internal object DiagnosticReport {
    fun markdown(
        context: Context,
        buildLabel: String,
        rustCore: String,
        rustDiagnostics: String,
        proxyOverrideSupported: Boolean,
        thirdPartyCookiesBlocked: Boolean,
        generatedAtMillis: Long = System.currentTimeMillis(),
    ): String =
        markdown(
            labels = DiagnosticReportLabels.from(context),
            buildLabel = buildLabel,
            rustCore = rustCore,
            rustDiagnostics = rustDiagnostics,
            proxyOverrideSupported = proxyOverrideSupported,
            thirdPartyCookiesBlocked = thirdPartyCookiesBlocked,
            generatedAtMillis = generatedAtMillis,
        )

    fun markdown(
        labels: DiagnosticReportLabels,
        buildLabel: String,
        rustCore: String,
        rustDiagnostics: String,
        proxyOverrideSupported: Boolean,
        thirdPartyCookiesBlocked: Boolean,
        generatedAtMillis: Long = System.currentTimeMillis(),
    ): String =
        buildString {
            appendLine(labels.title)
            appendLine()
            appendLine(labels.generated(Instant.ofEpochMilli(generatedAtMillis).toString()))
            appendLine(labels.build(buildLabel))
            appendLine(labels.rustCore(rustCore))
            appendLine(labels.proxyOverride(proxyOverrideSupported.toString()))
            appendLine(labels.thirdPartyCookies(thirdPartyCookiesBlocked.toString()))
            appendLine()
            appendLine(labels.rustDiagnostics)
            appendCodeBlock(rustDiagnostics)
        }

    private fun StringBuilder.appendCodeBlock(value: String) {
        appendLine("```")
        appendLine(value.replace("```", "` ` `"))
        appendLine("```")
    }
}

internal data class DiagnosticReportLabels(
    val title: String,
    val generated: (String) -> String,
    val build: (String) -> String,
    val rustCore: (String) -> String,
    val proxyOverride: (String) -> String,
    val thirdPartyCookies: (String) -> String,
    val rustDiagnostics: String,
) {
    companion object {
        fun from(context: Context): DiagnosticReportLabels =
            DiagnosticReportLabels(
                title = context.getString(R.string.diagnostics_report_title),
                generated = { context.getString(R.string.diagnostics_report_generated, it) },
                build = { context.getString(R.string.diagnostics_report_build, it) },
                rustCore = { context.getString(R.string.diagnostics_report_rust_core, it) },
                proxyOverride = { context.getString(R.string.diagnostics_report_proxy_override, it) },
                thirdPartyCookies = { context.getString(R.string.diagnostics_report_third_party_cookies, it) },
                rustDiagnostics = context.getString(R.string.diagnostics_report_rust_diagnostics),
            )
    }
}

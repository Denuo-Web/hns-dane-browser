package com.handshake.browser.ui

import java.time.Instant

internal object DiagnosticReport {
    fun markdown(
        buildLabel: String,
        rustCore: String,
        rustDiagnostics: String,
        syncStatus: String,
        proxyOverrideSupported: Boolean,
        thirdPartyCookiesBlocked: Boolean,
        gatewayEvents: String,
        generatedAtMillis: Long = System.currentTimeMillis(),
    ): String =
        buildString {
            appendLine("# HNS Browser Diagnostic Bundle")
            appendLine()
            appendLine("Generated: ${Instant.ofEpochMilli(generatedAtMillis)}")
            appendLine("Build: $buildLabel")
            appendLine("Rust core: $rustCore")
            appendLine("Proxy override supported: $proxyOverrideSupported")
            appendLine("Third-party cookies blocked: $thirdPartyCookiesBlocked")
            appendLine()
            appendLine("## Sync Status")
            appendCodeBlock(syncStatus)
            appendLine()
            appendLine("## Rust Diagnostics")
            appendCodeBlock(rustDiagnostics)
            appendLine()
            appendLine("## Recent Gateway Events")
            appendCodeBlock(gatewayEvents.ifBlank { "none" })
        }

    private fun StringBuilder.appendCodeBlock(value: String) {
        appendLine("```")
        appendLine(value.replace("```", "` ` `"))
        appendLine("```")
    }
}

package com.denuoweb.hnsdane.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.webkit.WebViewFeature
import com.denuoweb.hnsdane.BuildConfig
import com.denuoweb.hnsdane.R
import com.denuoweb.hnsdane.net.NativeBridge

class DiagnosticsActivity : ComponentActivity() {
    private val url: String
        get() = intent.getStringExtra(EXTRA_URL).orEmpty()

    private val traceJson: String
        get() = intent.getStringExtra(EXTRA_TRACE_JSON).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setSecondaryScreen(
            title = getString(R.string.screen_diagnostics),
            onSwipeLeft = {
                openAdjacentHnsDiagnostic(HnsDiagnosticTool.Diagnostics, forward = true, url, traceJson)
            },
            onSwipeRight = {
                openAdjacentHnsDiagnostic(HnsDiagnosticTool.Diagnostics, forward = false, url, traceJson)
            },
        ) {
            addView(hnsDiagnosticTabs(HnsDiagnosticTool.Diagnostics, url, traceJson))
            addView(screenSection(getString(R.string.section_app_and_runtime)) {
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_build),
                    summary = buildLabel(),
                    selectableSummary = true,
                    boldSummary = true,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_rust_core),
                    summary = NativeBridge.version(),
                    selectableSummary = true,
                    boldSummary = true,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_rust_diagnostics),
                    summary = NativeBridge.diagnostics(),
                    selectableSummary = true,
                    summaryMaxLines = Int.MAX_VALUE,
                    boldSummary = true,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_proxy_override),
                    summary = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE).toString(),
                    selectableSummary = true,
                    boldSummary = true,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_third_party_cookies_blocked),
                    summary = BrowserCookiePreferences.blockThirdPartyCookies(this@DiagnosticsActivity).toString(),
                    selectableSummary = true,
                    boldSummary = true,
                ))
            })
            addView(screenSection(getString(R.string.section_diagnostic_bundle)) {
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_copy_diagnostic_bundle),
                    summary = getString(R.string.row_copy_diagnostic_bundle_summary),
                    actionLabel = getString(R.string.action_copy),
                ) {
                    copyDiagnosticBundle()
                })
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_share_diagnostic_bundle),
                    summary = getString(R.string.row_share_diagnostic_bundle_summary),
                    actionLabel = getString(R.string.action_share),
                ) {
                    shareDiagnosticBundle()
                })
            })
        }
    }

    private fun copyDiagnosticBundle() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.diagnostics_clip_label), diagnosticBundle()))
        Toast.makeText(this, getString(R.string.diagnostics_copied), Toast.LENGTH_SHORT).show()
    }

    private fun shareDiagnosticBundle() {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diagnostics_clip_label))
            putExtra(Intent.EXTRA_TEXT, diagnosticBundle())
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.diagnostics_share_chooser)))
    }

    private fun diagnosticBundle(): String =
        DiagnosticReport.markdown(
            context = this,
            buildLabel = buildLabel(),
            rustCore = NativeBridge.version(),
            rustDiagnostics = NativeBridge.diagnostics(),
            proxyOverrideSupported = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE),
            thirdPartyCookiesBlocked = BrowserCookiePreferences.blockThirdPartyCookies(this),
        )

    private fun buildLabel(): String {
        val channel = if (BuildConfig.DEBUG) {
            getString(R.string.common_debug_demo)
        } else {
            getString(R.string.common_release)
        }
        return getString(R.string.common_build_label, channel, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    }

    companion object {
        const val EXTRA_URL = "com.denuoweb.hnsdane.DIAGNOSTICS_URL"
        const val EXTRA_TRACE_JSON = "com.denuoweb.hnsdane.DIAGNOSTICS_TRACE_JSON"
    }
}

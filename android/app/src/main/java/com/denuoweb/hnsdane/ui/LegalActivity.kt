package com.denuoweb.hnsdane.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.denuoweb.hnsdane.BuildConfig
import com.denuoweb.hnsdane.R

class LegalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setSecondaryScreen(getString(R.string.screen_legal)) {
            addView(screenSection(getString(R.string.section_app)) {
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_build),
                    summary = buildLabel(),
                    selectableSummary = true,
                ))
            })
            addView(screenSection(getString(R.string.section_privacy_policy)) {
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_license_summary_title),
                    summary = getString(R.string.legal_privacy_summary),
                    selectableSummary = true,
                    summaryMaxLines = Int.MAX_VALUE,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_privacy_policy_url),
                    summary = BrowserAppInfo.PRIVACY_POLICY_URL,
                    actionLabel = getString(R.string.action_open),
                ) {
                    openLink(
                        Uri.parse(BrowserAppInfo.PRIVACY_POLICY_URL),
                        getString(R.string.legal_copy_privacy_policy_url),
                        BrowserAppInfo.PRIVACY_POLICY_URL,
                    )
                })
            })
            addView(screenSection(getString(R.string.section_license)) {
                addScreenRow(preferenceRow(
                    title = getString(R.string.legal_license_name),
                    summary = getString(R.string.legal_license_summary),
                    selectableSummary = true,
                    summaryMaxLines = Int.MAX_VALUE,
                ))
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_source_code),
                    summary = BrowserAppInfo.SOURCE_CODE_URL,
                    actionLabel = getString(R.string.action_open),
                ) {
                    openLink(
                        Uri.parse(BrowserAppInfo.SOURCE_CODE_URL),
                        getString(R.string.legal_copy_source_code_url),
                        BrowserAppInfo.SOURCE_CODE_URL,
                    )
                })
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_third_party_notices),
                    summary = getString(R.string.row_third_party_notices_summary),
                    actionLabel = getString(R.string.action_open),
                ) {
                    startActivity(Intent(this@LegalActivity, ThirdPartyNoticesActivity::class.java))
                })
            })
            addView(screenSection(getString(R.string.section_user_agreement)) {
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_agreement),
                    summary = getString(R.string.legal_user_agreement),
                    selectableSummary = true,
                    summaryMaxLines = Int.MAX_VALUE,
                ))
            })
        }
    }

    private fun openLink(uri: Uri, copyLabel: String, copyText: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(copyLabel, copyText))
            Toast.makeText(this, getString(R.string.common_copied_label, copyLabel), Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildLabel(): String {
        val channel = if (BuildConfig.DEBUG) {
            getString(R.string.common_debug_demo)
        } else {
            getString(R.string.common_release)
        }
        return getString(R.string.common_build_label, channel, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    }
}

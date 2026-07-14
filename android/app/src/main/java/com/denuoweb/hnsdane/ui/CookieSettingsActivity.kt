package com.denuoweb.hnsdane.ui

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import com.denuoweb.hnsdane.R

class CookieSettingsActivity : ComponentActivity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = preferenceSummary(summary())

        setSecondaryScreen(getString(R.string.screen_cookie_options)) {
            addView(screenSection(getString(R.string.section_website_data)) {
                addScreenRow(checkboxRow(
                    title = getString(R.string.row_block_third_party_cookies),
                    summaryView = status,
                    checked = BrowserCookiePreferences.blockThirdPartyCookies(this@CookieSettingsActivity),
                ) { checked ->
                    BrowserCookiePreferences.setBlockThirdPartyCookies(this@CookieSettingsActivity, checked)
                    status.text = summary()
                })
                addScreenRow(preferenceRow(
                    title = getString(R.string.row_delete_website_data),
                    summary = getString(R.string.row_delete_website_data_summary),
                    actionLabel = getString(R.string.action_delete),
                    destructive = true,
                ) {
                    deleteWebsiteData()
                })
            })
        }
    }

    private fun deleteWebsiteData() {
        val webStorage = WebStorage.getInstance()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            try {
                WebStorageCompat.deleteBrowsingData(
                    webStorage,
                    ContextCompat.getMainExecutor(this),
                ) {
                    clearCookiesAndReport(R.string.website_data_deleted, Toast.LENGTH_SHORT)
                }
                return
            } catch (_: UnsupportedOperationException) {
                // The installed WebView can change between the feature check and invocation.
            }
        }

        requestLegacyWebsiteDataDeletion(webStorage)
    }

    private fun requestLegacyWebsiteDataDeletion(webStorage: WebStorage) {
        webStorage.deleteAllData()
        clearCookiesAndReport(R.string.website_data_deletion_requested, Toast.LENGTH_LONG)
    }

    private fun clearCookiesAndReport(messageRes: Int, toastDuration: Int) {
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            showDeletionStatus(messageRes, toastDuration)
        }
    }

    private fun showDeletionStatus(messageRes: Int, toastDuration: Int) {
        runOnUiThread {
            val message = getString(messageRes)
            status.text = getString(R.string.cookie_status_after_delete, message, summary())
            Toast.makeText(this, message, toastDuration).show()
        }
    }

    private fun summary(): String =
        if (BrowserCookiePreferences.blockThirdPartyCookies(this)) {
            getString(R.string.cookie_summary_blocking)
        } else {
            getString(R.string.cookie_summary_allowing_all)
        }
}

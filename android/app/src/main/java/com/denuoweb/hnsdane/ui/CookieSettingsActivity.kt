package com.denuoweb.hnsdane.ui

import android.os.Bundle
import android.webkit.CookieManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
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
                    title = getString(R.string.row_delete_cookies),
                    summary = getString(R.string.row_delete_cookies_summary),
                    actionLabel = getString(R.string.action_delete),
                    destructive = true,
                ) {
                    deleteCookies()
                })
            })
        }
    }

    private fun deleteCookies() {
        CookieManager.getInstance().removeAllCookies { removedAny ->
            CookieManager.getInstance().flush()
            runOnUiThread {
                val message = if (removedAny) {
                    getString(R.string.cookie_deleted)
                } else {
                    getString(R.string.cookie_none_to_delete)
                }
                status.text = getString(R.string.cookie_status_after_delete, message, summary())
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun summary(): String =
        if (BrowserCookiePreferences.blockThirdPartyCookies(this)) {
            getString(R.string.cookie_summary_blocking)
        } else {
            getString(R.string.cookie_summary_allowing_all)
        }
}

package com.handshake.browser.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.webkit.CookieManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.handshake.browser.net.NativeBridge

class SettingsActivity : ComponentActivity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "Settings"
            textSize = 16f
            setPadding(0, 10, 0, 18)
            setTextIsSelectable(true)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 32, 32, 32)
            applySystemBarPadding()
            addView(heading("Settings"))
            addView(status)
            addView(actionButton("View diagnostics") {
                startActivity(Intent(this@SettingsActivity, DiagnosticsActivity::class.java))
            })
            addView(actionButton("Clear cookies") {
                clearCookies()
            })
            addView(actionButton("Clear resolver cache") {
                clearResolverCache()
            })
        }

        setContentView(
            ScrollView(this).apply {
                addView(root)
            },
        )
    }

    private fun heading(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 24f
            setPadding(0, 0, 0, 14)
        }

    private fun actionButton(text: String, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setAllCaps(false)
            setOnClickListener { action() }
        }

    private fun clearCookies() {
        CookieManager.getInstance().removeAllCookies { removedAny ->
            CookieManager.getInstance().flush()
            runOnUiThread {
                val message = if (removedAny) "Cookies cleared" else "No cookies to clear"
                status.text = message
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun clearResolverCache() {
        val result = NativeBridge.clearResolverCache(filesDir.absolutePath)
        status.text = "Resolver cache: $result"
        Toast.makeText(this, "Resolver cache cleared", Toast.LENGTH_SHORT).show()
    }
}

package com.denuoweb.hnsdane.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Exported, extra-blind entry point for the system launcher.
 *
 * Keeping the browser activity non-exported prevents other apps from supplying
 * internal navigation extras that would otherwise be loaded with this app's
 * WebView cookies and storage.
 */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}

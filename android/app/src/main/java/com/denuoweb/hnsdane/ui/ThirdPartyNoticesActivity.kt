package com.denuoweb.hnsdane.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.denuoweb.hnsdane.R

class ThirdPartyNoticesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val notices = assets.open(NOTICES_ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readText()
        }
        setSecondaryScreen(getString(R.string.screen_third_party_notices)) {
            addView(reportText(notices, monospace = true))
        }
    }

    private companion object {
        const val NOTICES_ASSET = "third_party_notices.txt"
    }
}

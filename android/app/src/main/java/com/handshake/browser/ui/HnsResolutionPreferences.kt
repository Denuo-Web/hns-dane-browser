package com.handshake.browser.ui

import android.content.Context

internal object HnsResolutionPreferences {
    private const val PREFS = "hns_resolution_preferences"
    private const val KEY_STRICT_HNS_MODE = "strict_hns_mode"

    fun strictHnsMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_STRICT_HNS_MODE, false)

    fun setStrictHnsMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STRICT_HNS_MODE, enabled)
            .apply()
    }
}

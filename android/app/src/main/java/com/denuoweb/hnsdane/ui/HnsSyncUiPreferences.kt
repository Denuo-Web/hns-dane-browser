package com.denuoweb.hnsdane.ui

import android.content.Context

object HnsSyncUiPreferences {
    private const val PREFS_NAME = "hns_sync_ui"
    private const val KEY_PROGRESS_VISIBLE = "progress_visible"

    fun progressVisible(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROGRESS_VISIBLE, true)

    fun setProgressVisible(context: Context, visible: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROGRESS_VISIBLE, visible)
            .apply()
    }
}

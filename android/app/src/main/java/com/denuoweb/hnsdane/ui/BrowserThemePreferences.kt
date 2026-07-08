package com.denuoweb.hnsdane.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import com.denuoweb.hnsdane.R

internal enum class BrowserThemeMode(val id: String) {
    System("system"),
    Light("light"),
    Dark("dark"),
    ;

    companion object {
        fun fromId(id: String?): BrowserThemeMode =
            entries.firstOrNull { it.id == id } ?: System
    }
}

internal object BrowserThemePreferences {
    private const val PREFS = "browser_preferences"
    private const val KEY_THEME_MODE = "theme_mode"

    fun themeMode(context: Context): BrowserThemeMode =
        BrowserThemeMode.fromId(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_THEME_MODE, BrowserThemeMode.System.id),
        )

    fun setThemeMode(context: Context, mode: BrowserThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, mode.id)
            .apply()
    }

    fun applyTo(activity: Activity) {
        activity.setTheme(styleFor(activity))
    }

    fun effectiveDark(context: Context): Boolean =
        isDark(themeMode(context), context.resources.configuration.uiMode)

    fun styleFor(context: Context): Int =
        if (effectiveDark(context)) {
            R.style.AppTheme_Dark
        } else {
            R.style.AppTheme_Light
        }

    fun isDark(mode: BrowserThemeMode, uiMode: Int): Boolean =
        when (mode) {
            BrowserThemeMode.System -> {
                uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            }
            BrowserThemeMode.Light -> false
            BrowserThemeMode.Dark -> true
        }
}

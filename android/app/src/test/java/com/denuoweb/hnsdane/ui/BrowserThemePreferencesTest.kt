package com.denuoweb.hnsdane.ui

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserThemePreferencesTest {
    @Test
    fun themeModeFromIdDefaultsToSystem() {
        assertEquals(BrowserThemeMode.System, BrowserThemeMode.fromId(null))
        assertEquals(BrowserThemeMode.System, BrowserThemeMode.fromId("unknown"))
    }

    @Test
    fun themeModeFromIdSupportsManualChoices() {
        assertEquals(BrowserThemeMode.Light, BrowserThemeMode.fromId("light"))
        assertEquals(BrowserThemeMode.Dark, BrowserThemeMode.fromId("dark"))
    }

    @Test
    fun themeModeResolvesSystemNightMask() {
        assertFalse(
            BrowserThemePreferences.isDark(
                BrowserThemeMode.System,
                Configuration.UI_MODE_NIGHT_NO,
            ),
        )
        assertTrue(
            BrowserThemePreferences.isDark(
                BrowserThemeMode.System,
                Configuration.UI_MODE_NIGHT_YES,
            ),
        )
    }

    @Test
    fun manualThemeModesOverrideSystemNightMask() {
        assertFalse(
            BrowserThemePreferences.isDark(
                BrowserThemeMode.Light,
                Configuration.UI_MODE_NIGHT_YES,
            ),
        )
        assertTrue(
            BrowserThemePreferences.isDark(
                BrowserThemeMode.Dark,
                Configuration.UI_MODE_NIGHT_NO,
            ),
        )
    }
}

package com.denuoweb.hnsdane.ui

import android.content.Context
import android.graphics.Color

internal data class ThemeColors(
    val background: Int,
    val surface: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val action: Int,
    val actionContainer: Int,
    val onAction: Int,
    val destructive: Int,
    val divider: Int,
    val securityText: Int,
)

internal fun Context.themeColors(): ThemeColors =
    if (BrowserThemePreferences.effectiveDark(this)) {
        ThemeColors(
            background = Color.rgb(17, 19, 21),
            surface = Color.rgb(32, 33, 36),
            primaryText = Color.rgb(232, 234, 237),
            secondaryText = Color.rgb(189, 193, 198),
            action = Color.rgb(138, 180, 248),
            actionContainer = Color.rgb(32, 52, 89),
            onAction = Color.rgb(17, 19, 21),
            destructive = Color.rgb(242, 139, 130),
            divider = Color.rgb(60, 64, 67),
            securityText = Color.rgb(128, 203, 196),
        )
    } else {
        ThemeColors(
            background = Color.WHITE,
            surface = Color.WHITE,
            primaryText = Color.rgb(32, 33, 36),
            secondaryText = Color.rgb(95, 99, 104),
            action = Color.rgb(21, 101, 192),
            actionContainer = Color.rgb(232, 240, 254),
            onAction = Color.WHITE,
            destructive = Color.rgb(183, 28, 28),
            divider = Color.rgb(218, 220, 224),
            securityText = Color.rgb(28, 71, 75),
        )
    }

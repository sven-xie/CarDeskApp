package com.sven.cardesk.core.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.sven.cardesk.data.DesktopPreferences

object ThemeController {

    fun applyStoredTheme(context: Context) {
        val dark = DesktopPreferences(context).isDarkTheme
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    fun setDarkTheme(context: Context, dark: Boolean) {
        DesktopPreferences(context).isDarkTheme = dark
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}

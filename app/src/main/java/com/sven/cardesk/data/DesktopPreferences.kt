package com.sven.cardesk.data

import android.content.Context
import android.content.SharedPreferences

class DesktopPreferences(context: Context) {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isDarkTheme: Boolean
        get() = preferences.getBoolean(KEY_DARK_THEME, false)
        set(value) {
            preferences.edit().putBoolean(KEY_DARK_THEME, value).apply()
        }

    var backgroundUri: String?
        get() = preferences.getString(KEY_BACKGROUND_URI, null)
        set(value) {
            preferences.edit().putString(KEY_BACKGROUND_URI, value).apply()
        }

    var backgroundType: BackgroundType?
        get() = preferences.getString(KEY_BACKGROUND_TYPE, null)
            ?.let { runCatching { BackgroundType.valueOf(it) }.getOrNull() }
        set(value) {
            preferences.edit().putString(KEY_BACKGROUND_TYPE, value?.name).apply()
        }

    var cardRatios: FloatArray
        get() {
            val raw = preferences.getString(KEY_CARD_RATIOS, null) ?: return floatArrayOf(
                DEFAULT_RATIO,
                DEFAULT_RATIO,
                DEFAULT_RATIO
            )
            val values = raw.split(',').mapNotNull { it.toFloatOrNull() }
            return if (values.size == 3 && values.all { it > 0f }) {
                normalize(values.toFloatArray())
            } else {
                floatArrayOf(DEFAULT_RATIO, DEFAULT_RATIO, DEFAULT_RATIO)
            }
        }
        set(value) {
            val normalized = normalize(value)
            preferences.edit()
                .putString(KEY_CARD_RATIOS, normalized.joinToString(","))
                .apply()
        }

    var secondaryWidthRatio: Float
        get() = preferences.getFloat(KEY_SECONDARY_WIDTH_RATIO, DEFAULT_RATIO)
            .coerceIn(MIN_RATIO, MAX_RATIO)
        set(value) {
            preferences.edit()
                .putFloat(KEY_SECONDARY_WIDTH_RATIO, value.coerceIn(MIN_RATIO, MAX_RATIO))
                .apply()
        }

    var bottomBarHeightDp: Int
        get() = preferences.getInt(KEY_BOTTOM_BAR_HEIGHT, DEFAULT_BOTTOM_BAR_HEIGHT)
            .coerceIn(MIN_BOTTOM_BAR_HEIGHT, MAX_BOTTOM_BAR_HEIGHT)
        set(value) {
            preferences.edit()
                .putInt(
                    KEY_BOTTOM_BAR_HEIGHT,
                    value.coerceIn(MIN_BOTTOM_BAR_HEIGHT, MAX_BOTTOM_BAR_HEIGHT)
                )
                .apply()
        }

    fun windowPackage(index: Int): String? =
        preferences.getString(KEY_WINDOW_PACKAGE_PREFIX + index, null)

    fun setWindowPackage(index: Int, packageName: String?) {
        preferences.edit()
            .putString(KEY_WINDOW_PACKAGE_PREFIX + index, packageName)
            .apply()
    }

    fun clearBackground() {
        preferences.edit()
            .remove(KEY_BACKGROUND_URI)
            .remove(KEY_BACKGROUND_TYPE)
            .apply()
    }

    private fun normalize(values: FloatArray): FloatArray {
        val safe = FloatArray(3) { index ->
            values.getOrElse(index) { DEFAULT_RATIO }.coerceAtLeast(0.05f)
        }
        val sum = safe.sum().takeIf { it > 0f } ?: 3f
        return FloatArray(3) { safe[it] / sum }
    }

    enum class BackgroundType {
        IMAGE,
        VIDEO
    }

    companion object {
        private const val PREFS_NAME = "my_car_desk"
        private const val KEY_DARK_THEME = "dark_theme"
        private const val KEY_BACKGROUND_URI = "background_uri"
        private const val KEY_BACKGROUND_TYPE = "background_type"
        private const val KEY_CARD_RATIOS = "card_ratios"
        private const val KEY_SECONDARY_WIDTH_RATIO = "secondary_width_ratio"
        private const val KEY_BOTTOM_BAR_HEIGHT = "bottom_bar_height_dp"
        private const val KEY_WINDOW_PACKAGE_PREFIX = "window_package_"

        private const val DEFAULT_RATIO = 1f / 3f
        const val MIN_RATIO = 0.2f
        const val MAX_RATIO = 0.8f
        const val MIN_BOTTOM_BAR_HEIGHT = 72
        const val MAX_BOTTOM_BAR_HEIGHT = 160
        const val DEFAULT_BOTTOM_BAR_HEIGHT = 104
    }
}

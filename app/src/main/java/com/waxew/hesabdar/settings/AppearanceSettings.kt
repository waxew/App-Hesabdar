package com.waxew.hesabdar.settings

import android.content.Context

/** تنظیم ساده ظاهر برنامه. */
class AppearanceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("hesabdar_appearance", Context.MODE_PRIVATE)

    fun getThemeMode(): String = prefs.getString(KEY_THEME, SYSTEM) ?: SYSTEM

    fun setThemeMode(mode: String) {
        require(mode in setOf(SYSTEM, LIGHT, DARK)) { "حالت ظاهر نامعتبر است." }
        prefs.edit().putString(KEY_THEME, mode).apply()
    }

    companion object {
        const val SYSTEM = "SYSTEM"
        const val LIGHT = "LIGHT"
        const val DARK = "DARK"
        private const val KEY_THEME = "theme_mode"
    }
}

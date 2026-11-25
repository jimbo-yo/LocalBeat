package com.jimboyo.localbeat

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark") }

/** Colours: LocalBeat's own "Prism" (shared with LocalBeat Studio), or Material You from the wallpaper. */
enum class ColorStyle(val label: String) { PRISM("Prism (LocalBeat)"), WALLPAPER("Wallpaper colours (Material You)") }

enum class AppFont(val label: String) {
    FIGTREE("Figtree"), INTER("Inter"), NUNITO_SANS("Nunito Sans"), ATKINSON("Atkinson Hyperlegible")
}

/** User preferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(enumOr(prefs.getString("theme", null), ThemeMode.SYSTEM))
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _colors = MutableStateFlow(enumOr(prefs.getString("colors", null), ColorStyle.PRISM))
    val colors: StateFlow<ColorStyle> = _colors.asStateFlow()

    private val _font = MutableStateFlow(enumOr(prefs.getString("font", null), AppFont.FIGTREE))
    val font: StateFlow<AppFont> = _font.asStateFlow()

    /** Whether the first-run onboarding Welcome screen has been completed or skipped. */
    private val _onboardingDone = MutableStateFlow(prefs.getBoolean("onboardingDone", false))
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    fun setTheme(t: ThemeMode) { _theme.value = t; prefs.edit { putString("theme", t.name) } }
    fun setColors(c: ColorStyle) { _colors.value = c; prefs.edit { putString("colors", c.name) } }
    fun setFont(f: AppFont) { _font.value = f; prefs.edit { putString("font", f.name) } }
    fun markOnboardingDone() { _onboardingDone.value = true; prefs.edit { putBoolean("onboardingDone", true) } }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}

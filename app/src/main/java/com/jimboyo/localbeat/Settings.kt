package com.jimboyo.localbeat

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark") }

enum class AppFont(val label: String) {
    FIGTREE("Figtree"), INTER("Inter"), NUNITO_SANS("Nunito Sans"), ATKINSON("Atkinson Hyperlegible")
}

/** User preferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(enumOr(prefs.getString("theme", null), ThemeMode.SYSTEM))
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _font = MutableStateFlow(enumOr(prefs.getString("font", null), AppFont.FIGTREE))
    val font: StateFlow<AppFont> = _font.asStateFlow()

    fun setTheme(t: ThemeMode) { _theme.value = t; prefs.edit { putString("theme", t.name) } }
    fun setFont(f: AppFont) { _font.value = f; prefs.edit { putString("font", f.name) } }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}

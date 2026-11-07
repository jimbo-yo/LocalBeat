package com.jimboyo.localbeat.audio

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The equaliser settings and the user's own presets, kept across restarts. */
class EqualizerStore(context: Context) {
    private val prefs = context.getSharedPreferences("equalizer", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        EqState(prefs.getBoolean("enabled", false), parseGains(prefs.getString("gains", null)) ?: Eq.FLAT),
    )
    val state: StateFlow<EqState> = _state.asStateFlow()

    private val _custom = MutableStateFlow(loadCustom())
    /** Presets the user saved, in the order they were made. */
    val custom: StateFlow<List<EqPreset>> = _custom.asStateFlow()

    val presets get() = Eq.PRESETS + _custom.value

    /** The preset matching the current curve, or null when it's a custom setting. */
    fun currentPreset(s: EqState = _state.value, all: List<EqPreset> = presets) = all.firstOrNull { it.gains == s.gains }

    fun setEnabled(on: Boolean) = save(_state.value.copy(enabled = on))

    fun setBand(band: Int, db: Float) {
        val gains = _state.value.gains.toMutableList()
        gains[band] = db.coerceIn(-Eq.MAX_DB, Eq.MAX_DB)
        save(_state.value.copy(gains = gains))
    }

    /** Picking a preset also turns the equaliser on. */
    fun apply(p: EqPreset) = save(EqState(enabled = true, gains = p.gains))

    fun saveCustom(name: String) {
        val n = name.trim()
        _custom.value = _custom.value.filter { it.name != n } + EqPreset(n, _state.value.gains, builtIn = false)
        storeCustom()
    }

    fun deleteCustom(p: EqPreset) {
        _custom.value = _custom.value.filter { it.name != p.name }
        storeCustom()
    }

    private fun save(s: EqState) {
        _state.value = s
        prefs.edit { putBoolean("enabled", s.enabled); putString("gains", s.gains.joinToString(",")) }
    }

    // One preset per line: name, tab, comma-separated gains.
    private fun loadCustom(): List<EqPreset> = prefs.getString("custom", "").orEmpty().lines().mapNotNull { line ->
        val name = line.substringBefore('\t', "")
        parseGains(line.substringAfter('\t', ""))?.takeIf { name.isNotBlank() }?.let { EqPreset(name, it, builtIn = false) }
    }

    private fun storeCustom() = prefs.edit {
        putString("custom", _custom.value.joinToString("\n") { p -> p.name.replace('\t', ' ').replace('\n', ' ') + "\t" + p.gains.joinToString(",") })
    }

    private fun parseGains(s: String?): List<Float>? =
        s?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == Eq.BANDS }
}

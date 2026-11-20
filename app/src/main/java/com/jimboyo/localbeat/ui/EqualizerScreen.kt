package com.jimboyo.localbeat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimboyo.localbeat.audio.Eq
import com.jimboyo.localbeat.audio.EqPreset
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerScreen(ui: Ui) {
    val eq = ui.app.equalizer
    val state by eq.state.collectAsStateWithLifecycle()
    val custom by eq.custom.collectAsStateWithLifecycle()
    val current = eq.currentPreset(state, Eq.PRESETS + custom)
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<EqPreset?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (state.enabled) "On" else "Off", style = MaterialTheme.typography.titleMedium)
                Text(current?.name ?: "Custom", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(state.enabled, eq::setEnabled)
        }

        EqGraph(state.gains, state.enabled, Modifier.fillMaxWidth().height(280.dp).padding(horizontal = 8.dp, vertical = 8.dp)) { band, db ->
            if (!state.enabled) eq.setEnabled(true)
            eq.setBand(band, db)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Eq.LABELS.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(
            "Hz · drag a band up or down",
            Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { saving = true }, enabled = current == null) { Text("Save as preset") }
            TextButton(onClick = { eq.apply(Eq.PRESETS.first()) }, enabled = state.gains != Eq.FLAT) { Text("Reset to flat") }
        }

        Text("Presets", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (Eq.PRESETS + custom).forEach { p ->
                FilterChip(
                    selected = state.enabled && current == p,
                    onClick = { eq.apply(p) },
                    label = { Text(p.name) },
                    trailingIcon = if (p.builtIn) null else {
                        { IconButton(onClick = { deleting = p }, Modifier.size(24.dp)) { Icon(Icons.Default.Close, "Delete ${p.name}", Modifier.size(16.dp)) } }
                    },
                )
            }
        }

        Text(
            "The equaliser works everywhere LocalBeat plays, including Bluetooth and Android Auto. " +
                "When bands are boosted, the overall volume comes down by the same amount so loud songs don't distort.",
            Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (saving) NameDialog("Save preset", "", "Save", onDismiss = { saving = false }) {
        saving = false
        eq.saveCustom(it)
    }
    deleting?.let { p ->
        ConfirmDialog("Delete “${p.name}”?", "The current sound settings stay as they are.", "Delete", onDismiss = { deleting = null }) { eq.deleteCustom(p) }
    }
}

/**
 * The band graph: a knob per band on a -12…+12 dB scale, and the actual
 * response curve through them. Touch anywhere in a band's column and drag.
 */
@Composable
private fun EqGraph(gains: List<Float>, enabled: Boolean, modifier: Modifier, onChange: (Int, Float) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = if (enabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.6f)
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val valueStyle = MaterialTheme.typography.labelMedium.copy(color = colors.onSurface)
    val latest by rememberUpdatedState(onChange)
    var dragging by remember { mutableIntStateOf(-1) }
    val curve = remember(gains) { (0..200).map { Eq.responseDb(gains, 31.25 * 2.0.pow(it / 20.0 - 0.5)).toFloat() } }

    Canvas(
        modifier.pointerInput(Unit) {
            val pad = 14.dp.toPx()
            fun dbAt(y: Float): Float {
                val span = size.height - 2 * pad
                if (span <= 0f) return 0f
                val db = Eq.MAX_DB - (y - pad) / span * 2 * Eq.MAX_DB
                return ((db * 2).roundToInt() / 2f).coerceIn(-Eq.MAX_DB, Eq.MAX_DB) // half-dB steps
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                val band = (down.position.x / size.width * Eq.BANDS).toInt().coerceIn(0, Eq.BANDS - 1)
                dragging = band
                down.consume()
                latest(band, dbAt(down.position.y))
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                    latest(band, dbAt(change.position.y))
                }
                dragging = -1
            }
        },
    ) {
        val pad = 14.dp.toPx()
        val plotH = size.height - 2 * pad
        fun y(db: Float) = pad + (Eq.MAX_DB - db) / (2 * Eq.MAX_DB) * plotH
        val colW = size.width / Eq.BANDS

        // Grid: 0 dB stronger, ±6 and ±12 faint, with labels on the left.
        for (db in listOf(12f, 6f, 0f, -6f, -12f)) {
            drawLine(
                colors.outlineVariant.copy(alpha = if (db == 0f) 1f else 0.5f),
                Offset(0f, y(db)), Offset(size.width, y(db)), strokeWidth = if (db == 0f) 1.5.dp.toPx() else 1.dp.toPx(),
            )
            if (db != 0f) {
                val t = measurer.measure((if (db > 0) "+" else "") + db.toInt(), labelStyle)
                drawText(t, topLeft = Offset(2.dp.toPx(), y(db) - t.size.height - 1.dp.toPx()))
            }
        }
        // Column guides.
        for (i in 0 until Eq.BANDS) {
            val x = (i + 0.5f) * colW
            drawLine(colors.outlineVariant.copy(alpha = 0.35f), Offset(x, pad), Offset(x, size.height - pad), strokeWidth = 1.dp.toPx())
        }
        // The response curve, filled to the 0 dB line. Band i sits at the centre of column i.
        val line = Path()
        curve.forEachIndexed { j, db ->
            val x = j / 200f * size.width
            val yy = y(db.coerceIn(-Eq.MAX_DB, Eq.MAX_DB))
            if (j == 0) line.moveTo(x, yy) else line.lineTo(x, yy)
        }
        val fill = Path().apply { addPath(line); lineTo(size.width, y(0f)); lineTo(0f, y(0f)); close() }
        drawPath(fill, accent.copy(alpha = 0.15f))
        drawPath(line, accent, style = Stroke(width = 3.dp.toPx()))
        // Knobs.
        gains.forEachIndexed { i, db ->
            val c = Offset((i + 0.5f) * colW, y(db))
            val big = i == dragging
            drawCircle(colors.surface, radius = (if (big) 13 else 10).dp.toPx(), center = c)
            drawCircle(accent, radius = (if (big) 11 else 8).dp.toPx(), center = c)
            if (big) {
                val t = measurer.measure((if (db > 0) "+" else if (db < 0) "−" else "") + fmt(abs(db)) + " dB", valueStyle)
                val above = c.y - 20.dp.toPx() - t.size.height
                val ty = if (above < 0) c.y + 18.dp.toPx() else above
                val tx = (c.x - t.size.width / 2).coerceIn(0f, size.width - t.size.width)
                drawText(t, topLeft = Offset(tx, ty))
            }
        }
    }
}

private fun fmt(v: Float) = if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(v)

/** "Off", or "On · Rock" for the settings row. */
fun eqSummary(ui: Ui, enabled: Boolean, gains: List<Float>): String =
    if (!enabled) "Off" else "On · " + (ui.app.equalizer.presets.firstOrNull { it.gains == gains }?.name ?: "Custom")

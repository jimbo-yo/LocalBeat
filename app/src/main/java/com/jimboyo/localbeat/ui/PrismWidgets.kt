package com.jimboyo.localbeat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Whether the Prism colours are on (else Material You): the widgets below fall back to plain Material. */
val LocalPrism = staticCompositionLocalOf { true }

/** Colour at [t] (0..1) along the prism, for things drawn piece by piece. */
fun prismAt(t: Float): Color {
    val stops = listOf(Prism.cyan, Prism.blue, Prism.violet, Prism.magenta, Prism.amber)
    val x = t.coerceIn(0f, 1f) * (stops.size - 1)
    val i = x.toInt().coerceAtMost(stops.size - 2)
    val f = x - i
    val a = stops[i]
    val b = stops[i + 1]
    return Color(a.red + (b.red - a.red) * f, a.green + (b.green - a.green) * f, a.blue + (b.blue - a.blue) * f)
}

/** The round play/pause button: prism-filled, white icon (Material's filled button without Prism). */
@Composable
fun PlayPauseButton(playing: Boolean, onClick: () -> Unit, size: Dp, iconSize: Dp, enabled: Boolean = true) {
    val icon = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow
    if (!LocalPrism.current) {
        FilledIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(size)) { Icon(icon, "Play/pause", Modifier.size(iconSize)) }
        return
    }
    Box(
        Modifier.size(size).alpha(if (enabled) 1f else 0.4f).clip(CircleShape)
            .background(Brush.linearGradient(listOf(Prism.cyan, Prism.blue, Prism.violet, Prism.magenta, Prism.amber)))
            .background(Color.Black.copy(alpha = 0.12f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, "Play/pause", Modifier.size(iconSize), tint = Color.White) }
}

/** A pill button filled with the prism (the screen's main action), else Material's primary. */
@Composable
fun PrismPill(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    if (!LocalPrism.current) {
        androidx.compose.material3.Button(onClick = onClick, content = content)
        return
    }
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(Prism.gradient).background(Color.Black.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClick = onClick).padding(PaddingValues(start = 16.dp, end = 22.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White) { content() }
    }
}

/** A thin progress line: the part played in the prism (the colour says how far along). */
@Composable
fun PrismProgress(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    val track = MaterialTheme.colorScheme.outlineVariant
    val primary = MaterialTheme.colorScheme.primary
    val prism = LocalPrism.current
    Canvas(modifier.fillMaxWidth().height(height)) {
        drawRect(track)
        val w = size.width * fraction.coerceIn(0f, 1f)
        if (w > 0f) {
            if (prism) {
                val end = prismAt(fraction)
                drawRect(Brush.horizontalGradient(listOf(Prism.cyan, Prism.blue, end).take(if (fraction < 0.3f) 2 else 3), endX = w), size = Size(w, size.height))
            } else drawRect(primary, size = Size(w, size.height))
        }
    }
}

/** The seek bar's track with the prism up to the knob. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrismSeekSlider(value: Float, onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit) {
    if (!LocalPrism.current) {
        Slider(value = value, onValueChange = onValueChange, onValueChangeFinished = onValueChangeFinished)
        return
    }
    val interaction = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interaction,
        thumb = {
            SliderDefaults.Thumb(interactionSource = interaction, colors = SliderDefaults.colors(thumbColor = Color.White), thumbSize = androidx.compose.ui.unit.DpSize(6.dp, 22.dp))
        },
        track = { state: SliderState ->
            val track = MaterialTheme.colorScheme.outlineVariant
            Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                val r = CornerRadius(size.height / 2, size.height / 2)
                drawRoundRect(track, cornerRadius = r)
                val f = state.value.coerceIn(0f, 1f)
                val w = size.width * f
                if (w > 0f) {
                    drawRoundRect(Brush.horizontalGradient(listOf(Prism.cyan, Prism.blue, Prism.violet, Prism.magenta, Prism.amber), endX = size.width), size = Size(w, size.height), cornerRadius = r)
                }
            }
        },
    )
}

/** The LocalBeat mark (gold disc, navy note): the launcher icon, drawn. */
@Composable
fun LocalBeatMark(size: Dp = 24.dp) {
    Canvas(Modifier.size(size)) {
        val k = this.size.minDimension / 52f
        fun at(x: Float, y: Float) = Offset((x - 28f) * k, (y - 28f) * k)
        drawCircle(Prism.brandGold, 26f * k, at(54f, 54f))
        drawCircle(Prism.brandNavy, 5f * k, at(54f, 54f))
        drawRect(Prism.brandNavy, at(58f, 36f), Size(4f * k, 28f * k))
        drawRect(Prism.brandNavy, at(58f, 36f), Size(10f * k, 6f * k))
        drawCircle(Prism.brandNavy, 7f * k, at(55f, 64f))
    }
}

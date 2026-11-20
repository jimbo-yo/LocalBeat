package com.jimboyo.localbeat.ui

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The player, for song rows that mark the current song. */
val LocalPlayer = staticCompositionLocalOf<PlayerConnection?> { null }

/** True when [trackId] is the current song (playing or paused). */
@Composable
fun isCurrent(trackId: String?): Boolean = trackId != null && LocalPlayer.current?.currentTrackId == trackId

/**
 * Four bouncing equaliser bars in the theme colour: they move while music plays
 * and hold still (low) while paused.
 */
@Composable
fun PlayingBars(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, size: Dp = 20.dp) {
    val playing = LocalPlayer.current?.isPlaying == true
    val still = floatArrayOf(0.45f, 0.75f, 0.35f, 0.6f)
    val heights: List<State<Float>>? = if (playing) {
        val transition = rememberInfiniteTransition(label = "bars")
        // Each bar has its own period so they never line up.
        val periods = intArrayOf(520, 410, 610, 470)
        periods.mapIndexed { i, ms ->
            transition.animateFloat(
                initialValue = 0.2f, targetValue = 1f,
                animationSpec = bounce(ms), label = "bar$i",
            )
        }
    } else null
    // Prism: each bar a step along the gradient.
    val prism = LocalPrism.current && color == MaterialTheme.colorScheme.primary
    Canvas(modifier.size(size)) {
        val n = still.size
        val gap = this.size.width / (n * 3f)
        val barW = (this.size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val h = this.size.height * (heights?.get(i)?.value ?: still[i])
            drawRoundRect(
                if (prism) prismAt(0.1f + 0.25f * i) else color, topLeft = Offset(i * (barW + gap), this.size.height - h), size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2, barW / 2),
            )
        }
    }
}

private fun bounce(ms: Int): InfiniteRepeatableSpec<Float> = infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Reverse)

/** Bars over a cover thumbnail, on a dimmed background so they show on any art. */
@Composable
fun PlayingOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) { PlayingBars() }
}

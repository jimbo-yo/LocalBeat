package com.jimboyo.localbeat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jimboyo.localbeat.library.Search
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Room to leave at a list's right edge so rows don't sit under the strip. */
val FastScrollWidth = 24.dp

/**
 * The A–Z strip on the right edge of a long list (albums, artists, songs): touch
 * or drag to jump to a letter, shown big while dragging. Only letters that have
 * entries are listed; names are filed as the list sorts them ("The Beatles"
 * under B, digits and symbols under #).
 *
 * [names] are the list's items in order; [scrollTo] scrolls to an item index
 * (the caller adds any header rows).
 */
@Composable
fun BoxScope.FastScroll(names: List<String>, scrollTo: suspend (Int) -> Unit) {
    val index = remember(names) {
        val first = LinkedHashMap<Char, Int>()
        names.forEachIndexed { i, n -> first.putIfAbsent(Search.indexLetter(n), i) }
        first.toList()
    }
    if (index.size < 2) return
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var height by remember { mutableIntStateOf(1) }
    var active by remember { mutableStateOf<Int?>(null) }
    var touchY by remember { mutableIntStateOf(0) }

    fun pick(y: Float) {
        val i = (y / height * index.size).toInt().coerceIn(0, index.lastIndex)
        touchY = y.roundToInt().coerceIn(0, height)
        if (i != active) {
            active = i
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            scope.launch { scrollTo(index[i].second) }
        }
    }

    Column(
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(FastScrollWidth).padding(vertical = 8.dp)
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(index) {
                try {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pick(down.position.y)
                        down.consume()
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                pick(change.position.y)
                                change.consume()
                            }
                        } finally {
                            active = null
                        }
                    }
                } finally {
                    active = null
                }
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        index.forEachIndexed { i, (letter, _) ->
            Text(
                letter.toString(), fontSize = 11.sp, lineHeight = 11.sp,
                fontWeight = if (i == active) FontWeight.Bold else null,
                color = if (i == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    active?.let { i ->
        val letter = index.getOrNull(i)?.first ?: return@let
        // The letter, big, beside the finger.
        Box(
            Modifier.align(Alignment.TopEnd)
                .offset { IntOffset(-(FastScrollWidth + 16.dp).roundToPx(), (touchY - 36.dp.roundToPx()).coerceAtLeast(0)) }
                .size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(letter.toString(), fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

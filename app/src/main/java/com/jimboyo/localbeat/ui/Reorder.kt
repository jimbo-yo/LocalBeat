package com.jimboyo.localbeat.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex

/**
 * Drag-to-reorder for a run of items in a LazyColumn (the queue, a playlist).
 * [headerCount] is how many lazy items come before the reorderable ones.
 * The list shows the dragged order until the source catches up with the move.
 */
class ReorderState<T>(private val listState: LazyListState) {
    var headerCount = 0
    var keyOf: (T) -> Any = { it as Any }
    var onMove: (from: Int, to: Int) -> Unit = { _, _ -> }
    var source: List<T> = emptyList()

    private var override by mutableStateOf<List<T>?>(null)
    private var overrideBase: List<T>? = null
    var draggingKey by mutableStateOf<Any?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    private var from = -1
    private var currentIndex = -1

    /** What to display: the source, or the dragged order while it is pending. */
    val items: List<T> get() = override?.takeIf { overrideBase === source } ?: source

    fun start(key: Any) {
        val list = items
        from = list.indexOfFirst { keyOf(it) == key }
        if (from < 0) return
        currentIndex = from
        override = list
        overrideBase = source
        draggingKey = key
        offset = 0f
    }

    fun drag(dy: Float) {
        val key = draggingKey ?: return
        val list = override ?: return
        offset += dy
        val infos = listState.layoutInfo.visibleItemsInfo
        val cur = infos.firstOrNull { it.key == key } ?: return
        val ci = cur.index - headerCount
        // Wait for layout to reflect the previous swap before processing another swap in the same frame.
        if (ci != currentIndex || ci !in list.indices) return
        val center = cur.offset + offset + cur.size / 2f
        val target = infos.firstOrNull {
            it.key != key && (it.index - headerCount) in list.indices && center > it.offset && center < it.offset + it.size
        } ?: return
        val ti = target.index - headerCount
        if (ti == ci) return
        override = list.toMutableList().apply { add(ti, removeAt(ci)) }
        currentIndex = ti
        offset -= (target.offset - cur.offset)
    }

    fun end() {
        val key = draggingKey ?: return
        val to = override?.indexOfFirst { keyOf(it) == key } ?: -1
        draggingKey = null
        currentIndex = -1
        offset = 0f
        if (from >= 0 && to >= 0 && from != to) {
            onMove(from, to)
            // If source didn't synchronously change and the list has no pending IPC update, keep override until source updates;
            // if moving identical items (no-op), drop override right away.
            if (override == source) override = null
        } else {
            override = null
        }
    }

    /** For the dragged row: lifts it above the others and follows the finger. */
    fun rowModifier(key: Any): Modifier =
        if (key == draggingKey) Modifier.zIndex(1f).graphicsLayer { translationY = offset; shadowElevation = 8f }
        else Modifier

    /** For a row's drag handle. */
    fun handleModifier(key: Any): Modifier = Modifier.pointerInput(key) {
        detectDragGestures(
            onDragStart = { start(key) },
            onDrag = { change, amount -> change.consume(); drag(amount.y) },
            onDragEnd = { end() },
            onDragCancel = { end() },
        )
    }
}

@Composable
fun <T> rememberReorderState(
    listState: LazyListState, source: List<T>, headerCount: Int, keyOf: (T) -> Any, onMove: (Int, Int) -> Unit,
): ReorderState<T> {
    val state = remember(listState) { ReorderState<T>(listState) }
    state.source = source
    state.headerCount = headerCount
    state.keyOf = keyOf
    state.onMove = onMove
    return state
}

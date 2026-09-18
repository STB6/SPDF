package com.stb6.spdf.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

internal enum class SelectionDragMode { NONE, WORD, HANDLE }

internal class Located(
    val pageIndex: Int,
    val xPt: Float,
    val yPt: Float,
    val ptPerPx: Float,
    // Page coordinates are clamped; retain the outside distance for hit tolerance.
    val outsidePx: Float = 0f,
)

internal class SelectionDriver(
    private val scope: CoroutineScope,
    private val viewModel: ReaderViewModel,
    private val haptics: HapticFeedback,
    private val fingerRadiusPx: Float,
) {
    var locate: suspend (Offset) -> Located? = { null }

    var mode by mutableStateOf(SelectionDragMode.NONE)
        private set
    val dragging: Boolean get() = mode != SelectionDragMode.NONE
    private var grabOffset = Offset.Zero

    var lastPointer: Offset? = null
        private set

    private var anchorStart: DocPosition? = null
    private var anchorEnd: DocPosition? = null

    // Selection requests may finish after release; drag anchors must not.
    private var requestSerial = 0
    private var dragGeneration = 0
    private var dragJob: Job? = null
    private var longPressJob: Job? = null

    fun beginLongPress(pos: Offset) {
        mode = SelectionDragMode.WORD
        grabOffset = Offset.Zero
        dragJob?.cancel()
        lastPointer = pos
        anchorStart = null
        anchorEnd = null
        val request = ++requestSerial
        val drag = ++dragGeneration
        longPressJob?.cancel()
        longPressJob = scope.launch {
            val at = locate(pos)
            if (request != requestSerial) return@launch
            // Subtract the clamped-away distance so page gaps cannot select distant edge text.
            val reach = if (at == null) 0f else fingerRadiusPx - at.outsidePx
            val hit = at != null && reach > 0f &&
                viewModel.selectWordAt(at.pageIndex, at.xPt, at.yPt, reach * at.ptPerPx)
            if (request != requestSerial) return@launch
            if (!hit) return@launch
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (drag != dragGeneration) return@launch
            val selection = viewModel.selection.value ?: return@launch
            anchorStart = selection.start
            anchorEnd = selection.end
        }
    }

    fun beginHandleDrag(movingStart: Boolean, pointer: Offset, reference: Offset) {
        val other = viewModel.selectionAnchor(movingStart) ?: return
        ++requestSerial
        longPressJob?.cancel()
        ++dragGeneration
        dragJob?.cancel()
        anchorStart = other
        anchorEnd = other
        grabOffset = pointer - reference
        lastPointer = pointer
        mode = SelectionDragMode.HANDLE
    }

    fun dragTo(pos: Offset) {
        lastPointer = pos
        val start = anchorStart ?: return
        val end = anchorEnd ?: return
        dragJob?.cancel()
        val target = pos - grabOffset
        val generation = dragGeneration
        dragJob = scope.launch {
            val at = locate(target) ?: return@launch
            coroutineContext.ensureActive()
            if (generation != dragGeneration) return@launch
            val changed = viewModel.extendSelection(at.pageIndex, at.xPt, at.yPt, start, end)
            if (changed) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun endDrag() {
        dragJob?.cancel()
        // Allow pending word selection to finish without restoring drag anchors.
        ++dragGeneration
        anchorStart = null
        anchorEnd = null
        lastPointer = null
        grabOffset = Offset.Zero
        mode = SelectionDragMode.NONE
    }
}

internal suspend fun locateOnCanvas(
    pos: Offset,
    layout: CanvasLayout,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    vertical: Boolean,
    viewportW: Float,
    viewportH: Float,
    viewModel: ReaderViewModel,
): Located? {
    val visible = layout.visibleBoxes(scale, offsetX, offsetY, vertical, viewportW.toInt(), viewportH.toInt(), 0f)
    fun outsideSquared(b: PageBox): Float = layout.screenRect(b, scale, offsetX, offsetY) { l, t, r, bt ->
        val dx = maxOf(l - pos.x, pos.x - r, 0f)
        val dy = maxOf(t - pos.y, pos.y - bt, 0f)
        dx * dx + dy * dy
    }
    val box = visible.minByOrNull(::outsideSquared) ?: return null
    val r = layout.screenRect(box, scale, offsetX, offsetY)
    val w = r[2] - r[0]
    val h = r[3] - r[1]
    if (w <= 0f || h <= 0f) return null
    // Hit testing needs actual page dimensions, not layout estimates.
    val info = viewModel.exactPageInfo(box.index) ?: return null
    val toPtX = info.widthPt / w
    val toPtY = info.heightPt / h
    return Located(
        pageIndex = box.index,
        xPt = (pos.x - r[0]).coerceIn(0f, w) * toPtX,
        yPt = (pos.y - r[1]).coerceIn(0f, h) * toPtY,
        ptPerPx = toPtX,
        outsidePx = sqrt(outsideSquared(box)),
    )
}

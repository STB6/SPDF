package com.stb6.spdf.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.unit.dp
import com.stb6.spdf.pdf.TextQuad
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color

private class PageSelectionGeometry(
    val widthPt: Float,
    val heightPt: Float,
    val quads: List<TextQuad>,
    val selection: TextSelection,
    val startReference: Offset?,
    val endReference: Offset?,
)

private class HandleSpot(val anchor: Offset, val degrees: Float, val center: Offset, val reference: Offset)

// Cache geometry in page coordinates; read per-frame transforms outside composition.
@Composable
internal fun BoxScope.SelectionLayer(
    selection: TextSelection,
    documentToken: Long,
    viewModel: ReaderViewModel,
    driver: SelectionDriver,
    layout: CanvasLayout,
    vertical: Boolean,
    viewportW: Float,
    viewportH: Float,
    busy: Boolean,
    zooming: Boolean,
    contentBounds: Rect,
    onCopySelection: () -> Unit,
) {
    val canvas = viewModel.canvas
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val toolbar = LocalTextToolbar.current
    val handleRadius = with(density) { HandleRadius.toPx() }
    val handleTouchRadius = with(density) { 40.dp.toPx() }

    val pageRange = selection.start.page..selection.end.page
    val visiblePages by remember(layout, vertical, viewportW, viewportH, selection) {
        derivedStateOf {
            layout.visibleBoxes(
                canvas.scale.floatValue, canvas.offsetX.floatValue, canvas.offsetY.floatValue,
                vertical, viewportW.toInt(), viewportH.toInt(), 0f,
            ).mapNotNull { it.index.takeIf { index -> index in pageRange } }
        }
    }

    // Retain old geometry until replacements arrive to avoid flashing while dragging.
    val geometry = remember(documentToken) { mutableStateMapOf<Int, PageSelectionGeometry>() }
    LaunchedEffect(selection, visiblePages) {
        for (page in visiblePages) {
            val info = viewModel.exactPageInfo(page) ?: continue
            val text = viewModel.pageText(page, includeEmpty = true) ?: continue
            val range = selection.rangeOn(page, text.charCount)
            val quads = range?.let(text::quadsFor).orEmpty()
            fun reference(atStart: Boolean): Offset? {
                val range = range ?: return null
                val run = if (atStart) {
                    text.runs.firstOrNull { it.last >= range.first && it.first <= range.last }
                } else {
                    text.runs.lastOrNull { it.last >= range.first && it.first <= range.last }
                } ?: return null
                val index = if (atStart) maxOf(range.first, run.first) else minOf(range.last, run.last)
                val q = run.quad(index, index)
                return Offset((q.startTopX + q.endTopX + q.startBottomX + q.endBottomX) / 4f,
                    (q.startTopY + q.endTopY + q.startBottomY + q.endBottomY) / 4f)
            }
            geometry[page] = PageSelectionGeometry(
                info.widthPt.toFloat(), info.heightPt.toFloat(), quads, selection,
                if (page == selection.start.page) reference(true) else null,
                if (page == selection.end.page) reference(false) else null,
            )
        }
        geometry.keys.filter { it !in pageRange || it !in visiblePages }.forEach { geometry.remove(it) }
    }

    // Transform axes separately while estimated and actual page aspect ratios differ.
    fun pageTransform(page: Int): FloatArray? {
        val g = geometry[page] ?: return null
        val box = layout.boxOf(page) ?: return null
        val r = layout.screenRect(box, canvas.scale.floatValue, canvas.offsetX.floatValue, canvas.offsetY.floatValue)
        val sx = (r[2] - r[0]) / g.widthPt
        val sy = (r[3] - r[1]) / g.heightPt
        if (sx <= 0f || sy <= 0f) return null
        return floatArrayOf(r[0], r[1], sx, sy)
    }

    fun handles(currentSelection: TextSelection): Pair<HandleSpot?, HandleSpot?> {
        if (driver.mode == SelectionDragMode.WORD) return null to null
        fun spot(page: Int, pickQuad: (List<TextQuad>) -> TextQuad?, atStart: Boolean): HandleSpot? {
            if (page !in visiblePages) return null
            val g = geometry[page] ?: return null
            if (driver.mode != SelectionDragMode.HANDLE && g.selection != currentSelection) return null
            val q = pickQuad(g.quads) ?: return null
            val t = pageTransform(page) ?: return null
            fun toScreen(x: Float, y: Float) = Offset(t[0] + x * t[2], t[1] + y * t[3])
            val anchor = if (atStart) toScreen(q.startBottomX, q.startBottomY) else toScreen(q.endBottomX, q.endBottomY)
            val glyph = (if (atStart) g.startReference else g.endReference) ?: return null
            val reference = toScreen(glyph.x, glyph.y)
            if (!contentBounds.contains(reference)) return null
            val a = toScreen(q.startTopX, q.startTopY)
            val b = toScreen(q.endTopX, q.endTopY)
            val degrees = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
            val center = anchor + rotateBy(Offset(if (atStart) -handleRadius else handleRadius, handleRadius), degrees)
            return HandleSpot(anchor, degrees, center, reference)
        }
        return spot(currentSelection.start.page, { it.firstOrNull() }, atStart = true) to
            spot(currentSelection.end.page, { it.lastOrNull() }, atStart = false)
    }

    // Capture selection explicitly so hit testing follows updated endpoints.
    val liveHandles by rememberUpdatedState { handles(selection) }
    val liveContentBounds by rememberUpdatedState(contentBounds)
    val liveZooming by rememberUpdatedState(zooming)

    // Consume only handle hits so the canvas still receives pan and pinch gestures.
    Box(
        modifier = Modifier
            .matchParentSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (liveZooming || !liveContentBounds.contains(down.position)) return@awaitEachGesture
                    val (start, end) = liveHandles()
                    val toStart = start?.let { (down.position - it.center).getDistance() } ?: Float.MAX_VALUE
                    val toEnd = end?.let { (down.position - it.center).getDistance() } ?: Float.MAX_VALUE
                    if (minOf(toStart, toEnd) > handleTouchRadius) return@awaitEachGesture
                    val movingStart = toStart <= toEnd
                    val handle = (if (movingStart) start else end) ?: return@awaitEachGesture
                    driver.beginHandleDrag(movingStart, down.position, handle.reference)
                    down.consume()
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (liveZooming || event.changes.count { it.pressed } > 1) break
                            val moved = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!moved.pressed) break
                            moved.consume()
                            driver.dragTo(moved.position)
                        }
                    } finally {
                        driver.endDrag()
                    }
                }
            },
    )

    val quadPath = remember { Path() }
    Canvas(Modifier.matchParentSize()) {
        for (page in visiblePages) {
            val g = geometry[page] ?: continue
            val t = pageTransform(page) ?: continue
            g.quads.forEach { q ->
                val path = quadPath.apply {
                    reset()
                    moveTo(t[0] + q.startTopX * t[2], t[1] + q.startTopY * t[3])
                    lineTo(t[0] + q.endTopX * t[2], t[1] + q.endTopY * t[3])
                    lineTo(t[0] + q.endBottomX * t[2], t[1] + q.endBottomY * t[3])
                    lineTo(t[0] + q.startBottomX * t[2], t[1] + q.startBottomY * t[3])
                    close()
                }
                drawPath(path, SelectionFill)
            }
        }
        val (start, end) = handles(selection)
        clipRect(contentBounds.left, contentBounds.top, contentBounds.right, contentBounds.bottom) {
            listOf(start to true, end to false).forEach { (spot, squareOnRight) ->
                if (spot == null) return@forEach
                rotate(spot.degrees, pivot = spot.anchor) {
                    val center = spot.anchor + Offset(if (squareOnRight) -handleRadius else handleRadius, handleRadius)
                    drawCircle(SelectionHandle, handleRadius, center)
                    drawRect(
                        color = SelectionHandle,
                        topLeft = Offset(if (squareOnRight) center.x else center.x - handleRadius, center.y - handleRadius),
                        size = Size(handleRadius, handleRadius),
                    )
                }
            }
        }
    }

    // The system toolbar expects root-view coordinates.
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.matchParentSize().onGloballyPositioned { rootOrigin = it.localToRoot(Offset.Zero) })

    fun visibleSelectionRect(): Rect? {
        if (visiblePages.any { geometry[it]?.selection != selection }) return null
        val textBounds = buildList {
            for (page in visiblePages) {
                val g = geometry[page] ?: continue
                val t = pageTransform(page) ?: continue
                for (q in g.quads) add(Rect(t[0] + q.minX * t[2], t[1] + q.minY * t[3],
                    t[0] + q.maxX * t[2], t[1] + q.maxY * t[3]))
            }
        }
        val (start, end) = handles(selection)
        val handleBounds = listOfNotNull(start, end).map {
            Rect(minOf(it.center.x - handleRadius, it.anchor.x), minOf(it.center.y - handleRadius, it.anchor.y),
                maxOf(it.center.x + handleRadius, it.anchor.x), maxOf(it.center.y + handleRadius, it.anchor.y))
        }
        return selectionOverlayBounds(textBounds, handleBounds, contentBounds)?.translate(rootOrigin)
    }

    val liveCopySelection by rememberUpdatedState(onCopySelection)
    val showMenu = !busy && !zooming && !driver.dragging
    // Observe actual bounds, not page count: moving within a page still moves the toolbar.
    val liveSelectionRect by rememberUpdatedState { visibleSelectionRect() }
    LaunchedEffect(showMenu, selection) {
        if (!showMenu) {
            toolbar.hide()
            return@LaunchedEffect
        }
        snapshotFlow { liveSelectionRect() }.collect { rect ->
            if (rect == null) {
                toolbar.hide()
                return@collect
            }
            toolbar.showMenu(
                rect = rect,
                onCopyRequested = { liveCopySelection() },
                onSelectAllRequested = {
                    scope.launch { viewModel.selectWholePage(selection.start.page) }
                },
            )
        }
    }
    DisposableEffect(toolbar) {
        onDispose { toolbar.hide() }
    }
}

private fun rotateBy(v: Offset, degrees: Float): Offset {
    val radians = Math.toRadians(degrees.toDouble())
    val c = cos(radians).toFloat()
    val s = sin(radians).toFloat()
    return Offset(v.x * c - v.y * s, v.x * s + v.y * c)
}

// Selection colors must contrast with document content independently of the app theme.
private val SelectionFill = Color(0x553B82F6)
private val SelectionHandle = Color(0xFF3B82F6)

private val HandleRadius = 11.dp

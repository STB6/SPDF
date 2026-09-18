package com.stb6.spdf.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.stb6.spdf.R
import com.stb6.spdf.data.Axis
import com.stb6.spdf.data.ReaderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import com.stb6.spdf.ui.theme.Dimens
import kotlin.math.roundToInt

@Composable
fun CanvasReader(
    state: ReaderUiState.Ready,
    viewModel: ReaderViewModel,
    settings: ReaderSettings,
    colorFilter: ColorFilter?,
    onOpenUrl: (String) -> Unit,
    onBusyChange: (Boolean) -> Unit,
    onCopySelection: () -> Unit,
) {
    val vertical = settings.scrollDirection.axis == Axis.VERTICAL
    val reversed = settings.scrollDirection.reversed
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val selection by viewModel.selection.collectAsState()

    var scale by viewModel.canvas.scale
    var offsetX by viewModel.canvas.offsetX
    var offsetY by viewModel.canvas.offsetY

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasColor)
            .clipToBounds(),
    ) {
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        val topInset = with(density) {
            (WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + Dimens.TopBarHeight).toPx()
        }
        // Three-button navigation may occupy either side in landscape.
        val layoutDirection = LocalLayoutDirection.current
        val navigationBars = WindowInsets.navigationBars
        val bottomInset = navigationBars.getBottom(density).toFloat()
        val leftInset = navigationBars.getLeft(density, layoutDirection).toFloat()
        val rightInset = navigationBars.getRight(density, layoutDirection).toFloat()

        val metrics by produceState<PageMetrics?>(null, state.token, state.pageCount) {
            withContext(Dispatchers.Default) {
                collectPageMetrics(state.pageCount, viewModel::exactPageInfo) { value = it }
            }
        }
        val pages = metrics ?: return@BoxWithConstraints

        val layout = remember(pages.sizes, vertical, reversed) {
            buildCanvasLayout(pages.sizes, vertical, reversed, PAGE_GAP_PT)
        }

        val crossViewport =
            (if (vertical) vw - leftInset - rightInset else vh - topInset - bottomInset)
                .coerceAtLeast(1f)

        val fitScale = remember(pages.standard, vw, vh, topInset, bottomInset, leftInset, rightInset) {
            val availW = (vw - leftInset - rightInset).coerceAtLeast(1f)
            val availH = (vh - topInset - bottomInset).coerceAtLeast(1f)
            minOf(availW / pages.standard.widthPt, availH / pages.standard.heightPt)
        }

        // Preserve fit-to-page even when the cross-axis minimum would be larger.
        val minScale = remember(pages, crossViewport, vertical, fitScale) {
            val maxCross = (if (vertical) pages.maxWidthPt else pages.maxHeightPt).coerceAtLeast(1)
            minOf(crossViewport / maxCross * MIN_PAGE_CROSS_FRACTION, fitScale)
        }

        val maxScale = (fitScale * MAX_TOTAL_ZOOM).coerceAtLeast(minScale)

        val rules = remember(
            layout, vertical, reversed, vw, vh, topInset, bottomInset, leftInset, rightInset,
        ) {
            CanvasRules(
                layout = layout,
                vertical = vertical,
                reversed = reversed,
                viewportW = vw,
                viewportH = vh,
                topInset = topInset,
                bottomInset = bottomInset,
                leftInset = leftInset,
                rightInset = rightInset,
            )
        }

        fun alignTo(pageIndex: Int, s: Float) {
            val at = rules.placeFor(layout.boxOf(pageIndex), s)
            offsetX = at.x
            offsetY = at.y
        }

        val motion = rememberCanvasMotion(viewModel.canvas)
        LaunchedEffect(motion.busy) { onBusyChange(motion.busy) }

        val canvas = viewModel.canvas
        var lastPage by canvas.lastPage

        val fingerRadius = with(density) { FINGER_RADIUS.toPx() }
        val driver = remember(viewModel) { SelectionDriver(scope, viewModel, haptics, fingerRadius) }
        SideEffect {
            driver.locate = { pos ->
                locateOnCanvas(pos, layout, scale, offsetX, offsetY, vertical, vw, vh, viewModel)
            }
        }

        val mainOf = { b: PageBox -> if (vertical) b.top else b.left }
        val crossOf = { b: PageBox -> if (vertical) b.left else b.top }
        // Persist layout anchors across navigation, where the reader leaves composition.
        fun pinAnchors(box: PageBox?) {
            canvas.anchorMain = box?.let(mainOf) ?: Float.NaN
            canvas.anchorCross = box?.let(crossOf) ?: Float.NaN
        }
        val structure = CanvasStructure(vertical, reversed, vw, vh, topInset, bottomInset, leftInset, rightInset)
        val settleRequest by viewModel.settleRequest.collectAsState()
        LaunchedEffect(settleRequest) {
            if (settleRequest == 0L) return@LaunchedEffect
            motion.cancelFling()
            rules.restingOffset(Offset(offsetX, offsetY), scale)?.let {
                offsetX = it.x
                offsetY = it.y
            }
        }

        val pendingJump by viewModel.pendingJump.collectAsState()
        LaunchedEffect(pendingJump, layout) {
            val jump = pendingJump ?: return@LaunchedEffect
            motion.cancelFling()
            val box = layout.boxOf(jump.pageIndex)
            rules.centerOnPage(box, scale)?.let {
                offsetX = it.x
                offsetY = it.y
            }
            lastPage = jump.pageIndex
            // Update the page and its layout anchor together to avoid jumps on metric updates.
            pinAnchors(box)
            viewModel.consumeJump(jump)
        }

        // Metric refinement must preserve the current page position and absolute scale.
        LaunchedEffect(layout, fitScale, structure) {
            val first = offsetX.isNaN() || offsetY.isNaN()
            val lastStructure = canvas.lastStructure
            val structureChanged = lastStructure != null && lastStructure != structure
            val box = layout.boxOf(lastPage)

            if (first) {
                scale = fitScale
                alignTo(0, fitScale)
            } else if (structureChanged) {
                val at = rules.centerOnPage(box, scale) ?: rules.placeFor(box, scale)
                offsetX = at.x
                offsetY = at.y
            } else if (box != null && !canvas.anchorMain.isNaN()) {
                val shift = (mainOf(box) - canvas.anchorMain) * scale
                if (vertical) offsetY -= shift else offsetX -= shift
                if (!canvas.anchorCross.isNaN()) {
                    val crossShift = (crossOf(box) - canvas.anchorCross) * scale
                    if (vertical) offsetX -= crossShift else offsetY -= crossShift
                }
                if (!motion.gestureActive) {
                    rules.restingOffset(Offset(offsetX, offsetY), scale)?.let {
                        offsetX = it.x
                        offsetY = it.y
                    }
                }
            }

            motion.cancelFling()
            pinAnchors(box)
            canvas.lastStructure = structure
        }
        // Read per-frame positions through derived state, not directly in composition.
        val placed by remember(viewModel.canvas) {
            derivedStateOf { !viewModel.canvas.offsetX.floatValue.isNaN() && !viewModel.canvas.offsetY.floatValue.isNaN() }
        }
        if (!placed) return@BoxWithConstraints

        LaunchedEffect(minScale, maxScale, motion.busy) {
            if (motion.busy) return@LaunchedEffect
            val target = scale.coerceIn(minScale, maxScale)
            if (target == scale) return@LaunchedEffect
            motion.animateZoomTo(target, Offset(vw / 2f, vh / 2f), rules)
        }

        var renderScale by remember(state.token) { mutableFloatStateOf(Float.NaN) }
        LaunchedEffect(state.token, motion) {
            val policy = RenderScalePolicy()
            snapshotFlow { viewModel.canvas.scale.floatValue to motion.zooming }.collect { (scale, zooming) ->
                renderScale = policy.update(scale, zooming)
            }
        }

        suspend fun followLinkOrDismiss(pos: Offset) {
            if (viewModel.selection.value != null) {
                viewModel.clearSelection()
                return
            }
            val link = findLinkOnCanvas(pos, layout, scale, offsetX, offsetY, viewModel)
                ?: return
            when {
                link.uri != null -> onOpenUrl(link.uri)
                link.destPage != null -> viewModel.jumpToPage(link.destPage)
            }
        }

        val currentPage by remember(layout, vertical, vw, vh) {
            derivedStateOf {
                layout.pageAtCenter(scale, offsetX, offsetY, vertical, vw.toInt(), vh.toInt())
            }
        }
        LaunchedEffect(currentPage) {
            lastPage = currentPage
            pinAnchors(layout.boxOf(currentPage))
            viewModel.onCurrentPageChange(currentPage)
        }
        val visible by remember(layout, vertical, vw, vh) {
            derivedStateOf {
                layout.visibleBoxes(scale, offsetX, offsetY, vertical, vw.toInt(), vh.toInt(), 0f)
            }
        }
        val prefetchPages by remember(layout, visible) {
            derivedStateOf {
                if (visible.isEmpty() || !scale.isFinite()) emptyList() else buildList {
                    val first = visible.minOf { it.index }
                    val last = visible.maxOf { it.index }
                    for (distance in 1..4) {
                        for (index in listOf(first - distance, last + distance)) {
                            val box = layout.boxOf(index) ?: continue
                            val width = TilePool.baseWidthFor(
                                (box.width * scale).roundToInt(),
                                (box.height * scale).roundToInt(),
                            )
                            if (width > 0) add(index to width)
                        }
                    }
                }
            }
        }
        LaunchedEffect(state.token, prefetchPages) {
            viewModel.tiles.prefetchLowRes(prefetchPages)
        }
        val zoomed by remember(viewModel.canvas, fitScale) {
            derivedStateOf { !atFitScale(viewModel.canvas.scale.floatValue, fitScale) }
        }
        BackHandler(
            enabled = settings.backResetsZoom && zoomed && selection == null,
        ) {
            motion.animateZoomTo(fitScale, Offset(vw / 2f, vh / 2f), rules)
        }

        val pageDescription = stringResource(R.string.reader_page_accessibility, currentPage + 1, state.pageCount)
        val previousPageLabel = stringResource(R.string.reader_previous_page)
        val nextPageLabel = stringResource(R.string.reader_next_page)
        val zoomInLabel = stringResource(R.string.reader_zoom_in)
        val resetZoomLabel = stringResource(R.string.reader_reset_zoom)
        val selectPageLabel = stringResource(R.string.selection_select_page)
        val copyLabel = stringResource(R.string.selection_copy)
        val clearLabel = stringResource(R.string.selection_clear)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = pageDescription
                    customActions = buildList {
                        if (currentPage > 0) add(CustomAccessibilityAction(previousPageLabel) {
                            viewModel.jumpToPage(currentPage - 1); true
                        })
                        if (currentPage + 1 < state.pageCount) add(CustomAccessibilityAction(nextPageLabel) {
                            viewModel.jumpToPage(currentPage + 1); true
                        })
                        add(CustomAccessibilityAction(zoomInLabel) {
                            motion.animateZoomTo((scale * 2).coerceAtMost(maxScale), Offset(vw / 2, vh / 2), rules)
                            true
                        })
                        add(CustomAccessibilityAction(resetZoomLabel) {
                            motion.animateZoomTo(fitScale, Offset(vw / 2, vh / 2), rules); true
                        })
                        add(CustomAccessibilityAction(selectPageLabel) {
                            scope.launch { viewModel.selectWholePage(currentPage) }; true
                        })
                        if (selection != null) {
                            add(CustomAccessibilityAction(copyLabel) { onCopySelection(); true })
                            add(CustomAccessibilityAction(clearLabel) { viewModel.clearSelection(); true })
                        }
                    }
                }
                .canvasGestures(
                    motion = motion,
                    rules = rules,
                    fitScale = fitScale,
                    minScale = minScale,
                    maxScale = maxScale,
                    handleDragging = { driver.dragging },
                    onLongPress = driver::beginLongPress,
                    onLongPressDrag = driver::dragTo,
                    onLongPressEnd = driver::endDrag,
                    onTap = { followLinkOrDismiss(it) },
                    layoutKey = layout,
                ),
        ) {
            visible.forEach { box ->
                    key(box.index) {
                        PageTiles(
                            documentToken = state.token,
                            box = box,
                            screen = remember(layout, box) {
                                { layout.screenRect(box, scale, offsetX, offsetY) }
                            },
                            renderScale = renderScale,
                            viewportW = vw,
                            viewportH = vh,
                            viewModel = viewModel,
                            colorFilter = colorFilter,
                        )
                    }
                }

            selection?.let { sel ->
                SelectionLayer(
                    selection = sel,
                    documentToken = state.token,
                    viewModel = viewModel,
                    driver = driver,
                    layout = layout,
                    vertical = vertical,
                    viewportW = vw,
                    viewportH = vh,
                    busy = motion.busy,
                    zooming = motion.zooming,
                    contentBounds = Rect(leftInset, topInset, vw - rightInset, vh - bottomInset),
                    onCopySelection = onCopySelection,
                )
            }
        }

        // Use the unobscured reading bounds, not the full-screen canvas.
        val liveRules by rememberUpdatedState(rules)
        val liveLeft by rememberUpdatedState(leftInset)
        val liveTop by rememberUpdatedState(topInset)
        val liveRight by rememberUpdatedState(vw - rightInset)
        val liveBottom by rememberUpdatedState(vh - bottomInset)
        LaunchedEffect(driver.dragging, density) {
            if (!driver.dragging) return@LaunchedEffect
            val edgeScroll = SelectionEdgeScroll(density.density)
            while (true) {
                withFrameNanos {}
                val p = driver.lastPointer ?: continue
                val (dx, dy) = edgeScroll.delta(p.x, p.y, liveLeft, liveTop, liveRight, liveBottom)
                if (dx == 0f && dy == 0f) continue
                val nx = liveRules.glide(offsetX, -dx, scale, axisX = true)
                val ny = liveRules.glide(offsetY, -dy, scale, axisX = false)
                if (nx == offsetX && ny == offsetY) continue
                offsetX = nx
                offsetY = ny
                driver.dragTo(p)
            }
        }

    }
}

private val FINGER_RADIUS = 20.dp

internal data class CanvasStructure(
    val vertical: Boolean,
    val reversed: Boolean,
    val viewportW: Float,
    val viewportH: Float,
    val topInset: Float,
    val bottomInset: Float,
    val leftInset: Float,
    val rightInset: Float,
)

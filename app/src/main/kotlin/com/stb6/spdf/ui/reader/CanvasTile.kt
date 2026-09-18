package com.stb6.spdf.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.stb6.spdf.Perf
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
internal fun PageTiles(
    // Include document identity in page keys to prevent cross-document image reuse.
    documentToken: Long,
    box: PageBox,
    screen: () -> FloatArray,
    renderScale: Float,
    viewportW: Float,
    viewportH: Float,
    viewModel: ReaderViewModel,
    colorFilter: ColorFilter?,
) {
    val tiles = viewModel.tiles

    var baseWidthWanted by remember(documentToken, box.index) {
        val r = screen()
        val width = r[2] - r[0]
        val height = r[3] - r[1]
        mutableStateOf(if (width.isFinite() && height.isFinite()) {
            TilePool.baseWidthFor(width.roundToInt(), height.roundToInt())
        } else 0)
    }
    val base = remember(documentToken, box.index) { BaseImageSlot(tiles, box.index) }
    LaunchedEffect(documentToken, box.index, box.width, box.height, baseWidthWanted) {
        if (baseWidthWanted > 0) base.fetch(baseWidthWanted) else base.clear()
    }
    val baseBitmap = base.bitmap
    val hasBase = baseBitmap != null

    var wanted by remember(documentToken, box.index) { mutableStateOf<ImageSpec?>(null) }
    var baseOnly by remember(documentToken, box.index) { mutableStateOf(false) }
    val liveScreen by rememberUpdatedState(screen)
    val liveBox by rememberUpdatedState(box)
    val liveViewportW by rememberUpdatedState(viewportW)
    val liveViewportH by rememberUpdatedState(viewportH)
    LaunchedEffect(documentToken, box.index, renderScale, baseBitmap) {
        snapshotFlow { Triple(liveScreen(), liveBox, liveViewportW to liveViewportH) }.collect { (r, b, viewport) ->
            val pwFloat = r[2] - r[0]
            val phFloat = r[3] - r[1]
            // Document replacement briefly exposes NaN offsets; roundToInt would throw.
            if (!pwFloat.isFinite() || !phFloat.isFinite()) return@collect
            val pw = pwFloat.roundToInt()
            val ph = phFloat.roundToInt()
            if (pw <= 0 || ph <= 0) return@collect
            val baseWidth = TilePool.baseWidthFor(pw, ph)
            if (baseWidth != baseWidthWanted) baseWidthWanted = baseWidth
            val inViewport = r[2] > 0f && r[0] < viewport.first && r[3] > 0f && r[1] < viewport.second
            val candidate = if (inViewport && renderScale.isFinite()) {
                nextSpec(wanted, b, renderScale, r, pw, ph, viewport.first, viewport.second)
            } else null
            // A coarse zoom tier must not cover a sharper cached base image.
            val smallEnough = baseBitmap != null && (
                (pw <= baseBitmap.width && ph <= baseBitmap.height) ||
                    (candidate != null && candidate.pageWidthPx <= baseBitmap.width &&
                        candidate.pageHeightPx <= baseBitmap.height)
                )
            if (smallEnough != baseOnly) baseOnly = smallEnough
            val next = if (smallEnough) null else candidate
            if (next != wanted) wanted = next
        }
    }

    // Displayed leases outlive cancelled replacement requests.
    val exact = remember(documentToken, box.index) { ImageSlot(tiles) }
    val backdrop = remember(documentToken, box.index) { ImageSlot(tiles) }
    DisposableEffect(documentToken, box.index) {
        onDispose {
            exact.clear()
            backdrop.clear()
        }
    }

    LaunchedEffect(documentToken, box.index, wanted, hasBase, baseOnly) {
        val spec = wanted
        if (spec == null) {
            if (baseOnly) {
                exact.clear()
                backdrop.clear()
            }
            return@LaunchedEffect
        }
        if (!hasBase) return@LaunchedEffect
        val r = liveScreen()
        val dx = (r[0] + r[2]) / 2f - liveViewportW / 2f
        val dy = (r[1] + r[3]) / 2f - liveViewportH / 2f
        val priority = -sqrt(dx * dx + dy * dy).toLong().coerceAtMost(Long.MAX_VALUE / 4)
        exact.fetch(box.index, spec, priority)
        if (spec.isPatch) {
            backdrop.fetch(box.index, backdropSpec(liveBox), priority - 1)
        } else {
            backdrop.clear()
        }
    }

    val liveFilter by rememberUpdatedState(colorFilter)
    Canvas(Modifier.fillMaxSize()) {
        val r = liveScreen()
        if (!r[0].isFinite() || !r[1].isFinite() || !r[2].isFinite() || !r[3].isFinite()) return@Canvas
        val width = (r[2] - r[0]).roundToInt()
        val height = (r[3] - r[1]).roundToInt()
        if (width <= 0 || height <= 0) return@Canvas
        val origin = IntOffset(r[0].roundToInt(), r[1].roundToInt())
        val low = base.bitmap
        if (low != null && !low.isRecycled) {
            drawImage(low.asImageBitmap(), dstOffset = origin, dstSize = IntSize(width, height), colorFilter = liveFilter)
        } else {
            drawRect(
                color = PagePlaceholderColor,
                topLeft = Offset(origin.x.toFloat(), origin.y.toFloat()),
                size = Size(width.toFloat(), height.toFloat()),
            )
        }
        drawImage(backdrop.image, origin, width, height, liveFilter)
        drawImage(exact.image, origin, width, height, liveFilter)
    }
}

/** Owns the first-frame cache lease, including abandoned compositions. */
internal class BaseImageSlot(private val tiles: TilePool, private val page: Int) : RememberObserver {
    var bitmap: Bitmap? by mutableStateOf(tiles.acquireCachedBase(page))
        private set

    suspend fun fetch(width: Int) {
        val next = tiles.acquireBase(page, width) ?: return
        val previous = bitmap
        bitmap = next
        previous?.let(tiles::releaseTile)
    }

    fun clear() {
        val previous = bitmap
        bitmap = null
        previous?.let(tiles::releaseTile)
    }

    override fun onRemembered() = Unit
    override fun onForgotten() = clear()
    override fun onAbandoned() = clear()
}

internal data class ImageSpec(
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
) {
    val isPatch: Boolean get() = w < pageWidthPx || h < pageHeightPx

    // Reuse patches until the viewport crosses half the padding; page edges have no padding.
    fun innerContains(l: Float, t: Float, r: Float, b: Float, viewW: Float, viewH: Float): Boolean {
        val marginX = (w - viewW) / 4f
        val marginY = (h - viewH) / 4f
        val innerL = if (x == 0) 0f else x + marginX
        val innerR = if (x + w >= pageWidthPx) pageWidthPx.toFloat() else x + w - marginX
        val innerT = if (y == 0) 0f else y + marginY
        val innerB = if (y + h >= pageHeightPx) pageHeightPx.toFloat() else y + h - marginY
        return l >= innerL && r <= innerR && t >= innerT && b <= innerB
    }

    companion object {
        fun wholePage(widthPx: Int, heightPx: Int) = ImageSpec(widthPx, heightPx, 0, 0, widthPx, heightPx)
    }
}

private class HeldImage(val spec: ImageSpec, val bitmap: Bitmap)

/** Acquire the replacement before releasing the displayed lease. */
private class ImageSlot(private val tiles: TilePool) {
    var image: HeldImage? by mutableStateOf(null)
        private set

    suspend fun fetch(pageIndex: Int, spec: ImageSpec, priority: Long) {
        if (image?.spec == spec) return
        Perf.count("exact.request")
        val started = System.nanoTime()
        val bitmap = tiles.acquireTile(
            pageIndex,
            spec.x, spec.y,
            spec.pageWidthPx, spec.pageHeightPx,
            spec.w, spec.h,
            priority = priority,
        ) ?: return
        Perf.record("exact.ready", System.nanoTime() - started)
        val previous = image
        image = HeldImage(spec, bitmap)
        previous?.let { tiles.releaseTile(it.bitmap) }
    }

    fun clear() {
        image?.let { tiles.releaseTile(it.bitmap) }
        image = null
    }
}

private fun DrawScope.drawImage(
    held: HeldImage?,
    origin: IntOffset,
    width: Int,
    height: Int,
    filter: ColorFilter?,
) {
    val image = held ?: return
    if (image.bitmap.isRecycled) return
    val s = image.spec
    fun px(v: Int) = (width.toLong() * v / s.pageWidthPx).toInt()
    fun py(v: Int) = (height.toLong() * v / s.pageHeightPx).toInt()
    val dstW = px(s.x + s.w) - px(s.x)
    val dstH = py(s.y + s.h) - py(s.y)
    if (dstW <= 0 || dstH <= 0) return
    drawImage(
        image = image.bitmap.asImageBitmap(),
        dstOffset = IntOffset(origin.x + px(s.x), origin.y + py(s.y)),
        dstSize = IntSize(dstW, dstH),
        colorFilter = filter,
    )
}

internal fun nextSpec(
    current: ImageSpec?,
    box: PageBox,
    stableScale: Float,
    screen: FloatArray,
    pw: Int,
    ph: Int,
    viewportW: Float,
    viewportH: Float,
): ImageSpec? {
    if (screen[2] <= 0f || screen[0] >= viewportW || screen[3] <= 0f || screen[1] >= viewportH) return null
    val requestedW = (box.width * stableScale).roundToInt().coerceAtLeast(1)
    val requestedH = (box.height * stableScale).roundToInt().coerceAtLeast(1)
    if (requestedW.toLong() * requestedH <= EXACT_MAX_PIXELS && requestedW <= MAX_SIDE_PX && requestedH <= MAX_SIDE_PX) {
        return ImageSpec.wholePage(requestedW, requestedH)
    }
    val viewAtScaleW = viewportW.toDouble() * requestedW / pw + 2
    val viewAtScaleH = viewportH.toDouble() * requestedH / ph + 2
    // Downsample the coordinate space together to preserve full viewport coverage.
    val resolution = minOf(
        1.0,
        sqrt(EXACT_MAX_PIXELS / (viewAtScaleW * viewAtScaleH)),
        MAX_SIDE_PX / viewAtScaleW,
        MAX_SIDE_PX / viewAtScaleH,
    )
    val pageW = (requestedW * resolution).toInt().coerceAtLeast(1)
    val pageH = (requestedH * resolution).toInt().coerceAtLeast(1)
    if (pageW.toLong() * pageH <= EXACT_MAX_PIXELS && pageW <= MAX_SIDE_PX && pageH <= MAX_SIDE_PX) {
        return ImageSpec.wholePage(pageW, pageH)
    }

    val toX = pageW.toFloat() / pw
    val toY = pageH.toFloat() / ph
    val visL = (-screen[0]).coerceAtLeast(0f) * toX
    val visT = (-screen[1]).coerceAtLeast(0f) * toY
    val visR = (viewportW - screen[0]).coerceAtMost(pw.toFloat()) * toX
    val visB = (viewportH - screen[1]).coerceAtMost(ph.toFloat()) * toY
    if (visR <= visL || visB <= visT) return null
    val viewW = viewportW * toX
    val viewH = viewportH * toY

    if (current != null && current.isPatch &&
        current.pageWidthPx == pageW && current.pageHeightPx == pageH &&
        current.innerContains(visL, visT, visR, visB, viewW, viewH)
    ) {
        return current
    }

    val ratio = sqrt(EXACT_MAX_PIXELS.toDouble() / (viewW.toDouble() * viewH)).toFloat()
    val margin = ((ratio - 1f) / 2f).coerceIn(0f, MAX_MARGIN_VIEWPORTS)
    val w = (viewW * (1f + 2f * margin)).toInt().coerceIn(1, minOf(pageW, MAX_SIDE_PX))
    val h = (viewH * (1f + 2f * margin)).toInt()
        .coerceIn(1, minOf(pageH, MAX_SIDE_PX, (EXACT_MAX_PIXELS / w).toInt()))
    val x = ((visL + visR) / 2f - w / 2f).roundToInt().coerceIn(0, pageW - w)
    val y = ((visT + visB) / 2f - h / 2f).roundToInt().coerceIn(0, pageH - h)
    return ImageSpec(pageW, pageH, x, y, w, h)
}

// Derive from PDF points so the backdrop cache key stays stable across zoom levels.
internal fun backdropSpec(box: PageBox): ImageSpec {
    val k = minOf(
        sqrt(EXACT_MAX_PIXELS.toDouble() / (box.width.toDouble() * box.height)).toFloat(),
        MAX_SIDE_PX / box.width,
        MAX_SIDE_PX / box.height,
    )
    val w = (box.width * k).roundToInt().coerceAtLeast(1)
    val h = (box.height * k).roundToInt().coerceAtLeast(1)
    return ImageSpec.wholePage(w, h)
}

internal const val EXACT_MAX_PIXELS = 6_000_000L

private const val MAX_MARGIN_VIEWPORTS = 1f

private const val MAX_SIDE_PX = TilePool.MAX_BITMAP_SIDE

internal suspend fun findLinkOnCanvas(
    pos: Offset,
    layout: CanvasLayout,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    viewModel: ReaderViewModel,
): com.stb6.spdf.pdf.PdfLink? {
    val box = layout.boxes.firstOrNull {
        layout.screenRect(it, scale, offsetX, offsetY) { l, t, r, b ->
            pos.x >= l && pos.x <= r && pos.y >= t && pos.y <= b
        }
    } ?: return null
    val r = layout.screenRect(box, scale, offsetX, offsetY)
    val info = viewModel.exactPageInfo(box.index) ?: return null
    // Estimated layout and actual page aspect ratios can differ; transform axes separately.
    val toPtX = info.widthPt / (r[2] - r[0])
    val toPtY = info.heightPt / (r[3] - r[1])
    return viewModel.links(box.index)
        .firstOrNull { it.bounds.contains((pos.x - r[0]) * toPtX, (pos.y - r[1]) * toPtY) }
}

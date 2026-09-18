package com.stb6.spdf.ui.reader

import androidx.compose.ui.geometry.Offset

internal class CanvasRules(
    private val layout: CanvasLayout,
    private val vertical: Boolean,
    private val reversed: Boolean,
    private val viewportW: Float,
    private val viewportH: Float,
    private val topInset: Float,
    private val bottomInset: Float,
    private val leftInset: Float,
    private val rightInset: Float,
) {
    private val availTop = topInset
    private val availBottom = viewportH - bottomInset

    private val availLeft = leftInset
    private val availRight = viewportW - rightInset

    fun bounds(scale: Float): OffsetBounds {
        val x = axisBounds(availLeft, availRight, layout.minX * scale, layout.maxX * scale)
        val y = axisBounds(availTop, availBottom, layout.minY * scale, layout.maxY * scale)
        return OffsetBounds(minX = x[0], maxX = x[1], minY = y[0], maxY = y[1])
    }

    fun placeFor(box: PageBox?, scale: Float): Offset {
        val b = bounds(scale)
        if (box == null) {
            return Offset(if (reversed && !vertical) b.minX else b.maxX, b.maxY)
        }
        return if (vertical) {
            Offset(clampX(b.maxX, b), clampY(availTop - box.top * scale, b))
        } else {
            val startEdge =
                if (reversed) availRight - box.right * scale else availLeft - box.left * scale
            Offset(clampX(startEdge, b), clampY(b.maxY, b))
        }
    }

    fun centerOnPage(box: PageBox?, scale: Float): Offset? {
        if (box == null) return null
        val b = bounds(scale)
        val x = (availLeft + availRight) / 2f - (box.left + box.width / 2f) * scale
        val y = (availTop + availBottom) / 2f - (box.top + box.height / 2f) * scale
        return Offset(clampX(x, b), clampY(y, b))
    }

    fun glide(current: Float, delta: Float, scale: Float, axisX: Boolean): Float {
        val b = bounds(scale)
        return if (axisX) clampX(current + delta, b) else clampY(current + delta, b)
    }

    fun restingOffset(current: Offset, scale: Float): Offset? {
        val b = bounds(scale)
        val target = Offset(clampX(current.x, b), clampY(current.y, b))
        return if (target == current) null else target
    }

    private fun clampX(v: Float, b: OffsetBounds) =
        v.coerceIn(minOf(b.minX, b.maxX), maxOf(b.minX, b.maxX))

    private fun clampY(v: Float, b: OffsetBounds) =
        v.coerceIn(minOf(b.minY, b.maxY), maxOf(b.minY, b.maxY))
}

/** Bounds are scaled content coordinates; the cross axis can extend below zero. */
internal fun axisBounds(lo: Float, hi: Float, contentLo: Float, contentHi: Float): FloatArray {
    val avail = hi - lo
    val content = contentHi - contentLo
    if (content <= avail) {
        val centered = lo + (avail - content) / 2f - contentLo
        return floatArrayOf(centered, centered)
    }
    return floatArrayOf(hi - contentHi, lo - contentLo)
}

internal data class OffsetBounds(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
)

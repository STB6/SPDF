package com.stb6.spdf.pdf

import android.graphics.RectF

/** Converts unrotated, y-up PDF coordinates to display points using the crop origin and /Rotate (0..3). */
internal class PageSpace(
    val rotation: Int,
    val width: Float,
    val height: Float,
    private val originX: Float = 0f,
    private val originY: Float = 0f,
) {
    fun toDisplay(x: Float, y: Float): Pair<Float, Float> {
        val px = x - originX
        val py = y - originY
        return when (rotation) {
            1 -> py to px
            2 -> (width - px) to py
            3 -> (height - py) to (width - px)
            else -> px to (height - py)
        }
    }

    fun rotateVector(x: Float, y: Float): Pair<Float, Float> = when (rotation) {
        1 -> y to x
        2 -> -x to y
        3 -> -y to -x
        else -> x to -y
    }

    fun toDisplayRect(rect: RectF): RectF {
        val (x0, y0) = toDisplay(rect.left, rect.top)
        val (x1, y1) = toDisplay(rect.right, rect.bottom)
        return RectF(x0, y0, x1, y1).apply { sort() }
    }
}

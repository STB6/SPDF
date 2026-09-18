package com.stb6.spdf.ui.reader

internal class SelectionEdgeScroll(density: Float) {
    private val band = 24f * density
    private val minimumStep = 4f * density
    private val maximumStep = 24f * density
    private var step = minimumStep
    private var lastX = 0
    private var lastY = 0

    fun delta(x: Float, y: Float, left: Float, top: Float, right: Float, bottom: Float): Pair<Float, Float> {
        if (right <= left || bottom <= top) return reset()
        fun depth(distance: Float) = ((band - distance) / band).coerceIn(0f, 1f)
        val dx = depth(right - x) - depth(x - left)
        val dy = depth(bottom - y) - depth(y - top)
        if (dx == 0f && dy == 0f) return reset()
        val dirX = dx.compareTo(0f)
        val dirY = dy.compareTo(0f)
        if (dirX != lastX || dirY != lastY) step = minimumStep
        lastX = dirX
        lastY = dirY
        val result = dx * step to dy * step
        step = (step * 1.04f).coerceAtMost(maximumStep)
        return result
    }

    private fun reset(): Pair<Float, Float> {
        step = minimumStep
        lastX = 0
        lastY = 0
        return 0f to 0f
    }
}

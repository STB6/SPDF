package com.stb6.spdf.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PageSpaceTest {

    private val w = 600f
    private val h = 800f
    private val originX = 10f
    private val originY = 20f

    private fun space(rotation: Int) = PageSpace(rotation, w, h, originX, originY)

    private fun fromDisplay(rotation: Int, dx: Float, dy: Float): Pair<Float, Float> {
        val (px, py) = when (rotation) {
            1 -> dy to dx
            2 -> (w - dx) to dy
            3 -> (w - dy) to (h - dx)
            else -> dx to (h - dy)
        }
        return (px + originX) to (py + originY)
    }

    private val points = listOf(0f to 0f, 123f to 456f, 600f to 800f, -30f to 70f)

    @Test
    fun `点变换和逆变换互逆`() {
        for (rotation in 0..3) {
            val s = space(rotation)
            for ((x, y) in points) {
                val (dx, dy) = s.toDisplay(x, y)
                val (bx, by) = fromDisplay(rotation, dx, dy)
                assertEquals("rot=$rotation x", x, bx, 1e-3f)
                assertEquals("rot=$rotation y", y, by, 1e-3f)
            }
        }
    }

    @Test
    fun `向量变换是点变换的线性部分`() {

        val vectors = listOf(1f to 0f, 0f to 1f, 13f to -27f)
        for (rotation in 0..3) {
            val s = space(rotation)
            for ((x, y) in points) {
                val (ax, ay) = s.toDisplay(x, y)
                for ((vx, vy) in vectors) {
                    val (bx, by) = s.toDisplay(x + vx, y + vy)
                    val (rx, ry) = s.rotateVector(vx, vy)
                    assertEquals("rot=$rotation dx", bx - ax, rx, 1e-3f)
                    assertEquals("rot=$rotation dy", by - ay, ry, 1e-3f)
                }
            }
        }
    }

    @Test
    fun `向量变换连用两次回到原样`() {

        for (rotation in 0..3) {
            val s = space(rotation)
            for ((vx, vy) in points) {
                val (ax, ay) = s.rotateVector(vx, vy)
                val (bx, by) = s.rotateVector(ax, ay)
                assertEquals("rot=$rotation", vx, bx, 1e-3f)
                assertEquals("rot=$rotation", vy, by, 1e-3f)
            }
        }
    }

    @Test
    fun `未旋转时只翻 Y 并减掉框原点`() {
        val (x, y) = space(0).toDisplay(110f, 120f)

        assertEquals(100f, x, 0f)
        assertEquals(700f, y, 0f)
    }

    @Test
    fun `九十度时宽高互换`() {

        val (x, y) = space(1).toDisplay(10f, 20f)
        assertEquals(0f, x, 0f)
        assertEquals(0f, y, 0f)
        val (x2, y2) = space(1).toDisplay(610f, 820f)
        assertEquals(800f, x2, 0f)
        assertEquals(600f, y2, 0f)
    }

    @Test
    fun `框原点不是零时整页平移`() {

        val shifted = PageSpace(0, w, h, 100f, 200f)
        val (x, y) = shifted.toDisplay(100f, 200f)
        assertEquals(0f, x, 0f)
        assertEquals(h, y, 0f)
    }
}

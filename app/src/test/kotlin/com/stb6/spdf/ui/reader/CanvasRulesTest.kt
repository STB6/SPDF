package com.stb6.spdf.ui.reader

import androidx.compose.ui.geometry.Offset
import com.stb6.spdf.pdf.PageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasRulesTest {

    private val pages = List(5) { PageInfo(100, 200) }

    private fun rules(
        layout: CanvasLayout,
        vertical: Boolean,
        reversed: Boolean = false,
        viewportW: Float = 200f,
        viewportH: Float = 300f,
    ) = CanvasRules(
        layout = layout,
        vertical = vertical,
        reversed = reversed,
        viewportW = viewportW,
        viewportH = viewportH,
        topInset = 0f,
        bottomInset = 0f,
        leftInset = 0f,
        rightInset = 0f,
    )

    @Test
    fun `内容装得下就钉死在正中`() {

        val b = axisBounds(0f, 100f, -10f, 10f)
        assertEquals(50f, b[0], 0f)
        assertEquals(50f, b[1], 0f)
    }

    @Test
    fun `内容装不下就在两端贴边之间`() {

        val b = axisBounds(0f, 100f, 0f, 300f)
        assertEquals(-200f, b[0], 0f)
        assertEquals(0f, b[1], 0f)
    }

    @Test
    fun `可用区间让开插入物之后仍然居中`() {

        val b = axisBounds(20f, 100f, 0f, 40f)
        assertEquals(40f, b[0], 0f)
        assertEquals(40f, b[1], 0f)
    }

    @Test
    fun `内容刚好等于可用区间时钉死`() {
        val b = axisBounds(0f, 100f, 0f, 100f)
        assertEquals(0f, b[0], 0f)
        assertEquals(0f, b[1], 0f)
    }

    @Test
    fun `已经在范围内就不用动`() {
        val layout = buildCanvasLayout(pages, vertical = true, reversed = false, gapPt = 8f)
        val r = rules(layout, vertical = true)

        assertNull(r.restingOffset(Offset(100f, 0f), 1f))
    }

    @Test
    fun `越界的两个轴一起收回来`() {
        val layout = buildCanvasLayout(pages, vertical = true, reversed = false, gapPt = 8f)
        val r = rules(layout, vertical = true)

        val back = r.restingOffset(Offset(0f, 50f), 1f)
        assertNotNull(back)
        assertEquals(100f, back!!.x, 0f)
        assertEquals(0f, back.y, 0f)

        val back2 = r.restingOffset(Offset(100f, -9999f), 1f)!!
        assertEquals(-732f, back2.y, 0f)
    }

    @Test
    fun `定位到中间某页时两个轴都居中`() {
        val layout = buildCanvasLayout(pages, vertical = true, reversed = false, gapPt = 8f)
        val r = rules(layout, vertical = true)
        val box = layout.boxOf(2)!!
        val at = r.centerOnPage(box, 1f)!!

        assertEquals(-366f, at.y, 0f)
        assertEquals(100f, at.x, 0f)
    }

    @Test
    fun `首末页的居中会退化成贴边`() {
        val layout = buildCanvasLayout(pages, vertical = true, reversed = false, gapPt = 8f)
        val r = rules(layout, vertical = true)

        assertEquals(0f, r.centerOnPage(layout.boxOf(0)!!, 1f)!!.y, 0f)

        assertEquals(-732f, r.centerOnPage(layout.boxOf(4)!!, 1f)!!.y, 0f)
    }

    @Test
    fun `页不在布局里给 null`() {
        val layout = buildCanvasLayout(pages, vertical = true, reversed = false, gapPt = 8f)
        assertNull(rules(layout, vertical = true).centerOnPage(null, 1f))
        assertNull(layout.boxOf(99))
    }

    @Test
    fun `竖排摆放让这一页的顶边贴住可用区间开头`() {
        val layout = buildCanvasLayout(pages, vertical = true, reversed = false, gapPt = 8f)
        val r = rules(layout, vertical = true)
        assertEquals(0f, r.placeFor(layout.boxOf(0), 1f).y, 0f)

        assertEquals(-208f, r.placeFor(layout.boxOf(1), 1f).y, 0f)
    }

    @Test
    fun `向左延伸时起始边是右边`() {
        val layout = buildCanvasLayout(pages, vertical = false, reversed = true, gapPt = 8f)
        val r = rules(layout, vertical = false, reversed = true)

        val box0 = layout.boxOf(0)!!
        assertEquals(432f, box0.left, 0f)

        assertEquals(-332f, r.placeFor(box0, 1f).x, 0f)
    }

    @Test
    fun `向右延伸时起始边是左边`() {
        val layout = buildCanvasLayout(pages, vertical = false, reversed = false, gapPt = 8f)
        val r = rules(layout, vertical = false)
        assertEquals(0f, r.placeFor(layout.boxOf(0), 1f).x, 0f)
    }

    @Test
    fun `没有页可摆时落到该方向的起点`() {
        val layout = buildCanvasLayout(pages, vertical = false, reversed = true, gapPt = 8f)

        val bounds = rules(layout, vertical = false, reversed = true).bounds(1f)
        assertEquals(bounds.minX, rules(layout, vertical = false, reversed = true).placeFor(null, 1f).x, 0f)

        val forward = buildCanvasLayout(pages, vertical = false, reversed = false, gapPt = 8f)
        val fr = rules(forward, vertical = false)
        assertEquals(fr.bounds(1f).maxX, fr.placeFor(null, 1f).x, 0f)
    }
}

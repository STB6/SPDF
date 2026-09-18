package com.stb6.spdf.ui.reader

import com.stb6.spdf.pdf.PageInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasLayoutTest {

    private val uniform = List(5) { PageInfo(100, 200) }

    @Test
    fun `向左延伸时页序反着摆并按 left 重排`() {
        val layout = buildCanvasLayout(uniform, vertical = false, reversed = true, gapPt = 8f)

        assertEquals(listOf(4, 3, 2, 1, 0), layout.boxes.map { it.index })
        assertTrue(layout.boxes.zipWithNext().all { (a, b) -> a.left <= b.left })

        assertEquals(432f, layout.boxOf(0)!!.left, 0f)
        assertEquals(0f, layout.boxOf(4)!!.left, 0f)

        layout.boxes.forEach { assertEquals(it, layout.boxOf(it.index)) }
    }

    @Test
    fun `不反向时列表顺序就是页序`() {
        val layout = buildCanvasLayout(uniform, vertical = false, reversed = false, gapPt = 8f)
        assertEquals(listOf(0, 1, 2, 3, 4), layout.boxes.map { it.index })
        assertEquals(0f, layout.boxOf(0)!!.left, 0f)
    }

    @Test
    fun `交叉轴以 0 为中轴对称摆放`() {
        val mixed = listOf(PageInfo(100, 200), PageInfo(400, 200))
        val layout = buildCanvasLayout(mixed, vertical = true, reversed = false, gapPt = 8f)
        assertEquals(-50f, layout.boxOf(0)!!.left, 0f)
        assertEquals(-200f, layout.boxOf(1)!!.left, 0f)

        assertEquals(-200f, layout.minX, 0f)
        assertEquals(200f, layout.maxX, 0f)
    }

    @Test
    fun `空文档给一块空布局`() {
        val layout = buildCanvasLayout(emptyList(), vertical = true, reversed = false, gapPt = 8f)
        assertTrue(layout.boxes.isEmpty())
        assertNull(layout.boxOf(0))
        assertTrue(layout.visibleBoxes(1f, 0f, 0f, true, 100, 100, 0f).isEmpty())
    }

    @Test
    fun `二分跳过主轴上已经划过去的页`() {
        val layout = buildCanvasLayout(uniform, vertical = true, reversed = false, gapPt = 8f)

        val visible = layout.visibleBoxes(1f, 100f, -400f, true, 200, 300, 0f)
        assertEquals(listOf(1, 2, 3), visible.map { it.index })
    }

    @Test
    fun `交叉轴整个错开视口的页不算可见`() {
        val layout = buildCanvasLayout(uniform, vertical = true, reversed = false, gapPt = 8f)

        val visible = layout.visibleBoxes(1f, 5000f, 0f, true, 200, 300, 0f)
        assertTrue(visible.isEmpty())
    }

    @Test
    fun `预取余量把边上那一页也算进来`() {
        val layout = buildCanvasLayout(uniform, vertical = true, reversed = false, gapPt = 8f)
        val tight = layout.visibleBoxes(1f, 100f, -400f, true, 200, 300, 0f)
        val loose = layout.visibleBoxes(1f, 100f, -400f, true, 200, 300, 300f)
        assertEquals(listOf(1, 2, 3), tight.map { it.index })
        assertEquals(listOf(0, 1, 2, 3, 4), loose.map { it.index })
    }

    @Test
    fun `反向横排的二分照样落对`() {
        val layout = buildCanvasLayout(uniform, vertical = false, reversed = true, gapPt = 8f)

        val visible = layout.visibleBoxes(1f, -332f, 0f, false, 200, 300, 0f)
        assertEquals(listOf(1, 0), visible.map { it.index })
    }

    @Test
    fun `视口中心落在哪一页就是当前页`() {
        val layout = buildCanvasLayout(uniform, vertical = true, reversed = false, gapPt = 8f)

        assertEquals(2, layout.pageAtCenter(1f, 100f, -400f, true, 200, 300))
        assertEquals(0, layout.pageAtCenter(1f, 100f, 0f, true, 200, 300))
    }

    @Test
    fun `中位数只看用到的那一段`() {

        assertEquals(5, medianOf(intArrayOf(5, 1, 9, 0, 0), 3))

        assertEquals(4, medianOf(intArrayOf(4, 2), 2))
    }

    @Test
    fun `一页都没加载时给 1 而不是 0`() {
        assertEquals(1, medianOf(intArrayOf(0, 0), 0))

        assertEquals(1, medianOf(intArrayOf(0, 0, 0), 3))
    }

    @Test
    fun `读不出来的页用标准页顶着不占尺寸池`() = runBlocking {
        val published = mutableListOf<PageMetrics>()
        collectPageMetrics(
            count = 5,
            read = { i -> if (i == 2) null else PageInfo(100 + i * 10, 200) },
            publish = { published += it },
        )
        val last = published.last()
        assertEquals(5, last.sizes.size)

        assertEquals(last.standard, last.sizes[2])

        assertEquals(130, last.standard.widthPt)
        assertEquals(200, last.standard.heightPt)
        assertEquals(140, last.maxWidthPt)
        assertEquals(200, last.maxHeightPt)
    }

    @Test
    fun `末页损坏不吞掉最后一批真值`() = runBlocking {
        val published = mutableListOf<PageMetrics>()
        collectPageMetrics(
            count = 4,
            read = { i -> if (i == 3) null else PageInfo(100, 200 + i) },
            publish = { published += it },
        )
        val last = published.last()
        assertEquals(listOf(200, 201, 202), last.sizes.take(3).map { it.heightPt })
        assertEquals(last.standard, last.sizes[3])
        assertEquals(202, last.maxHeightPt)
    }

    @Test
    fun `一页都读不出来时不发布`() = runBlocking {
        val published = mutableListOf<PageMetrics>()
        collectPageMetrics(count = 3, read = { null }, publish = { published += it })

        assertTrue(published.isEmpty())
    }
}

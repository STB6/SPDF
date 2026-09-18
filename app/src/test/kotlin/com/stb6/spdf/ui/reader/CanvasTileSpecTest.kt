package com.stb6.spdf.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasTileSpecTest {

    @Test fun offscreenPagesDoNotRequestExactImages() {
        val box = PageBox(0, 0f, 0f, 100f, 100f)
        val previous = ImageSpec.wholePage(100, 100)
        for (screen in listOf(
            floatArrayOf(-100f, 0f, 0f, 100f),
            floatArrayOf(200f, 0f, 300f, 100f),
            floatArrayOf(0f, -100f, 100f, 0f),
            floatArrayOf(0f, 200f, 100f, 300f),
        )) {
            assertNull(nextSpec(previous, box, 1f, screen, 100, 100, 200f, 200f))
        }
    }

    @Test
    fun `大视口里的小页保持原始分辨率`() {
        val box = PageBox(0, 0f, 0f, 595f, 842f)
        val spec = nextSpec(null, box, 1f, floatArrayOf(0f, 0f, 595f, 842f),
            595, 842, 8000f, 8000f)!!
        assertEquals(ImageSpec.wholePage(595, 842), spec)
    }

    @Test
    fun `大视口降采样仍覆盖视口且不超预算`() {
        val box = PageBox(0, 0f, 0f, 14400f, 14400f)
        for ((vw, vh) in listOf(3840f to 2160f, 12000f to 1000f, 8000f to 8000f)) {
            val spec = nextSpec(null, box, 1f, floatArrayOf(-100f, -100f, 14300f, 14300f),
                14400, 14400, vw, vh)!!
            assertTrue(spec.w.toLong() * spec.h <= EXACT_MAX_PIXELS)
            assertTrue(spec.w <= TilePool.MAX_BITMAP_SIDE && spec.h <= TilePool.MAX_BITMAP_SIDE)
            val sx = spec.pageWidthPx / 14400f
            val sy = spec.pageHeightPx / 14400f
            assertTrue(spec.x <= 100f * sx && spec.y <= 100f * sy)
            assertTrue(spec.x + spec.w >= (100f + vw) * sx)
            assertTrue(spec.y + spec.h >= (100f + vh) * sy)
        }
    }

    private val a4 = PageBox(index = 0, left = -297.5f, top = 0f, width = 595f, height = 842f)

    private val viewW = 1080f
    private val viewH = 2340f

    private fun screenAt(l: Float, t: Float, pw: Int, ph: Int) =
        floatArrayOf(-l, -t, -l + pw, -t + ph)

    @Test
    fun `装得下就整页一张`() {

        val spec = nextSpec(null, a4, 1f, screenAt(0f, 0f, 595, 842), 595, 842, viewW, viewH)!!
        assertFalse(spec.isPatch)
        assertEquals(595, spec.pageWidthPx)
        assertEquals(595, spec.w)
        assertEquals(0, spec.x)
        assertEquals(0, spec.y)
    }

    @Test
    fun `超过像素上限才切补丁`() {

        val pw = 2975
        val ph = 4210
        val spec = nextSpec(null, a4, 5f, screenAt(800f, 1000f, pw, ph), pw, ph, viewW, viewH)!!
        assertTrue(spec.isPatch)
        assertEquals(pw, spec.pageWidthPx)
        assertEquals(ph, spec.pageHeightPx)

        assertTrue(spec.w <= pw && spec.h <= ph)
        assertTrue(spec.w <= TilePool.MAX_BITMAP_SIDE && spec.h <= TilePool.MAX_BITMAP_SIDE)
        assertTrue(spec.x >= 0 && spec.x + spec.w <= pw)
        assertTrue(spec.y >= 0 && spec.y + spec.h <= ph)

        val area = spec.w.toLong() * spec.h
        assertTrue("补丁 ${spec.w}×${spec.h} = $area 超出上限太多", area <= EXACT_MAX_PIXELS * 11 / 10)
        assertTrue("补丁 ${spec.w}×${spec.h} = $area 明显小于上限", area >= EXACT_MAX_PIXELS / 2)
    }

    @Test
    fun `补丁盖住此刻看得见的那一块`() {
        val pw = 2975
        val ph = 4210
        val spec = nextSpec(null, a4, 5f, screenAt(800f, 1000f, pw, ph), pw, ph, viewW, viewH)!!

        assertTrue(spec.x <= 800 && spec.x + spec.w >= 800 + viewW)
        assertTrue(spec.y <= 1000 && spec.y + spec.h >= 1000 + viewH)
    }

    @Test
    fun offscreenPatchCancelsItsRenderRequest() {
        val pw = 2975
        val ph = 4210
        val current = nextSpec(null, a4, 5f, screenAt(800f, 1000f, pw, ph), pw, ph, viewW, viewH)

        val next = nextSpec(current, a4, 5f, screenAt(0f, -5000f, pw, ph), pw, ph, viewW, viewH)
        assertNull(next)
    }

    @Test
    fun `视口在余量里挪动不重出`() {
        val pw = 2975
        val ph = 4210
        val current = nextSpec(null, a4, 5f, screenAt(800f, 1000f, pw, ph), pw, ph, viewW, viewH)!!
        val same = nextSpec(current, a4, 5f, screenAt(850f, 1050f, pw, ph), pw, ph, viewW, viewH)
        assertSame(current, same)
    }

    @Test
    fun `挪出内圈就换一块新补丁`() {
        val pw = 2975
        val ph = 4210
        val current = nextSpec(null, a4, 5f, screenAt(800f, 1000f, pw, ph), pw, ph, viewW, viewH)!!
        val moved = nextSpec(current, a4, 5f, screenAt(2000f, 1000f, pw, ph), pw, ph, viewW, viewH)!!
        assertTrue(moved != current)
        assertTrue(moved.x > current.x)
    }

    @Test
    fun `倍数变了就重算，哪怕视口没动`() {
        val current = nextSpec(null, a4, 5f, screenAt(800f, 1000f, 2975, 4210), 2975, 4210, viewW, viewH)!!
        val zoomed = nextSpec(current, a4, 6f, screenAt(800f, 1000f, 3570, 5052), 3570, 5052, viewW, viewH)!!
        assertEquals(3570, zoomed.pageWidthPx)
    }

    @Test
    fun `内圈让出一半余量`() {

        val spec = ImageSpec(1000, 1000, x = 200, y = 200, w = 400, h = 400)
        assertTrue(spec.innerContains(300f, 300f, 500f, 500f, 200f, 200f))

        assertFalse(spec.innerContains(240f, 300f, 440f, 500f, 200f, 200f))

        assertFalse(spec.innerContains(300f, 360f, 500f, 560f, 200f, 200f))
    }

    @Test
    fun `贴着页边的一侧没有余量可言`() {

        val spec = ImageSpec(1000, 1000, x = 0, y = 0, w = 400, h = 400)
        assertTrue(spec.innerContains(0f, 0f, 200f, 200f, 200f, 200f))

        assertFalse(spec.innerContains(160f, 160f, 360f, 360f, 200f, 200f))
    }

    @Test
    fun `背景图整页且贴着像素上限`() {
        val spec = backdropSpec(a4)
        assertFalse(spec.isPatch)
        assertEquals(0, spec.x)
        assertEquals(0, spec.y)
        assertEquals(spec.pageWidthPx, spec.w)
        assertEquals(spec.pageHeightPx, spec.h)
        val area = spec.w.toLong() * spec.h
        assertTrue("背景图 ${spec.w}×${spec.h} = $area 超上限", area <= EXACT_MAX_PIXELS)
        assertTrue("背景图 ${spec.w}×${spec.h} = $area 太小", area >= EXACT_MAX_PIXELS * 9 / 10)

        assertEquals(a4.width / a4.height, spec.w.toFloat() / spec.h, 0.002f)
    }

    @Test
    fun `背景图不看倍数，一页只有一种规格`() {

        assertEquals(backdropSpec(a4), backdropSpec(a4.copy(left = 0f, top = 999f)))
    }

    @Test
    fun `细长页按单边上限收住`() {

        val strip = PageBox(index = 0, left = 0f, top = 0f, width = 60f, height = 6000f)
        val spec = backdropSpec(strip)
        assertTrue(spec.h <= TilePool.MAX_BITMAP_SIDE)
        assertTrue(spec.w <= TilePool.MAX_BITMAP_SIDE)
        assertTrue(spec.w >= 1 && spec.h >= 1)
    }
}

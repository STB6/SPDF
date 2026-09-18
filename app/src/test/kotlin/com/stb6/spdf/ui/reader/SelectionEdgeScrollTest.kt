package com.stb6.spdf.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionEdgeScrollTest {
    @Test fun topBandStartsAtTheReadingAreaBelowTheToolbar() {
        val scroll = SelectionEdgeScroll(1f)
        assertEquals(0f to 0f, scroll.delta(200f, 124f, 0f, 100f, 400f, 700f))
        assertEquals(0f to -2f, scroll.delta(200f, 112f, 0f, 100f, 400f, 700f))
    }

    @Test fun sustainedDragAcceleratesButLeavingTheViewportCannotExceedTheCap() {
        val scroll = SelectionEdgeScroll(1f)
        assertEquals(0f to 4f, scroll.delta(200f, 700f, 0f, 100f, 400f, 700f))
        var last = 4f
        repeat(100) {
            val dy = scroll.delta(200f, 10000f, 0f, 100f, 400f, 700f).second
            assertTrue(dy >= last && dy <= 24f)
            last = dy
        }
        assertEquals(24f, last, 0f)
    }

    @Test fun reversingOrReturningFromTheCenterRestartsAtMinimumSpeed() {
        val scroll = SelectionEdgeScroll(1f)
        repeat(100) { scroll.delta(200f, 700f, 0f, 100f, 400f, 700f) }
        assertEquals(0f to -4f, scroll.delta(200f, 100f, 0f, 100f, 400f, 700f))
        assertEquals(0f to 0f, scroll.delta(200f, 400f, 0f, 100f, 400f, 700f))
        assertEquals(0f to -4f, scroll.delta(200f, 100f, 0f, 100f, 400f, 700f))
    }

    @Test fun navigationInsetsAndDensityApplyToBothAxes() {
        val scroll = SelectionEdgeScroll(2f)
        assertEquals(4f to 0f, scroll.delta(736f, 400f, 40f, 100f, 760f, 700f))
        assertEquals(0f to 4f, scroll.delta(400f, 676f, 40f, 100f, 760f, 700f))
        assertEquals(0f to 0f, scroll.delta(400f, 400f, 40f, 100f, 40f, 700f))
    }
}

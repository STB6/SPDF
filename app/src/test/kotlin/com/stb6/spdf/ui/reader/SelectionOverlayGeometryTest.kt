package com.stb6.spdf.ui.reader

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectionOverlayGeometryTest {
    private val viewport = Rect(0f, 100f, 300f, 500f)

    @Test fun invisibleLinesOnOppositeSidesDoNotCreateAnAnchorBetweenThem() {
        assertNull(selectionOverlayBounds(
            listOf(Rect(10f, 20f, 90f, 60f), Rect(10f, 520f, 90f, 550f)),
            emptyList(), viewport,
        ))
    }

    @Test fun toolbarIncludesVisibleHandlesWithoutEnteringSystemBars() {
        assertEquals(Rect(5f, 450f, 160f, 500f), selectionOverlayBounds(
            listOf(Rect(10f, 450f, 150f, 480f)),
            listOf(Rect(5f, 475f, 25f, 515f), Rect(140f, 475f, 160f, 515f)), viewport,
        ))
    }

    @Test fun staleHandleAloneCannotKeepTheToolbarVisible() {
        assertNull(selectionOverlayBounds(
            listOf(Rect(10f, 40f, 90f, 80f)), listOf(Rect(40f, 200f, 60f, 230f)), viewport,
        ))
    }

    @Test fun partlyVisibleTextIsClippedToTheReadingArea() {
        assertEquals(Rect(0f, 100f, 80f, 120f), selectionOverlayBounds(
            listOf(Rect(-20f, 80f, 80f, 120f)), emptyList(), viewport,
        ))
    }
}

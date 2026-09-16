package com.jake.duolauncher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class DragEdgeTest {
    @Test fun `screen edges work beyond the pager and dock centers stay neutral`() {
        val cover = Rect(0f, 0f, 1248f, 1972f)
        val band = 30f * 2.625f
        assertEquals(1, dragEdgeDirection(Offset(1236f, 920f), cover, band))
        assertEquals(-1, dragEdgeDirection(Offset(12f, 920f), cover, band))
        assertEquals(0, dragEdgeDirection(Offset(1119f, 920f), cover, band))
        assertEquals(0, dragEdgeDirection(Offset(970f, 920f), cover, band))
        val inner = Rect(0f, 0f, 2448f, 1848f)
        assertEquals(1, dragEdgeDirection(Offset(2436f, 920f), inner, band))
        assertEquals(0, dragEdgeDirection(Offset(2319f, 920f), inner, band))
    }
    @Test fun `edge regions follow window origin and exclude touches outside the window`() {
        val window = Rect(30f, 100f, 1030f, 1800f)
        assertEquals(-1, dragEdgeDirection(Offset(35f, 800f), window, 60f))
        assertEquals(1, dragEdgeDirection(Offset(1020f, 800f), window, 60f))
        assertEquals(0, dragEdgeDirection(Offset(1020f, 90f), window, 60f))
        assertEquals(0, dragEdgeDirection(Offset(1020f, 1801f), window, 60f))
        assertEquals(0, dragEdgeDirection(Offset(1031f, 800f), window, 60f))
        assertEquals(0, dragEdgeDirection(Offset.Zero, Rect.Zero, 60f))
    }
}

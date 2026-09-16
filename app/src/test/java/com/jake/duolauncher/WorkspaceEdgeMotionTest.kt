package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceEdgeMotionTest {
    @Test fun `legal page travel has no edge transform`() {
        for (offset in listOf(0f, 250f, 1000f)) {
            assertEquals(WorkspaceEdgeVisual(0f, 1f, 0f, 0f),
                workspaceEdgeVisual(offset, 0f, 1000f, 100f))
        }
    }

    @Test fun `both edges follow the pull with bounded resistance`() {
        val leading = workspaceEdgeVisual(-100f, 0f, 1000f, 100f)
        val trailing = workspaceEdgeVisual(1100f, 0f, 1000f, 100f)

        assertEquals(50f, leading.translationPx, .0001f)
        assertEquals(-50f, trailing.translationPx, .0001f)
        assertEquals(leading.progress, trailing.progress, 0f)
        assertEquals(.993f, leading.scale, .0001f)
        assertEquals(.1f, leading.shadeAlpha, .0001f)
    }

    @Test fun `rubber band is monotonic and never reaches its travel limit`() {
        val values = listOf(0f, 10f, 50f, 100f, 1000f).map { rubberBand(it, 80f) }
        assertEquals(0f, values.first(), 0f)
        values.zipWithNext().forEach { (before, after) -> assertTrue(after > before) }
        assertTrue(values.last() < 80f)
    }
}


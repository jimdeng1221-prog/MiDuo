package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePageMotionTest {
    private val motion = WorkspacePageMotion(
        firstHome = 1,
        homePages = 3,
        pageWidth = 1000f,
        homeStride = 500f,
    )

    @Test fun `leading page homes and library have the intended endpoints`() {
        assertEquals(-1000f, motion.offset(0f), 0f)
        assertEquals(0f, motion.offset(1f), 0f)
        assertEquals(500f, motion.offset(2f), 0f)
        assertEquals(1000f, motion.offset(3f), 0f)
        assertEquals(1500f, motion.offset(4f), 0f)

        assertEquals(0f, motion.position(-1000f), 0f)
        assertEquals(1f, motion.position(0f), 0f)
        assertEquals(2f, motion.position(500f), 0f)
        assertEquals(3f, motion.position(1000f), 0f)
        assertEquals(4f, motion.position(1500f), 0f)
    }

    @Test fun `mapping and inverse stay continuous through every boundary`() {
        for (step in -1000..4000) {
            val position = step / 1000f
            assertEquals(position, motion.position(motion.offset(position)), .00001f)
        }

        val epsilon = .0001f
        for (boundary in listOf(1f, 3f)) {
            assertTrue(motion.offset(boundary) - motion.offset(boundary - epsilon) < .11f)
            assertTrue(motion.offset(boundary + epsilon) - motion.offset(boundary) < .11f)
        }
    }

    @Test fun `one home page still has an adjacent single pane library`() {
        val single = WorkspacePageMotion(1, 1, 1000f, 500f)
        for (position in listOf(-.5f, 0f, .25f, 1f, 1.75f, 2f, 2.5f)) {
            val stride = if (position < 1f) 1000f else 500f
            assertEquals((position - 1f) * stride, single.offset(position), 0f)
            assertEquals(position, single.position(single.offset(position)), 0f)
        }
    }

    @Test fun `unfolded workspace starts at physical page zero`() {
        val unfolded = WorkspacePageMotion(0, 3, 1000f, 500f)
        assertEquals(0f, unfolded.offset(0f), 0f)
        assertEquals(500f, unfolded.offset(1f), 0f)
        assertEquals(1000f, unfolded.offset(2f), 0f)
        assertEquals(1500f, unfolded.offset(3f), 0f)
        assertEquals(3f, unfolded.position(1500f), 0f)
    }

    @Test fun `half width home panes preserve one to one visual input`() {
        val startingPositions = listOf(.25f, 1f, 1.5f, 2.75f, 3.25f)
        val deltas = listOf(-120f, -10f, 0f, 35f, 140f)
        for (start in startingPositions) for (delta in deltas) {
            val moved = motion.positionAfterVisualDelta(start, delta)
            // Two Float conversions can accumulate a tiny subpixel rounding error.
            assertEquals(delta, motion.offset(moved) - motion.offset(start), .001f)
        }
        assertEquals(1000f, motion.stride(0, 1), 0f)
        assertEquals(500f, motion.stride(1, 2), 0f)
        assertEquals(500f, motion.stride(2, 3), 0f)
        assertEquals(500f, motion.stride(3, 4), 0f)
    }

    @Test fun `library stop retains last home on left for any number of homes`() {
        for (homes in 1..12) {
            val expanded = WorkspacePageMotion(0, homes, 1000f, 500f)
            val rightOrigin = 500f
            val scroll = expanded.offset(homes.toFloat())
            assertEquals(0f, rightOrigin + (homes - 1) * 500f - scroll, 0f)
            assertEquals(500f, rightOrigin + homes * 500f - scroll, 0f)
            assertEquals(500f, expanded.stride(homes - 1, homes), 0f)
        }
    }
}

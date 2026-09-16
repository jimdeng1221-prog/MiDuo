package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationGestureTest {
    @Test fun `quick bottom swipe goes home`() {
        val gesture = NavigationGesture(NavigationEdge.BOTTOM)
        gesture.down(100f, 200f, 0L)
        gesture.move(100f, 140f, 120L)

        assertEquals(NavigationAction.HOME, gesture.up(180L))
    }

    @Test fun `bottom swipe held still opens recents`() {
        val gesture = NavigationGesture(NavigationEdge.BOTTOM)
        gesture.down(100f, 200f, 0L)
        gesture.move(100f, 140f, 100L)

        assertNull(gesture.tick(449L))
        assertEquals(NavigationAction.RECENTS, gesture.tick(450L))
        assertNull(gesture.up(500L))
    }

    @Test fun `stationary bottom hold opens recovery`() {
        val gesture = NavigationGesture(NavigationEdge.BOTTOM)
        gesture.down(100f, 200f, 0L)

        assertNull(gesture.tick(1_999L))
        assertEquals(NavigationAction.RECOVERY, gesture.tick(2_000L))
        assertNull(gesture.up(2_100L))
    }

    @Test fun `either side inward swipe goes back`() {
        val left = NavigationGesture(NavigationEdge.LEFT)
        left.down(0f, 100f, 0L)
        left.move(40f, 104f, 100L)
        assertEquals(NavigationAction.BACK, left.up(120L))

        val right = NavigationGesture(NavigationEdge.RIGHT)
        right.down(200f, 100f, 0L)
        right.move(160f, 96f, 100L)
        assertEquals(NavigationAction.BACK, right.up(120L))
    }

    @Test fun `wrong direction and steep diagonal do nothing`() {
        val outward = NavigationGesture(NavigationEdge.LEFT)
        outward.down(20f, 100f, 0L)
        outward.move(0f, 100f, 100L)
        assertNull(outward.up(120L))

        val diagonal = NavigationGesture(NavigationEdge.RIGHT)
        diagonal.down(200f, 100f, 0L)
        diagonal.move(160f, 150f, 100L)
        assertNull(diagonal.up(120L))
    }

    @Test fun `cancellation prevents every action`() {
        val gesture = NavigationGesture(NavigationEdge.BOTTOM)
        gesture.down(100f, 200f, 0L)
        gesture.move(100f, 130f, 100L)
        gesture.cancel()

        assertNull(gesture.tick(1_000L))
        assertNull(gesture.up(1_000L))
    }

    @Test fun `side feedback follows inward distance and keeps pointer position`() {
        val gesture = NavigationGesture(NavigationEdge.LEFT)
        gesture.down(2f, 100f, 0L)
        gesture.move(16f, 108f, 40L)

        val halfway = gesture.progress(40L)
        assertTrue(halfway.active)
        assertEquals(.5f, halfway.distance, .0001f)
        assertEquals(16f, halfway.x, 0f)
        assertEquals(108f, halfway.y, 0f)

        gesture.move(80f, 110f, 80L)
        assertEquals(1f, gesture.progress(80L).distance, 0f)
    }

    @Test fun `bottom feedback separates travel recents hold and recovery hold`() {
        val gesture = NavigationGesture(NavigationEdge.BOTTOM)
        gesture.down(100f, 200f, 0L)
        assertEquals(.5f, gesture.progress(1_000L).recovery, .0001f)

        gesture.move(100f, 176f, 1_050L)
        assertEquals(.5f, gesture.progress(1_050L).distance, .0001f)
        assertEquals(0f, gesture.progress(1_200L).hold, 0f)

        gesture.move(100f, 140f, 1_250L)
        assertEquals(.5f, gesture.progress(1_425L).hold, .0001f)
        assertEquals(0f, gesture.progress(1_425L).recovery, 0f)
    }

    @Test fun `finished gesture stops reporting active feedback`() {
        val gesture = NavigationGesture(NavigationEdge.RIGHT)
        gesture.down(200f, 100f, 0L)
        gesture.move(160f, 100f, 50L)
        assertEquals(NavigationAction.BACK, gesture.up(60L))

        assertFalse(gesture.progress(70L).active)
    }
}

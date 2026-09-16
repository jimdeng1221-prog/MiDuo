package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class LeadingShadeGestureTest {
    @Test fun innerLeadingPaneLeavesDownwardGesturesToWidgets() {
        assertTrue(startsInExpandedLeadingPane(true, 100f, 400f, 0f))
        assertTrue(startsInExpandedLeadingPane(true, 399f, 400f, 0f))
        assertFalse(startsInExpandedLeadingPane(true, 400f, 400f, 0f))
    }
    @Test fun pageMotionAndCoverDoNotDisableOtherHomeGestures() {
        assertTrue(startsInExpandedLeadingPane(true, 99f, 400f, 300f))
        assertFalse(startsInExpandedLeadingPane(true, 100f, 400f, 300f))
        assertFalse(startsInExpandedLeadingPane(true, 10f, 400f, 400f))
        assertFalse(startsInExpandedLeadingPane(false, 100f, 400f, 0f))
    }
}

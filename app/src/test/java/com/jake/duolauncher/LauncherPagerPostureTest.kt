package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherPagerPostureTest {
    @Test fun `every home including third fourth and later survives repeated fold cycles`() {
        for (homeCount in 1..12) {
            for (coverPage in 1..homeCount) {
                var cover = coverPage
                repeat(10) {
                    val spread = remapPhysicalPageForPosture(cover, 1, 0, homeCount + 1)
                    assertEquals("Right home must remain $coverPage with $homeCount homes", coverPage - 1, spread)
                    cover = remapPhysicalPageForPosture(spread, 0, 1, homeCount + 2)
                    assertEquals(coverPage, cover)
                }
            }
        }
    }

    @Test fun `library stays the single final page across folding with any number of homes`() {
        for (homeCount in 1..12) {
            assertEquals(homeCount, remapPhysicalPageForPosture(homeCount + 1, 1, 0, homeCount + 1))
            assertEquals(homeCount + 1, remapPhysicalPageForPosture(homeCount, 0, 1, homeCount + 2))
        }
    }

    @Test fun `folded first home opens as component and first home spread`() {
        assertEquals(0, remapPhysicalPageForPosture(1, 1, 0, 3))
    }

    @Test fun `folded second home opens as first and second home spread`() {
        assertEquals(1, remapPhysicalPageForPosture(2, 1, 0, 3))
    }

    @Test fun `folded component page remains at first unfolded spread`() {
        assertEquals(0, remapPhysicalPageForPosture(0, 1, 0, 3))
    }

    @Test fun `unfolded spread folds onto its right cover page`() {
        assertEquals(2, remapPhysicalPageForPosture(1, 0, 1, 4))
        assertEquals(1, remapPhysicalPageForPosture(0, 0, 1, 4))
    }

    @Test fun `position is clamped when the narrower page model has fewer pages`() {
        assertEquals(2, remapPhysicalPageForPosture(3, 0, 1, 3))
        assertEquals(0, remapPhysicalPageForPosture(-1, 1, 0, 3))
    }

    @Test fun `recreated window resumes transition from posture owning saved page`() {
        assertEquals(0, restoredPagePosture(savedPosture = 0, currentPosture = 1))
        assertEquals(1, restoredPagePosture(savedPosture = 1, currentPosture = 0))
        assertEquals(1, restoredPagePosture(savedPosture = -1, currentPosture = 1))
    }

    @Test fun `cover page navigation follows first home away from leading page`() {
        assertEquals(0f, coverPageNavigationProgress(false, 1, 1f), 0f)
        assertEquals(.5f, coverPageNavigationProgress(false, 1, .5f), 0f)
        assertEquals(1f, coverPageNavigationProgress(false, 1, 0f), 0f)
        assertEquals(0f, coverPageNavigationProgress(true, 0, 0f), 0f)

        assertEquals(true, showPageNavigation(false, 1, 1, false))
        assertEquals(true, showPageNavigation(false, 1, 0, true))
        assertEquals(false, showPageNavigation(false, 1, 0, false))
        assertEquals(true, showPageNavigation(true, 0, 0, false))
    }

    @Test fun `expanded page navigation follows leading page to workspace centre`() {
        assertEquals(.25f, expandedPageNavigationOffsetFraction(true, 0f), 0f)
        assertEquals(.125f, expandedPageNavigationOffsetFraction(true, .5f), 0f)
        assertEquals(0f, expandedPageNavigationOffsetFraction(true, 1f), 0f)
        assertEquals(0f, expandedPageNavigationOffsetFraction(true, 4f), 0f)
        assertEquals(0f, expandedPageNavigationOffsetFraction(false, 0f), 0f)
    }
}

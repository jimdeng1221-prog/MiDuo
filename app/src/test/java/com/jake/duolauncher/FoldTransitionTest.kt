package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class FoldTransitionTest {
    @Test fun `missing final cover reading settles without a new sensor event`() {
        val signal = FoldSignal(angleDegrees = 120f, activePanel = FoldPanel.COVER,
            receivedElapsedNs = 10_000_000_000L)
        assertEquals(1f, coverFoldProgress(foldTrackingProgress(signal, FoldPanel.COVER,
            signal.receivedElapsedNs + COVER_FOLD_IDLE_NS - 1)), .001f)
        assertEquals(0f, foldTrackingProgress(signal, FoldPanel.COVER,
            signal.receivedElapsedNs + COVER_FOLD_IDLE_NS), .001f)
        assertEquals(0f, foldTrackingProgress(signal, FoldPanel.COVER,
            signal.receivedElapsedNs + 60_000_000_000L), .001f)
    }

    @Test fun `fresh cover motion resumes after stale recovery including small angles`() {
        val now = 20_000_000_000L
        for (angle in listOf(5f, 30f, 90f, 30f, 5f, 0f)) {
            val signal = FoldSignal(angleDegrees = angle, activePanel = FoldPanel.COVER,
                receivedElapsedNs = now)
            assertEquals(hingeReveal(angle), foldTrackingProgress(signal, FoldPanel.COVER, now), .001f)
        }
    }

    @Test fun `cover rejects other panel and invalid timestamps without flattening a resting inner pose`() {
        val signal = FoldSignal(angleDegrees = 90f, activePanel = FoldPanel.INNER,
            receivedElapsedNs = 10_000_000_000L)
        assertEquals(0f, foldTrackingProgress(signal, FoldPanel.COVER, 10_100_000_000L), .001f)
        assertEquals(hingeReveal(90f), foldTrackingProgress(signal, FoldPanel.INNER, 80_000_000_000L), .001f)
        assertEquals(0f, foldTrackingProgress(signal.copy(activePanel = FoldPanel.COVER),
            FoldPanel.COVER, 1L), .001f)
        assertEquals(0f, foldTrackingProgress(signal.copy(activePanel = FoldPanel.COVER,
            receivedElapsedNs = 0L), FoldPanel.COVER, 1L), .001f)
    }

    @Test fun `background reset cannot carry an old pose into a new cover session`() {
        val reset = resetFoldTracking(FoldSignal(angleDegrees = 130f,
            receivedElapsedNs = 10L, sensorTimestampNs = 9L,
            direction = FoldDirection.OPENING, activePanel = FoldPanel.INNER,
            panelEpoch = 3L, panelChangedElapsedNs = 8L))
        assertNull(reset.angleDegrees)
        assertEquals(0L, reset.sensorTimestampNs)
        assertEquals(FoldPanel.UNKNOWN, reset.activePanel)
        assertEquals(3L, reset.panelEpoch)
        assertEquals(0f, foldTrackingProgress(reset, FoldPanel.COVER, 20L), .001f)
        assertEquals(1f, foldTrackingProgress(reset, FoldPanel.INNER, 20L), .001f)
    }

    @Test fun `perspective inverse fixes hinge and free edge and is monotonic`() {
        assertEquals(0f, foldSourceFraction(0f, .16f), .0001f)
        assertEquals(1f, foldSourceFraction(1f, .16f), .0001f)
        for (step in 0..100) {
            val q = step / 100f
            assertEquals(q, foldSourceFraction(q, 0f), .0001f)
            assertTrue(foldSourceFraction(q, .16f) >= q)
            if (step > 0) assertTrue(foldSourceFraction(q, .16f) > foldSourceFraction(q - .01f, .16f))
        }
    }
    @Test fun `physical angle holds a half open pose and supports reversing`() {
        val angles = listOf(0f, 25f, 90f, 140f, 170f, 180f)
        val reveal = angles.map(::hingeReveal)
        assertEquals(0f, reveal.first(), .001f)
        assertEquals(1f, reveal.last(), .001f)
        assertTrue(reveal.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(hingeReveal(90f) in .5f.. .52f)
        assertTrue(coverFoldProgress(hingeReveal(5f)) > .03f)
        assertEquals(reveal.reversed(), angles.reversed().map(::hingeReveal))
        assertEquals(1f, hingeReveal(Float.NaN), .001f)
    }

    @Test fun `settled rendering is identity and folding does not fade the whole page`() {
        val flat = foldVisual(hingeReveal(175f))
        assertEquals(1f, flat.scaleX, .001f)
        assertEquals(1f, flat.opacity, .001f)
        assertEquals(0f, flat.blurDp, .001f)
        assertEquals(0f, flat.rotationY, .001f)
        assertEquals(1f, foldVisual(0f).opacity, .001f)
        val half = foldVisual(hingeReveal(90f))
        assertTrue(half.opacity in 0f..1f && half.blurDp >= 8f)
        assertTrue(half.scaleX > 1.11f && half.rotationY > 5f)
    }

    @Test fun `cover transforms the whole plane around the opposite hinge`() {
        val stable = foldCoverVisual(0f)
        val retiring = foldCoverVisual(1f)
        assertEquals(1f, stable.scaleX, .001f)
        assertEquals(0f, stable.rotationY, .001f)
        assertEquals(0f, stable.blurDp, .001f)
        assertTrue(retiring.scaleX > 1.17f)
        assertTrue(retiring.rotationY > 0f)
        assertTrue(retiring.blurDp > 10f)
        assertEquals(stable, foldCoverVisual(Float.NaN))
    }

    @Test fun `projection grows one trapezoid from a fixed hinge`() {
        val flat = foldProjectionGeometry(0f)
        val half = foldProjectionGeometry(.5f)
        val folded = foldProjectionGeometry(1f)
        assertEquals(0f, flat.horizontalInsetFraction, .001f)
        assertEquals(0f, flat.verticalInsetFraction, .001f)
        assertEquals(0f, flat.maxBlurFraction, .001f)
        assertEquals(.065f, half.horizontalInsetFraction, .001f)
        assertEquals(.08f, half.verticalInsetFraction, .001f)
        assertEquals(.0225f, half.maxBlurFraction, .001f)
        assertEquals(.13f, folded.horizontalInsetFraction, .001f)
        assertEquals(.16f, folded.verticalInsetFraction, .001f)
        assertEquals(.045f, folded.maxBlurFraction, .001f)
        assertEquals(flat, foldProjectionGeometry(Float.NaN))
        assertEquals(folded, foldProjectionGeometry(2f))
        assertTrue(half.horizontalInsetFraction < folded.horizontalInsetFraction)
        assertTrue(half.verticalInsetFraction < folded.verticalInsetFraction)
        assertEquals(FoldShadeSide.RIGHT, foldShadeSide(expanded = false))
        assertEquals(FoldShadeSide.LEFT, foldShadeSide(expanded = true))
    }

    @Test fun `cover consumes the early hinge range`() {
        assertEquals(0f, coverFoldProgress(0f), .001f)
        assertEquals(1f, coverFoldProgress(.68f), .001f)
        assertEquals(1f, coverFoldProgress(1f), .001f)
    }

    @Test fun `direction survives jitter but reverses with a real angle change`() {
        assertEquals(FoldDirection.UNKNOWN, nextFoldDirection(null, 30f, FoldDirection.UNKNOWN))
        assertEquals(FoldDirection.OPENING, nextFoldDirection(30f, 31f, FoldDirection.UNKNOWN))
        assertEquals(FoldDirection.OPENING, nextFoldDirection(31f, 30.5f, FoldDirection.OPENING))
        assertEquals(FoldDirection.CLOSING, nextFoldDirection(31f, 29f, FoldDirection.OPENING))
    }

    @Test fun `panel state machine links cover and inner phases`() {
        assertEquals(FoldPhase.COVER_STABLE, foldPhase(FoldPanel.COVER, 0f, FoldDirection.UNKNOWN))
        assertEquals(FoldPhase.OPENING_COVER, foldPhase(FoldPanel.COVER, .4f, FoldDirection.OPENING))
        assertEquals(FoldPhase.OPENING_INNER, foldPhase(FoldPanel.INNER, .6f, FoldDirection.OPENING))
        assertEquals(FoldPhase.INNER_STABLE, foldPhase(FoldPanel.INNER, 1f, FoldDirection.OPENING))
        assertEquals(FoldPhase.CLOSING_INNER, foldPhase(FoldPanel.INNER, .6f, FoldDirection.CLOSING))
        assertEquals(FoldPhase.CLOSING_COVER, foldPhase(FoldPanel.COVER, .4f, FoldDirection.CLOSING))
        assertEquals(FoldPhase.RECOVERY, foldPhase(FoldPanel.UNKNOWN, .4f, FoldDirection.OPENING))
    }

    @Test fun `physical panel detection ignores reused logical display ids`() {
        val panels = listOf(1_999_616L, 3_952_608L)
        assertEquals(FoldPanel.COVER, panelFromPhysicalAreas(listOf(1_999_616L), panels))
        assertEquals(FoldPanel.INNER, panelFromPhysicalAreas(listOf(3_952_608L), panels))
        assertEquals(FoldPanel.UNKNOWN, panelFromPhysicalAreas(panels, panels))
        assertEquals(FoldPanel.UNKNOWN, panelFromPhysicalAreas(listOf(1_999_616L), listOf(1_999_616L)))
    }
}

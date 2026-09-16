package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

/** Regressions adapted from upstream FoldDirectContactTest, plus MiDuo's signal gate. */
class GlassFoldPoseTest {
    private fun hall(field: Float, bit: Float = 0f) =
        floatArrayOf(bit, 708f, -744f, -field, -field, 681f, 1630f, 0f, 3136f)
    private fun fold(angle: Float, contact: Float = 1f) =
        floatArrayOf(1f, angle, contact, 2f, 0f, 3f, 0f, 0f, -493f, 681f, 1630f)

    @Test fun `closed tilt remains blocked despite noisy hinge and stale separated summary`() {
        var pose = GlassFoldPose(true, true, true).withFoldEvent(fold(9f), 1)
            .withDirectContactEvent(hall(1900f), 2)
        for ((i, angle) in listOf(0f, 1f, 2f, 7f, 17f, 38f, 3f).withIndex()) {
            pose = pose.withAngle(angle, 10L + i)
            assertTrue(pose.blocksProjection())
            assertEquals(0f, pose.angle(), 0f)
            val signal = FoldSignal(angleDegrees = angle, activePanel = FoldPanel.COVER,
                receivedElapsedNs = 100L, coverProjectionBlocked = pose.blocksProjection())
            assertEquals(0f, foldTrackingProgress(signal, FoldPanel.COVER, 101L), 0f)
        }
    }

    @Test fun `small gap releases before digital switch and stale summary without raising onset`() {
        var pose = GlassFoldPose(true, true, true).withAngle(3f, 1)
            .withDirectContactEvent(hall(1900f), 2)
            .withDirectContactEvent(hall(1200f, bit = 0f), 3)
            .withFoldEvent(fold(3f, contact = 0f), 4)
        assertFalse(pose.blocksProjection())
        assertEquals(3f, pose.angle(), 0f)
        for (angle in listOf(0f, 1f, 2f, 3f, 7f, 2f)) {
            pose = pose.withAngle(angle)
            assertFalse(pose.blocksProjection())
        }
        val signal = FoldSignal(angleDegrees = 3f, activePanel = FoldPanel.COVER,
            receivedElapsedNs = 100L, coverProjectionBlocked = pose.blocksProjection())
        assertTrue(coverFoldProgress(foldTrackingProgress(signal, FoldPanel.COVER, 101L)) > 0f)
    }

    @Test fun `contact threshold and hysteresis preserve both states without chatter`() {
        var pose = GlassFoldPose(true, true, true).withDirectContactEvent(hall(1200f), 1)
        for (field in listOf(1468f, 1500f, 1629f)) {
            pose = pose.withDirectContactEvent(hall(field), field.toLong())
            assertFalse(pose.blocksProjection())
        }
        pose = pose.withDirectContactEvent(hall(1630f), 2000)
        for ((i, field) in listOf(1468f, 1500f, 1629f, 1900f).withIndex()) {
            pose = pose.withDirectContactEvent(hall(field), 2001L + i)
            assertTrue(pose.blocksProjection())
        }
        assertFalse(pose.withDirectContactEvent(hall(1467f), 3000).blocksProjection())
    }

    @Test fun `pending expired replayed and invalid contact cannot enable projection`() {
        var pose = GlassFoldPose(true, true, true).withAngle(3f, 1)
        assertTrue(pose.blocksProjection())
        pose = pose.withDirectContactEvent(hall(1200f), 1_000_000_000L)
        assertFalse(pose.expireDirectContact(1_500_000_000L).blocksProjection())
        pose = pose.expireDirectContact(1_501_000_000L)
        assertTrue(pose.blocksProjection())
        assertSame(pose, pose.withDirectContactEvent(hall(1200f), 1_000_000_000L))
        for (field in listOf(Float.NaN, Float.POSITIVE_INFINITY))
            assertSame(pose, pose.withDirectContactEvent(hall(field), 1_502_000_000L))
        assertSame(pose, pose.withDirectContactEvent(floatArrayOf(1f), 1_502_000_000L))
        assertSame(pose, pose.withDirectContactEvent(hall(1200f, 2f), 1_502_000_000L))
        assertFalse(pose.withDirectContactEvent(hall(1200f), 1_502_000_000L).blocksProjection())
    }

    @Test fun `unavailable sensors fail safe only for validated device and retain summary fallback`() {
        assertTrue(GlassFoldPose(false, true, false).withAngle(30f).blocksProjection())
        assertFalse(GlassFoldPose(false, false, false).withAngle(30f).blocksProjection())
        var pose = GlassFoldPose(true, true, false).withFoldEvent(fold(9f), 1).withAngle(1f, 2)
        assertTrue(pose.withAngle(17f, 3).blocksProjection())
        pose = pose.withFoldEvent(fold(2f), 4).withAngle(2f, 5)
        assertFalse(pose.blocksProjection())
    }

    @Test fun `cover contact gate never flattens the existing inner fold animation`() {
        val signal = FoldSignal(angleDegrees = 90f, activePanel = FoldPanel.INNER,
            receivedElapsedNs = 100L, coverProjectionBlocked = true)
        assertEquals(hingeReveal(90f), foldTrackingProgress(signal, FoldPanel.INNER, 101L), 0f)
        assertFalse(resetFoldTracking(signal).coverProjectionBlocked)
    }
}

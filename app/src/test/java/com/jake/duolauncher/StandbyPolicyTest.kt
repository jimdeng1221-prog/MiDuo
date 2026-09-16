package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import android.content.pm.ActivityInfo

class StandbyPolicyTest {
    @Test fun `letterboxed inner uses maximum display bounds instead of cover configuration`() {
        val cover = 1168L * 1712L
        val inner = 1672L * 2364L
        for (areas in listOf(listOf(cover, inner), listOf(cover), emptyList())) {
            assertEquals(FoldPanel.INNER, launcherPanel(cover, areas, 498, 2364, 1672, 2.75f))
            assertEquals(FoldPanel.INNER, launcherPanel(1370L * 1672L, areas, 498, 1672, 2364, 2.75f))
        }
    }
    @Test fun `closing uses cover maximum bounds even with stale inner mode and configuration`() {
        val inner = 1672L * 2364L
        for (areas in listOf(listOf(1168L * 1712L, inner), listOf(inner), emptyList())) {
            assertEquals(FoldPanel.COVER, launcherPanel(inner, areas, 608, 1168, 1712, 2.75f))
            assertEquals(FoldPanel.COVER, launcherPanel(inner, areas, 608, 1712, 1168, 2.75f))
        }
    }
    @Test fun `unavailable full bounds retain physical fallback and invalid density is ignored`() {
        val cover = 1168L * 1712L
        val inner = 1672L * 2364L
        assertEquals(FoldPanel.INNER, launcherPanel(inner, listOf(cover, inner), 498, 0, 0, 2.75f))
        assertEquals(FoldPanel.COVER, launcherPanel(cover, listOf(cover, inner), 608, 1370, 1672, Float.NaN))
    }
    @Test fun `folding upright never exposes standby even while cover config is still landscape`() {
        val gate = StandbyEntryGate()
        assertFalse(gate.update(FoldPanel.INNER, true, false, 0))
        for (time in listOf(1L, 100L, 700L, 1500L))
            assertFalse(gate.update(FoldPanel.COVER, true, physicallyLandscape(0f, 9.8f), time))
        assertFalse(gate.update(FoldPanel.COVER, false, false, 2000))
    }
    @Test fun `genuine cover landscape must settle and portrait immediately exits`() {
        val gate = StandbyEntryGate()
        assertFalse(gate.update(FoldPanel.COVER, true, true, 100))
        assertFalse(gate.update(FoldPanel.COVER, true, true, 799))
        assertTrue(gate.update(FoldPanel.COVER, true, true, 800))
        assertFalse(gate.update(FoldPanel.COVER, true, false, 801))
        assertFalse(gate.update(FoldPanel.COVER, true, true, 802))
        assertTrue(gate.update(FoldPanel.COVER, true, true, 1502))
    }
    @Test fun `panel handoff and lifecycle restart discard previous standby eligibility`() {
        val gate = StandbyEntryGate()
        gate.update(FoldPanel.COVER, true, true, 0)
        assertTrue(gate.update(FoldPanel.COVER, true, true, 800))
        assertFalse(gate.update(FoldPanel.INNER, true, true, 900))
        assertFalse(gate.update(FoldPanel.COVER, true, true, 901))
        gate.reset()
        assertFalse(gate.update(FoldPanel.COVER, true, true, 5000))
    }
    @Test fun `both horizontal directions work but flat diagonal and invalid samples do not`() {
        assertTrue(physicallyLandscape(9.8f, 0f))
        assertTrue(physicallyLandscape(-9.8f, 0f))
        assertFalse(physicallyLandscape(0f, 9.8f))
        assertFalse(physicallyLandscape(0f, 0f))
        assertFalse(physicallyLandscape(6f, 6f))
        assertFalse(physicallyLandscape(Float.NaN, 0f))
    }
    @Test fun `standby safety respects cutout and rounded corners on every rotated edge`() {
        assertEquals(24, standbySafeEdgePx(0, 0, 2f))
        assertEquals(116, standbySafeEdgePx(116, 100, 2f))
        assertEquals(66, standbySafeEdgePx(0, 100, 2f))
        for (cutoutEdge in 0..3) {
            val edges = List(4) { standbySafeEdgePx(if (it == cutoutEdge) 116 else 0, 100, 2f) }
            assertEquals(116, edges[cutoutEdge])
            assertTrue(edges.all { it >= 66 })
        }
    }
    @Test fun `cover landscape never gets classified as inner by window width`() {
        val cover = 1168L * 1712L
        val inner = 1672L * 2364L
        assertEquals(FoldPanel.COVER, launcherPanel(cover, listOf(cover, inner), 800))
        assertEquals(FoldPanel.INNER, launcherPanel(inner, listOf(cover, inner), 425))
        assertEquals(FoldPanel.COVER, launcherPanel(cover, listOf(cover), 425))
        assertEquals(FoldPanel.INNER, launcherPanel(inner, listOf(inner), 608))
    }
    @Test fun `only a settled cover standby draws the standby surface`() {
        for (panel in FoldPanel.entries) for (landscape in listOf(true, false)) for (standby in listOf(true, false)) {
            assertEquals(panel == FoldPanel.COVER && landscape && standby,
                launcherSurface(panel, landscape, standby) == LauncherSurface.STANDBY)
        }
    }
    @Test fun `inner stays fixed and cover only unlocks horizontal for standby`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, launcherOrientation(FoldPanel.INNER))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, launcherOrientation(FoldPanel.INNER, true))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, launcherOrientation(FoldPanel.COVER))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, launcherOrientation(FoldPanel.COVER, true))
        for (direction in StandbyDirection.entries) {
            assertEquals(direction.orientation, launcherOrientation(FoldPanel.COVER, true, direction))
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, launcherOrientation(FoldPanel.COVER, false, direction))
        }
    }
    @Test fun `entry requests the measured side directly without sensor landscape default`() {
        for ((x, expected) in listOf(9.8f to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            -9.8f to ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)) {
            val gate = StandbyEntryGate()
            val requests = listOf(0L, 100L, 699L, 700L, 800L).map { time ->
                launcherOrientation(FoldPanel.COVER,
                    gate.update(FoldPanel.COVER, true, physicallyLandscape(x, 0f), time), standbyDirection(x, 0f))
            }
            assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, expected, expected), requests)
        }
    }
    @Test fun `unknown upright diagonal and invalid samples never choose a default standby direction`() {
        for ((x, y) in listOf(0f to 0f, 0f to 9.8f, 6f to 6f, Float.NaN to 0f, Float.POSITIVE_INFINITY to 0f)) {
            assertNull(standbyDirection(x, y))
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                launcherOrientation(FoldPanel.COVER, true, standbyDirection(x, y)))
        }
    }
    @Test fun `opposite landscape side is hidden until requested direction commits`() {
        assertEquals(LauncherSurface.ROTATING, launcherSurface(FoldPanel.COVER, true, true, false))
        assertEquals(LauncherSurface.STANDBY, launcherSurface(FoldPanel.COVER, true, true, true))
        assertEquals(LauncherSurface.HOME, launcherSurface(FoldPanel.COVER, false, false, false))
        assertEquals(LauncherSurface.HOME, launcherSurface(FoldPanel.INNER, true, false, false))
    }
    @Test fun `cover never draws home in stale landscape or standby in portrait`() {
        assertEquals(LauncherSurface.ROTATING, launcherSurface(FoldPanel.COVER, true, false))
        assertEquals(LauncherSurface.HOME, launcherSurface(FoldPanel.COVER, false, false))
        assertEquals(LauncherSurface.ROTATING, launcherSurface(FoldPanel.COVER, false, true))
        assertEquals(LauncherSurface.STANDBY, launcherSurface(FoldPanel.COVER, true, true))
        assertEquals(LauncherSurface.HOME, launcherSurface(FoldPanel.INNER, true, false))
    }
    @Test fun `manual exit stays portrait until a real upright then sideways gesture`() {
        val gate = StandbyEntryGate()
        gate.update(FoldPanel.COVER, true, true, 0)
        assertTrue(gate.update(FoldPanel.COVER, true, true, 700))
        gate.dismiss()
        assertFalse(gate.update(FoldPanel.COVER, true, true, 2000))
        gate.pause()
        assertFalse(gate.update(FoldPanel.COVER, true, true, 4000))
        assertFalse(gate.update(FoldPanel.COVER, true, false, 4100)) // flat/unknown does not re-arm
        assertFalse(gate.update(FoldPanel.COVER, true, true, 5000))
        gate.update(FoldPanel.COVER, true, false, 6000, upright = true)
        assertFalse(gate.update(FoldPanel.COVER, true, true, 7000))
        assertTrue(gate.update(FoldPanel.COVER, true, true, 7700))
    }
    @Test fun `setup can prevent entry without unlocking cover home orientation`() {
        val gate = StandbyEntryGate()
        assertFalse(gate.update(FoldPanel.COVER, false, true, 0))
        assertFalse(gate.update(FoldPanel.COVER, false, true, 1000))
        assertFalse(gate.update(FoldPanel.COVER, true, true, 2000))
        assertTrue(gate.update(FoldPanel.COVER, true, true, 2700))
    }
    @Test fun `month cells handle Sunday leap February and six week months`() {
        for (date in listOf(LocalDate.of(2024, 2, 29), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 2, 1))) {
            val cells = standbyMonthCells(date)
            assertEquals(42, cells.size)
            assertEquals((1..date.lengthOfMonth()).toList(), cells.filterNotNull())
            assertEquals(date.withDayOfMonth(1).dayOfWeek.value % 7, cells.indexOf(1))
        }
    }
}

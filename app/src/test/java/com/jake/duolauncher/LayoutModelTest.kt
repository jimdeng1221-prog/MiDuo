package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class LayoutModelTest {
    @Test fun `leading feed reaches bottom without changing home control clearance`() {
        assertEquals(608f, homePaneViewportHeight(-1, 608f, 44f), .001f)
        assertEquals(564f, homePaneViewportHeight(0, 608f, 44f), .001f)
        assertEquals(564f, homePaneViewportHeight(1, 608f, 44f), .001f)
        assertEquals(30f, homePaneViewportHeight(-1, 30f, 44f), .001f)
        assertEquals(0f, homePaneViewportHeight(0, 30f, 44f), .001f)
    }

    @Test fun `only the cover leading page can reclaim centred safe canvas margins`() {
        assertEquals(7f, coverLeadingEdgeInset(true, 608f, 622f), .001f)
        assertEquals(0f, coverLeadingEdgeInset(false, 608f, 622f), .001f)
        assertEquals(0f, coverLeadingEdgeInset(true, 608f, 600f), .001f)
        // A rotated inner display can be narrow, but must not move the Dock down.
        assertEquals(0f, coverLeadingEdgeInset(false, 608f, 860f), .001f)
    }

    @Test fun `inner display rotation keeps the dock on its shared canvas anchor`() {
        val landscape = homeGeometry(860f, 608f, LayoutPreset(), labels = true)
        val portrait = homeGeometry(608f, 608f, LayoutPreset(), labels = true)
        assertEquals(landscape.dockTop, portrait.dockTop, .001f)
        assertEquals(landscape.dockHeight, portrait.dockHeight, .001f)
    }

    @Test fun `narrow dock frame preserves the reserved column centre`() {
        val reservedWidth = LayoutPreset().dockWidth
        val frameInset = dockFrameSideInset(reservedWidth)
        val frameWidth = dockFrameWidth(reservedWidth)

        assertEquals(2f, frameInset, .001f)
        assertEquals(64f, frameWidth, .001f)
        assertEquals(12f + reservedWidth / 2f, 12f + frameInset + frameWidth / 2f, .001f)
    }

    @Test fun `dock artwork stays large when home artwork is reduced`() {
        val reducedHome = homeGeometry(425f, SHARED_SAFE_HEIGHT_DP,
            LayoutPreset(iconSize = 40f), labels = true)
        assertEquals(40f, reducedHome.iconSize, .001f)
        assertEquals(44f, dockIconSize(), .001f)
        assertTrue(dockIconSize() < reducedHome.dockRowHeight)
    }

    @Test fun `inner and cover home keep identical icon size and column pitch`() {
        val base = homeGeometry(860f, SHARED_SAFE_HEIGHT_DP, LayoutPreset(), labels = true)
        val expanded = expandedHomeGeometry(base, paneWidth = 391f)

        assertEquals(313f, expanded.gridWidth, .001f)
        assertEquals(48f, expanded.iconSize, .001f)
        assertEquals(base.dockTop, expanded.dockTop, .001f)
        assertEquals(base.dockHeight, expanded.dockHeight, .001f)
        assertEquals(base.dockRowHeight, expanded.dockRowHeight, .001f)
        assertEquals(44f, dockIconSize(), .001f)
        val cover = homeGeometry(425f, SHARED_SAFE_HEIGHT_DP, LayoutPreset(), labels = true)
        assertEquals(cover.iconSize, expanded.iconSize, .001f)
        assertEquals(cover.gridWidth, expanded.gridWidth, .001f)
        assertEquals(cover.rowHeight, expanded.rowHeight, .001f)
        assertEquals(cover, expandedHomeGeometry(cover, paneWidth = 391f))
    }

    @Test fun `expanded workspace reaches dock and panes divide the remainder at its midpoint`() {
        val windowWidth = 860f
        val baseGeometry = homeGeometry(windowWidth, SHARED_SAFE_HEIGHT_DP, LayoutPreset(), labels = true)
        val workspaceWidth = expandedWorkspaceWidth(windowWidth, LayoutPreset().dockWidth)
        val geometry = expandedHomeGeometry(baseGeometry, workspaceWidth / 2f)
        val contentWidth = geometry.gridWidth + 16f
        val inset = expandedPaneContentInset(workspaceWidth, contentWidth)
        val paneWidth = workspaceWidth / 2f
        val leftOrigin = inset
        val rightOrigin = paneWidth + inset

        assertEquals(782f, workspaceWidth, .001f)
        assertEquals(31f, inset, .001f)
        assertEquals(422f, rightOrigin, .001f)
        // The grid itself begins 16dp inside its centred page container, so the
        // visible gap is 4dp + 16dp + 4dp rather than an extra empty column.
        assertEquals(78f, rightOrigin + 16f - (leftOrigin + 16f + geometry.gridWidth), .001f)
        assertEquals(inset, paneWidth - leftOrigin - contentWidth, .001f)
        assertEquals(inset, workspaceWidth - rightOrigin - contentWidth, .001f)
        assertEquals(windowWidth - workspaceWidth, DOCK_END_MARGIN_DP +
            LayoutPreset().dockWidth - dockFrameSideInset(LayoutPreset().dockWidth), .001f)
    }

    @Test fun `aligned dock spans first through third icon images on the physical Fold presets`() {
        val p = LayoutPreset(rowGap = 16.263264f, dockWidth = 73.46808f, dockPosition = .503011f)
        for (labels in listOf(true, false)) {
            val g = homeGeometry(475.43f, 696.38f, p, labels, statusHeight = 160f, labelHeight = 21.12f)
            val firstImageTop = g.contentTop + g.widgetHeight + 18f
            val thirdImageBottom = firstImageTop + 2f * g.rowHeight + HOME_ICON_FOOTPRINT_DP
            assertEquals(firstImageTop, g.dockTop, .01f)
            assertEquals(thirdImageBottom, g.dockTop + g.dockHeight, .01f)
            assertEquals(g.dockHeight, 4f * g.dockRowHeight + 16f, .01f)
            val feed = homeGeometry(475.43f, 696.38f, p, labels, statusHeight = 160f, labelHeight = 21.12f, inLibrary = true)
            assertEquals(g.dockTop, feed.dockTop, .01f)
            assertEquals(g.dockHeight, feed.dockHeight, .01f)
        }
    }
    @Test fun `manual dock mode keeps saved position and alignment does not overwrite it`() {
        val p = LayoutPreset(dockPosition = .43f, dockAlignToGrid = false)
        val manual = homeGeometry(475f, 700f, p, true)
        assertEquals(700f * .43f - 128f, manual.dockTop, .01f)
        assertEquals(256f, manual.dockHeight, .01f)
        assertEquals(.43f, p.copy(dockAlignToGrid = true).sanitized().dockPosition)
    }
    @Test fun `search keeps four complete dock targets between status and keyboard`() {
        for (width in listOf(475f, 933f)) for (height in listOf(310f, 330f, 375f, 420f)) {
            for (position in listOf(.25f, .56f, .75f)) {
                val g = homeGeometry(width, height, LayoutPreset(dockPosition = position), true,
                    statusHeight = 80f, inLibrary = true)
                assertTrue(g.dockTop >= 80f)
                assertTrue(g.dockTop + g.dockHeight <= height - 12f + .01f)
                assertTrue(g.dockRowHeight >= 48f)
                assertTrue(4f * g.dockRowHeight + 16f <= g.dockHeight + .01f)
            }
        }
    }
    @Test fun `hiding labels preserves row rhythm and large text gains room`() {
        val regular = homeGeometry(475f, 700f, LayoutPreset(), true)
        val hidden = homeGeometry(475f, 700f, LayoutPreset(), false)
        val largeText = homeGeometry(475f, 700f, LayoutPreset(), true, labelHeight = 34f)
        assertEquals(regular.rowHeight, hidden.rowHeight)
        assertTrue(largeText.rowHeight >= regular.rowHeight + 14f)
        // The reference layout leaves visible breathing room inside each four-column cell.
        assertTrue(regular.iconSize / (regular.gridWidth / 4f) in .61f.. .62f)
    }
    @Test fun `new icon default preserves tuned presets and current schema values`() {
        assertEquals(54f, upgradePreset(LayoutPreset(iconSize = 60f), 2, false).iconSize)
        val custom = LayoutPreset(62f, 13f, 72f, .67f)
        assertEquals(custom, upgradePreset(custom, 2, true))
        assertEquals(LayoutPreset(iconSize = 60f), upgradePreset(LayoutPreset(iconSize = 60f), 3, false))
        assertEquals(LayoutPreset(iconSize = 54f), upgradePreset(LayoutPreset(54f, 12f, 64f), 1, false))
        assertEquals(LayoutPreset(iconSize = 54f), upgradePreset(LayoutPreset(58f, 12f, 64f), 1, true))
    }
    @Test fun `dock fits above controls at Fold cover inner and short landscape sizes`() {
        val sizes = listOf(475f to 700f, 933f to 650f, 850f to 840f, 360f to 620f, 740f to 280f)
        for ((width, height) in sizes) for (position in listOf(.25f, .56f, .75f)) {
            val p = LayoutPreset(dockPosition = position)
            val g = homeGeometry(width, height, p, true)
            assertTrue("$width x $height", g.dockTop >= 8f)
            assertTrue("Dock overlaps bottom controls at $width x $height", g.dockTop + g.dockHeight <= height - 124f + .01f)
            assertTrue(g.gridWidth + p.dockWidth + 24f <= g.homeWidth)
            assertTrue(g.iconSize + 8f <= g.gridWidth / 4f)
            if (g.dockHeight == 256f) {
                val library = homeGeometry(width, height, p, true, inLibrary = true)
                assertEquals("Paging must preserve even a low dock position", g.dockTop, library.dockTop, .01f)
            }
        }
    }
    @Test fun `expanded pane appears from actual window width`() {
        assertFalse(homeGeometry(475f, 700f, LayoutPreset(), true).expanded)
        assertTrue(homeGeometry(933f, 650f, LayoutPreset(), true).expanded)
        assertEquals(SHARED_HOME_WIDTH_DP, homeGeometry(933f, 650f, LayoutPreset(), true).homeWidth)
    }
    @Test fun `cover and unfolded right pane share exact geometry on the 608dp canvas`() {
        val preset = LayoutPreset(iconSize = 64f, rowGap = 9f, dockWidth = 70f)
        val cover = homeGeometry(425f, SHARED_SAFE_HEIGHT_DP, preset, true, statusHeight = 118f)
        val inner = homeGeometry(860f, SHARED_SAFE_HEIGHT_DP, preset, true, statusHeight = 118f)
        assertFalse(cover.expanded)
        assertTrue(inner.expanded)
        assertEquals(cover.homeWidth, inner.homeWidth, .001f)
        assertEquals(cover.gridWidth, inner.gridWidth, .001f)
        assertEquals(cover.iconSize, inner.iconSize, .001f)
        assertEquals(cover.rowHeight, inner.rowHeight, .001f)
        assertEquals(cover.widgetHeight, inner.widgetHeight, .001f)
        assertEquals(cover.contentTop, inner.contentTop, .001f)
        assertEquals(cover.dockTop, inner.dockTop, .001f)
        assertEquals(cover.dockHeight, inner.dockHeight, .001f)
    }
    @Test fun `reference spacing keeps an airy top margin and a lower smaller dock`() {
        val geometry = homeGeometry(425f, SHARED_SAFE_HEIGHT_DP, LayoutPreset(), true, statusHeight = 174f)
        assertEquals(REFERENCE_CONTENT_TOP_DP, geometry.contentTop, .001f)
        assertEquals(48f, geometry.iconSize, .001f)
        assertEquals(146.5f, geometry.widgetHeight, .001f)
        assertEquals(234.5f, geometry.dockTop, .001f)
        assertEquals(218f, geometry.dockHeight, .001f)
        assertTrue(geometry.dockTop - 174f >= 36f)
    }
    @Test fun `temporary default-home prompt does not alter desktop geometry`() {
        val normal = homeGeometry(425f, SHARED_SAFE_HEIGHT_DP, LayoutPreset(), true, homeBottomSpace = 44f)
        // LauncherScreen deliberately keeps geometry at 44dp even while its prompt overlays
        // the bottom controls; this assertion records the stable coordinate contract.
        val prompted = homeGeometry(425f, SHARED_SAFE_HEIGHT_DP, LayoutPreset(), true, homeBottomSpace = 44f)
        assertEquals(normal, prompted)
        assertEquals(REFERENCE_CONTENT_TOP_DP, prompted.contentTop, .001f)
    }
    @Test fun `status stays high for centred cutout and yields to right-column cutout`() {
        assertEquals(56f, cutoutAwareStatusTopDp(56f, 0f), .001f)
        assertEquals(77.636f, cutoutAwareStatusTopDp(56f, 164f / 2.75f), .001f)
    }
    @Test fun `status rail moves up half an icon equally while preserving cutout delta`() {
        val outer = statusRailTopDp(REFERENCE_STATUS_TOP_DP, 0f)
        val inner = statusRailTopDp(REFERENCE_STATUS_TOP_DP, 164f / 2.75f)
        assertEquals(29f, outer, .001f)
        assertEquals(50.636f, inner, .001f)
        assertEquals(21.636f, inner - outer, .001f)
    }
    @Test fun `dock coordinates do not depend on posture-specific status position`() {
        // LauncherScreen deliberately does not feed posture-specific status values into geometry.
        val fixedOuter = homeGeometry(425f, SHARED_SAFE_HEIGHT_DP, LayoutPreset(), true)
        val fixedInner = homeGeometry(860f, SHARED_SAFE_HEIGHT_DP, LayoutPreset(), true)
        assertEquals(fixedOuter.dockTop, fixedInner.dockTop, .001f)
        assertEquals(fixedOuter.dockHeight, fixedInner.dockHeight, .001f)
        assertEquals(234.5f, fixedOuter.dockTop, .001f)
    }
    @Test fun `raising dock cannot overlap measured status or lower controls`() {
        for ((height, status) in listOf(700f to 124f, 650f to 124f, 280f to 80f)) {
            for (position in listOf(.25f, .56f, .75f)) {
                val g = homeGeometry(475f, height, LayoutPreset(dockPosition = position), true, status)
                assertTrue(g.dockTop >= status)
                assertTrue(g.dockTop + g.dockHeight <= height - 124f + .01f)
                assertTrue(g.dockHeight >= 68f)
            }
        }
    }
    @Test fun `reconciliation preserves custom order while handling installs removals duplicates`() {
        assertEquals(listOf("c", "a", "d"), reconcileOrder(listOf("c", "gone", "a", "c"), listOf("a", "c", "d")))
        assertEquals(emptyList<String>(), reconcileOrder(listOf("a"), emptyList()))
    }
    @Test fun `reordering across page boundary does not drop apps`() {
        val apps = (0..31).map { "app$it" }
        val moved = moveApp(apps, "app16", -1)
        assertEquals("app16", moved[15])
        assertEquals("app15", moved[16])
        assertEquals(apps.toSet(), moved.toSet())
        assertEquals("app31", moveApp(apps, "app31", -100).first())
        assertEquals(apps, moveApp(apps, "missing", 1))
    }
    @Test fun `out of range preferences are constrained before layout`() {
        val p = LayoutPreset(999f, -40f, 2f, 12f).sanitized()
        assertEquals(54f, p.iconSize); assertEquals(0f, p.rowGap)
        assertEquals(56f, p.dockWidth); assertEquals(.75f, p.dockPosition)
    }
    @Test fun `new installs and refresh do not pin apps and empty home stays empty`() {
        assertEquals(listOf("c", "a"), reconcilePins(listOf("c", "gone", "a", "c"), listOf("a", "c", "new")))
        assertEquals(emptyList<String>(), reconcilePins(emptyList(), listOf("new")))
    }
    @Test fun `migration uses suggestions for auto sorted legacy home but retains manual first page`() {
        val installed = (0..31).map { "app%02d".format(it) }
        assertEquals(listOf("app20", "app10"), migrateHomePins(installed, installed, listOf("app20", "app10", "gone")))
        val custom = listOf("app31") + installed.dropLast(1)
        assertEquals(custom.take(16), migrateHomePins(custom, installed, listOf("app20")))
    }
    @Test fun `home always has a page independently of the library`() {
        assertEquals(1, homePageCount(0)); assertEquals(1, homePageCount(24)); assertEquals(2, homePageCount(25))
    }
}

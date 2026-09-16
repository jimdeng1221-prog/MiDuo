package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class LeadingGridEditingTest {
    @Test fun `native binding can fill every default slot without moving other widgets`() {
        var layout = withDefaultLeadingWidgets(HomeLayout(listOf("keep"), List(4) { "dock$it" }, DEFAULT_WIDGET_PLACEMENTS))
        val defaults = layout.widgetPlacements.filter { it.page == -1 }
        defaults.forEachIndexed { index, original ->
            val bound = original.copy(id = 100 + index)
            val next = placeWidget(layout, bound)
            assertEquals(bound, next.placement(original.slot))
            assertEquals(layout.widgetPlacements.filter { it.slot != original.slot },
                next.widgetPlacements.filter { it.slot != original.slot })
            assertEquals(layout.slots, next.slots)
            assertEquals(layout.dock, next.dock)
            layout = next
        }
    }

    @Test fun `explicit dashboard replaces only leading page and clears its stale restore descriptors`() {
        val home = DEFAULT_WIDGET_PLACEMENTS
        val original = HomeLayout(listOf("keep"), List(4) { "dock$it" },
            home + WidgetPlacement(8, NEEDS_BINDING_WIDGET, -1, 0, 0, 2, 2),
            widgetRestores = listOf(WidgetRestore(8, "app/Provider", 0, "saved", "Personal")),
            leadingSlots = List(HOME_CELLS) { if (it == 23) "shortcut" else null })
        val result = withLeadingDashboard(original)
        assertEquals(home, result.widgetPlacements.filter { it.page >= 0 })
        assertEquals(original.slots, result.slots)
        assertEquals(original.dock, result.dock)
        assertTrue(result.leadingSlots.all { it == null })
        assertTrue(result.widgetRestores.isEmpty())
        val cells = result.widgetPlacements.filter { it.page == -1 }.flatMap { placement ->
            (placement.row until placement.row + placement.spanY).flatMap { row ->
                (placement.column until placement.column + placement.spanX).map { row * GRID_COLUMNS + it }
            }
        }
        assertEquals(28, cells.size)
        assertEquals(28, cells.toSet().size)
    }

    @Test fun `blank leading page gets the five-widget dashboard once`() {
        val original = HomeLayout(emptyList(), List(4) { null }, DEFAULT_WIDGET_PLACEMENTS)
        val seeded = withDefaultLeadingWidgets(original)
        assertEquals(original.widgetPlacements, seeded.widgetPlacements.filter { it.page >= 0 })
        assertEquals(listOf(
            WidgetPlacement(2, MONTH_WIDGET, -1, 0, 2, 2, 2),
            WidgetPlacement(3, WEATHER_WIDGET, -1, 0, 0, 4, 2),
            WidgetPlacement(4, BATTERY_WIDGET, -1, 0, 4, 2, 2),
            WidgetPlacement(5, AGENDA_WIDGET, -1, 2, 2, 2, 2),
            WidgetPlacement(6, MAP_WIDGET, -1, 0, 6, 4, 2),
        ), seeded.widgetPlacements.filter { it.page == -1 })
        assertSame(seeded, withDefaultLeadingWidgets(seeded))
    }

    @Test fun `existing leading content is preserved without automatic widgets`() {
        val shortcut = HomeLayout(emptyList(), emptyList(), leadingSlots = List(HOME_CELLS) { if (it == 7) "keep" else null })
        assertSame(shortcut, withDefaultLeadingWidgets(shortcut))
        val widget = HomeLayout(emptyList(), emptyList(), listOf(WidgetPlacement(9, CLOCK_WIDGET, -1, 1, 1, 2, 2)))
        assertSame(widget, withDefaultLeadingWidgets(widget))
    }

    @Test fun `scrolling leading widgets bind and collide without aliasing home shortcuts`() {
        val initial = HomeLayout(listOf("home"), emptyList())
        val belowFold = WidgetPlacement(7, MAP_WIDGET, -1, 0, 6, 4, 2)
        val layout = placeWidget(initial, belowFold)
        assertEquals(belowFold, layout.placement(7))
        assertEquals((24 until 32).map { homeCellIndex(-1, it) }.toSet(), belowFold.coveredIndices())
        assertTrue(validBackupPlacement(belowFold))
        assertEquals(listOf("home"), layout.slots)
        assertSame(layout, placeWidget(layout, belowFold.copy(slot = 8, row = 7)))
        assertEquals(belowFold.copy(id = 999), placeWidget(layout, belowFold.copy(id = 999)).placement(7))
        assertSame(initial, placeWidget(initial, belowFold.copy(page = 0)))
        assertFalse(validBackupPlacement(belowFold.copy(page = 0)))
        assertSame(initial, placeWidget(initial, belowFold.copy(row = LEADING_WIDGET_ROWS)))
    }

    @Test fun `signed leading addresses round trip without changing normal pages`() {
        assertEquals(-1, homeCellPage(-24)); assertEquals(0, homeCellLocal(-24))
        assertEquals(-1, homeCellPage(-1)); assertEquals(23, homeCellLocal(-1))
        assertEquals(-24, homeCellIndex(-1, 0)); assertEquals(-1, homeCellIndex(-1, 23))
        assertEquals(0, homeCellIndex(0, 0)); assertEquals(24, homeCellIndex(1, 0))
        val layout = HomeLayout(listOf("screen1"), emptyList(), leadingSlots = List(24) { if (it == 23) "leading" else null })
        assertEquals("leading", layout.slotAt(-1)); assertEquals(-1, layout.indexOfShortcut("leading"))
        assertEquals("screen1", layout.slotAt(0)); assertEquals(1, layout.pageCount)
    }

    @Test fun `overflow addresses round trip and cannot overwrite shortcuts`() {
        val layout = HomeLayout(listOf("keep"), emptyList())
        for (local in 0 until LEADING_WIDGET_ROWS * GRID_COLUMNS) {
            val index = homeCellIndex(-1, local)
            assertEquals(-1, homeCellPage(index))
            assertEquals(local, homeCellLocal(index))
            if (local >= HOME_CELLS) {
                assertNull(layout.slotAt(index))
                assertSame(layout, layout.withSlot(index, "invalid"))
                assertSame(layout, dropApp(layout, "invalid", DropTarget.Home(index)))
            }
        }
    }

    @Test fun `new widgets can be placed below dashboard without overlapping it`() {
        val layout = withDefaultLeadingWidgets(HomeLayout(listOf("keep"), emptyList()))
        val candidate = widgetCandidate(layout, 99, homeCellIndex(-1, 8 * GRID_COLUMNS), 4, 2)!!
        val placed = placeWidget(layout, candidate.copy(id = CLOCK_WIDGET))
        assertEquals(8, placed.placement(99)?.row)
        assertNull(widgetCandidate(placed, 100, homeCellIndex(-1, 9 * GRID_COLUMNS), 2, 2))
        val moved = moveWidget(placed, 99, homeCellIndex(-1, 20 * GRID_COLUMNS))
        assertEquals(20, moved.placement(99)?.row)
        assertEquals(layout.slots, moved.slots)
        assertTrue(validBackupPlacement(moved.placement(99)!!))
    }

    @Test fun `missing home folder never aliases last leading cell`() {
        assertNull(shortcutIndexOnPage(-1, "folder", listOf("folder"), List(HOME_CELLS) { null }))
        assertNull(shortcutIndexOnPage(0, "missing", listOf("folder"), emptyList()))
        val leading = List(HOME_CELLS) { if (it == 23) "real-folder" else null }
        assertEquals(-1, shortcutIndexOnPage(-1, "real-folder", emptyList(), leading))
        assertNull(shortcutIndexOnPage(1, "folder", listOf("folder"), leading))
    }

    @Test fun `leading page reserves empty space and expands while dragging`() {
        assertEquals(12, homeRenderedRows(-1, 8, false))
        assertEquals(LEADING_WIDGET_ROWS, homeRenderedRows(-1, 8, true))
        assertEquals(LEADING_WIDGET_ROWS, homeRenderedRows(-1, LEADING_WIDGET_ROWS, false))
        assertEquals(GRID_ROWS, homeRenderedRows(0, 2, true))
    }

    @Test fun `leading moves into empty cells directly and occupied insertion stays bounded`() {
        val leading = MutableList<String?>(24) { null }.apply { this[0] = "a"; this[1] = "b"; this[23] = "z" }
        val before = HomeLayout(listOf("home"), emptyList(), leadingSlots = leading)
        val direct = dropApp(before, "a", DropTarget.Home(-22))
        assertNull(direct.slotAt(-24)); assertEquals("a", direct.slotAt(-22)); assertEquals("b", direct.slotAt(-23))
        val inserted = dropApp(before, "new", DropTarget.Home(-23))
        assertEquals(listOf("a", "new", "b"), listOf(-24, -23, -22).map(inserted::slotAt))
        assertEquals(listOf("home"), inserted.slots)
        val full = HomeLayout(listOf("home"), emptyList(), leadingSlots = List(24) { "l$it" })
        assertSame(full, dropApp(full, "new", DropTarget.Home(-24)))
    }

    @Test fun `cross-surface moves are atomic and never duplicate shortcuts`() {
        val before = HomeLayout(listOf("home"), listOf(null, null, null, null), leadingSlots = List(24) { null })
        val toLeading = dropApp(before, "home", DropTarget.Home(-24))
        assertEquals("home", toLeading.slotAt(-24)); assertNull(toLeading.slotAt(0))
        val back = dropApp(toLeading, "home", DropTarget.Home(4))
        assertNull(back.slotAt(-24)); assertEquals("home", back.slotAt(4))
        assertEquals(1, (back.leadingSlots + back.slots + back.dock).count { it == "home" })
    }

    @Test fun `leading widgets collide with leading apps using signed cells`() {
        val widget = WidgetPlacement(4, 26, -1, 0, 0, 2, 2)
        val leading = List<String?>(24) { if (it == 2) "app" else null }
        val layout = HomeLayout(emptyList(), emptyList(), listOf(widget), leadingSlots = leading)
        assertEquals(setOf(-24, -23, -20, -19), widget.coveredIndices())
        assertNull(widgetCandidate(layout, 5, -24, 2, 2))
        assertNull(widgetCandidate(layout, 5, -22, 1, 1))
        assertEquals(WidgetPlacement(5, EMPTY_WIDGET, -1, 2, 1, 1, 1), widgetCandidate(layout, 5, -18, 1, 1))
        assertEquals(widget.copy(column = 2), moveWidget(layout.copy(leadingSlots = List(24) { null }), 4, -22).placement(4))
    }

    @Test fun `folders create dissolve and transfer on leading surface`() {
        val folder = FolderEntry("folder:00000000-0000-0000-0000-000000000008", "Pair", emptyList())
        val before = HomeLayout(listOf("b"), emptyList(), leadingSlots = List(24) { if (it == 0) "a" else null })
        val created = createFolder(before, folder, "a", "b", -24)
        assertEquals(folder.id, created.slotAt(-24)); assertNull(created.slotAt(0))
        val extracted = removeAppFromFolder(created, folder.id, "a", DropTarget.Home(3))
        assertEquals("b", extracted.slotAt(-24)); assertEquals("a", extracted.slotAt(3))
        assertTrue(extracted.folders.isEmpty())
    }
}

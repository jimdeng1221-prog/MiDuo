package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class GridEditingTest {
    @Test fun `widget candidate validates empty current and temporary pages without sentinel persistence`() {
        val occupied = WidgetPlacement(0, 100, 0, 0, 0, 2, 2)
        val layout = HomeLayout(List(9) { if (it == 8) "app" else null }, emptyList(), listOf(occupied))
        assertNull(widgetCandidate(layout, 1, 0, 2, 2))
        assertNull(widgetCandidate(layout, 1, 8, 2, 2))
        assertNull(widgetCandidate(layout, 1, 3, 2, 2))
        assertEquals(WidgetPlacement(1, EMPTY_WIDGET, 1, 0, 0, 4, 2),
            widgetCandidate(layout, 1, HOME_CELLS, 4, 2))
        assertEquals(WidgetPlacement(0, EMPTY_WIDGET, 0, 0, 0, 2, 2),
            widgetCandidate(layout, 0, 0, 2, 2))
    }

    @Test fun `schema5 apps retain pages gaps and rows below top widgets`() {
        val old = MutableList<String?>(18) { null }.apply { this[0] = "a"; this[15] = "p"; this[17] = "b" }
        val migrated = migrateSchema5Apps(old)
        assertEquals("a", migrated[8])
        assertEquals("p", migrated[23])
        assertNull(migrated[24])
        assertEquals("b", migrated[33])
        assertEquals(2, homePageCount(migrated.size))
    }

    @Test fun `schema5 widgets preserve stable slots and special panels`() {
        val migrated = migrateSchema5Widgets(listOf(101, DATE_WIDGET, 102, 201, EMPTY_WIDGET, 202))
        assertEquals(WidgetPlacement(0, 101, 0, 0, 0, 2, 2), migrated[0])
        assertEquals(WidgetPlacement(2, 102, -1, 0, 0, 4, 6), migrated[2])
        assertEquals(WidgetPlacement(5, 202, 1, 0, 6, 4, 4), migrated.last())
        assertEquals(2, HomeLayout(emptyList(), emptyList(), migrated).pageCount)
    }

    @Test fun `legacy overflow does not block the following page`() {
        val overflow = WidgetPlacement(5, 202, 1, 0, 6, 4, 4)
        val before = HomeLayout(emptyList(), emptyList(), listOf(overflow))
        val next = dropApp(before, "app", DropTarget.Home(2 * HOME_CELLS))
        assertEquals("app", next.slots[2 * HOME_CELLS])
    }

    @Test fun `existing app insertion rotates only usable cells around widget footprint`() {
        val widget = WidgetPlacement(0, 101, 0, 1, 1, 2, 2) // cells 5, 6, 9, 10
        val slots = MutableList<String?>(12) { null }.apply {
            listOf(0, 1, 2, 3, 4, 7, 8, 11).forEach { this[it] = "app$it" }
        }
        val before = HomeLayout(slots, emptyList(), listOf(widget))
        val next = dropApp(before, "app0", DropTarget.Home(11))
        assertEquals(listOf("app1", "app2", "app3", "app4", "app7", "app8", "app11", "app0"),
            listOf(0, 1, 2, 3, 4, 7, 8, 11).map { next.slots[it] })
        assertEquals(before.widgetPlacements, next.widgetPlacements)
    }

    @Test fun `new insertion shifts through usable cells around widget footprint`() {
        val widget = WidgetPlacement(0, 101, 0, 1, 1, 2, 2)
        val slots = MutableList<String?>(12) { null }.apply {
            this[4] = "a"; this[7] = "b"; this[8] = "c"; this[11] = "d"
        }
        val next = dropApp(HomeLayout(slots, emptyList(), listOf(widget)), "new", DropTarget.Home(4))
        assertEquals(listOf("new", "a", "b", "c", "d"), listOf(4, 7, 8, 11, 12).map { next.slots[it] })
    }

    @Test fun `full page insertion spills past widget cells at start of next page`() {
        val pageOneWidget = WidgetPlacement(0, 101, 1, 0, 0, 4, 1)
        val before = HomeLayout((0 until HOME_CELLS).map(Int::toString), emptyList(), listOf(pageOneWidget))
        val next = dropApp(before, "new", DropTarget.Home(HOME_CELLS - 1))
        assertEquals("new", next.slots[23])
        assertEquals("23", next.slots[28])
        assertTrue((24..27).all { next.slots[it] == null })
    }

    @Test fun `widget movement and resize reject app and widget collisions`() {
        val before = HomeLayout(List(9) { if (it == 8) "app" else null }, emptyList(), DEFAULT_WIDGET_PLACEMENTS)
        assertSame(before, moveWidget(before, 2, 0))
        assertSame(before, resizeWidget(before, 0, 4, 2))
        assertSame(before, moveWidget(before, 0, 8))
    }

    @Test fun `imported widget restore metadata follows geometry until rebound or removed`() {
        val restore = WidgetRestore(4, "com.example/.Widget", 42, "Weather", "Work")
        val placeholder = WidgetPlacement(4, NEEDS_BINDING_WIDGET, 0, 0, 0, 2, 2)
        val before = HomeLayout(emptyList(), emptyList(), listOf(placeholder), widgetRestores = listOf(restore))
        val moved = moveWidget(before, 4, 8)
        assertEquals(restore, moved.widgetRestore(4))
        val rebound = placeWidget(moved, moved.placement(4)!!.copy(id = 100))
        assertNull(rebound.widgetRestore(4))
        assertTrue(removePlacement(moved, DropTarget.Widget(4)).widgetRestores.isEmpty())
    }

    @Test fun `backup widget geometry rejects extreme pages slots and unbounded leading panel rows`() {
        assertFalse(validBackupPlacement(WidgetPlacement(Int.MAX_VALUE, NEEDS_BINDING_WIDGET, 0, 0, 0, 1, 1)))
        assertFalse(validBackupPlacement(WidgetPlacement(1, NEEDS_BINDING_WIDGET, Int.MAX_VALUE, 0, 0, 1, 1)))
        assertFalse(validBackupPlacement(WidgetPlacement(1, NEEDS_BINDING_WIDGET, -1, 0, LEADING_WIDGET_ROWS, 1, 1)))
        assertTrue(validBackupPlacement(WidgetPlacement(1, NEEDS_BINDING_WIDGET, 99, 3, 5, 1, 1)))
    }

    @Test fun `reexport preserves unresolved work widget source scope`() {
        val restore = WidgetRestore(1, "com.example/.Widget", 42, "Weather", "Work", isWork = true, sourceScope = "source-a")
        assertEquals("source-a", exportedWidgetScope(restore, "source-b"))
        assertEquals("source-b", exportedWidgetScope(restore.copy(sourceScope = null), "source-b"))
    }

    @Test fun `legacy overflow replacement is exact while bounded leading panels remain editable`() {
        val leading = WidgetPlacement(2, INFO_WIDGET, -1, 0, 0, 4, 6)
        val overflow = WidgetPlacement(5, 101, 1, 0, 6, 4, 4)
        val before = HomeLayout(emptyList(), emptyList(), listOf(leading, overflow))
        assertEquals(202, placeWidget(before, overflow.copy(id = 202)).placement(5)?.id)
        assertEquals(303, placeWidget(before, leading.copy(id = 303)).placement(2)?.id)
        assertEquals(5, placeWidget(before, leading.copy(spanY = 5)).placement(2)?.spanY)
        assertSame(before, placeWidget(before, overflow.copy(id = 202, row = 5)))
        assertSame(before, placeWidget(before, overflow.copy(slot = 8, id = 202)))
        assertEquals(404, placeWidget(HomeLayout(emptyList(), emptyList()),
            WidgetPlacement(9, 404, -1, 0, 0, 4, 6)).placement(9)?.id)
        assertSame(before, placeWidget(before, leading.copy(row = LEADING_WIDGET_ROWS, spanY = 1)))
    }
}

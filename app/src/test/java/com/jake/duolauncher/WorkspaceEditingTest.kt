package com.jake.duolauncher

import org.junit.Assert.*
import org.junit.Test

class WorkspaceEditingTest {
    @Test fun `widget collision is rejected and retains bindings and apps`() {
        val before = HomeLayout(listOf(null, null, null, null, null, null, null, null, "a", "b"), listOf("a"), DEFAULT_WIDGET_PLACEMENTS)
        assertEquals(before, moveWidget(before, 0, 2))
    }
    @Test fun `widget creates a page even with no apps on that page`() {
        val before = HomeLayout(emptyList(), List(4) { null }, listOf(WidgetPlacement(0, CLOCK_WIDGET, 0, 0, 0, 2, 2)))
        val next = moveWidget(before, 0, HOME_CELLS)
        assertEquals(2, next.pageCount)
        assertEquals(1, next.placement(0)?.page)
        assertEquals(1, removePlacement(next, DropTarget.Widget(0)).pageCount)
    }
    @Test fun `widget only page permits app drop onto the following new page`() {
        val before = HomeLayout(listOf("a"), List(4) { null }, listOf(WidgetPlacement(0, CLOCK_WIDGET, 1, 0, 0, 2, 2)))
        assertEquals("a", dropApp(before, "a", DropTarget.Home(48)).slots[48])
    }
    @Test fun `home insertion crossing a page boundary leaves widgets and dock unchanged`() {
        val before = HomeLayout((0 until 18).map(Int::toString), listOf("dock", null))
        val next = dropApp(before, "new", DropTarget.Home(15))
        assertEquals("new", next.slots[15])
        assertEquals("15", next.slots[16])
        assertEquals("16", next.slots[17])
        assertEquals("17", next.slots[18])
        assertEquals(before.dock, next.dock)
        assertEquals(before.widgets, next.widgets)
    }
    @Test fun `cross area app moves never alter widgets`() {
        val widgets = listOf(WidgetPlacement(0, 41, -1, 0, 0, 4, 6))
        val before = HomeLayout(listOf("moving", "home"), listOf("a", null, "c", "d"), widgets)
        val inDock = dropApp(before, "moving", DropTarget.Dock(1))
        assertEquals(widgets, inDock.widgetPlacements)
        val backHome = dropApp(inDock, "moving", DropTarget.Home(1))
        assertEquals(widgets, backHome.widgetPlacements)
        assertNull(backHome.dock[1])
        assertEquals("moving", backHome.slots[1])
        assertEquals((before.slots + before.dock).filterNotNull().toSet(),
            (backHome.slots + backHome.dock).filterNotNull().toSet())
    }
    @Test fun `invalid widget drop and library removal are inert`() {
        val before = HomeLayout(listOf("a"), listOf("a"))
        assertEquals(before, moveWidget(before, 0, HOME_CELLS * 2))
        assertEquals(before, moveWidget(before, -1, 0))
        assertEquals(before, removePlacement(before, DropTarget.Library("a")))
        val removed = removePlacement(before, DropTarget.Home(0))
        assertEquals(before.dock, removed.dock)
        assertEquals(before.widgets, removed.widgets)
    }
    @Test fun `one gesture cannot travel beyond adjacent pages`() {
        assertEquals(3f, boundedPagePosition(8f, 2, 10), 0f)
        assertEquals(1f, boundedPagePosition(-5f, 2, 10), 0f)
        assertEquals(2f, boundedPagePosition(2f, 2, 10), 0f)
        assertEquals(0f, boundedPagePosition(-2f, 0, 5), 0f)
        assertEquals(4f, boundedPagePosition(8f, 4, 5), 0f)
    }
    @Test fun `release velocity stays within the gesture origin and reversals return`() {
        assertEquals(3, releasePage(2.95f, 2, 8, -9000f, 400f))
        assertEquals(1, releasePage(1.05f, 2, 8, 9000f, 400f))
        assertEquals(2, releasePage(2.7f, 2, 8, 900f, 400f))
        assertEquals(2, releasePage(2.1f, 2, 8, 0f, 400f))
        assertEquals(0, releasePage(0f, 0, 8, 9000f, 400f))
        assertEquals(7, releasePage(7f, 7, 8, -9000f, 400f))
    }
    @Test fun `deliberate short drags commit even after slowing or pausing`() {
        assertEquals(3, releasePage(2.26f, 2, 8, 0f, 250f))
        assertEquals(1, releasePage(1.74f, 2, 8, 0f, 250f))
        assertEquals(3, releasePage(2.26f, 2, 8, -180f, 250f))
        assertEquals(1, releasePage(1.74f, 2, 8, 180f, 250f))
        // The same thumb travel represents a smaller fraction of an unfolded page.
        assertEquals(3, releasePage(2.12f, 2, 8, 0f, 250f, distanceThreshold = .09f))
    }
    @Test fun `small peeks cancel while intentional flicks and reversals respect direction`() {
        assertEquals(2, releasePage(2.07f, 2, 8, 0f, 250f))
        assertEquals(2, releasePage(1.93f, 2, 8, 0f, 250f))
        assertEquals(3, releasePage(2.12f, 2, 8, -300f, 250f))
        assertEquals(1, releasePage(1.88f, 2, 8, 300f, 250f))
        assertEquals(2, releasePage(2.3f, 2, 8, 300f, 250f))
        assertEquals(2, releasePage(1.7f, 2, 8, -300f, 250f))
    }
}

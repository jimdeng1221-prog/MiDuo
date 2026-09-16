package com.jake.duolauncher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetMoveDragTest {
    @Test fun `widget edge scrolling reaches below viewport but ignores other pane`() {
        val viewport = Rect(0f, 100f, 400f, 900f)
        assertEquals(0f, widgetAutoScrollDelta(Offset(200f, 500f), viewport, 64f, 12f), .001f)
        assertEquals(12f, widgetAutoScrollDelta(Offset(200f, 910f), viewport, 64f, 12f), .001f)
        assertEquals(-12f, widgetAutoScrollDelta(Offset(200f, 90f), viewport, 64f, 12f), .001f)
        assertEquals(6f, widgetAutoScrollDelta(Offset(200f, 868f), viewport, 64f, 12f), .001f)
        assertEquals(0f, widgetAutoScrollDelta(Offset(450f, 890f), viewport, 64f, 12f), .001f)
        assertEquals(0f, widgetAutoScrollDelta(Offset(200f, 990f), viewport, 64f, 12f), .001f)
    }

    @Test fun `overflow grab remains on leading page instead of aliasing home`() {
        val placement = WidgetPlacement(4, 26, -1, 0, 6, 4, 2)
        assertEquals(homeCellIndex(-1, 12 * GRID_COLUMNS), adjustedWidgetDropIndex(
            homeCellIndex(-1, 13 * GRID_COLUMNS + 2), placement, Rect(0f, 0f, 400f, 200f), Offset(250f, 150f)))
    }
    @Test fun `source preview does not shadow the home cell beneath it`() {
        val drag = HomeDragState()
        val source = DragRegion(DropTarget.Widget(4), Rect(0f, 0f, 300f, 300f), null, 1, widgetId = 26)
        drag.source = source
        drag.register(Any(), DragRegion(DropTarget.Home(27), Rect(0f, 0f, 100f, 100f), null, 1))
        drag.register(Any(), source.copy(bounds = Rect(0f, 0f, 300f, 300f)))

        assertEquals(DropTarget.Home(27), drag.destination(Offset(50f, 50f), setOf(1))?.target)
    }

    @Test fun `leading widget preview does not shadow its signed home cell`() {
        val drag = HomeDragState()
        drag.source = DragRegion(DropTarget.Widget(4), Rect(0f, 0f, 300f, 300f), null, 1, widgetId = 26)
        drag.register(Any(), DragRegion(DropTarget.Home(-24), Rect(0f, 0f, 100f, 100f), null, -1))
        drag.register(Any(), DragRegion(DropTarget.Widget(2), Rect(0f, 0f, 100f, 100f), null, -1, widgetId = INFO_WIDGET))

        assertEquals(DropTarget.Home(-24), drag.destination(Offset(50f, 50f), setOf(-1, 0))?.target)
    }

    @Test fun `regular widget region does not steal its underlying home destination`() {
        val drag = HomeDragState()
        drag.source = DragRegion(DropTarget.Widget(4), Rect(0f, 0f, 300f, 300f), null, 1, widgetId = 26)
        drag.register(Any(), DragRegion(DropTarget.Home(28), Rect(0f, 0f, 100f, 100f), null, 1))
        drag.register(Any(), DragRegion(DropTarget.Widget(5), Rect(0f, 0f, 100f, 100f), null, 1, widgetId = 27))

        assertEquals(DropTarget.Home(28), drag.destination(Offset(50f, 50f), setOf(1))?.target)
    }

    @Test fun `right-edge grab of three-column widget reaches rightmost legal anchor on another page`() {
        val placement = WidgetPlacement(4, 26, 1, 0, 0, 3, 3)
        val bounds = Rect(100f, 200f, 400f, 500f)
        val rawRightCellOnPageThree = 2 * HOME_CELLS + 3

        assertEquals(2 * HOME_CELLS + 1,
            adjustedWidgetDropIndex(rawRightCellOnPageThree, placement, bounds, Offset(399f, 350f)))
    }

    @Test fun `right-edge grab keeps a leading-page address signed`() {
        val placement = WidgetPlacement(4, 26, 0, 0, 0, 3, 3)
        val bounds = Rect(100f, 200f, 400f, 500f)

        assertEquals(homeCellIndex(-1, 1), adjustedWidgetDropIndex(
            homeCellIndex(-1, 3), placement, bounds, Offset(399f, 350f)))
    }

    @Test fun `moving sole last-page widget to earlier page shrinks page count without changing its id`() {
        val placement = WidgetPlacement(4, 26, 1, 0, 0, 3, 3)
        val before = HomeLayout(emptyList(), emptyList(), listOf(placement))
        val moved = moveWidget(before, placement.slot, 1)

        assertEquals(1, moved.pageCount)
        assertEquals(WidgetPlacement(4, 26, 0, 1, 0, 3, 3), moved.placement(4))
    }
}

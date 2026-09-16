package com.jake.duolauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiPageTestLayoutTest {
    private fun app(index: Int, work: Boolean = false) = Phase22AppCandidate(
        id = "app-$index/Main", packageName = "app-$index", label = "App $index", isWork = work)

    @Test fun `appends five lightly populated pages without changing existing layout`() {
        val leading = List(HOME_CELLS) { if (it == 4) "leading/Main" else null }
        val widgets = listOf(WidgetPlacement(9, CLOCK_WIDGET, 2, 0, 0, 2, 2))
        val restores = listOf(WidgetRestore(9, "provider/Widget", 0L, "Widget", "Owner"))
        val folders = listOf(FolderEntry("folder:one", "Folder", listOf("folder-app/Main")))
        val originalSlots = List(HOME_CELLS * 2) { index ->
            when (index) { 0 -> "home/Main"; HOME_CELLS -> "folder:one"; else -> null }
        }
        val original = HomeLayout(originalSlots, listOf("dock/Main", null), widgets, folders, restores, leading)

        val result = multiPageTestLayout(original,
            listOf(app(90, work = true)) + (0..30).map(::app) + listOf(
                Phase22AppCandidate("home/Main", "home", "Home"),
                Phase22AppCandidate("dock/Main", "dock", "Dock"),
                Phase22AppCandidate("leading/Main", "leading", "Leading"),
                Phase22AppCandidate("folder-app/Main", "folder-app", "Folder app"),
            ))

        assertEquals(5, result.pageCount)
        assertEquals(originalSlots, result.slots.take(originalSlots.size))
        assertEquals(original.dock, result.dock)
        assertEquals(original.leadingSlots, result.leadingSlots)
        assertEquals(original.widgetPlacements, result.widgetPlacements)
        assertEquals(original.folders, result.folders)
        assertEquals(original.widgetRestores, result.widgetRestores)
        assertTrue(result.slotsForPage(2).take(4).all { it == null })
        for (page in 3 until 5) assertTrue(result.slotsForPage(page).any { it != null })
        val added = result.slots.drop(originalSlots.size).filterNotNull()
        assertFalse("app-90/Main" in added)
        assertEquals(added.size, added.distinct().size)
        assertEquals(result, multiPageTestLayout(result, (0..30).map(::app)))
    }

    @Test fun `few candidates create real pages instead of trailing empty placeholders`() {
        val original = HomeLayout(listOf("home/Main"), emptyList())
        val result = multiPageTestLayout(original, listOf(app(1), app(2)))

        assertEquals(3, result.pageCount)
        assertEquals("app-1/Main", result.slotsForPage(1).filterNotNull().single())
        assertEquals("app-2/Main", result.slotsForPage(2).filterNotNull().single())
    }
}

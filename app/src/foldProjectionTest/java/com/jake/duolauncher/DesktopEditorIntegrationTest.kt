package com.jake.duolauncher

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Only emulator test state is changed. Does not change HOME or accessibility settings. */
class DesktopEditorIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @org.junit.Before fun activatePersonalizationForRegression() {
        val receipt = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("miduoTestReceipt")
        compose.runOnUiThread {
            assertTrue("Pass a server-signed emulator receipt for personalization tests", receipt != null && compose.activity.licensing.activate(receipt))
        }
    }

    private fun capture(name: String) {
        val bitmap = if (name == "editor-settings") androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            else compose.onNodeWithTag("launcher-root").captureToImage().asAndroidBitmap()
        val file = java.io.File(compose.activity.getExternalFilesDir(null), "qa/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun innerLeadingPaneScrollsBothWaysAndWidgetDragAutoScrolls() {
        enterEditor()
        compose.onNodeWithTag("editor-page-0").performClick()
        compose.onNodeWithTag("editor-done").performClick()
        lateinit var model: LauncherModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[LauncherModel::class.java] }
        val before = model.state.value.layout
        try {
            compose.runOnUiThread { model.placeWidget(WidgetPlacement(901, CLOCK_WIDGET, -1, 0, 12, 2, 2)) }
            compose.waitForIdle()
            val scroll = compose.onNodeWithTag("home-scroll--1")
            fun position() = scroll.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            scroll.performTouchInput { swipeUp() }
            compose.waitForIdle()
            val below = position()
            assertTrue("Leading content must scroll down into additional rows", below > 100f)
            scroll.performTouchInput { swipeDown() }
            compose.waitForIdle()
            assertTrue("Downward movement must scroll, not summon the notification setup", position() < below)
            scroll.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, -10000f) }
            compose.waitForIdle()
            val first = model.state.value.layout.widgetPlacements.first { it.page == -1 && it.row == 0 }
            val source = compose.onNodeWithTag("widget-slot-${first.slot}").fetchSemanticsNode().boundsInRoot.center
            val pane = compose.onNodeWithTag("home-page--1").fetchSemanticsNode().boundsInRoot
            val edge = androidx.compose.ui.geometry.Offset(pane.center.x, pane.bottom - 12f)
            val initial = position()
            compose.onNodeWithTag("launcher-root").performTouchInput {
                down(source); advanceEventTime(700); moveTo(source); moveTo(edge, 300)
            }
            compose.mainClock.advanceTimeBy(800)
            val moved = position()
            compose.onNodeWithTag("launcher-root").performTouchInput { cancel() }
            assertTrue("Holding a dragged widget at bottom must reveal lower rows", moved > initial + 50f)
        } finally {
            compose.runOnUiThread { model.restoreLayout(before) }
        }
    }

    private fun enterEditor() {
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("setup-explore").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("setup-explore").performClick()
        compose.onNodeWithTag("home-page-0").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) { it() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("editor-toolbar").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    @Test fun editorKeepsLayoutAndToolbarOutsidePreview() {
        lateinit var model: LauncherModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[LauncherModel::class.java] }
        val before = model.state.value.layout
        enterEditor()
        val preview = compose.onNodeWithTag("editor-live-desktop").fetchSemanticsNode().boundsInRoot
        val toolbar = compose.onNodeWithTag("editor-toolbar").fetchSemanticsNode().boundsInRoot
        assertTrue("Toolbar must not be scaled into the desktop", toolbar.top >= preview.bottom)
        val pageDot = compose.onNodeWithTag("editor-page-0").fetchSemanticsNode().boundsInRoot
        assertTrue("Page controls must be below the preview frame", pageDot.top >= preview.bottom)
        for (direction in listOf(-1, 1)) {
            compose.onAllNodesWithTag("editor-neighbour-$direction").fetchSemanticsNodes().forEach {
                assertTrue("The selected page must be larger than adjacent previews", it.boundsInRoot.height < preview.height)
            }
        }
        capture("editor-selected")
        compose.onNodeWithTag("editor-settings").performClick()
        compose.onNodeWithTag("setup-default").assertExists()
        compose.onNodeWithTag("setup-accessibility").assertExists()
        compose.onNodeWithTag("setup-gestures").assertExists()
        if (compose.activity.getSystemService(android.app.role.RoleManager::class.java)
                .isRoleHeld(android.app.role.RoleManager.ROLE_HOME))
            compose.onNodeWithTag("restore-system-home").assertExists()
        else compose.onNodeWithTag("restore-system-home").assertDoesNotExist()
        compose.onNodeWithTag("google-search-switch").assertDoesNotExist()
        compose.onNodeWithTag("apply-phase-22-app-layout").assertDoesNotExist()
        capture("editor-settings")
        androidx.test.espresso.Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag("editor-done").performClick()
        compose.onNodeWithTag("editor-toolbar").assertDoesNotExist()
        assertEquals("Entering settings must not rewrite any placement", before, model.state.value.layout)
    }

    @Test fun chineseAndEnglishResourcesAndSafeSearchFallback() {
        fun localized(language: String) = compose.activity.createConfigurationContext(
            android.content.res.Configuration(compose.activity.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag(language))
            })
        assertEquals("编辑桌面", localized("zh-CN").getString(R.string.editor_title))
        assertEquals("Edit Home", localized("en-US").getString(R.string.editor_title))
        assertEquals("恢复系统桌面", localized("zh-CN").getString(R.string.restore_system_home))
        assertEquals("Restore system launcher", localized("en-US").getString(R.string.restore_system_home))
        assertTrue(systemSearchIntents(compose.activity).none { it.component?.packageName == GOOGLE_APP_PACKAGE })
    }

    @Test fun compactAppAndFolderMenusKeepOnlyTwoAccessibleActions() {
        enterEditor()
        compose.onNodeWithTag("editor-done").performClick()
        compose.waitForIdle()
        lateinit var model: LauncherModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[LauncherModel::class.java] }
        val before = model.state.value.layout
        val appIds = before.slots.filterNotNull().filterNot(::isFolderId).take(2)
        fun longPress(tag: String) {
            val point = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center
            compose.onNodeWithTag("launcher-root").performTouchInput {
                down(point); advanceEventTime(750); moveTo(point); up()
            }
            compose.waitForIdle()
        }
        longPress("home-app-${appIds[0]}")
        compose.onNodeWithTag("context-primary").assertExists()
        compose.onNodeWithTag("context-remove").assertExists()
        val menu = compose.onNodeWithTag("app-context-menu").fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("launcher-root").fetchSemanticsNode().boundsInRoot
        assertTrue(menu.width < root.width * .4f)
        compose.onNodeWithText("Move on Home").assertDoesNotExist()
        capture("app-menu")
        androidx.test.espresso.Espresso.pressBack()
        lateinit var folderId: String
        compose.runOnUiThread {
            folderId = model.createFolder(appIds[0], appIds[1], before.indexOfShortcut(appIds[0])!!, "Test")!!
        }
        compose.waitForIdle()
        longPress("home-folder-$folderId")
        compose.onNodeWithTag("context-primary").performClick()
        compose.onNodeWithTag("compact-folder-name").assertExists()
        androidx.test.espresso.Espresso.pressBack()
        longPress("home-folder-$folderId")
        compose.onNodeWithTag("context-remove").performClick()
        compose.waitForIdle()
        assertNull(model.folder(folderId))
        appIds.forEach { assertNotNull(model.state.value.layout.indexOfShortcut(it)) }
        compose.runOnUiThread { model.restoreLayout(before) }
    }

    @Test fun editorPageSelectionAndWidgetTrayWork() {
        enterEditor()
        val pageButtons = compose.onAllNodes(hasTestTag("editor-page-1")).fetchSemanticsNodes()
        if (pageButtons.isNotEmpty()) {
            compose.onNodeWithTag("editor-page-1").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("editor-page-0").performClick()
        }
        compose.onNodeWithTag("editor-widgets").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("widget-builtin-$CLOCK_WIDGET").fetchSemanticsNodes().isNotEmpty() }
        val tray = compose.onNodeWithTag("visual-widget-picker").fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("launcher-root").fetchSemanticsNode().boundsInRoot
        assertTrue("The catalogue leaves the upper desktop visible", tray.top > root.height * .3f)
        compose.onNodeWithTag("widget-builtin-$CLOCK_WIDGET").performClick()
        compose.onNodeWithTag("widget-placement-mode").assertExists()
        compose.onNodeWithTag("widget-placement-cancel").performClick()
        compose.onNodeWithTag("editor-done").performClick()
    }

    @Test fun scaledDesktopDragUsesVisibleCellCoordinates() {
        lateinit var model: LauncherModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[LauncherModel::class.java] }
        enterEditor()
        val layout = model.state.value.layout
        val appId = (0 until HOME_CELLS).mapNotNull { layout.slotAt(it) }.first { !isFolderId(it) }
        val targetIndex = (8 until HOME_CELLS).last { index -> layout.slotAt(index) == null &&
            layout.widgetPlacements.none { index in it.coveredIndices() } }
        val source = compose.onNodeWithTag("home-app-$appId").fetchSemanticsNode().boundsInRoot.center
        val destination = compose.onNodeWithTag("home-cell-$targetIndex").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("launcher-root").performTouchInput {
            down(source)
            advanceEventTime(650)
            moveTo(source)
            moveTo(destination, 600)
            up()
        }
        compose.waitForIdle()
        assertEquals("Drop must use the visually scaled cell", appId, model.state.value.layout.slotAt(targetIndex))
        compose.runOnUiThread { model.undoEdit() }
    }

    @Test fun builtinWidgetDragsFromTrayIntoScaledPage() {
        lateinit var model: LauncherModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[LauncherModel::class.java] }
        compose.waitForIdle()
        val original = model.state.value.layout
        try {
        // Repeated local runs must not drop onto a widget left by an earlier test.
        compose.runOnUiThread { model.restoreLayout(original.copy(
            slots = original.slots.mapIndexed { index, id -> if (homeCellPage(index) == 1) null else id },
            widgetPlacements = original.widgetPlacements.filterNot { it.page == 1 })) }
        val appId = model.state.value.apps.first().id
        compose.runOnUiThread { model.applyDrop(appId, DropTarget.Home(homeCellIndex(1, 20))) }
        enterEditor()
        compose.onNodeWithTag("editor-page-1").performClick()
        compose.waitForIdle()
        val destination = compose.onNodeWithTag("home-cell-${homeCellIndex(1, 0)}").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("editor-widgets").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("widget-builtin-$CLOCK_WIDGET").fetchSemanticsNodes().isNotEmpty() }
        val source = compose.onNodeWithTag("widget-builtin-$CLOCK_WIDGET").fetchSemanticsNode().boundsInRoot.center
        val before = model.state.value.layout.widgetPlacements.size
        compose.onNodeWithTag("launcher-root").performTouchInput {
            down(source); advanceEventTime(650); moveTo(source)
            for (step in 1..20) moveTo(source + (destination - source) * (step / 20f), 30)
            up()
        }
        compose.waitForIdle()
        assertEquals(before + 1, model.state.value.layout.widgetPlacements.size)
        assertTrue(model.state.value.layout.widgetPlacements.any { it.id == CLOCK_WIDGET && it.page == 1 && it.column == 0 && it.row == 0 })
        } finally {
            compose.runOnUiThread { model.restoreLayout(original) }
        }
    }
}

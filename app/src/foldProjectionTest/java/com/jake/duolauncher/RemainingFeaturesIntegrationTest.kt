package com.jake.duolauncher

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemainingFeaturesIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun capture(tag: String, name: String) {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val file = File(context.getExternalFilesDir(null), "qa/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun standby(language: String) {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        val localized = context.createConfigurationContext(config)
        context.getSharedPreferences("standby", 0).edit().putInt("page", 0).commit()
        var exited = false
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config) {
                DuoTheme(true) { Box(Modifier.size(622.dp, 425.dp)) { StandbyScreen(onExit = { exited = true }) } }
            }
        }
        compose.onNodeWithTag("standby-view-0").assertIsDisplayed()
        capture("standby-root", "standby-$language-0")
        compose.onNodeWithTag("standby-pager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithTag("standby-view-1").assertIsDisplayed()
        capture("standby-root", "standby-$language-1")
        compose.onNodeWithTag("standby-page-2").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("standby-view-2").assertIsDisplayed()
        capture("standby-root", "standby-$language-2")
        compose.onNodeWithTag("standby-page-3").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("standby-view-3").assertIsDisplayed()
        capture("standby-root", "standby-$language-3")
        compose.onNodeWithTag("standby-page-4").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("standby-view-4").assertIsDisplayed()
        capture("standby-root", "standby-$language-4")
        compose.onNodeWithTag("standby-page-2").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("standby-pager").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(1, context.getSharedPreferences("standby", 0).getInt("page", -1))
        compose.onNodeWithTag("standby-exit").performClick()
        assertTrue(exited)
        compose.onNodeWithTag("launcher-root").assertDoesNotExist()
    }

    @Test fun standbyEnglishPagesSwipePersistAndExit() = standby("en-US")
    @Test fun standbyChinesePagesSwipePersistAndExit() = standby("zh-CN")

    @Test fun standbyCutoutAndRoundedCornerSafetyAppliesToControlsAndEveryFace() {
        var cameraLeft by mutableStateOf(true)
        context.getSharedPreferences("standby", 0).edit().putInt("page", 0).commit()
        compose.setContent { DuoTheme(true) {
            Box(Modifier.size(622.dp, 425.dp)) {
                StandbyScreen(onExit = {}, safePadding = PaddingValues(
                    start = if (cameraLeft) 46.dp else 24.dp, end = if (cameraLeft) 24.dp else 46.dp,
                    top = 24.dp, bottom = 24.dp))
            }
        } }
        for (left in listOf(true, false)) {
            compose.runOnIdle { cameraLeft = left }
            for (page in 0..4) {
                compose.onNodeWithTag("standby-page-$page").performClick()
                compose.waitForIdle()
                val root = compose.onNodeWithTag("standby-root").fetchSemanticsNode().boundsInRoot
                val safe = compose.onNodeWithTag("standby-safe-content-$page", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val scale = root.width / 622f
                assertTrue(safe.left >= root.left + (if (left) 46 else 24) * scale - 1)
                assertTrue(safe.right <= root.right - (if (left) 24 else 46) * scale + 1)
                assertTrue(safe.top >= root.top + 24 * scale - 1)
                assertTrue(safe.bottom <= root.bottom - 24 * scale + 1)
                val exit = compose.onNodeWithTag("standby-exit").fetchSemanticsNode().boundsInRoot
                assertTrue(exit.left >= safe.left && exit.right <= safe.right + 1 && exit.top >= safe.top)
                for (dot in 0..4) {
                    val button = compose.onNodeWithTag("standby-page-$dot").fetchSemanticsNode().boundsInRoot
                    assertTrue("Page controls must also avoid corners and system gesture edge", button.left >= safe.left &&
                        button.right <= safe.right + 1 && button.bottom <= safe.bottom + 1)
                }
            }
            capture("standby-root", "standby-camera-${if (left) "left" else "right"}")
        }
    }

    @Test fun referenceWidgetCardsRemainReadableInBothLanguages() {
        var language by mutableStateOf("zh-CN")
        compose.setContent {
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            CompositionLocalProvider(LocalContext provides context.createConfigurationContext(config), LocalConfiguration provides config) {
                DuoTheme(true) {
                    Column(Modifier.width(340.dp).background(Color.Black).padding(10.dp).testTag("widget-qa")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(154.dp)) { ReferenceClockCard {} }
                            Box(Modifier.size(154.dp)) { ReferenceCalendarCard {} }
                        }
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.width(320.dp).height(154.dp)) { ReferenceWeatherCard {} }
                    }
                }
            }
        }
        compose.onNodeWithTag("reference-weather").assertIsDisplayed()
        capture("widget-qa", "widgets-zh")
        compose.runOnIdle { language = "en-US" }
        capture("widget-qa", "widgets-en")
    }

    @Test fun appLibraryHasFourColumnsDockIconsLabelsSearchAndActions() {
        val icon = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val apps = (0..11).map { AppEntry("test.app$it/.Main", "App $it", icon) }
        var query by mutableStateOf("")
        var opened: String? = null
        var action: String? = null
        compose.setContent { DuoTheme(true) {
            AppLibrary(LauncherState(apps = apps, loading = false), query, { query = it },
                { opened = it.id }, { _, _ -> }, { action = it.id }, Modifier.width(380.dp).height(550.dp))
        } }
        val bounds = apps.take(5).map { compose.onNodeWithTag("library-app-${it.id}").fetchSemanticsNode().boundsInRoot }
        assertTrue(bounds.take(4).all { kotlin.math.abs(it.top - bounds[0].top) < 2 })
        assertTrue(bounds[4].top > bounds[0].bottom)
        compose.onNodeWithTag("library-icon-${apps[0].id}", useUnmergedTree = true).assertWidthIsEqualTo(44.dp)
        compose.onNodeWithText("App 0").assertIsDisplayed()
        compose.onNodeWithTag("library-app-${apps[0].id}").performTouchInput { longClick() }
        assertEquals(apps[0].id, action)
        compose.onNodeWithTag("library-search").performTextInput("App 7")
        compose.onNodeWithTag("library-app-${apps[0].id}").assertDoesNotExist()
        compose.onNodeWithTag("library-app-${apps[7].id}").performClick()
        assertEquals(apps[7].id, opened)
    }

    @Test fun folderChildrenUseTwoIconActionsAndRemoveOnlyChosenShortcut() {
        val icon = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        val apps = (0..1).map { AppEntry("test.app$it/.Main", "App $it", icon) }
        var removed: String? = null
        var info: String? = null
        compose.setContent { DuoTheme(true) {
            FolderPanel(FolderEntry("folder-test", "Test", apps.map { it.id }), apps.associateBy { it.id },
                remember { HomeDragState() }, 0, emptyList(), emptyList(), {}, {}, { _, _ -> },
                onMoveOut = { id, target -> assertEquals(DropTarget.Remove, target); removed = id },
                onAppInfo = { info = it.id })
        } }
        compose.waitForIdle()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("folder-child-${apps[0].id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("folder-child-${apps[0].id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithTag("folder-child-menu-info").performClick()
        assertEquals(apps[0].id, info)
        compose.onNodeWithTag("folder-child-${apps[1].id}", useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithTag("folder-child-menu").assertWidthIsEqualTo(116.dp)
        compose.onNodeWithText("Move to dock").assertDoesNotExist()
        compose.onNodeWithTag("folder-child-menu-remove").performClick()
        assertEquals(apps[1].id, removed)
    }
}

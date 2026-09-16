package com.jake.duolauncher

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LicenseGateRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun recoveryEntryRequiresDefaultHomeInWelcomeAndSettings() {
        val defaultHome = mutableStateOf(false)
        val welcome = mutableStateOf(true)
        compose.runOnUiThread {
            val activity = compose.activity
            val model = ViewModelProvider(activity)[LauncherModel::class.java]
            activity.setContent {
                DuoTheme(false) {
                    if (welcome.value) FirstRunSetupSheet(defaultHome.value, {}, {}, {}, {})
                    else SimpleLauncherSettings(model.state.collectAsState().value, model, defaultHome.value,
                        wallpaper = false, onClose = {}, onMakeDefault = {}, onShadeSetup = {},
                        onWidgets = {}, onWallpaper = {}, onExport = {}, onImport = {},
                        backgrounds = activity.backgrounds, onSystemWallpaper = {})
                }
            }
        }
        for (firstRun in listOf(true, false)) {
            compose.runOnUiThread { welcome.value = firstRun; defaultHome.value = false }
            compose.onNodeWithTag("restore-system-home").assertDoesNotExist()
            compose.runOnUiThread { defaultHome.value = true }
            compose.onNodeWithTag("restore-system-home").performScrollTo().assertIsDisplayed()
            // A stale visible entry must not open Android's chooser if the real role differs.
            if (!compose.activity.getSystemService(android.app.role.RoleManager::class.java)
                    .isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                compose.onNodeWithTag("restore-system-home").performClick()
                assertFalse(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).hasObject(
                    androidx.test.uiautomator.By.text(compose.activity.getString(R.string.restore_home_confirm))))
            }
            compose.runOnUiThread { defaultHome.value = false }
            compose.onNodeWithTag("restore-system-home").assertDoesNotExist()
        }
    }

    @Test fun appFolderAndFolderChildLongPressCannotEditWithoutLicense() {
        val prefs = compose.activity.getSharedPreferences("miduo_offline_license", Context.MODE_PRIVATE)
        val saved = prefs.all
        var model: LauncherModel? = null
        var before: HomeLayout? = null
        try {
            prefs.edit().clear().commit()
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            if (compose.onAllNodesWithTag("setup-explore").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithTag("setup-explore").performClick()
            compose.runOnUiThread { model = ViewModelProvider(compose.activity)[LauncherModel::class.java] }
            compose.waitUntil(10000) { model!!.state.value.apps.size >= 3 }
            val apps = model!!.state.value.apps.take(3)
            val folder = FolderEntry(newFolderId(), "License test", apps.drop(1).map { it.id })
            compose.runOnUiThread {
                before = model!!.state.value.layout
                model!!.restoreLayout(before!!.copy(slots = listOf(apps[0].id, folder.id),
                    folders = listOf(folder), widgetPlacements = emptyList(), dock = List(4) { null }))
            }
            compose.waitForIdle()
            val expected = model!!.state.value.layout
            for (tag in listOf("home-app-${apps[0].id}", "home-folder-${folder.id}")) {
                compose.onNodeWithTag(tag).performTouchInput { longClick() }
                compose.onNodeWithTag("license-code-input").performScrollTo().assertExists()
                assertEquals(expected, model!!.state.value.layout)
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            }
            compose.onNodeWithTag("home-folder-${folder.id}").performTouchInput { click() }
            compose.onNode(hasAnyAncestor(hasTestTag("folder-child-${apps[1].id}")) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.OnLongClick) { it() }
            compose.onNodeWithTag("license-code-input").performScrollTo().assertExists()
            compose.onNodeWithTag("folder-child-menu").assertDoesNotExist()
            assertEquals(expected, model!!.state.value.layout)
        } finally {
            before?.let { original -> compose.runOnUiThread { model!!.restoreLayout(original) } }
            val edit = prefs.edit().clear()
            saved.forEach { (k,v) -> when(v) { is String -> edit.putString(k,v); is Boolean -> edit.putBoolean(k,v) } }
            edit.commit()
        }
    }

    @Test fun unlicensedSetupEntriesRedirectAndRecoverySurvives() {
        val prefs = compose.activity.getSharedPreferences("miduo_offline_license", Context.MODE_PRIVATE)
        val saved = prefs.all
        try {
            prefs.edit().clear().commit()
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            if (compose.onAllNodesWithTag("setup-explore").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithTag("setup-explore").performClick()
            assertFalse(compose.activity.licensing.activated)
            compose.onNodeWithTag("home-page-0").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
            for (tag in listOf("setup-default", "setup-accessibility", "setup-gestures")) {
                compose.onNodeWithTag("editor-settings").performClick()
                compose.onNodeWithTag(tag).performScrollTo().performClick()
                compose.onNodeWithTag("license-code-input").performScrollTo().assertExists()
                assertFalse(compose.activity.licensing.activated)
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            }
            compose.onNodeWithTag("editor-settings").performClick()
            val defaultHome = compose.activity.getSystemService(android.app.role.RoleManager::class.java)
                .isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
            if (defaultHome) compose.onNodeWithTag("restore-system-home").performScrollTo().assertIsDisplayed()
            else compose.onNodeWithTag("restore-system-home").assertDoesNotExist()
            compose.onNodeWithTag("settings-more").performScrollTo().performClick()
            compose.onNodeWithTag("miduo-about").performScrollTo().assertIsDisplayed()
        } finally {
            val edit = prefs.edit().clear()
            saved.forEach { (k,v) -> when(v) { is String -> edit.putString(k,v); is Boolean -> edit.putBoolean(k,v) } }
            edit.commit()
        }
    }
}

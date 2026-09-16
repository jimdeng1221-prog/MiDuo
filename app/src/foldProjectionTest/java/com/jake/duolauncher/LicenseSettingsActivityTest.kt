package com.jake.duolauncher

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LicenseSettingsActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsGatedButAboutRecoveryAndDefaultOffRemainUsable() {
        val prefs = compose.activity.getSharedPreferences("miduo_offline_license", Context.MODE_PRIVATE)
        val saved = prefs.all
        try {
            prefs.edit().clear().commit()
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            if (compose.onAllNodesWithTag("setup-explore").fetchSemanticsNodes().isNotEmpty())
                compose.onNodeWithTag("setup-explore").performClick()
            assertFalse(compose.activity.licensing.activated)
            assertFalse(compose.activity.licensing.standbyEnabled)
            compose.onNodeWithTag("home-page-0").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
            compose.onNodeWithTag("editor-settings").performClick()
            compose.onNodeWithTag("license-code-input").performScrollTo().assertExists()
            compose.onNodeWithTag("settings-standby").assertDoesNotExist()
            if (compose.activity.getSystemService(android.app.role.RoleManager::class.java)
                    .isRoleHeld(android.app.role.RoleManager.ROLE_HOME))
                compose.onNodeWithTag("restore-system-home").performScrollTo().assertIsDisplayed()
            else compose.onNodeWithTag("restore-system-home").assertDoesNotExist()
            compose.onNodeWithTag("settings-more").performScrollTo().performClick()
            compose.onNodeWithTag("miduo-about").performScrollTo().assertIsDisplayed()
            capture("settings-about")
            androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            compose.onNodeWithTag("editor-wallpaper").performClick()
            compose.onNodeWithTag("license-code-input").performScrollTo().assertExists()
            androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            compose.onNodeWithTag("editor-widgets").performClick()
            compose.onNodeWithTag("license-code-input").performScrollTo().assertExists()
            val receipt = requireNotNull(InstrumentationRegistry.getArguments().getString("miduoTestReceipt"))
            compose.runOnUiThread { assertTrue(compose.activity.licensing.activate(receipt)) }
            androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            compose.onNodeWithTag("editor-settings").performClick()
            compose.onNodeWithTag("settings-standby").performScrollTo().assertIsOff().performClick().assertIsOn()
            assertTrue(compose.activity.licensing.standbyEnabled)
            capture("settings-activated")
            compose.onNodeWithTag("settings-standby").performClick().assertIsOff()
        } finally {
            val editor = prefs.edit().clear()
            saved.forEach { (key,value) -> when(value) { is String -> editor.putString(key,value); is Boolean -> editor.putBoolean(key,value) } }
            editor.commit()
        }
    }

    private fun capture(name: String) {
        val panel = if (compose.activity.panelOrientation.panel.value == FoldPanel.COVER) "cover" else "inner"
        val file = File(compose.activity.getExternalFilesDir(null), "qa/$name-$panel.png")
        file.parentFile!!.mkdirs()
        compose.waitForIdle()
        file.outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            .compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

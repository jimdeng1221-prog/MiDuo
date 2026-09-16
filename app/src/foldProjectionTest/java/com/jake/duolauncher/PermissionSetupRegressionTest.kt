package com.jake.duolauncher

import android.content.Context
import android.app.UiAutomation
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PermissionSetupRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device get(): UiDevice {
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @Test fun permissionGuideIsReadableButAppInfoStillNeedsLicense() {
        val prefs = compose.activity.getSharedPreferences("miduo_offline_license", Context.MODE_PRIVATE)
        val saved = prefs.all
        try {
            prefs.edit().clear().commit()
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            val activity = compose.activity
            val before = activity.activationRequests.intValue
            compose.runOnUiThread { activity.showPermissionGuide(); activity.showPermissionGuide() }
            assertTrue(device.wait(Until.hasObject(By.text(activity.getString(R.string.setup_permissions_title))), 5000))
            assertEquals(1, device.findObjects(By.res("android", "alertTitle")).size)
            assertEquals(before, activity.activationRequests.intValue)
            device.wait(Until.findObject(By.res("android", "button3")), 5000).click()
            compose.waitForIdle()
            assertEquals(before + 1, activity.activationRequests.intValue)
            assertFalse(activity.licensing.activated)
        } finally {
            val edit = prefs.edit().clear()
            saved.forEach { (k, v) -> when (v) { is String -> edit.putString(k,v); is Boolean -> edit.putBoolean(k,v) } }
            edit.commit()
        }
    }

    /** Run only on the prepared emulator with its existing signed receipt and service enabled. */
    @Test fun authorizedGestureEntryShowsOnlyOneRecoveryMenu() {
        check(android.os.Build.HARDWARE in listOf("ranchu", "goldfish")) { "Emulator-only permission test" }
        assertTrue("Use the existing licensed emulator receipt", compose.activity.licensing.activated)
        // UI automation otherwise suppresses other accessibility services, unlike a real user.
        val uiDevice = device
        val automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        val services = shell("settings get secure enabled_accessibility_services").trim()
        val enabled = shell("settings get secure accessibility_enabled").trim()
        fun restore(key: String, value: String) {
            require(value.all { it.isLetterOrDigit() || it in "._/:-$" })
            shell(if (value == "null") "settings delete secure $key" else "settings put secure $key $value")
        }
        try {
            // Instrumentation force-stops the target at startup. Rebind after the Activity is running.
            shell("settings delete secure enabled_accessibility_services")
            shell("settings put secure enabled_accessibility_services com.jake.duolauncher/com.jake.duolauncher.SystemShadeAccessibilityService")
            shell("settings put secure accessibility_enabled 1")
            compose.waitUntil(15000) { SystemShadeAccessibilityService.isConnected() }
            val title = compose.activity.getString(R.string.navigation_title)
            compose.runOnUiThread { compose.activity.showNavigationSetup(); compose.activity.showNavigationSetup() }
            assertTrue(uiDevice.wait(Until.hasObject(By.text(title)), 5000))
            assertEquals(1, uiDevice.findObjects(By.res("android", "alertTitle")).size)
            uiDevice.pressBack()
            assertTrue(uiDevice.wait(Until.gone(By.res("android", "alertTitle")), 5000))
        } finally {
            restore("enabled_accessibility_services", services)
            restore("accessibility_enabled", enabled)
        }
    }
}

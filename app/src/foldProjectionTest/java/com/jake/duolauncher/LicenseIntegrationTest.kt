package com.jake.duolauncher

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LicenseIntegrationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun exportDeviceForRealServerReceipt() {
        val store = OfflineLicenseStore(context)
        File(context.getExternalFilesDir(null), "qa").mkdirs()
        File(context.getExternalFilesDir(null), "qa/license-device.txt").writeText(requireNotNull(store.deviceCode))
    }

    @Test fun chineseActivationAndAboutDoNotRequireDeviceCodeOrContact() = ui("zh-CN")
    @Test fun englishActivationAndAboutDoNotRequireDeviceCodeOrContact() = ui("en")
    private fun ui(language: String) {
        val preferences = context.getSharedPreferences("miduo_offline_license", Context.MODE_PRIVATE)
        val saved = preferences.all
        preferences.edit().clear().commit()
        try {
            val store = OfflineLicenseStore(context)
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val localized = context.createConfigurationContext(config)
            compose.setContent {
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config) {
                    DuoTheme(true) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("license-qa")) {
                            LicenseActivationPanel(store)
                            MiDuoAbout()
                        }
                    }
                }
            }
            assertFalse(store.activated)
            assertFalse(store.standbyEnabled)
            store.setStandby(true)
            assertFalse(store.standbyEnabled)
            compose.onNodeWithTag("license-code-input").performTextInput("MD-INVALID")
            compose.onNodeWithTag("license-activate").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("license-consent").performScrollTo().performClick()
            compose.onNodeWithTag("license-activate").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("license-error").fetchSemanticsNodes().isNotEmpty() }
            assertFalse(store.activated)
            compose.onNodeWithTag("license-website").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(localized.getString(R.string.miduo_author)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("miduo-open-licenses").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("MIT License", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("miduo-open-licenses").performScrollTo().performClick()
            compose.onNodeWithTag("license-status").performScrollTo()
            val file = File(context.getExternalFilesDir(null), "qa/license-$language.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { compose.onNodeWithTag("license-qa").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
        } finally {
            val editor = preferences.edit().clear()
            saved.forEach { (key, value) -> when(value) { is String -> editor.putString(key,value); is Boolean -> editor.putBoolean(key,value) } }
            editor.commit()
        }
    }

    @Test fun actualServerReceiptSurvivesOfflineRestartAndCannotChangeDevice() {
        val receipt = requireNotNull(InstrumentationRegistry.getArguments().getString("miduoTestReceipt")) { "Supply the real server receipt for this emulator" }
        val store = OfflineLicenseStore(context)
        assertTrue(store.activate(receipt))
        assertTrue(OfflineLicenseStore(context).activated)
        assertFalse(OfflineLicense.verify(receipt, "F".repeat(32)))
        store.setStandby(true)
        assertTrue(OfflineLicenseStore(context).standbyEnabled)
        store.setStandby(false)
        assertFalse(OfflineLicenseStore(context).standbyEnabled)
        // Explicit test harness request for the separate orientation replay, never an app intent.
        if (InstrumentationRegistry.getArguments().getString("miduoEnableStandbyForReplay") == "true") store.setStandby(true)
    }

    @Test fun onlineActivationThroughOfficialHttpsThenOfflineStartup() {
        val code = requireNotNull(InstrumentationRegistry.getArguments().getString("miduoNetworkCode")) { "Supply a disposable real server code" }
        val prefs = context.getSharedPreferences("miduo_offline_license", Context.MODE_PRIVATE)
        val saved = prefs.all
        prefs.edit().clear().commit()
        try {
            val store = OfflineLicenseStore(context)
            compose.setContent {
                DuoTheme(true) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                        LicenseActivationPanel(store)
                    }
                }
            }
            compose.onNodeWithTag("license-code-input").performTextInput(code)
            compose.onNodeWithTag("license-consent").performScrollTo().performClick()
            compose.onNodeWithTag("license-activate").performScrollTo().performClick()
            compose.waitUntil(30000) { store.activated || compose.onAllNodesWithTag("license-error").fetchSemanticsNodes().isNotEmpty() }
            assertTrue("Official HTTPS activation failed; inspect the displayed error", store.activated)
            compose.onNodeWithTag("license-code-input").assertDoesNotExist()
            assertTrue(OfflineLicenseStore(context).activated)
            assertFalse(OfflineLicenseStore(context).standbyEnabled)
            // Same-device recovery after clearing data must receive the identical receipt.
            val first = prefs.getString("signed_license", null)
            kotlinx.coroutines.runBlocking { assertNull(store.redeem(code)) }
            assertEquals(first, prefs.getString("signed_license", null))
        } finally {
            val editor = prefs.edit().clear()
            saved.forEach { (key,value) -> when(value) { is String -> editor.putString(key,value); is Boolean -> editor.putBoolean(key,value) } }
            editor.commit()
        }
    }
}

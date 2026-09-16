package com.jake.duolauncher

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FoldAnimationSettingTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun freshAndUpgradedInstallsDefaultOnAndStoredOffSurvivesRecreation() {
        // A separate preferences file: never touch the emulator's actual license/layout.
        val isolated = context.getSharedPreferences("qa_fold_animation", Context.MODE_PRIVATE)
        val wrapper = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = isolated
        }
        isolated.edit().clear().commit()
        try {
            val fresh = OfflineLicenseStore(wrapper)
            assertTrue(fresh.foldAnimationEnabled)
            assertFalse(fresh.activated)
            fresh.setFoldAnimation(false)
            assertTrue("Unlicensed changes must be rejected", fresh.foldAnimationEnabled)
            isolated.edit().putBoolean("standby_enabled", true).commit()
            assertTrue("Existing install without new key defaults on", OfflineLicenseStore(wrapper).foldAnimationEnabled)
            isolated.edit().putBoolean("fold_animation_enabled", false).commit()
            assertFalse(OfflineLicenseStore(wrapper).foldAnimationEnabled)
            assertTrue("StandBy is independent", OfflineLicenseStore(wrapper).standbyEnabled)
        } finally { isolated.edit().clear().commit() }
    }

    @Test fun disabledModeIsNeutralOnBothPanelsAndIgnoresReplay() {
        val inner = mutableStateOf(false)
        val preview = mutableStateOf(1)
        lateinit var state: FoldTransitionState
        compose.setContent { state = rememberFoldTransition(inner.value, preview.value, enabled = false) }
        fun neutral() = compose.runOnIdle {
            assertEquals(1f, state.reveal.value, 0f)
            assertEquals(0f, state.coverFold.value, 0f)
            assertEquals(1f, state.arrival.value, 0f)
            assertEquals(if (inner.value) FoldPhase.INNER_STABLE else FoldPhase.COVER_STABLE, state.phase.value)
        }
        neutral()
        compose.runOnIdle { inner.value = true; preview.value++ }
        neutral()
        compose.runOnIdle { inner.value = false; preview.value++ }
        neutral()
    }

    @Test fun switchUsesChineseAndEnglishLabelsAndCanToggleBothWays() {
        val language = mutableStateOf("zh-CN")
        val checked = mutableStateOf(true)
        compose.setContent {
            val config = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language.value))
            }
            CompositionLocalProvider(LocalContext provides context.createConfigurationContext(config)) {
                DuoTheme(true) { FoldAnimationSetting(checked.value) { checked.value = it } }
            }
        }
        compose.onNodeWithTag("settings-fold-animation").assertIsOn()
        compose.onNodeWithContentDescription("开合动画").performClick().assertIsOff()
        compose.runOnIdle { language.value = "en" }
        compose.onNodeWithContentDescription("Fold animation").assertIsOff().performClick().assertIsOn()
    }
}

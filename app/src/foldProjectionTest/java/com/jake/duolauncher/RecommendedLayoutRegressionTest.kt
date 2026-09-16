package com.jake.duolauncher

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RecommendedLayoutRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun moreOpensAsVisiblePageAndReturnsToTopOfSettings() {
        check(android.os.Build.HARDWARE in listOf("ranchu", "goldfish"))
        assertTrue(compose.activity.licensing.activated)
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("setup-explore").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("setup-explore").performClick()
        compose.onNodeWithTag("home-page-0").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithTag("editor-settings").performClick()
        compose.onNodeWithTag("settings-more").performScrollTo().performClick()
        compose.onNodeWithTag("apply-recommended-layout").assertIsDisplayed()
        compose.onNodeWithTag("settings-more-back").assertIsDisplayed().performClick()
        compose.onNodeWithTag("setup-permission-guide").assertIsDisplayed()
    }
    @Test fun recommendationPreservesWidgetsAndCanBeUndone() {
        check(android.os.Build.HARDWARE in listOf("ranchu", "goldfish"))
        assertTrue(compose.activity.licensing.activated)
        val model = ViewModelProvider(compose.activity)[LauncherModel::class.java]
        compose.waitUntil(15000) { !model.state.value.loading && model.state.value.apps.isNotEmpty() }
        val before = model.state.value
        try {
            compose.runOnUiThread { assertTrue(model.applyRecommendedAppLayout()) }
            val after = model.state.value
            assertEquals(48f, after.sharedPreset.iconSize)
            assertEquals(before.widgetPlacements, after.widgetPlacements)
            assertEquals(before.leadingSlots, after.leadingSlots)
            val eligible = before.apps.filter { it.available }.map { Phase22AppCandidate(it.id, it.packageName, it.label, it.isWork) }
            val leading = before.leadingSlots.filterNotNull().toSet()
            val retained = leading + before.folders.filter { it.id in leading }.flatMap { it.appIds }
            assertEquals(freshInstallDock(eligible.filter { it.id !in retained }), after.dock)
            assertTrue(after.canUndoEdit)
        } finally {
            compose.runOnUiThread { assertTrue(model.undoEdit()) }
            assertEquals(before.layout, model.state.value.layout)
            assertEquals(before.sharedPreset, model.state.value.sharedPreset)
        }
    }
}

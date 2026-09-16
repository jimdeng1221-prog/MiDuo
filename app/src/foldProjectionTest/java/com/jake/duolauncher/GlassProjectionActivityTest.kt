package com.jake.duolauncher

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Uses the production tree including real wallpaper, Haze and the current widget host.
 * Only the built-in debug replay is triggered; no HOME, layout or permission mutations. */
class GlassProjectionActivityTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun realWidgetLongPressUsesCompactMenuAndKeepsResizeAndRemovalWorking() {
        val receipt = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("miduoTestReceipt")
        compose.runOnUiThread {
            assertTrue("Pass a server-signed test receipt", receipt != null && compose.activity.licensing.activate(receipt))
        }
        compose.waitForIdle()
        if(compose.onAllNodesWithTag("setup-explore").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("setup-explore").performClick()
        compose.waitForIdle()
        val model=ViewModelProvider(compose.activity)[LauncherModel::class.java]
        val before=model.state.value.layout
        val placement=before.widgetPlacements.first { it.page==0 && it.id<0 }
        fun open() {
            compose.onNodeWithTag("widget-slot-${placement.slot}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick) { it() }
            compose.onNodeWithTag("widget-context-menu").assertIsDisplayed()
        }
        try {
            open()
            assertEquals(before,model.state.value.layout)
            capture("widget-menu-${if(compose.activity.panelOrientation.panel.value==FoldPanel.COVER)"cover" else "inner"}")
            compose.onNodeWithTag("widget-context-resize").performClick()
            compose.onNodeWithTag("widget-resize-preview-${placement.slot}").assertIsDisplayed()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("widget-resize-preview-${placement.slot}").assertDoesNotExist()
            assertEquals(before,model.state.value.layout)
            open()
            compose.onNodeWithTag("widget-context-remove").performClick()
            compose.onNodeWithTag("widget-context-menu").assertDoesNotExist()
            compose.runOnIdle { assertNull(model.placement(placement.slot)) }
        } finally { compose.runOnUiThread { model.restoreLayout(before) } }
    }

    private fun capture(name: String): androidx.compose.ui.graphics.ImageBitmap {
        val image=compose.onNodeWithTag("launcher-root").captureToImage()
        val file=File(compose.activity.getExternalFilesDir(null),"qa/glass-activity-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
        return image
    }

    @Test fun liveLauncherProjectsOnlyItsOwnLeafAndRestoresOriginalPixels() {
        compose.waitForIdle()
        if(compose.onAllNodesWithTag("setup-explore").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("setup-explore").performClick()
        compose.waitForIdle()
        val model=ViewModelProvider(compose.activity)[LauncherModel::class.java]
        val layout=model.state.value.layout
        val cover=compose.activity.panelOrientation.panel.value==FoldPanel.COVER
        val label=if(cover)"cover" else "inner"
        compose.mainClock.autoAdvance=false
        try {
            val before=capture("$label-flat").toPixelMap()
            compose.runOnUiThread { compose.activity.foldPreviewRequests.intValue++ }
            compose.mainClock.advanceTimeBy(560)
            val during=capture("$label-fold").toPixelMap()
            var leftDiff=0f; var rightDiff=0f; var leftCount=0;var rightCount=0
            for(y in before.height/8 until before.height*7/8 step 17) for(x in 20 until before.width-20 step 17) {
                val a=before[x,y];val b=during[x,y]
                val delta=(abs(a.red-b.red)+abs(a.green-b.green)+abs(a.blue-b.blue))/3f
                if(x<before.width/2){leftDiff+=delta;leftCount++} else {rightDiff+=delta;rightCount++}
            }
            assertTrue("Real content must actually project",(leftDiff+rightDiff)/(leftCount+rightCount)>.003f)
            if(!cover)assertTrue("Stationary right leaf changed: ${rightDiff/rightCount}",rightDiff/rightCount<.025f)
            compose.mainClock.advanceTimeBy(1800)
            val restored=capture("$label-restored").toPixelMap()
            var diff=0f;var count=0
            for(y in before.height/8 until before.height*7/8 step 17)for(x in 20 until before.width-20 step 17) {
                val a=before[x,y];val b=restored[x,y]
                diff+=abs(a.red-b.red)+abs(a.green-b.green)+abs(a.blue-b.blue);count++
            }
            assertTrue("Projection must release back to live original: ${diff/(count*3)}",diff/(count*3)<.025f)
            assertEquals("Rendering cannot mutate app/widget placements",layout,model.state.value.layout)
        } finally { compose.mainClock.autoAdvance=true }
    }
}

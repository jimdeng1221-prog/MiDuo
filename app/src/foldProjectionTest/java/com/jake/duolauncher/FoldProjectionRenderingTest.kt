package com.jake.duolauncher

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.hazeSource
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Run with -PduoProjectionTestsOnly=true; not a substitute for Xiaomi hinge testing. */
class FoldProjectionRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fullDesktopHingeIgnoresDockWidthAndBlendsToUnchangedRightLeaf() {
        assumeTrue(Build.VERSION.SDK_INT>=33)
        val amount=mutableStateOf(0f)
        compose.setContent {
            Canvas(Modifier.size(400.dp,300.dp).testTag("physical-hinge")
                .glassProjection({amount.value},true,true,floatArrayOf(0f,0f,0f,0f),wholeDesktop=true)) {
                for(y in 0 until size.height.toInt() step 6)for(x in 0 until size.width.toInt() step 6)
                    drawRect(if((x/6+y/6)%2==0)Color.White else Color.Black,Offset(x.toFloat(),y.toFloat()),Size(6f,6f))
            }
        }
        val before=compose.onNodeWithTag("physical-hinge").captureToImage().toPixelMap()
        compose.runOnIdle { amount.value=.7f }
        val after=compose.onNodeWithTag("physical-hinge").captureToImage().toPixelMap()
        fun difference(from: Int,to: Int): Float {
            var sum=0f;var count=0
            for(x in from until to)for(y in before.height/3 until before.height*2/3 step 7) {
                sum+=kotlin.math.abs(before[x,y].red-after[x,y].red);count++
            }
            return sum/count
        }
        val hinge=before.width/2
        assertTrue("Blur must extend beyond old grid-centre to physical hinge",difference((before.width*.46f).toInt(),(before.width*.475f).toInt())>.02f)
        assertTrue("Every right-side pixel remains native",difference(hinge,before.width)<.005f)
        assertTrue("Narrow transition approaches native continuously",difference(hinge-4,hinge)<difference(hinge-36,hinge-20))
        save("physical-hinge","physical-hinge-transition")
    }

    private fun save(tag: String, name: String) {
        val bitmap=compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),"qa/glass-$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }

    @Test fun nativeViewContentRemainsLiveAndReceivesClicks() {
        assumeTrue(Build.VERSION.SDK_INT>=33)
        val color=mutableStateOf(Color.Red); var clicks=0
        compose.setContent {
            Box(Modifier.size(240.dp,360.dp).testTag("native")
                .glassProjection({.3f},false,true,floatArrayOf(0f,0f,0f,0f))) {
                AndroidView(modifier=Modifier.fillMaxSize(),factory={ context ->
                    android.widget.TextView(context).apply {
                        text="Native widget content";gravity=android.view.Gravity.CENTER
                        setOnClickListener { clicks++ }
                    }
                },update={ it.setBackgroundColor(color.value.toArgb()) })
            }
        }
        compose.onNodeWithTag("native").performTouchInput { click() }
        compose.runOnIdle { org.junit.Assert.assertEquals(1,clicks); color.value=Color.Green }
        val pixels=compose.onNodeWithTag("native").captureToImage().toPixelMap()
        assertTrue("AndroidView must not turn into a stale screenshot",pixels[pixels.width/2,pixels.height/3].green>.8f)
    }

    @Test fun pyramidBlursCheckerboardInLinearLightWithoutDarkBands() {
        assumeTrue(Build.VERSION.SDK_INT>=33)
        val amount=mutableStateOf(.7f)
        compose.setContent {
            Canvas(Modifier.size(240.dp,360.dp).testTag("checker")
                .glassProjection({amount.value},false,true,floatArrayOf(0f,0f,0f,0f))) {
                for(y in 0 until size.height.toInt() step 2) for(x in 0 until size.width.toInt() step 2) {
                    drawRect(if((x/2+y/2)%2==0)Color.White else Color.Black,Offset(x.toFloat(),y.toFloat()),Size(2f,2f))
                }
            }
        }
        val pixels=compose.onNodeWithTag("checker").captureToImage().toPixelMap()
        val samples=(pixels.width/3 until pixels.width*4/5 step 7).map { x -> pixels[x,pixels.height/2].red }
        assertTrue("Linear-light 50% black/white should stay near sRGB .735, not .5: $samples",samples.all { it in .64f.. .83f })
        assertTrue("No repeating dark stripes",samples.max()-samples.min()<.1f)
        save("checker","linear-checker")
    }

    @Test fun projectedHazeContentRefreshesWhilePoseIsStationary() {
        assumeTrue(Build.VERSION.SDK_INT>=33)
        val color=mutableStateOf(Color.Red)
        compose.setContent {
            val backdrop=rememberHazeState()
            DuoTheme(true) {
                Box(Modifier.size(240.dp,360.dp).testTag("live")
                    .glassProjection({.4f},false,true,floatArrayOf(0f,0f,0f,0f))) {
                    Canvas(Modifier.matchParentSize().hazeSource(backdrop)) { drawRect(color.value) }
                    CompositionLocalProvider(LocalDuoGlassBackdrop provides backdrop) {
                        Box(Modifier.align(Alignment.Center).size(140.dp,160.dp)
                            .duoGlass(DuoGlassRole.Card,RoundedCornerShape(24.dp)))
                    }
                }
            }
        }
        val red=compose.onNodeWithTag("live").captureToImage().toPixelMap()
        assertTrue(red[red.width/2,red.height/2].red>.7f)
        compose.runOnIdle { color.value=Color.Blue }
        val blue=compose.onNodeWithTag("live").captureToImage().toPixelMap()
        assertTrue("New backdrop must reach glass in the same live scene",blue[blue.width/2,blue.height/2].blue>.7f && blue[blue.width/2,blue.height/2].red<.4f)
        save("live","live-haze")
    }

    @Test fun physicalCornerRadiiAreNotReplacedByEqualDemoCorners() {
        assumeTrue(Build.VERSION.SDK_INT>=33)
        compose.setContent {
            Box(Modifier.size(240.dp,360.dp).testTag("corners")
                .glassProjection({.02f},false,false,floatArrayOf(154f,0f,0f,0f)).background(Color.White))
        }
        val pixels=compose.onNodeWithTag("corners").captureToImage().toPixelMap()
        assertTrue("Large top-left cutout must remain black",pixels[5,30].red<.1f)
        assertTrue("Square top-right must not inherit left radius",pixels[pixels.width-6,30].red>.9f)
        save("corners","asymmetric-corners")
    }

    @Test fun repeatedPoseEndpointsAndResizeDoNotRetainOldBuffers() {
        assumeTrue(Build.VERSION.SDK_INT>=33)
        val amount=mutableStateOf(.3f); val width=mutableStateOf(240.dp)
        compose.setContent {
            Box(Modifier.size(width.value,360.dp).testTag("resize")
                .glassProjection({amount.value},true,true,floatArrayOf(28f,0f,154f,0f)).background(Color.White))
        }
        for(step in 0..5) {
            compose.runOnIdle { amount.value=if(step%2==0)0f else .6f; width.value=if(step%2==0)200.dp else 240.dp }
            val pixels=compose.onNodeWithTag("resize").captureToImage().toPixelMap()
            assertTrue("Live centre should survive resize/re-entry",pixels[pixels.width/2,pixels.height/2].red>.85f)
            if(step%2==0)assertTrue("Idle is exact original",pixels[1,1].red>.99f)
        }
        save("resize","resize-reentry")
    }

    @Test fun stalledCoverSignalActuallyRemovesProjectionAndBlur() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        compose.mainClock.autoAdvance = false
        val signal = mutableStateOf(FoldSignal(angleDegrees = 120f,
            activePanel = FoldPanel.COVER, receivedElapsedNs = android.os.SystemClock.elapsedRealtimeNanos()))
        lateinit var transition: FoldTransitionState
        compose.setContent {
            transition = rememberFoldTransitionFromSignal(false, 0, signal)
            Box(Modifier.size(200.dp, 300.dp).testTag("stalled-cover")
                .foldCoverPane(transition.coverFold, enabled = { true }).background(Color.White))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertTrue("Test must start in the broken trapezoid pose", transition.coverFold.value > .9f) }
        val projected = compose.onNodeWithTag("stalled-cover").captureToImage().toPixelMap()
        assertTrue(projected[projected.width / 2, 1].red < .5f)
        // No subsequent sensor event: the deadline itself must invalidate the layer.
        compose.mainClock.advanceTimeBy(2500)
        compose.runOnIdle { assertTrue("Stale angle must settle", transition.coverFold.value < .001f) }
        val flat = compose.onNodeWithTag("stalled-cover").captureToImage().toPixelMap()
        assertTrue("Old aperture/effect must be removed", flat[flat.width - 2, flat.height / 2].red > .9f)
        assertTrue(flat[flat.width / 2, 1].red > .9f)
    }

    @Test fun cancelledHandoffCannotLeaveArrivalAtZero() {
        compose.mainClock.autoAdvance = false
        val signal = mutableStateOf(FoldSignal(activePanel = FoldPanel.COVER,
            panelEpoch = 1L, panelChangedElapsedNs = android.os.SystemClock.elapsedRealtimeNanos()))
        lateinit var transition: FoldTransitionState
        compose.setContent { transition = rememberFoldTransitionFromSignal(false, 0, signal) }
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle { signal.value = signal.value.copy(activePanel = FoldPanel.INNER) }
        compose.mainClock.advanceTimeBy(800)
        compose.runOnIdle {
            assertTrue(transition.arrival.value > .999f)
            assertTrue(transition.coverFold.value < .001f)
        }
    }

    @Test fun oversizedProjectionInputCannotPaintStripesAcrossNeighbour() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        RuntimeShader(GLASS_PROJECTION_SHADER)
        val progress = mutableStateOf(.1f)
        compose.setContent {
            Box(Modifier.size(400.dp, 300.dp).background(Color.Green).testTag("neighbours")) {
                Canvas(Modifier.size(200.dp, 300.dp).foldLeftPane(progress)) {
                    // Model Haze/shadow/wallpaper outsets beyond the nominal pane.
                    drawRect(Color.Red, size = Size(size.width * 2f, size.height))
                }
            }
        }
        val pixels = compose.onNodeWithTag("neighbours").captureToImage().toPixelMap()
        for (y in 1 until pixels.height step 11) {
            val pixel = pixels[pixels.width * 3 / 4, y]
            assertTrue("Neighbour was overpainted at y=$y", pixel.green > .9f && pixel.red < .1f)
        }
    }

    @Test fun projectedPaperContoursRecedeButBothSidesStayPinnedLikeUpstream() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        // Explicit construction prevents the production fallback hiding a syntax failure.
        RuntimeShader(GLASS_PROJECTION_SHADER)
        RuntimeShader(GLASS_LINEAR_SHADER)
        RuntimeShader(GLASS_GAUSSIAN_SHADER)
        val progress = mutableStateOf(.5f)
        compose.setContent {
            Box(Modifier.size(200.dp, 300.dp).testTag("projection").foldLeftPane(progress, blur = false)
                .background(Color.White))
        }
        val pixels = compose.onNodeWithTag("projection").captureToImage().toPixelMap()
        assertTrue("Latest upstream keeps sides pinned", pixels[1, pixels.height / 2].red > .9f)
        assertTrue("Hinge stays bright", pixels[pixels.width - 3, pixels.height / 2].red > .9f)
        assertTrue("Top outside is black", pixels[pixels.width / 2, 1].red < .1f)
        compose.runOnIdle { progress.value = 1f }
        val flat = compose.onNodeWithTag("projection").captureToImage().toPixelMap()
        assertTrue("Flat endpoint is original content", flat[1, flat.height / 2].red > .9f)
    }

    @Test fun rightPaneRemainsUnprojectedDuringFold() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val progress = mutableStateOf(.1f)
        compose.setContent {
            Box(Modifier.size(200.dp, 300.dp).testTag("right")
                .foldLeftPane(progress, isLeft = { false }).background(Color.White))
        }
        val pixels = compose.onNodeWithTag("right").captureToImage().toPixelMap()
        assertTrue(pixels[1, 1].red > .9f)
        assertTrue(pixels[pixels.width - 2, pixels.height - 2].red > .9f)
    }
}

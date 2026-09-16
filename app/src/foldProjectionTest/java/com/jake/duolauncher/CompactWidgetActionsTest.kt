package com.jake.duolauncher

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.hazeSource

class CompactWidgetActionsTest {
    @get:Rule val compose=createComposeRule()
    @Test fun chineseMenuHasSameCompactDimensionsOnBothPanels() = menu("zh-CN")
    @Test fun englishMenuHasSameCompactDimensionsOnBothPanels() = menu("en")
    private fun menu(language: String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val config=Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        val localized=context.createConfigurationContext(config)
        val expanded=mutableStateOf(false);val configurable=mutableStateOf(true);val resizable=mutableStateOf(true)
        var resize=0;var settings=0;var replace=0;var remove=0;var closed=0;var pixelsPerDp=1f
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized,LocalConfiguration provides config) {
                pixelsPerDp=LocalDensity.current.density
                val backdrop=rememberHazeState()
                DuoTheme(true) {
                    Box(Modifier.size(if(expanded.value)800.dp else 425.dp,620.dp).testTag("widget-menu-scene")) {
                        DuneWallpaper(Modifier.fillMaxSize().hazeSource(backdrop))
                        CompositionLocalProvider(LocalDuoGlassBackdrop provides backdrop) {
                        CompactWidgetActions(Rect(100f,240f,400f,600f),resizable.value,configurable.value,
                            {resize++},{settings++},{replace++},{remove++},{closed++})
                        }
                    }
                }
            }
        }
        var compactWidth=0f
        for(inner in listOf(false,true)) {
            compose.runOnIdle { expanded.value=inner }
            val bounds=compose.onNodeWithTag("widget-context-menu").fetchSemanticsNode().boundsInRoot
            assertEquals(264f,bounds.width/pixelsPerDp,1f)
            assertTrue(bounds.height/pixelsPerDp<=96f)
            if(inner)assertEquals(compactWidth,bounds.width,1f)else compactWidth=bounds.width
            for(action in listOf("resize","settings","replace","remove")) {
                val button=compose.onNodeWithTag("widget-context-$action")
                assertEquals(48f,button.fetchSemanticsNode().boundsInRoot.height/pixelsPerDp,1f)
                button.performClick()
            }
            compose.onNodeWithText(if(language=="zh-CN")"调整大小" else "Resize").assertExists()
            compose.mainClock.advanceTimeBy(350)
            val image=compose.onNodeWithTag("widget-menu-scene").captureToImage().asAndroidBitmap()
            val file=File(context.getExternalFilesDir(null),"qa/widget-menu-$language-${if(inner)"inner" else "cover"}.png")
            file.parentFile!!.mkdirs();file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        compose.runOnIdle { assertEquals(2,resize);assertEquals(2,settings);assertEquals(2,replace);assertEquals(2,remove);configurable.value=false;resizable.value=false }
        compose.onNodeWithTag("widget-context-settings").assertDoesNotExist()
        compose.onNodeWithTag("widget-context-resize").assertIsNotEnabled()
        assertEquals(204f,compose.onNodeWithTag("widget-context-menu").fetchSemanticsNode().boundsInRoot.width/pixelsPerDp,1f)
        compose.onNodeWithTag("widget-context-overlay").performTouchInput { click(bottomRight-androidx.compose.ui.geometry.Offset(4f,4f)) }
        compose.runOnIdle { assertEquals(1,closed) }
    }
}

package com.jake.duolauncher

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

class SettingsGlassRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsRowsUseSameSoftSurfaceOnBothWidthsAndThemes() {
        val dark = mutableStateOf(false)
        val width = mutableStateOf(380)
        compose.setContent {
            DuoTheme(dark.value) {
                Surface(color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface) {
                Column(Modifier.width(width.value.dp).background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp).testTag("settings-material-qa"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsEntry(Icons.Rounded.Home, R.string.setup_default, R.string.setup_default_detail, onClick = {})
                    SettingsEntry(Icons.Rounded.Widgets, R.string.editor_widgets, onClick = {})
                    SettingsEntry(Icons.Rounded.Wallpaper, R.string.editor_wallpaper, onClick = {})
                    FoldAnimationSetting(true) {}
                }
                }
            }
        }
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "qa")
        output.mkdirs()
        for (night in listOf(false, true)) for (size in listOf(380, 760)) {
            compose.runOnIdle { dark.value = night; width.value = size }
            compose.waitForIdle()
            File(output, "settings-1.0.2-$night-$size.png").outputStream().use {
                compose.onNodeWithTag("settings-material-qa").captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}

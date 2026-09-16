package com.jake.duolauncher

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassUiTest {
    @Test fun settings_rows_have_uniform_light_fill_and_no_dark_drop_shadow() {
        for (dark in listOf(false, true)) for (pressed in listOf(false, true)) {
            val spec = duoGlassSpec(DuoGlassRole.SettingsRow, dark, pressed)
            assertEquals(0f, spec.elevation.value, 0f)
            assertEquals(0f, spec.highlightColor.alpha, 0f)
            assertEquals(1f, spec.fallbackColor.red, 0f)
            assertEquals(1f, spec.fallbackColor.green, 0f)
            assertEquals(1f, spec.fallbackColor.blue, 0f)
            assertTrue(spec.fallbackColor.alpha < .26f)
            assertTrue(spec.borderLightColor.alpha <= .22f)
            assertEquals(1f, spec.pressedScale, 0f)
        }
    }
    @Test fun soft_light_backdrop_stays_enabled_during_motion() {
        assertTrue(duoGlassBackdropEnabled())
    }

    @Test fun roles_have_a_small_deliberate_material_scale() {
        val specs = DuoGlassRole.entries.associateWith { duoGlassSpec(it, dark = false) }

        assertTrue(specs.getValue(DuoGlassRole.Dock).blurRadius > specs.getValue(DuoGlassRole.Control).blurRadius)
        assertTrue(specs.getValue(DuoGlassRole.Elevated).blurRadius >= specs.getValue(DuoGlassRole.Card).blurRadius)
        assertTrue(specs.getValue(DuoGlassRole.Screen).fallbackColor.alpha > specs.getValue(DuoGlassRole.Dock).fallbackColor.alpha)
        assertEquals(1f, specs.getValue(DuoGlassRole.WidgetFrame).pressedScale, 0f)
    }

    @Test fun pressed_material_brightens_without_changing_geometry() {
        DuoGlassRole.entries.forEach { role ->
            val resting = duoGlassSpec(role, dark = false)
            val pressed = duoGlassSpec(role, dark = false, pressed = true)

            assertTrue(pressed.tintColor.alpha >= resting.tintColor.alpha)
            assertTrue(pressed.borderLightColor.alpha >= resting.borderLightColor.alpha)
            assertTrue(pressed.pressedScale in .97f..1f)
            assertEquals(resting.blurRadius, pressed.blurRadius)
            assertEquals(resting.elevation, pressed.elevation)
        }
    }

    @Test fun dark_material_has_stronger_fallback_and_light_content() {
        val light = duoGlassSpec(DuoGlassRole.Card, dark = false)
        val dark = duoGlassSpec(DuoGlassRole.Card, dark = true)

        assertTrue(dark.fallbackColor.alpha > light.fallbackColor.alpha)
        assertTrue(dark.contentColor.red > light.contentColor.red)
        assertTrue(dark.contentColor.green > light.contentColor.green)
        assertTrue(dark.contentColor.blue > light.contentColor.blue)
    }

    @Test fun internal_glass_layers_fade_to_clear_and_never_to_black() {
        DuoGlassRole.entries.forEach { role ->
            val spec = duoGlassSpec(role, dark = false)
            val visibleLayers = duoGlassEdgeColors(spec) + duoGlassGlowColors(spec)
            assertEquals(Color.Transparent, duoGlassEdgeColors(spec).last())
            assertEquals(Color.Transparent, duoGlassGlowColors(spec).last())
            visibleLayers.filter { it.alpha > 0f }.forEach { color ->
                assertEquals(1f, color.red, .001f)
                assertEquals(1f, color.green, .001f)
                assertEquals(1f, color.blue, .001f)
            }
            assertTrue(spec.tintColor.alpha <= .11f)
            assertTrue(spec.noiseFactor <= .02f)
        }
    }
}

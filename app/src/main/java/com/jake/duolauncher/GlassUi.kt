package com.jake.duolauncher

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

internal enum class DuoGlassRole {
    Control,
    SettingsRow,
    Dock,
    Card,
    Elevated,
    Screen,
    Floating,
    WidgetFrame,
}

@Immutable
internal data class DuoGlassSpec(
    val backgroundColor: Color,
    val tintColor: Color,
    val fallbackColor: Color,
    val contentColor: Color,
    val highlightColor: Color,
    val borderLightColor: Color,
    val blurRadius: Dp,
    val noiseFactor: Float,
    val elevation: Dp,
    val pressedScale: Float,
)

internal val LocalDuoGlassBackdrop = staticCompositionLocalOf<HazeState?> { null }

/**
 * Soft-light glass is part of the visual material, not an idle-only effect.
 * Keep the sampled backdrop active while pages, widgets, and fold planes move.
 */
internal fun duoGlassBackdropEnabled(): Boolean = true

internal val DuoGlassRoleKey = SemanticsPropertyKey<String>("DuoGlassRole")
internal var SemanticsPropertyReceiver.duoGlassRole by DuoGlassRoleKey
internal val DuoGlassPressedKey = SemanticsPropertyKey<Boolean>("DuoGlassPressed")
internal var SemanticsPropertyReceiver.duoGlassPressed by DuoGlassPressedKey

internal fun duoGlassSpec(
    role: DuoGlassRole,
    dark: Boolean,
    pressed: Boolean = false,
): DuoGlassSpec {
    val base = if (dark) Color(0xFF121B1F) else Color(0xFFF7FAFA)
    val neutralLight = Color.White
    val content = if (dark) Color(0xFFF1F8FA) else Color(0xFF24383F)
    val values = when (role) {
        DuoGlassRole.Control -> floatArrayOf(.04f, .06f, .30f, 15f, .018f, 3f, .975f)
        // Nested settings rows already sit on a sheet; another dark drop shadow
        // shows through their translucent fill as a dirty ring around a light centre.
        DuoGlassRole.SettingsRow -> floatArrayOf(.025f, .035f, .12f, 15f, .012f, 0f, 1f)
        DuoGlassRole.Dock -> floatArrayOf(.055f, .075f, .36f, 22f, .020f, 7f, .985f)
        DuoGlassRole.Card -> floatArrayOf(.065f, .07f, .40f, 22f, .020f, 5f, .985f)
        DuoGlassRole.Elevated -> floatArrayOf(.085f, .08f, .52f, 26f, .020f, 13f, .985f)
        DuoGlassRole.Screen -> floatArrayOf(.11f, .065f, .66f, 28f, .018f, 0f, 1f)
        DuoGlassRole.Floating -> floatArrayOf(.08f, .085f, .56f, 20f, .020f, 11f, .98f)
        DuoGlassRole.WidgetFrame -> floatArrayOf(.035f, .05f, .25f, 18f, .015f, 3f, 1f)
    }
    val pressBoost = if (pressed) .025f else 0f
    val darkFallbackBoost = if (dark) .12f else 0f
    return DuoGlassSpec(
        backgroundColor = base.copy(alpha = values[0]),
        tintColor = neutralLight.copy(alpha = (values[1] + pressBoost) * if (dark) .65f else 1f),
        fallbackColor = if (role == DuoGlassRole.SettingsRow)
            Color.White.copy(alpha = (if (dark) .055f else .22f) + pressBoost)
            else base.copy(alpha = (values[2] + darkFallbackBoost + pressBoost).coerceAtMost(.96f)),
        contentColor = content,
        highlightColor = Color.White.copy(alpha = if (role == DuoGlassRole.SettingsRow) 0f else if (pressed) .13f else if (dark) .055f else .08f),
        borderLightColor = Color.White.copy(alpha = if (role == DuoGlassRole.SettingsRow) (if (pressed) .22f else .12f) else if (pressed) .40f else if (dark) .22f else .30f),
        blurRadius = values[3].dp,
        noiseFactor = values[4],
        elevation = values[5].dp,
        pressedScale = if (pressed) values[6] else 1f,
    )
}

internal fun duoGlassEdgeColors(spec: DuoGlassSpec): List<Color> = listOf(
    spec.borderLightColor,
    spec.borderLightColor.copy(alpha = spec.borderLightColor.alpha * .22f),
    Color.Transparent,
)

internal fun duoGlassGlowColors(spec: DuoGlassSpec): List<Color> = listOf(
    spec.highlightColor,
    spec.highlightColor.copy(alpha = spec.highlightColor.alpha * .22f),
    Color.Transparent,
)

@Composable
internal fun duoGlassContentColor(role: DuoGlassRole): Color =
    duoGlassSpec(role, LocalDuoPalette.current.dark).contentColor

/**
 * Measurement-neutral soft-light material. The caller owns size, padding and input;
 * this modifier only changes drawing and exposes testable material semantics.
 */
@Composable
internal fun Modifier.duoGlass(
    role: DuoGlassRole,
    shape: Shape,
    interactionSource: MutableInteractionSource? = null,
    backdrop: Boolean = true,
): Modifier {
    val noPress: State<Boolean> = remember { androidx.compose.runtime.mutableStateOf(false) }
    val pressedState = interactionSource?.collectIsPressedAsState() ?: noPress
    val pressed = pressedState.value
    val spec = duoGlassSpec(role, LocalDuoPalette.current.dark, pressed)
    val scale by animateFloatAsState(spec.pressedScale, label = "${role.name.lowercase()} glass press")
    val haze = LocalDuoGlassBackdrop.current
    val hazeStyle = remember(spec) {
        HazeStyle(
            backgroundColor = spec.backgroundColor,
            tint = HazeTint(spec.tintColor),
            blurRadius = spec.blurRadius,
            noiseFactor = spec.noiseFactor,
            fallbackTint = HazeTint(spec.fallbackColor),
        )
    }
    val material = if (backdrop && haze != null) {
        Modifier.hazeEffect(haze, hazeStyle)
    } else {
        Modifier.background(spec.fallbackColor, shape)
    }
    val edge = Brush.linearGradient(
        colors = duoGlassEdgeColors(spec),
    )
    val glow = Brush.linearGradient(
        colors = duoGlassGlowColors(spec),
    )
    return this
        .shadow(
            elevation = spec.elevation,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = .12f),
            spotColor = Color.Black.copy(alpha = .18f),
        )
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clip(shape)
        .then(material)
        .background(glow, shape)
        .border(1.dp, edge, shape)
        .semantics {
            duoGlassRole = role.name
            duoGlassPressed = pressed
        }
}

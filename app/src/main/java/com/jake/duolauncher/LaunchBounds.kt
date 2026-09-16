package com.jake.duolauncher

import androidx.compose.ui.geometry.Rect
import kotlin.math.roundToInt

internal fun Rect.toAndroidBounds() = android.graphics.Rect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())

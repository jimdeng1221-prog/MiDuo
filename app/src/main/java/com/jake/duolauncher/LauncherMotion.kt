package com.jake.duolauncher

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/** Shared motion language for direct-manipulation elements on Home. */
internal object LauncherMotion {
    val placement: AnimationSpec<IntOffset> = spring(
        dampingRatio = .82f,
        stiffness = 520f,
        visibilityThreshold = IntOffset(1, 1),
    )

    val lift: AnimationSpec<Float> = spring(
        dampingRatio = .72f,
        stiffness = 620f,
        visibilityThreshold = .002f,
    )

    val settle: AnimationSpec<Float> = spring(
        dampingRatio = .84f,
        stiffness = 480f,
        visibilityThreshold = .002f,
    )

    val size: AnimationSpec<Dp> = spring(
        dampingRatio = .84f,
        stiffness = 480f,
        visibilityThreshold = .5.dp,
    )

    val edgeReturn: AnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 680f,
        visibilityThreshold = .1f,
    )
}

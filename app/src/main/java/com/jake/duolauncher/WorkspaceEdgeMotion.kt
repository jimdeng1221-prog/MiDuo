package com.jake.duolauncher

import kotlin.math.abs
import kotlin.math.sign

/** Draw-phase values for a page dragged beyond its first or last legal stop. */
internal data class WorkspaceEdgeVisual(
    val translationPx: Float,
    val scale: Float,
    val shadeAlpha: Float,
    val progress: Float,
) {
    companion object {
        val Rest = WorkspaceEdgeVisual(translationPx = 0f, scale = 1f, shadeAlpha = 0f, progress = 0f)
    }
}

/**
 * Convert unbounded pointer travel into a short rubber-band movement. The legal
 * pager position remains unchanged; only the rendered workspace is transformed.
 */
internal fun workspaceEdgeVisual(
    requestedOffsetPx: Float,
    minimumOffsetPx: Float,
    maximumOffsetPx: Float,
    maximumTravelPx: Float,
): WorkspaceEdgeVisual {
    require(minimumOffsetPx <= maximumOffsetPx)
    require(maximumTravelPx.isFinite() && maximumTravelPx > 0f)
    val legal = requestedOffsetPx.coerceIn(minimumOffsetPx, maximumOffsetPx)
    val excess = requestedOffsetPx - legal
    if (excess == 0f) return WorkspaceEdgeVisual.Rest

    val resisted = rubberBand(abs(excess), maximumTravelPx)
    val progress = (resisted / maximumTravelPx).coerceIn(0f, 1f)
    return WorkspaceEdgeVisual(
        // Content follows the finger: pulling before the first stop moves it
        // right, while pulling beyond the last stop moves it left.
        translationPx = -sign(excess) * resisted,
        scale = 1f - .014f * progress,
        shadeAlpha = .20f * progress,
        progress = progress,
    )
}

/** Monotonic resistance with a finite asymptote and no discontinuity at zero. */
internal fun rubberBand(distancePx: Float, maximumTravelPx: Float): Float {
    require(distancePx.isFinite() && distancePx >= 0f)
    require(maximumTravelPx.isFinite() && maximumTravelPx > 0f)
    return maximumTravelPx * distancePx / (distancePx + maximumTravelPx)
}

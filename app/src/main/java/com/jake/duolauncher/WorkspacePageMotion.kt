package com.jake.duolauncher

import kotlin.math.abs

/**
 * Maps the native full-width pager position to the scroll distance shown by the
 * expanded workspace. Homes and the trailing All Apps page each advance by one
 * pane, so the last stop pairs the final home with All Apps.
 */
internal data class WorkspacePageMotion(
    val firstHome: Int,
    val homePages: Int,
    val pageWidth: Float,
    val homeStride: Float,
) {
    init {
        require(homePages > 0)
        require(pageWidth.isFinite() && pageWidth > 0f)
        require(homeStride.isFinite() && homeStride > 0f)
    }

    /** Visual scroll offset for a physical (and possibly fractional) pager position. */
    fun offset(position: Float): Float {
        val logical = position - firstHome
        return when {
            logical < 0f -> logical * pageWidth
            else -> logical * homeStride
        }
    }

    /** Physical pager position for a visual scroll offset. */
    fun position(offset: Float): Float {
        val logical = when {
            offset < 0f -> offset / pageWidth
            else -> offset / homeStride
        }
        return logical + firstHome
    }

    fun positionAfterVisualDelta(position: Float, delta: Float): Float =
        position(offset(position) + delta)

    fun stride(fromPosition: Int, towardPosition: Int): Float =
        abs(offset(towardPosition.toFloat()) - offset(fromPosition.toFloat()))
}

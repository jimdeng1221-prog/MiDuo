package com.jake.duolauncher

import androidx.compose.foundation.pager.PagerState

/** Home/drop indices stay zero based; the leading component page is logical page -1. */
internal class LauncherPager(val state: PagerState, private val firstHome: Int) {
    val currentPage get() = state.currentPage - firstHome
    val settledPage get() = state.settledPage - firstHome
    fun requestScrollToPage(page: Int) = state.requestScrollToPage(page + firstHome)
    suspend fun scrollToPage(page: Int) = state.scrollToPage(page + firstHome)
    suspend fun animateScrollToPage(page: Int) = state.animateScrollToPage(page + firstHome)
}

internal fun remapPhysicalPageForPosture(
    currentPhysicalPage: Int,
    previousFirstHome: Int,
    nextFirstHome: Int,
    pageCount: Int,
): Int {
    // The cover is the right pane of an unfolded spread. Opening therefore moves
    // the spread start one page left (clamped at the leading component page), and
    // folding moves one page right to keep the unfolded spread's right pane.
    val target = when {
        previousFirstHome == 0 && nextFirstHome == 1 -> currentPhysicalPage + 1
        previousFirstHome == 1 && nextFirstHome == 0 -> currentPhysicalPage - 1
        else -> currentPhysicalPage
    }
    return target.coerceIn(0, pageCount - 1)
}

/**
 * A fold transition can recreate the window instead of recomposing it in place.
 * In that case the non-saveable transition tracker must start from the posture
 * that owned the saved page, otherwise the first effect mistakes the new posture
 * for an already handled one and leaves the cover on the spread's left page.
 */
internal fun restoredPagePosture(savedPosture: Int, currentPosture: Int): Int =
    savedPosture.takeIf { it >= 0 } ?: currentPosture

/** The cover navigation belongs to Home 1 and follows it to the right. */
internal fun coverPageNavigationProgress(
    expanded: Boolean,
    firstHome: Int,
    physicalPosition: Float,
): Float = if (expanded) 0f else (firstHome - physicalPosition).coerceIn(0f, 1f)

/**
 * The unfolded indicator starts under the right-hand Home pane. As the leading
 * page leaves, it follows the swipe to the centre of the complete icon workspace.
 */
internal fun expandedPageNavigationOffsetFraction(
    expanded: Boolean,
    physicalPosition: Float,
): Float = if (!expanded) 0f else (1f - physicalPosition).coerceIn(0f, 1f) / 4f

/** Keep it composed during a transition so it can slide out and back in. */
internal fun showPageNavigation(
    expanded: Boolean,
    firstHome: Int,
    settledPhysicalPage: Int,
    scrollInProgress: Boolean,
): Boolean = expanded || settledPhysicalPage >= firstHome || scrollInProgress

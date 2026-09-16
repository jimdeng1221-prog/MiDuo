package com.jake.duolauncher

import androidx.compose.animation.core.animate
import androidx.compose.foundation.MutatePriority
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput

/** One adjacent page per ordinary pointer gesture, including its release fling. */
internal class PageGestureLimits(private val pager: PagerState) : NestedScrollConnection, PagerSnapDistance {
    var anchor: Int? = null
    var pointerDown = false
    var editing = false
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val start = anchor ?: return Offset.Zero
        if (!pointerDown || editing || source != NestedScrollSource.UserInput || available.x == 0f) return Offset.Zero
        val stride = pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing
        if (stride <= 0) return Offset.Zero
        val position = pager.currentPage + pager.currentPageOffsetFraction
        val allowed = boundedPagePosition(position - available.x / stride, start, pager.pageCount)
        return Offset(available.x + (allowed - position) * stride, 0f)
    }
    override fun calculateTargetPage(startPage: Int, suggestedTargetPage: Int, velocity: Float,
        pageSize: Int, pageSpacing: Int): Int =
        if (editing) suggestedTargetPage else boundedPagePosition(suggestedTargetPage.toFloat(), anchor ?: startPage, pager.pageCount).toInt()
}

internal fun boundedPagePosition(position: Float, anchor: Int, count: Int): Float =
    position.coerceIn((anchor - 1).coerceAtLeast(0).toFloat(), (anchor + 1).coerceAtMost(count - 1).coerceAtLeast(0).toFloat())

internal fun releasePage(position: Float, anchor: Int, count: Int, velocity: Float, threshold: Float,
    distanceThreshold: Float = .2f): Int {
    val displacement = position - anchor
    val candidate = when {
        velocity < -threshold -> floor(position + .0001f).toInt() + 1
        velocity > threshold -> ceil(position - .0001f).toInt() - 1
        abs(displacement) >= distanceThreshold -> anchor + sign(displacement).toInt()
        else -> anchor
    }
    return boundedPagePosition(candidate.toFloat(), anchor, count).toInt()
}

internal fun shadePanelForStart(startX: Float, width: Float): ShadePanel =
    if (startX < width * .7f) ShadePanel.NOTIFICATIONS else ShadePanel.QUICK_SETTINGS

/** The visible leading pane owns vertical drags, including empty space between widgets. */
internal fun startsInExpandedLeadingPane(expanded: Boolean, startX: Float, paneWidth: Float,
    scrollOffset: Float): Boolean = expanded && startX >= 0f && startX < (paneWidth - scrollOffset).coerceAtLeast(0f)

/** Choose an axis before children see the slop-crossing event. A vertical list must not
 * steal a mostly-horizontal swipe simply because one fast sample crossed both thresholds.
 * PagerState still owns scrolling, layout, cancellation, and the settling animation. */
@Composable
internal fun Modifier.onePageGestures(
    pager: PagerState,
    limits: PageGestureLimits,
    motion: WorkspacePageMotion? = null,
    enabled: Boolean = true,
    canStartDownwardSwipe: (Offset) -> Boolean = { true },
    onDownwardSwipe: ((ShadePanel) -> Unit)? = null,
    onLeadingOverscroll: (() -> Unit)? = null,
    onEdgeVisual: ((WorkspaceEdgeVisual) -> Unit)? = null,
) : Modifier {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentCanStartDownwardSwipe by rememberUpdatedState(canStartDownwardSwipe)
    val currentDownwardSwipe by rememberUpdatedState(onDownwardSwipe)
    val currentLeadingOverscroll by rememberUpdatedState(onLeadingOverscroll)
    val currentEdgeVisual by rememberUpdatedState(onEdgeVisual)
    return nestedScroll(limits).pointerInput(pager, limits, motion) {
        coroutineScope {
            var motionJob: Job? = null
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val gestureEnabled = currentEnabled
                val anchor = pager.currentPage
                val trace = DuoMotionTrace.begin(anchor,
                    pager.currentPage + pager.currentPageOffsetFraction, down.position.x, down.position.y)
                if (!gestureEnabled) {
                    trace?.cancel("disabled", pager.currentPage + pager.currentPageOffsetFraction)
                    return@awaitEachGesture
                }
                limits.anchor = anchor
                limits.pointerDown = true
                val tracker = VelocityTracker().apply { addPointerInputChange(down) }
                var dragPositions: Channel<Float>? = null
                var dragSlopOffset = 0f
                var releaseVelocity = 0f
                var leadingDrag = 0f
                var canceled = false
                var cancelReason: String? = null
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed || limits.editing || event.changes.any { it.id != down.id && it.pressed }) {
                            canceled = true
                            cancelReason = when {
                                change == null -> "pointer_missing"
                                change.isConsumed -> "consumed"
                                limits.editing -> "editing"
                                else -> "multiple_pointers"
                            }
                            break
                        }
                        // Match Compose's drag tracking, including Android's batched historical
                        // samples and its handling of the final finger-up event.
                        tracker.addPointerInputChange(change)
                        leadingDrag = maxOf(leadingDrag, change.position.x - down.position.x)
                        val terminalRelease = event.type == PointerEventType.Release && change.changedToUp()
                        if (dragPositions == null && (change.pressed || terminalRelease)) {
                            val distance = change.position - down.position
                            if (maxOf(abs(distance.x), abs(distance.y)) > viewConfiguration.touchSlop) {
                                trace?.slop(distance.x, distance.y,
                                    pager.currentPage + pager.currentPageOffsetFraction)
                                if (abs(distance.y) >= abs(distance.x)) {
                                    // A terminal release can preserve meaningful travel after a
                                    // blocked frame dropped every MOVE. It can page horizontally,
                                    // but must not trigger a vertical system action on finger-up.
                                    val openDownward = change.pressed && distance.y > 0f && currentDownwardSwipe != null &&
                                        currentCanStartDownwardSwipe(down.position)
                                    cancelReason = if (openDownward) "downward_action" else "vertical_axis"
                                    if (openDownward) {
                                        change.consume()
                                        currentDownwardSwipe?.invoke(shadePanelForStart(down.position.x, size.width.toFloat()))
                                    }
                                    break
                                }
                                motionJob?.cancel()
                                // Pointer input can outpace layout on a busy frame. Keep the
                                // latest absolute drag position rather than queueing and replaying
                                // every stale delta after the finger has already moved on.
                                val channel = Channel<Float>(Channel.CONFLATED)
                                dragPositions = channel
                                dragSlopOffset = sign(distance.x) * viewConfiguration.touchSlop
                                motionJob = launch(start = CoroutineStart.UNDISPATCHED) {
                                    fun stride() = (pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing).toFloat().coerceAtLeast(1f)
                                    fun position() = pager.currentPage + pager.currentPageOffsetFraction
                                    fun visualOffset() = motion?.offset(position()) ?: position() * stride()
                                    try {
                                        pager.scroll(MutatePriority.UserInput) {
                                            with(pager) { updateTargetPage((anchor - sign(distance.x).toInt()).coerceIn(0, pager.pageCount - 1)) }
                                            fun moveBy(pixels: Float) {
                                                val current = position()
                                                val requested = motion?.positionAfterVisualDelta(current, pixels)
                                                    ?: (current + pixels / stride())
                                                val requestedVisual = motion?.offset(requested) ?: requested * stride()
                                                val firstVisual = motion?.offset(0f) ?: 0f
                                                val lastVisual = motion?.offset((pager.pageCount - 1).coerceAtLeast(0).toFloat())
                                                    ?: (pager.pageCount - 1).coerceAtLeast(0) * stride()
                                                currentEdgeVisual?.invoke(workspaceEdgeVisual(
                                                    requestedOffsetPx = requestedVisual,
                                                    minimumOffsetPx = minOf(firstVisual, lastVisual),
                                                    maximumOffsetPx = maxOf(firstVisual, lastVisual),
                                                    maximumTravelPx = 36f * density,
                                                ))
                                                val allowed = boundedPagePosition(requested, anchor, pager.pageCount)
                                                scrollBy((allowed - current) * stride())
                                            }
                                            val dragStartVisualOffset = visualOffset()
                                            for (dragPosition in channel) {
                                                if (dragPosition != 0f) with(pager) { updateTargetPage((anchor - sign(dragPosition).toInt()).coerceIn(0, pager.pageCount - 1)) }
                                                moveBy(dragStartVisualOffset - dragPosition - visualOffset())
                                            }
                                            val velocity = if (canceled) 0f else releaseVelocity
                                            // A relaxed thumb swipe should commit even if the finger
                                            // slows before lifting. Cap the distance on the unfolded
                                            // display so it never requires a half-screen hand stretch.
                                            val releaseDirection = when {
                                                position() > anchor -> 1
                                                position() < anchor -> -1
                                                velocity < 0f -> 1
                                                velocity > 0f -> -1
                                                else -> 0
                                            }
                                            val adjacent = (anchor + releaseDirection).coerceIn(0, pager.pageCount - 1)
                                            val releaseStride = motion?.stride(anchor, adjacent)
                                                ?.takeIf { it > 0f } ?: stride()
                                            val target = if (canceled) position().roundToInt() else releasePage(
                                                position(), anchor, pager.pageCount, velocity, 250f * density,
                                                distanceThreshold = minOf(.2f, 72f * density / releaseStride)
                                            )
                                            trace?.release(position(), velocity, target, canceled)
                                            with(pager) { updateTargetPage(target) }
                                            val startVisualOffset = visualOffset()
                                            val targetVisualOffset = motion?.offset(target.toFloat()) ?: target * stride()
                                            val distanceToPage = targetVisualOffset - startVisualOffset
                                            animate(0f, distanceToPage, initialVelocity = (-velocity).coerceIn(-stride() * 5f, stride() * 5f)) { value, _ ->
                                                // Springs can overshoot the one-page bound. Base every
                                                // frame on the pager's actual position so a rejected delta
                                                // cannot become a persistent settling offset.
                                                moveBy(startVisualOffset + value - visualOffset())
                                            }
                                            // The final callback normally lands exactly here; resolve any
                                            // subpixel pager rounding before this scroll mutation completes.
                                            moveBy(targetVisualOffset - visualOffset())
                                        }
                                        trace?.motionCompleted(position(), visualOffset())
                                    } catch (failure: CancellationException) {
                                        trace?.motionCanceled(position(), visualOffset())
                                        throw failure
                                    }
                                    if (!canceled && anchor == 0 && leadingDrag >= 72f * density) {
                                        currentLeadingOverscroll?.invoke()
                                    }
                                }
                                channel.trySend(distance.x - dragSlopOffset)
                            }
                        } else if (dragPositions != null) {
                            dragPositions?.trySend(change.position.x - down.position.x - dragSlopOffset)
                        }
                        if (dragPositions != null) change.consume()
                        if (!change.pressed) {
                            if (trace != null) trace.terminal(event.type.toString(),
                                change.position.x - down.position.x, change.position.y - down.position.y,
                                change.uptimeMillis, android.os.SystemClock.uptimeMillis(),
                                change.historical.size, change.isConsumed,
                                pager.currentPage + pager.currentPageOffsetFraction)
                            releaseVelocity = tracker.calculateVelocity().x
                            break
                        }
                    }
                } finally {
                    limits.pointerDown = false
                    dragPositions?.close()
                    val finalCancelReason = cancelReason
                    if (finalCancelReason != null) {
                        trace?.cancel(finalCancelReason, pager.currentPage + pager.currentPageOffsetFraction)
                    } else if (dragPositions == null) {
                        trace?.release(pager.currentPage + pager.currentPageOffsetFraction,
                            releaseVelocity, anchor, canceled = false)
                    }
                    currentEdgeVisual?.invoke(WorkspaceEdgeVisual.Rest)
                }
            }
        }
    }
}

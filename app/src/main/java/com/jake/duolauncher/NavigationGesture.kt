package com.jake.duolauncher

import kotlin.math.abs
import kotlin.math.hypot

internal enum class NavigationEdge { LEFT, RIGHT, BOTTOM }
internal enum class NavigationAction { BACK, HOME, RECENTS, RECOVERY }

internal data class NavigationGestureProgress(
    val edge: NavigationEdge,
    val x: Float,
    val y: Float,
    val distance: Float,
    val hold: Float,
    val recovery: Float,
    val active: Boolean,
)

/** Coordinates are dp; cancellation and multi-touch must never trigger navigation. */
internal class NavigationGesture(private val edge: NavigationEdge) {
    private var startX = 0f
    private var startY = 0f
    private var x = 0f
    private var y = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var started = 0L
    private var stableSince = 0L
    private var active = false
    private var moved = false

    fun down(x: Float, y: Float, time: Long) {
        startX = x; startY = y; this.x = x; this.y = y
        anchorX = x; anchorY = y; started = time; stableSince = time
        active = true; moved = false
    }

    fun move(x: Float, y: Float, time: Long) {
        if (!active) return
        this.x = x; this.y = y
        if (hypot(x - startX, y - startY) > 10f) moved = true
        if (hypot(x - anchorX, y - anchorY) > 7f) {
            anchorX = x; anchorY = y; stableSince = time
        }
    }

    fun tick(time: Long): NavigationAction? {
        if (!active || edge != NavigationEdge.BOTTOM) return null
        if (!moved && time - started >= 2000L) return finish(NavigationAction.RECOVERY)
        if (upward() && time - stableSince >= 350L) return finish(NavigationAction.RECENTS)
        return null
    }

    fun progress(time: Long): NavigationGestureProgress {
        if (!active) return NavigationGestureProgress(edge, x, y, 0f, 0f, 0f, false)
        val distance = when (edge) {
            NavigationEdge.BOTTOM -> ((startY - y) / 48f).coerceIn(0f, 1f)
            NavigationEdge.LEFT -> ((x - startX) / 28f).coerceIn(0f, 1f)
            NavigationEdge.RIGHT -> ((startX - x) / 28f).coerceIn(0f, 1f)
        }
        val hold = if (edge == NavigationEdge.BOTTOM && upward())
            ((time - stableSince) / 350f).coerceIn(0f, 1f) else 0f
        val recovery = if (edge == NavigationEdge.BOTTOM && !moved)
            ((time - started) / 2000f).coerceIn(0f, 1f) else 0f
        return NavigationGestureProgress(edge, x, y, distance, hold, recovery, true)
    }

    fun up(time: Long): NavigationAction? {
        if (!active) return null
        tick(time)?.let { return it }
        val dx = x - startX
        val dy = y - startY
        val action = when (edge) {
            NavigationEdge.BOTTOM -> if (upward()) NavigationAction.HOME else null
            NavigationEdge.LEFT -> if (dx >= 28f && dx > abs(dy) * 1.2f) NavigationAction.BACK else null
            NavigationEdge.RIGHT -> if (-dx >= 28f && -dx > abs(dy) * 1.2f) NavigationAction.BACK else null
        }
        active = false
        return action
    }

    fun cancel() { active = false }
    private fun upward() = startY - y >= 48f && startY - y > abs(x - startX) * 1.2f
    private fun finish(action: NavigationAction): NavigationAction { active = false; return action }
}

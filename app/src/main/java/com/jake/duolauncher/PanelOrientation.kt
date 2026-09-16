package com.jake.duolauncher

import android.app.Activity
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateOf

/** No logical-display-id constants: HyperOS may reuse id 0 for either panel. */
internal fun launcherPanel(
    currentArea: Long,
    deviceAreas: List<Long>,
    smallestWidthDp: Int,
    maximumWidthPx: Int = 0,
    maximumHeightPx: Int = 0,
    density: Float = 0f,
): FoldPanel {
    // Activity mode/configuration can be compatibility-adjusted after a panel handoff.
    // Use the full display bounds first, otherwise a portrait letterbox on the inner
    // screen is mistaken for the cover and keeps requesting that same letterbox.
    if (maximumWidthPx > 0 && maximumHeightPx > 0) {
        val maximumPanel = panelFromPhysicalAreas(
            listOf(maximumWidthPx.toLong() * maximumHeightPx), deviceAreas,
        )
        if (maximumPanel != FoldPanel.UNKNOWN) return maximumPanel
        if (density.isFinite() && density > 0f) {
            val fullSmallestWidthDp = minOf(maximumWidthPx, maximumHeightPx) / density
            return if (fullSmallestWidthDp >= 600f) FoldPanel.INNER else FoldPanel.COVER
        }
    }
    val physical = panelFromPhysicalAreas(listOf(currentArea), deviceAreas)
    return if (physical != FoldPanel.UNKNOWN) physical
        else if (smallestWidthDp >= 600) FoldPanel.INNER else FoldPanel.COVER
}

internal enum class StandbyDirection(val orientation: Int, val rotation: Int) {
    LEFT(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Surface.ROTATION_90),
    RIGHT(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, Surface.ROTATION_270)
}

internal fun standbyDirection(x: Float, y: Float): StandbyDirection? =
    if (!physicallyLandscape(x, y)) null else if (x > 0f) StandbyDirection.LEFT else StandbyDirection.RIGHT

internal fun launcherOrientation(panel: FoldPanel, standby: Boolean = false, direction: StandbyDirection? = null): Int = when (panel) {
    // Preserve the already accepted inner-screen landscape direction (rotation 270).
    FoldPanel.INNER -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    // SENSOR_LANDSCAPE first commits the default side before correcting to the sensor.
    // Select the explicit side from the same fresh sample that admitted StandBy.
    FoldPanel.COVER -> if (standby && direction != null) direction.orientation else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}

/** Device axes, not the window rotation inherited from the inner activity. Flat/diagonal
 * and upright devices are not evidence of an intentional landscape StandBy posture. */
internal fun physicallyLandscape(x: Float, y: Float): Boolean =
    x.isFinite() && y.isFinite() && kotlin.math.abs(x) >= 4.5f && kotlin.math.abs(x) > kotlin.math.abs(y) * 1.25f

internal fun physicallyUpright(x: Float, y: Float): Boolean = physicallyLandscape(y, x)

internal enum class LauncherSurface { HOME, STANDBY, ROTATING }

/** Never render the cover Home using a landscape configuration, including handoff frames. */
internal fun launcherSurface(panel: FoldPanel, landscape: Boolean, standby: Boolean, directionReady: Boolean = true): LauncherSurface = when {
    panel != FoldPanel.COVER -> LauncherSurface.HOME
    standby && landscape && directionReady -> LauncherSurface.STANDBY
    !standby && !landscape -> LauncherSurface.HOME
    else -> LauncherSurface.ROTATING
}

internal class StandbyEntryGate {
    private var lastPanel = FoldPanel.UNKNOWN
    private var eligibleSince: Long? = null
    private var dismissed = false
    fun reset() { lastPanel = FoldPanel.UNKNOWN; eligibleSince = null; dismissed = false }
    fun pause() { eligibleSince = null }
    fun dismiss() { dismissed = true; eligibleSince = null }
    fun update(panel: FoldPanel, allowed: Boolean, physicalLandscape: Boolean, now: Long, upright: Boolean = false): Boolean {
        if (panel != lastPanel) { eligibleSince = null; lastPanel = panel; dismissed = false }
        if (upright) dismissed = false
        if (panel != FoldPanel.COVER || !allowed || !physicalLandscape || dismissed) {
            eligibleSince = null
            return false
        }
        val since = eligibleSince ?: now.also { eligibleSince = it }
        return now - since >= 700L
    }
}

internal class PanelOrientationController(private val activity: Activity) : DisplayManager.DisplayListener, SensorEventListener {
    val panel = mutableStateOf(FoldPanel.UNKNOWN)
    val standbyReady = mutableStateOf(false)
    val standbyDirectionReady = mutableStateOf(false)
    private val manager = activity.applicationContext.getSystemService(DisplayManager::class.java)
    private val sensors = activity.getSystemService(SensorManager::class.java)
    private val orientationSensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val entryGate = StandbyEntryGate()
    private var physicalLandscape = false
    private var direction: StandbyDirection? = null
    private var upright = false
    private var standbyAllowed = false
    private var sampleAt = -1L
    private var samplesAfter = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private val refreshTask = Runnable { refresh() }

    fun start() {
        if (!started) {
            started = true
            resetStandby(clearDismissal = false)
            manager.registerDisplayListener(this, handler)
            orientationSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        }
        refresh()
    }
    fun stop() {
        if (started) manager.unregisterDisplayListener(this)
        sensors.unregisterListener(this)
        started = false
        resetStandby(clearDismissal = false)
        handler.removeCallbacks(refreshTask)
    }
    fun refresh() {
        val display = activity.display ?: return
        val area = display.mode.physicalWidth.toLong() * display.mode.physicalHeight
        val areas = manager.displays.filter { it.isValid && it.name == display.name }
            .map { it.mode.physicalWidth.toLong() * it.mode.physicalHeight }
        val maximum = activity.windowManager.maximumWindowMetrics
        val bounds = maximum.bounds
        val density = if (android.os.Build.VERSION.SDK_INT >= 34) maximum.density
            else activity.resources.displayMetrics.density
        val current = launcherPanel(area, areas, activity.resources.configuration.smallestScreenWidthDp,
            bounds.width(), bounds.height(), density)
        if (current != panel.value) {
            Log.i("MiDuoPanel", "panel=$current max=${bounds.width()}x${bounds.height()} density=$density compatSw=${activity.resources.configuration.smallestScreenWidthDp} modeArea=$area")
            resetStandby()
        }
        panel.value = current
        updateStandby()
    }
    private fun resetStandby(clearDismissal: Boolean = true) {
        if (clearDismissal) entryGate.reset() else entryGate.pause()
        standbyReady.value = false
        standbyDirectionReady.value = false
        direction = null
        physicalLandscape = false; upright = false; sampleAt = -1L; samplesAfter = SystemClock.elapsedRealtime()
    }
    fun setStandbyAllowed(allowed: Boolean) {
        standbyAllowed = allowed
        updateStandby()
    }
    fun dismissStandby() {
        entryGate.dismiss()
        updateStandby()
    }
    private fun updateStandby() {
        val now = SystemClock.elapsedRealtime()
        val fresh = started && sampleAt >= samplesAfter && now - sampleAt in 0L..1000L
        // Entry is driven by the accelerometer even while Home is locked portrait.
        standbyReady.value = entryGate.update(panel.value, standbyAllowed, fresh && physicalLandscape, now, fresh && upright)
        val requested = launcherOrientation(panel.value, standbyReady.value, if (fresh) direction else null)
        if (activity.requestedOrientation != requested) activity.requestedOrientation = requested
        // A stale landscape configuration can still belong to the opposite side.
        standbyDirectionReady.value = standbyReady.value && direction != null && activity.display?.rotation == direction?.rotation
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (!started || event.values.size < 2) return
        val timestamp = event.timestamp / 1_000_000L
        if (timestamp < samplesAfter) return // queued samples from before the panel handoff are not evidence
        sampleAt = timestamp
        physicalLandscape = physicallyLandscape(event.values[0], event.values[1])
        direction = standbyDirection(event.values[0], event.values[1])
        upright = physicallyUpright(event.values[0], event.values[1])
        updateStandby()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    private fun schedule() { if (started) { handler.removeCallbacks(refreshTask); handler.post(refreshTask) } }
    override fun onDisplayAdded(displayId: Int) = schedule()
    override fun onDisplayRemoved(displayId: Int) = schedule()
    override fun onDisplayChanged(displayId: Int) = schedule()
}

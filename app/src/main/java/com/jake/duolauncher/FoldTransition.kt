package com.jake.duolauncher

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.RoundedCorner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/** The last few degrees settle early so a slightly imperfect flat hinge is crisp. */
internal fun hingeReveal(degrees: Float): Float {
    if (!degrees.isFinite()) return 1f
    return ((degrees - 1f) / 174f).coerceIn(0f, 1f)
}

internal data class FoldVisual(val opacity: Float, val scaleX: Float, val rotationY: Float, val blurDp: Float)
internal data class FoldEdgeDepths(val left: Float, val right: Float)
internal data class FoldProjectionGeometry(
    val horizontalInsetFraction: Float,
    val verticalInsetFraction: Float,
    val maxBlurFraction: Float,
)

internal enum class FoldPanel { UNKNOWN, COVER, INNER }
internal enum class FoldShadeSide { LEFT, RIGHT }
internal enum class FoldDirection { UNKNOWN, OPENING, CLOSING }
internal enum class FoldPhase {
    RECOVERY,
    COVER_STABLE,
    OPENING_COVER,
    HANDOFF_TO_INNER,
    OPENING_INNER,
    INNER_STABLE,
    CLOSING_INNER,
    HANDOFF_TO_COVER,
    CLOSING_COVER,
}

internal data class FoldSignal(
    val angleDegrees: Float? = null,
    val sensorTimestampNs: Long = 0L,
    val receivedElapsedNs: Long = 0L,
    val velocityDegreesPerSecond: Float = 0f,
    val direction: FoldDirection = FoldDirection.UNKNOWN,
    val activePanel: FoldPanel = FoldPanel.UNKNOWN,
    val previousPanel: FoldPanel = FoldPanel.UNKNOWN,
    val panelEpoch: Long = 0L,
    val panelChangedElapsedNs: Long = 0L,
    val phase: FoldPhase = FoldPhase.RECOVERY,
    val coverProjectionBlocked: Boolean = false,
)

// Cover projection is a transition, not a resting desktop posture. TYPE_HINGE_ANGLE
// is an on-change sensor: a missing final closed reading must not strand Home in
// its last trapezoid. Keep tracking while moving, then settle the cover only.
internal const val COVER_FOLD_IDLE_NS = 1_200_000_000L

internal fun foldTrackingProgress(signal: FoldSignal, panel: FoldPanel, nowNs: Long): Float {
    val rest = if (panel == FoldPanel.INNER) 1f else 0f
    val angle = signal.angleDegrees?.takeIf { it.isFinite() } ?: return rest
    if (signal.activePanel != panel) return rest
    if (panel == FoldPanel.COVER && signal.coverProjectionBlocked) return rest
    if (panel == FoldPanel.COVER && (signal.receivedElapsedNs <= 0L ||
            nowNs - signal.receivedElapsedNs !in 0 until COVER_FOLD_IDLE_NS)) return rest
    return hingeReveal(angle)
}

internal fun resetFoldTracking(signal: FoldSignal): FoldSignal = signal.copy(
    angleDegrees = null,
    sensorTimestampNs = 0L,
    receivedElapsedNs = 0L,
    velocityDegreesPerSecond = 0f,
    direction = FoldDirection.UNKNOWN,
    activePanel = FoldPanel.UNKNOWN,
    previousPanel = FoldPanel.UNKNOWN,
    panelChangedElapsedNs = 0L,
    phase = FoldPhase.RECOVERY,
    coverProjectionBlocked = false,
)

internal fun foldVisual(progress: Float): FoldVisual {
    val p = progress.coerceIn(0f, 1f)
    val remaining = 1f - p
    return FoldVisual(
        // Darkness comes from the three outside edges, not from fading the
        // complete page into its wallpaper.
        opacity = 1f,
        // Strong overscan compensates the foreshortened far edge. The content
        // therefore appears to continue beyond the panel instead of becoming a
        // smaller card floating inside it.
        scaleX = 1f + .24f * remaining,
        // With the hinge fixed on the right, positive Y rotation sends the
        // free left edge away from the camera, so the left side is shorter.
        rotationY = 30f * remaining,
        blurDp = 18f * remaining,
    )
}

/** The cover is the opposite face of the folding plane. It rotates away around
 * its left hinge while opening, and reverses the exact path when it takes Home
 * back during closing. */
internal fun foldCoverVisual(progress: Float): FoldVisual {
    val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    return FoldVisual(
        opacity = 1f,
        scaleX = 1f + .18f * p,
        // The cover keeps its left hinge edge tall and sends the right edge
        // away from the camera, making the right side of the trapezoid shorter.
        rotationY = 24f * p,
        blurDp = 15f * p,
    )
}

internal fun foldEdgeInsetFraction(amount: Float): Float =
    .072f * (if (amount.isFinite()) amount.coerceIn(0f, 1f) else 0f)

internal fun foldPerspectiveInsetFraction(amount: Float): Float =
    .055f * (if (amount.isFinite()) amount.coerceIn(0f, 1f) else 0f)

internal fun foldEdgeDepths(edge: Float, perspective: Float, side: FoldShadeSide): FoldEdgeDepths =
    when (side) {
        FoldShadeSide.LEFT -> FoldEdgeDepths(left = edge + perspective, right = edge)
        FoldShadeSide.RIGHT -> FoldEdgeDepths(left = edge, right = edge + perspective)
    }

/** Geometry shared by the AGSL renderer and unit tests. The free edge moves
 * inward while both sloping edges converge to zero exactly at the hinge. */
internal fun foldProjectionGeometry(amount: Float): FoldProjectionGeometry {
    val p = if (amount.isFinite()) amount.coerceIn(0f, 1f) else 0f
    val eased = p
    return FoldProjectionGeometry(
        horizontalInsetFraction = .13f * eased,
        verticalInsetFraction = .16f * eased,
        maxBlurFraction = .045f * eased,
    )
}

/** Inverse homography of a plane whose free edge is shorter than its hinge. */
internal fun foldSourceFraction(projectedFraction: Float, verticalInsetFraction: Float): Float {
    val q = projectedFraction.coerceIn(0f, 1f)
    val freeEdgeScale = (1f - 2f * verticalInsetFraction).coerceIn(.01f, 1f)
    return q / (freeEdgeScale + (1f - freeEdgeScale) * q)
}

/** Cover-screen motion consumes only the first part of the physical opening.
 * The exact panel handoff remains display-event driven rather than threshold driven. */
internal fun coverFoldProgress(hingeProgress: Float): Float {
    val normalized = (hingeProgress.coerceIn(0f, 1f) / .68f).coerceIn(0f, 1f)
    return normalized
}


/** The unfolded left pane meets the hinge on its right. The cover is mounted on
 * the opposite face, so its hinge edge is on the left and its free edge is right. */
internal fun foldShadeSide(expanded: Boolean): FoldShadeSide =
    if (expanded) FoldShadeSide.LEFT else FoldShadeSide.RIGHT

internal fun nextFoldDirection(previousDegrees: Float?, degrees: Float,
    previousDirection: FoldDirection): FoldDirection {
    if (!degrees.isFinite() || previousDegrees == null || !previousDegrees.isFinite()) return previousDirection
    val delta = degrees - previousDegrees
    return when {
        delta >= .9f -> FoldDirection.OPENING
        delta <= -.9f -> FoldDirection.CLOSING
        else -> previousDirection
    }
}

internal fun foldPhase(panel: FoldPanel, hingeProgress: Float,
    direction: FoldDirection): FoldPhase {
    val progress = hingeProgress.coerceIn(0f, 1f)
    return when {
        panel == FoldPanel.UNKNOWN -> FoldPhase.RECOVERY
        panel == FoldPanel.COVER && progress <= .015f -> FoldPhase.COVER_STABLE
        panel == FoldPanel.INNER && progress >= .985f -> FoldPhase.INNER_STABLE
        panel == FoldPanel.COVER && direction == FoldDirection.OPENING -> FoldPhase.OPENING_COVER
        panel == FoldPanel.COVER -> FoldPhase.CLOSING_COVER
        direction == FoldDirection.CLOSING -> FoldPhase.CLOSING_INNER
        else -> FoldPhase.OPENING_INNER
    }
}

/** Physical mode area, not logical display id, distinguishes the two panels.
 * Logical id 0 is reused by HyperOS when it hands Home from one panel to the other. */
internal fun panelFromPhysicalAreas(activeAreas: List<Long>, allAreas: List<Long>): FoldPanel {
    val known = allAreas.filter { it > 0L }.distinct().sorted()
    val active = activeAreas.filter { it > 0L }.distinct()
    if (known.size < 2 || active.size != 1) return FoldPanel.UNKNOWN
    return when (active.single()) {
        known.first() -> FoldPanel.COVER
        known.last() -> FoldPanel.INNER
        else -> FoldPanel.UNKNOWN
    }
}

internal class FoldTransitionState(
    val reveal: State<Float>,
    val coverFold: State<Float>,
    val arrival: State<Float>,
    val phase: State<FoldPhase>,
)

/** Process-local bridge across Xiaomi's cover/inner Home handoff. The listener remains
 * active only for a short grace window between visible Launcher instances. */
private object FoldSignalCoordinator : SensorEventListener, DisplayManager.DisplayListener {
    private const val TAG = "DuoFoldTransition"
    private const val HANDOFF_GRACE_MS = 3_500L
    private val handler = Handler(Looper.getMainLooper())
    val signal = mutableStateOf(FoldSignal())

    private var sensorManager: SensorManager? = null
    private var displayManager: DisplayManager? = null
    private var hingeSensor: Sensor? = null
    private var physicalFoldSensor: Sensor? = null
    private var directContactSensor: Sensor? = null
    private var contactPose = GlassFoldPose(false)
    private val hasContactProfile = Build.DEVICE == "lhasa"
    private var sensorSessionStartedNs = 0L
    private val contactExpiry = Runnable {
        contactPose = contactPose.expireDirectContact(SystemClock.elapsedRealtimeNanos())
        publishContactGate()
    }
    private var clients = 0
    private var registered = false
    private var internalDisplayName: String? = null
    private var stopRunnable: Runnable? = null

    fun acquire(context: Context) {
        ensure(context.applicationContext)
        stopRunnable?.let(handler::removeCallbacks)
        stopRunnable = null
        clients++
        if (!registered) start()
    }

    fun release() {
        clients = (clients - 1).coerceAtLeast(0)
        if (clients != 0 || stopRunnable != null) return
        Runnable {
            stopRunnable = null
            if (clients == 0) stop()
        }.also { task -> stopRunnable = task; handler.postDelayed(task, HANDOFF_GRACE_MS) }
    }

    fun reportWindowPanel(panel: FoldPanel, display: Display?) {
        if (display != null) {
            internalDisplayName = display.name
        }
        refreshDisplays("window", panel)
    }

    private fun ensure(context: Context) {
        if (sensorManager != null) return
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        hingeSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        if (hasContactProfile) {
            val available = sensorManager?.getSensorList(Sensor.TYPE_ALL).orEmpty()
            physicalFoldSensor = available.firstOrNull {
                it.stringType == "xiaomi.sensor.fold_status" && it.name == "fold_status FOLD_STATUS Wakeup"
            }
            directContactSensor = available.firstOrNull {
                it.stringType == "xiaomi.sensor.dighall" && it.name == "ak0991x Digital Hall Sensor Non-wakeup"
            }
        }
    }

    private fun start() {
        val sensors = sensorManager ?: return
        sensorSessionStartedNs = SystemClock.elapsedRealtimeNanos()
        // MiDuo owns the foreground desktop, unlike upstream's background overlay.
        // Read Hall locally: no shell helper, wireless debugging or extra permission.
        fun registerContact(sensor: Sensor?): Boolean = sensor?.let {
            runCatching { sensors.registerListener(this, it, 20_000, 0, handler) }.getOrDefault(false)
        } ?: false
        val foldRegistered = registerContact(physicalFoldSensor)
        val hallRegistered = registerContact(directContactSensor)
        contactPose = GlassFoldPose(foldRegistered, hasContactProfile, hallRegistered)
        publishContactGate()
        if (hasContactProfile) Log.i(TAG, "contactSensors fold=$foldRegistered hall=$hallRegistered")
        displayManager?.registerDisplayListener(this, handler)
        registered = hingeSensor?.let { sensor ->
            sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, 0, handler)
        } == true
        // Display callbacks are still useful on devices without a public hinge sensor.
        if (!registered) registered = true
        refreshDisplays("start")
    }

    private fun stop() {
        sensorManager?.unregisterListener(this)
        displayManager?.unregisterDisplayListener(this)
        registered = false
        handler.removeCallbacks(contactExpiry)
        signal.value = resetFoldTracking(signal.value)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.timestamp < sensorSessionStartedNs) return
        if (hasContactProfile && event.timestamp >= sensorSessionStartedNs) {
            when (event.sensor) {
                directContactSensor -> {
                    val next = contactPose.withDirectContactEvent(event.values, event.timestamp)
                    if (next !== contactPose) {
                        contactPose = next.expireDirectContact(SystemClock.elapsedRealtimeNanos())
                        handler.removeCallbacks(contactExpiry)
                        val ageMs = contactPose.directContactAgeMs(SystemClock.elapsedRealtimeNanos())
                        if (ageMs <= 500L) handler.postDelayed(contactExpiry, 501L - ageMs)
                        publishContactGate()
                    }
                    return
                }
                physicalFoldSensor -> {
                    contactPose = contactPose.withFoldEvent(event.values, event.timestamp)
                    publishContactGate()
                    return
                }
            }
            if (event.sensor.type == Sensor.TYPE_HINGE_ANGLE && event.values.isNotEmpty()) {
                contactPose = contactPose.withAngle(event.values[0], event.timestamp)
                publishContactGate()
            }
        }
        if (event.sensor.type != Sensor.TYPE_HINGE_ANGLE || event.values.isEmpty()) return
        val degrees = event.values[0]
        if (!degrees.isFinite() || degrees !in 0f..360f) return
        val before = signal.value
        if (event.timestamp < before.sensorTimestampNs) return
        if (before.angleDegrees != null && abs(degrees - before.angleDegrees) < .1f) return
        val direction = nextFoldDirection(before.angleDegrees, degrees, before.direction)
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val seconds = (event.timestamp - before.sensorTimestampNs) / 1_000_000_000f
        val velocity = if (before.angleDegrees != null && seconds in .002f..2f)
            (degrees - before.angleDegrees) / seconds else 0f
        val progress = hingeReveal(degrees)
        val next = before.copy(
            angleDegrees = degrees,
            sensorTimestampNs = event.timestamp,
            receivedElapsedNs = elapsed,
            velocityDegreesPerSecond = velocity,
            direction = direction,
            phase = foldPhase(before.activePanel, progress, direction),
        )
        signal.value = next
        if (next.phase != before.phase || next.direction != before.direction) {
            Log.i(TAG, "phase=${next.phase} panel=${next.activePanel} angle=$degrees " +
                "velocity=$velocity direction=$direction epoch=${next.panelEpoch}")
        }
    }

    private fun publishContactGate() {
        val blocked = hasContactProfile && contactPose.blocksProjection()
        if (signal.value.coverProjectionBlocked == blocked) return
        signal.value = signal.value.copy(coverProjectionBlocked = blocked)
        Log.i(TAG, "coverBlocked=$blocked hall=${contactPose.directContactStatus} rawAngle=${contactPose.rawAngle}")
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onDisplayAdded(displayId: Int) = refreshDisplays("added:$displayId")
    override fun onDisplayRemoved(displayId: Int) = refreshDisplays("removed:$displayId")
    override fun onDisplayChanged(displayId: Int) = refreshDisplays("changed:$displayId")

    private fun refreshDisplays(source: String, windowFallback: FoldPanel = FoldPanel.UNKNOWN) {
        val manager = displayManager
        val namedDisplays = manager?.displays?.filter { display ->
            display.isValid && internalDisplayName?.let(display.name::equals) != false
        }.orEmpty()
        val allAreas = namedDisplays.map(::modeArea)
        val activeAreas = namedDisplays.filter(::isDisplayLit).map(::modeArea)
        val physical = panelFromPhysicalAreas(activeAreas, allAreas)
        val panel = if (physical != FoldPanel.UNKNOWN) physical else windowFallback
        if (panel != FoldPanel.UNKNOWN) updatePanel(panel, source, namedDisplays)
    }

    private fun updatePanel(panel: FoldPanel, source: String, displays: List<Display>) {
        val before = signal.value
        if (panel == before.activePanel) return
        val elapsed = SystemClock.elapsedRealtimeNanos()
        val handoff = before.activePanel != FoldPanel.UNKNOWN
        val phase = when {
            !handoff -> foldPhase(panel, before.angleDegrees?.let(::hingeReveal)
                ?: if (panel == FoldPanel.INNER) 1f else 0f, before.direction)
            panel == FoldPanel.INNER -> FoldPhase.HANDOFF_TO_INNER
            else -> FoldPhase.HANDOFF_TO_COVER
        }
        val next = before.copy(
            activePanel = panel,
            previousPanel = before.activePanel,
            panelEpoch = before.panelEpoch + if (handoff) 1L else 0L,
            panelChangedElapsedNs = elapsed,
            phase = phase,
        )
        signal.value = next
        val displaySummary = displays.joinToString { display ->
            "${display.displayId}:${display.state}:${display.mode.physicalWidth}x${display.mode.physicalHeight}"
        }
        val ageMs = before.receivedElapsedNs.takeIf { it > 0L }
            ?.let { (elapsed - it) / 1_000_000L }
        Log.i(TAG, "handoff source=$source from=${before.activePanel} to=$panel " +
            "angle=${before.angleDegrees} angleAgeMs=$ageMs epoch=${next.panelEpoch} displays=[$displaySummary]")
    }

    private fun modeArea(display: Display): Long =
        display.mode.physicalWidth.toLong() * display.mode.physicalHeight.toLong()

    private fun isDisplayLit(display: Display): Boolean = when (display.state) {
        Display.STATE_ON, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND,
        Display.STATE_ON_SUSPEND -> true
        else -> false
    }
}

@Composable
private fun rememberFoldSignal(innerDisplay: Boolean): State<FoldSignal> {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val display = LocalView.current.display
    DisposableEffect(context, lifecycle) {
        var acquired = false
        fun start() {
            if (!acquired) { FoldSignalCoordinator.acquire(context); acquired = true }
        }
        fun stop() {
            if (acquired) { FoldSignalCoordinator.release(); acquired = false }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    SideEffect {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) FoldSignalCoordinator.reportWindowPanel(
            if (innerDisplay) FoldPanel.INNER else FoldPanel.COVER,
            display,
        )
    }
    return FoldSignalCoordinator.signal
}

@Composable
internal fun rememberFoldTransition(innerDisplay: Boolean, previewRequest: Int, enabled: Boolean = true): FoldTransitionState {
    if (!enabled) return rememberDisabledFoldTransition(innerDisplay)
    val signalState = rememberFoldSignal(innerDisplay)
    return rememberFoldTransitionFromSignal(innerDisplay, previewRequest, signalState)
}

@Composable
internal fun rememberDisabledFoldTransition(innerDisplay: Boolean): FoldTransitionState = remember(innerDisplay) {
    // No sensor subscription, frame follower, debug replay or handoff animation.
    // Panel orientation/layout and StandBy remain controlled independently.
    FoldTransitionState(mutableStateOf(1f), mutableStateOf(0f), mutableStateOf(1f),
        mutableStateOf(if (innerDisplay) FoldPhase.INNER_STABLE else FoldPhase.COVER_STABLE))
}

@Composable
internal fun rememberFoldTransitionFromSignal(
    innerDisplay: Boolean,
    previewRequest: Int,
    signalState: State<FoldSignal>,
): FoldTransitionState {
    val signal = signalState.value
    val panel = if (innerDisplay) FoldPanel.INNER else FoldPanel.COVER
    var trackingNowNs by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(panel, signal.receivedElapsedNs, signal.activePanel) {
        trackingNowNs = SystemClock.elapsedRealtimeNanos()
        if (panel == FoldPanel.COVER && signal.receivedElapsedNs > 0L) {
            val remaining = COVER_FOLD_IDLE_NS - (trackingNowNs - signal.receivedElapsedNs)
            if (remaining > 0L) delay((remaining + 999_999L) / 1_000_000L)
            // Advance even under a Compose test clock; never resurrect the old angle.
            trackingNowNs = maxOf(SystemClock.elapsedRealtimeNanos(),
                signal.receivedElapsedNs + COVER_FOLD_IDLE_NS)
        }
    }
    val targetProgress = rememberUpdatedState(foldTrackingProgress(signal, panel,
        maxOf(trackingNowNs, SystemClock.elapsedRealtimeNanos())))
    val sensorProgress = remember(panel) { mutableFloatStateOf(targetProgress.value) }
    LaunchedEffect(panel) {
        val follower = GlassAngleFollow()
        // Wait without scheduling frames at rest. During motion consume the latest target
        // every VSYNC rather than restarting a spring for every integer sensor reading.
        while (true) {
            snapshotFlow { targetProgress.value }.first { abs(it - sensorProgress.floatValue) > .0001f }
            var now = withFrameNanos { it / 1_000_000L }
            follower.reset(now, sensorProgress.floatValue * 174f + 1f)
            do {
                now = withFrameNanos { it / 1_000_000L }
                val degrees = follower.update(now, sensorProgress.floatValue * 174f + 1f,
                    targetProgress.value * 174f + 1f, innerDisplay)
                sensorProgress.floatValue = ((degrees - 1f) / 174f).coerceIn(0f, 1f)
            } while (abs(targetProgress.value - sensorProgress.floatValue) > .0001f)
            sensorProgress.floatValue = targetProgress.value
        }
    }

    val recentHandoff = signal.panelEpoch > 0L && signal.activePanel == panel &&
        SystemClock.elapsedRealtimeNanos() - signal.panelChangedElapsedNs < 3_500_000_000L
    val arrival = remember(panel) { Animatable(if (recentHandoff) 0f else 1f) }
    var consumedEpoch by remember(panel) {
        mutableLongStateOf(if (recentHandoff) signal.panelEpoch - 1L else signal.panelEpoch)
    }
    LaunchedEffect(panel, signal.panelEpoch, signal.activePanel) {
        if (signal.activePanel == panel && signal.panelEpoch > consumedEpoch) {
            consumedEpoch = signal.panelEpoch
            arrival.snapTo(0f)
            try {
                arrival.animateTo(1f, tween(if (innerDisplay) 460 else 280, easing = FastOutSlowInEasing))
            } finally {
                withContext(NonCancellable) { arrival.snapTo(1f) }
            }
        }
    }

    // The debug Intent now exercises whichever physical panel is visible.
    val preview = remember(panel) { Animatable(if (innerDisplay) 1f else 0f) }
    var previewing by remember(panel) { mutableStateOf(false) }
    LaunchedEffect(previewRequest, panel) {
        if (previewRequest > 0) {
            previewing = true
            try {
            preview.snapTo(if (innerDisplay) 1f else 0f)
            preview.animateTo(if (innerDisplay) .06f else .68f,
                tween(650, easing = FastOutSlowInEasing))
            delay(180)
            preview.animateTo(if (innerDisplay) 1f else 0f,
                tween(850, easing = FastOutSlowInEasing))
            } finally {
                previewing = false
            }
        }
    }
    val commonProgress = remember(panel) { derivedStateOf {
        if (previewing) preview.value else sensorProgress.floatValue
    } }
    val reveal = remember(panel) { derivedStateOf {
        if (innerDisplay) minOf(commonProgress.value, arrival.value) else 1f
    } }
    val coverFold = remember(panel) { derivedStateOf {
        // Confirmed contact clears even the arrival/follower residual immediately.
        if (innerDisplay || (!previewing && signalState.value.coverProjectionBlocked)) 0f else maxOf(
            coverFoldProgress(commonProgress.value),
            1f - arrival.value,
        )
    } }
    val phase = remember { derivedStateOf { signalState.value.phase } }
    return remember(reveal, coverFold, arrival, phase) {
        FoldTransitionState(reveal, coverFold, arrival.asState(), phase)
    }
}

@Composable
private fun rememberFoldCornerRadii(): FloatArray {
    val view = LocalView.current
    val fallback = 32f * LocalDensity.current.density
    // Insets can change when HyperOS reuses the window for the other panel.
    return intArrayOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
        RoundedCorner.POSITION_BOTTOM_LEFT, RoundedCorner.POSITION_BOTTOM_RIGHT).map { position ->
        view.rootWindowInsets?.getRoundedCorner(position)?.radius?.toFloat() ?: fallback
    }.toFloatArray()
}

/** Projects the complete left pane, including its wallpaper, apps, and widgets. */
@Composable
internal fun Modifier.foldInnerDesktop(progress: State<Float>, enabled: Boolean): Modifier {
    val corners = rememberFoldCornerRadii()
    return if (Build.VERSION.SDK_INT >= 33) glassProjection(
        amount = { if (enabled) 1f - progress.value else 0f }, inner = true, blur = true,
        corners = corners, wholeDesktop = true) else this
}

/** Android 12 fallback and isolated leaf tests; production API 33+ uses the full desktop. */
@Composable
internal fun Modifier.foldLeftPane(
    progress: State<Float>,
    isLeft: () -> Boolean = { true },
    blur: Boolean = true,
): Modifier {
    val corners = rememberFoldCornerRadii()
    if (Build.VERSION.SDK_INT >= 33) return glassProjection(
        amount = { if (isLeft()) 1f - progress.value else 0f }, inner = true, blur = blur, corners = corners)
    return graphicsLayer {
        val p = if (isLeft()) progress.value else 1f
        val amount = 1f - p.coerceIn(0f, 1f)
        clip = amount > .001f

        // Android 12/12L fallback. The tested Xiaomi path uses AGSL on API 37;
        // this preserves a functional transition on the project's minSdk 31.
        val visual = foldVisual(p)
        transformOrigin = TransformOrigin(1f, .5f)
        // Android recommends keeping the camera just beyond a large layer's
        // width. Tying it to this pane preserves the same perspective on the
        // compact screen and the half-width inner pane.
        cameraDistance = maxOf(size.width * 1.05f, 1f)
        scaleX = visual.scaleX
        rotationY = visual.rotationY
        alpha = visual.opacity
        renderEffect = if (blur && visual.blurDp > .1f) android.graphics.RenderEffect.createBlurEffect(
            visual.blurDp * density, visual.blurDp * density, android.graphics.Shader.TileMode.CLAMP,
        ).asComposeRenderEffect() else null
    }
}

/** Applied to the complete compact cover workspace. Unlike the inner motion,
 * this pivots around the left hinge and grows stronger as the device opens. */
@Composable
internal fun Modifier.foldCoverPane(
    progress: State<Float>,
    enabled: () -> Boolean,
    blur: Boolean = true,
): Modifier {
    val corners = rememberFoldCornerRadii()
    if (Build.VERSION.SDK_INT >= 33) return glassProjection(
        amount = { if (enabled()) progress.value else 0f }, inner = false, blur = blur, corners = corners)
    return graphicsLayer {
        val amount = if (enabled()) progress.value.coerceIn(0f, 1f) else 0f
        clip = amount > .001f

        val visual = foldCoverVisual(amount)
        transformOrigin = TransformOrigin(0f, .5f)
        cameraDistance = maxOf(size.width * 1.05f, 1f)
        scaleX = visual.scaleX
        rotationY = visual.rotationY
        alpha = visual.opacity
        renderEffect = if (blur && visual.blurDp > .1f) android.graphics.RenderEffect.createBlurEffect(
            visual.blurDp * density, visual.blurDp * density, android.graphics.Shader.TileMode.CLAMP,
        ).asComposeRenderEffect() else null
    }
}

/** A solid-black aperture sits above the projected content plane. The unequal
 * top/bottom insets expose a real trapezoid, while the third side stays opaque
 * black instead of being blurred, dimmed, or pushed off-screen by overscan. */
@Composable
internal fun FoldProjectionEdges(
    progress: State<Float>,
    side: FoldShadeSide,
    cover: Boolean = false,
    enabled: () -> Boolean = { true },
) {
    // AGSL produces the trapezoid and its connected black outside region in a
    // single pass. The Canvas aperture remains only for Android 12/12L.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
    Canvas(Modifier.fillMaxSize()) {
        val raw = if (enabled()) progress.value else if (cover) 0f else 1f
        val amount = if (cover) raw.coerceIn(0f, 1f) else 1f - raw.coerceIn(0f, 1f)
        val edge = minOf(size.width, size.height) * foldEdgeInsetFraction(amount)
        val perspective = minOf(size.width, size.height) * foldPerspectiveInsetFraction(amount)
        if (edge <= .5f) return@Canvas
        val depths = foldEdgeDepths(edge, perspective, side)
        val topLeft = depths.left
        val topRight = depths.right
        drawPath(Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width, topRight)
            lineTo(0f, topLeft)
            close()
        }, Color.Black)
        drawPath(Path().apply {
            moveTo(0f, size.height)
            lineTo(size.width, size.height)
            lineTo(size.width, size.height - topRight)
            lineTo(0f, size.height - topLeft)
            close()
        }, Color.Black)
        when (side) {
            FoldShadeSide.LEFT -> drawRect(Color.Black, size = Size(edge, size.height))
            FoldShadeSide.RIGHT -> drawRect(Color.Black, topLeft = Offset(size.width - edge, 0f),
                size = Size(edge, size.height))
        }
    }
}

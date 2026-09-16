package com.jake.duolauncher

import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast

/** Three narrow input windows, no screen capture, window retrieval, or event observation. */
internal class NavigationOverlay(private val service: AccessibilityService) : DisplayManager.DisplayListener {
    private val handler = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private val displays = service.getSystemService(DisplayManager::class.java)
    private val prefs = service.getSharedPreferences("navigation_gestures", 0)
    private val views = mutableListOf<EdgeView>()
    private var feedback: GestureFeedbackView? = null
    private var signature = ""
    private var dialog: AlertDialog? = null
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { refresh() }
    }
    private val monitor = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1500) }
    }

    fun start() {
        service.contentResolver.registerContentObserver(Settings.Global.getUriFor("force_fsg_nav_bar"), false, observer)
        displays.registerDisplayListener(this, handler)
        handler.post(monitor)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        service.contentResolver.unregisterContentObserver(observer)
        displays.unregisterDisplayListener(this)
        dialog?.dismiss(); dialog = null
        removeWindows()
    }

    override fun onDisplayAdded(displayId: Int) { refresh() }
    override fun onDisplayRemoved(displayId: Int) { refresh() }
    override fun onDisplayChanged(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) refresh() }

    private fun refresh() {
        val enabled = prefs.getBoolean("enabled", true) &&
            service.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME) &&
            Settings.Global.getInt(service.contentResolver, "force_fsg_nav_bar", 0) == 1 &&
            !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            displays.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON
        if (!enabled) { removeWindows(); signature = ""; return }
        val bounds = wm.currentWindowMetrics.bounds
        val density = service.resources.displayMetrics.density
        val next = "${bounds.width()}:${bounds.height()}:$density"
        if (signature == next && views.size == 3 && feedback != null) return
        removeWindows()
        fun dp(value: Int) = (value * density).toInt().coerceAtLeast(1)
        try {
            addFeedback()
            add(NavigationEdge.LEFT, dp(8), (bounds.height() * .60f).toInt(), Gravity.LEFT or Gravity.TOP,
                0, (bounds.height() * .20f).toInt())
            add(NavigationEdge.RIGHT, dp(8), (bounds.height() * .60f).toInt(), Gravity.RIGHT or Gravity.TOP,
                0, (bounds.height() * .20f).toInt())
            add(NavigationEdge.BOTTOM, (bounds.width() * .70f).toInt(), dp(18), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 0)
            signature = next
            Log.i("DuoNavigation", "overlay ready $next")
        } catch (e: RuntimeException) {
            removeWindows(); signature = ""
            Log.e("DuoNavigation", "Could not attach gesture windows", e)
        }
    }

    private fun add(edge: NavigationEdge, width: Int, height: Int, gravity: Int, x: Int, y: Int) {
        val view = EdgeView(edge)
        val lp = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            this.gravity = gravity; this.x = x; this.y = y
            title = "DuoNavigation-${edge.name}"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
        }
        wm.addView(view, lp)
        views.add(view)
    }

    private fun addFeedback() {
        val view = GestureFeedbackView()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.FILL
            title = "DuoNavigation-Feedback"
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
        }
        wm.addView(view, lp)
        feedback = view
    }

    private fun removeWindows() {
        views.forEach { it.cancel(); runCatching { wm.removeViewImmediate(it) } }
        views.clear()
        feedback?.let { runCatching { wm.removeViewImmediate(it) } }
        feedback = null
    }

    fun showRecovery() {
        if (dialog?.isShowing == true) return
        val canEnable = Settings.Global.getInt(service.contentResolver, "force_fsg_nav_bar", 0) == 1 ||
            service.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        dialog = AlertDialog.Builder(service)
            .setTitle(R.string.navigation_title)
            .setMessage(service.getString(R.string.navigation_help) + "\n\n" +
                service.getString(R.string.setup_gesture_check) + "\n\n" +
                service.getString(R.string.navigation_system_permission_help))
            .setPositiveButton(R.string.navigation_restore) { _, _ ->
                try {
                    check(Settings.Global.putInt(service.contentResolver, "force_fsg_nav_bar", 0))
                    prefs.edit().putBoolean("enabled", false).apply()
                    refresh()
                } catch (_: Exception) {
                    Toast.makeText(service, R.string.navigation_restore_settings, Toast.LENGTH_LONG).show()
                    service.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            .setNeutralButton(if (canEnable) R.string.navigation_enable else R.string.wireless_title) { _, _ ->
                enableFromSetup()
            }
            .setNegativeButton(android.R.string.cancel, null).create().also {
                it.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                it.setOnDismissListener { dialog = null }
                it.show()
            }
    }


    internal fun enableFromSetup() {
        when (activateNavigation(
            licensed = OfflineLicenseStore(service).activated,
            defaultHome = service.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME),
            gestureMode = { Settings.Global.getInt(service.contentResolver, "force_fsg_nav_bar", 0) == 1 },
            requestGestureMode = { Settings.Global.putInt(service.contentResolver, "force_fsg_nav_bar", 1) },
            enableOverlay = { prefs.edit().putBoolean("enabled", true).apply(); refresh() },
            hideGestureLine = { Settings.Global.putInt(service.contentResolver, "hide_gesture_line", 1) },
        )) {
            NavigationEnableResult.ENABLED -> Toast.makeText(service, R.string.navigation_enabled_check, Toast.LENGTH_LONG).show()
            NavigationEnableResult.LICENSE_REQUIRED -> Toast.makeText(service, R.string.miduo_activate_before_setup, Toast.LENGTH_LONG).show()
            NavigationEnableResult.DEFAULT_HOME_REQUIRED -> Toast.makeText(service, R.string.navigation_default_required, Toast.LENGTH_LONG).show()
            NavigationEnableResult.SYSTEM_MODE_REQUIRED -> {
                SystemShadeAccessibilityService.showWirelessSetup()
            }
        }
    }

    private fun perform(action: NavigationAction, view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        if (action == NavigationAction.RECOVERY) { showRecovery(); return }
        val id = when (action) {
            NavigationAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            NavigationAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            NavigationAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> return
        }
        val success = service.performGlobalAction(id)
        Log.i("DuoNavigation", "action=$action accepted=$success")
        if (!success) Toast.makeText(service, R.string.navigation_action_failed, Toast.LENGTH_SHORT).show()
    }

    private inner class EdgeView(edge: NavigationEdge) : View(service) {
        private val gesture = NavigationGesture(edge)
        private val density = resources.displayMetrics.density
        private val tick = object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                feedback?.show(gesture.progress(now))
                val action = gesture.tick(now)
                if (action != null) {
                    feedback?.release(committed = action != NavigationAction.RECOVERY)
                    perform(action, this@EdgeView)
                } else this@EdgeView.postOnAnimation(this)
            }
        }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        fun cancel() {
            gesture.cancel()
            // View.handler is null after a display handoff detaches this edge.
            // View.removeCallbacks handles both attached and queued callbacks.
            removeCallbacks(tick)
            feedback?.release(committed = false)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancel()
                    gesture.down(event.rawX / density, event.rawY / density, event.eventTime)
                    feedback?.show(gesture.progress(event.eventTime))
                    postOnAnimation(tick)
                }
                MotionEvent.ACTION_MOVE -> {
                    gesture.move(event.rawX / density, event.rawY / density, event.eventTime)
                    feedback?.show(gesture.progress(event.eventTime))
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(tick)
                    gesture.move(event.rawX / density, event.rawY / density, event.eventTime)
                    feedback?.show(gesture.progress(event.eventTime))
                    val action = gesture.up(event.eventTime)
                    feedback?.release(committed = action != null && action != NavigationAction.RECOVERY)
                    action?.let { perform(it, this) }
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancel()
            }
            return true
        }
    }

    /** Draw-only overlay. Gesture capture remains in the three narrow EdgeViews. */
    private inner class GestureFeedbackView : View(service) {
        private val density = resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val arrow = Path()
        private var progress: NavigationGestureProgress? = null

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = INVISIBLE
        }

        fun show(value: NavigationGestureProgress) {
            if (!value.active) return
            animate().cancel()
            alpha = 1f
            visibility = VISIBLE
            progress = value
            invalidate()
        }

        fun release(committed: Boolean) {
            if (progress == null) return
            animate().cancel()
            animate().alpha(0f).setDuration(if (committed) 110L else 170L).withEndAction {
                progress = null
                visibility = INVISIBLE
                alpha = 1f
            }.start()
        }

        override fun onDraw(canvas: Canvas) {
            val value = progress ?: return
            when (value.edge) {
                NavigationEdge.LEFT, NavigationEdge.RIGHT -> drawBack(canvas, value)
                NavigationEdge.BOTTOM -> drawBottom(canvas, value)
            }
        }

        private fun drawBack(canvas: Canvas, value: NavigationGestureProgress) {
            val p = smooth(value.distance)
            val direction = if (value.edge == NavigationEdge.LEFT) 1f else -1f
            val centerX = if (direction > 0f) dp(10f + 25f * p) else width - dp(10f + 25f * p)
            val centerY = (value.y * density).coerceIn(dp(28f), height - dp(28f))
            fill.color = Color.argb((70f + 48f * p).toInt(), 248, 250, 255)
            canvas.drawCircle(centerX, centerY, dp(15f + 3f * p), fill)

            stroke.color = Color.argb((170f + 85f * p).toInt(), 255, 255, 255)
            stroke.strokeWidth = dp(2.2f)
            val tipX = centerX - direction * dp(4.5f)
            arrow.reset()
            arrow.moveTo(tipX + direction * dp(6f), centerY - dp(6f))
            arrow.lineTo(tipX, centerY)
            arrow.lineTo(tipX + direction * dp(6f), centerY + dp(6f))
            canvas.drawPath(arrow, stroke)
        }

        private fun drawBottom(canvas: Canvas, value: NavigationGestureProgress) {
            val travel = smooth(value.distance)
            val hold = smooth(value.hold)
            val centerX = width / 2f
            val centerY = height - dp(13f + 20f * travel)
            val halfWidth = dp(27f + 12f * travel + 8f * hold)
            val halfHeight = dp(2f + 1.5f * hold)
            fill.color = Color.argb((145f + 90f * travel).toInt(), 255, 255, 255)
            canvas.drawRoundRect(RectF(centerX - halfWidth, centerY - halfHeight,
                centerX + halfWidth, centerY + halfHeight), halfHeight, halfHeight, fill)

            if (hold > 0f) {
                stroke.color = Color.argb((75f + 150f * hold).toInt(), 255, 255, 255)
                stroke.strokeWidth = dp(2f)
                val radius = dp(13f)
                canvas.drawArc(RectF(centerX - radius, centerY - radius,
                    centerX + radius, centerY + radius), -90f, 360f * hold, false, stroke)
            } else if (value.recovery > 0f) {
                stroke.color = Color.argb((55f + 120f * value.recovery).toInt(), 255, 255, 255)
                stroke.strokeWidth = dp(1.5f)
                val radius = dp(10f)
                canvas.drawArc(RectF(centerX - radius, centerY - radius,
                    centerX + radius, centerY + radius), -90f, 360f * value.recovery, false, stroke)
            }
        }

        private fun dp(value: Float) = value * density
        private fun smooth(value: Float): Float {
            val p = value.coerceIn(0f, 1f)
            return p * p * (3f - 2f * p)
        }
    }
}

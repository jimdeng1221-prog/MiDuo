package com.jake.duolauncher

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** User-operated setup panel. Accessibility is used ONLY to display our own UI, not read Settings. */
internal class WirelessAdbSetup(private val service: AccessibilityService, private val enable: () -> Unit) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var closed = false
    @Volatile private var client: AdbClient? = null
    @Volatile private var pairingClient: AdbPairingClient? = null
    private var key: AdbKey? = null // created only on worker; private key encrypted with Android Keystore
    private var dialog: AlertDialog? = null
    private var outcomeDialog: AlertDialog? = null
    private var bubble: TextView? = null
    private var discovery: LocalAdbDiscovery? = null
    private var pairPort: Int? = null
    @Volatile private var connectPort: Int? = null
    private var pairInput: EditText? = null
    private var connectInput: EditText? = null
    private var status: TextView? = null
    private val expiry = Runnable { close() }

    private fun str(id: Int) = service.getString(id)
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    private fun allowed() = wirelessSetupAllowed(OfflineLicenseStore(service).activated,
        service.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME),
        SystemShadeAccessibilityService.isConnected())
    private fun granted() = service.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun show() {
        if (closed || dialog?.isShowing == true) return
        if (!allowed()) {
            Toast.makeText(service, R.string.wireless_prerequisites, Toast.LENGTH_LONG).show()
            return
        }
        main.removeCallbacks(expiry); main.postDelayed(expiry, 10 * 60_000L)
        removeBubble()
        val content = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)) }
        fun text(id: Int) = TextView(service).apply {
            text = str(id); setTextColor(Color.WHITE); textSize = 13f; setPadding(0, dp(6), 0, dp(6)); content.addView(this)
        }
        text(R.string.wireless_intro)
        status = text(if (granted()) R.string.wireless_authorized else R.string.wireless_steps)
        fun input(id: Int, max: Int) = EditText(service).apply {
            hint = str(id); contentDescription = str(id); setTextColor(Color.WHITE); setHintTextColor(0xffb6bdca.toInt())
            textSize = 14f; inputType = InputType.TYPE_CLASS_NUMBER; isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(max)); isSaveEnabled = false
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_NEXT
            content.addView(this, LinearLayout.LayoutParams(-1, dp(48)))
        }
        pairInput = input(R.string.wireless_pair_port, 5).apply { pairPort?.let { setText(it.toString()) } }
        val code = input(R.string.wireless_code, 6)
        connectInput = input(R.string.wireless_connect_port, 5).apply { connectPort?.let { setText(it.toString()) } }
        fun button(id: Int, action: () -> Unit) = Button(service).apply {
            text = str(id); textSize = 13f; isAllCaps = false; setOnClickListener { action() }; content.addView(this)
        }
        val run = button(R.string.wireless_pair_authorize) {
            val pp = localAdbPort(pairInput?.text.toString())
            val cp = localAdbPort(connectInput?.text.toString())
            val secret = code.text.toString()
            if (pp == null || !validAdbPairingCode(secret)) {
                status?.setText(R.string.wireless_invalid_input)
            } else {
                code.text.clear()
                start(pp, cp, secret)
            }
        }
        val reconnect = button(R.string.wireless_connect_authorize) {
            val cp = localAdbPort(connectInput?.text.toString())
            if (cp == null) status?.setText(R.string.wireless_missing_connect) else start(null, cp, null)
        }
        button(R.string.wireless_open_settings) {
            if (busy.get()) return@button
            pairPort = localAdbPort(pairInput?.text.toString()); connectPort = localAdbPort(connectInput?.text.toString())
            code.text.clear(); dialog?.dismiss(); dialog = null
            showBubble()
            val direct = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { service.startActivity(direct) }.recoverCatching {
                service.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Toast.makeText(service, R.string.wireless_open_manually, Toast.LENGTH_LONG).show() }
        }
        button(R.string.wireless_enable) {
            if (!busy.get() && granted() && allowed()) { close(); enable() }
            else status?.setText(R.string.wireless_prerequisites)
        }
        text(R.string.wireless_privacy)
        dialog = AlertDialog.Builder(service).setTitle(R.string.wireless_title)
            .setView(ScrollView(service).apply { addView(content) })
            .setNegativeButton(R.string.close_menu) { _, _ -> close() }.create().also { d ->
                d.setCanceledOnTouchOutside(false)
                d.setOnCancelListener { close() }
                d.window?.apply {
                    setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                    addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                    setBackgroundDrawable(GradientDrawable().apply {
                        setColor(0xf2222937.toInt()); cornerRadius = dp(24).toFloat(); setStroke(dp(1), 0x55ffffff)
                    })
                }
                d.show()
                d.window?.setLayout(minOf(dp(380), wm.currentWindowMetrics.bounds.width() - dp(24)),
                    minOf(dp(600), (wm.currentWindowMetrics.bounds.height() * .78f).toInt()))
            }
        if (discovery == null) discovery = LocalAdbDiscovery(service) { pairing, port ->
            main.post {
                if (!closed) {
                    if (pairing) { pairPort = port; if (port != null && pairInput?.hasFocus() != true) pairInput?.setText(port.toString()) }
                    else { connectPort = port; if (port != null && connectInput?.hasFocus() != true) connectInput?.setText(port.toString()) }
                }
            }
        }.also { it.start() }
    }

    private fun showBubble() {
        if (closed || bubble != null) return
        val view = TextView(service).apply {
            text = str(R.string.wireless_bubble); contentDescription = text
            textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply { setColor(0xee263348.toInt()); cornerRadius = dp(24).toFloat(); setStroke(dp(1), 0x66ffffff) }
            setOnClickListener { show() }; setOnLongClickListener { close(); true }
        }
        val params = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END; x = dp(12); y = dp(96); title = "MiDuo-local-pairing"
        }
        runCatching { wm.addView(view, params); bubble = view }.onFailure { close() }
    }

    private fun start(pair: Int?, connect: Int?, code: String?) {
        if (closed || !allowed() || !busy.compareAndSet(false, true)) return
        status?.setText(R.string.wireless_working)
        val timedOut = AtomicBoolean(false)
        val timeout = Runnable { timedOut.set(true); client?.abort(); pairingClient?.abort() }
        main.postDelayed(timeout, 45_000)
        executor.execute {
            var result = R.string.wireless_failed
            try {
                if (closed || !allowed()) return@execute
                val identity = key ?: AdbKey(PreferenceAdbKeyStore(service.getSharedPreferences("miduo_local_adb", 0)), "MiDuo@localhost").also { key = it }
                if (pair != null && code != null) {
                    AdbPairingClient("127.0.0.1", pair, code, identity).use { connection ->
                        pairingClient = connection
                        check(!closed && !timedOut.get() && connection.start())
                    }
                    pairingClient = null
                    main.post { if (!closed) status?.setText(R.string.wireless_pairing_complete) }
                }
                val port = connect ?: connectPort
                if (port == null) { result = R.string.wireless_paired_need_connect; return@execute }
                check(!closed && !timedOut.get() && allowed())
                AdbClient("127.0.0.1", port, identity).use { connection ->
                    client = connection
                    check(!closed && !timedOut.get())
                    connection.connect()
                    main.post {
                        if (!closed) {
                            status?.setText(R.string.wireless_connected)
                            Toast.makeText(service, R.string.wireless_connected, Toast.LENGTH_SHORT).show()
                        }
                    }
                    check(!closed && !timedOut.get() && allowed())
                    // No files, app data, input injection or external commands are accessed.
                    connection.shellCommand(MIDUO_NAVIGATION_GRANT) { /* never record shell output */ }
                }
                client = null
                result = if (granted()) R.string.wireless_authorized else R.string.wireless_grant_denied
            } catch (_: Exception) {
                result = R.string.wireless_failed
            } catch (_: LinkageError) {
                result = R.string.wireless_native_failed
            } finally {
                runCatching { client?.close() }; client = null
                // Native pairing context is destroyed by the owning worker's use/finally only.
                pairingClient = null
                busy.set(false)
                main.removeCallbacks(timeout)
                val message = result
                main.post {
                    if (!closed) {
                        status?.setText(message)
                        showOutcome(message)
                    }
                }
            }
        }
    }

    /** A visible modal result, independent of scroll position and the keyboard. */
    private fun showOutcome(message: Int) {
        if (closed) return
        val success = message == R.string.wireless_authorized && granted()
        val paired = message == R.string.wireless_paired_need_connect
        outcomeDialog?.dismiss()
        outcomeDialog = createWirelessAdbOutcome(service, success, paired, message,
            onEnable = { if (granted() && allowed()) { close(); enable() } },
            onClose = { close() }).also { result ->
                result.setCanceledOnTouchOutside(false)
                result.window?.apply {
                    setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                    addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                }
                result.show()
                result.window?.setLayout(minOf(dp(360), wm.currentWindowMetrics.bounds.width() - dp(24)), -2)
            }
    }

    private fun removeBubble() { bubble?.let { runCatching { wm.removeViewImmediate(it) } }; bubble = null }
    override fun close() {
        if (closed) return
        closed = true; main.removeCallbacksAndMessages(null)
        discovery?.close(); discovery = null
        outcomeDialog?.dismiss(); outcomeDialog = null
        dialog?.dismiss(); dialog = null; removeBubble()
        // Closing only sockets interrupts I/O without freeing native state concurrently.
        client?.abort(); pairingClient?.abort()
        executor.shutdownNow(); pairInput = null; connectInput = null; status = null
    }
}

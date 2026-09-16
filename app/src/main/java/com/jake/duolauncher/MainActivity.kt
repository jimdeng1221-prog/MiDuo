package com.jake.duolauncher

import android.app.role.RoleManager
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter

class MainActivity : ComponentActivity() {
    internal val licensing by lazy { OfflineLicenseStore(this) }
    internal val activationRequests = mutableIntStateOf(0)

    internal fun requireFeatureLicense(): Boolean {
        if (licensing.activated) return true
        finishFirstRun()
        activationRequests.intValue++
        return false
    }
    internal lateinit var panelOrientation: PanelOrientationController
    private val model: LauncherModel by viewModels()
    private lateinit var widgets: WidgetController
    internal lateinit var backups: BackupController
        private set
    internal lateinit var backgrounds: LauncherBackgroundController
        private set
    private val homeRequests = mutableIntStateOf(0)
    private val searchRequests = mutableIntStateOf(0)
    internal val foldPreviewRequests = mutableIntStateOf(0)
    private val defaultHome = mutableStateOf(false)
    private val showFirstRun = mutableStateOf(false)
    private lateinit var setupExperience: SetupExperience
    private lateinit var status: DeviceStatusMonitor
    private lateinit var appearance: AppearanceStore
    private var appearanceLocationGeneration = 0
    private var appearancePermissionGeneration = -1
    private var appearanceLocationCancellation: CancellationSignal? = null
    private var timeReceiverRegistered = false
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { appearance.refresh(systemDark()) }
    }
    private val locationPermission = activityResultRegistry.register("duo.appearance.location", this,
        ActivityResultContracts.RequestPermission(), permissionResult@{ granted ->
        if (appearancePermissionGeneration != appearanceLocationGeneration || isDestroyed) return@permissionResult
        appearancePermissionGeneration = -1
        if (granted) requestAppearanceLocation(keepPending = true)
        else finishAppearanceLocation("Location permission wasn’t granted. Using the system theme until you set a place.")
    })
    private var shadeSetupDialog: android.app.AlertDialog? = null
    private var permissionGuideDialog: android.app.AlertDialog? = null
    private var returningFromShadeSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        panelOrientation = PanelOrientationController(this)
        panelOrientation.refresh()
        setupExperience = SetupExperience(this)
        showFirstRun.value = setupExperience.entryDecision(SetupExperience.hadLauncherState(this)) ==
            SetupEntryDecision.SHOW
        panelOrientation.setStandbyAllowed(!showFirstRun.value && licensing.activated && licensing.standbyEnabled)
        returningFromShadeSettings = savedInstanceState?.getBoolean(SHADE_SETTINGS_PENDING) == true
        val restoreShadeDialog = savedInstanceState?.getBoolean(SHADE_DIALOG_VISIBLE) == true
        appearance = AppearanceStore(this)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        widgets = WidgetController(this, model) { _ -> }.also { it.restore(savedInstanceState) }
        backups = BackupController(this, model, widgets) { _ -> }.also { it.restore() }
        backgrounds = LauncherBackgroundController(this) { _ -> }
        status = DeviceStatusMonitor(this).also { lifecycle.addObserver(it) }
        updateDefaultHome()
        if (savedInstanceState == null && intent.getStringExtra("duo_destination") == "search") searchRequests.intValue++
        intent.removeExtra("duo_destination")
        consumeFoldPreview(intent)
        setContent {
            val standbyAllowed = !showFirstRun.value && licensing.activated && licensing.standbyEnabled
            androidx.compose.runtime.SideEffect { panelOrientation.setStandbyAllowed(standbyAllowed) }
            val state = model.state.collectAsStateWithLifecycle().value
            val deviceStatus = status.state.collectAsStateWithLifecycle().value
            DuoTheme(systemDark()) {
                val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                val panel = panelOrientation.panel.value
                when (launcherSurface(panel, landscape, panelOrientation.standbyReady.value, panelOrientation.standbyDirectionReady.value)) {
                LauncherSurface.STANDBY -> StandbyScreen(onExit = panelOrientation::dismissStandby)
                // A neutral wallpaper frame while Android commits portrait/landscape.
                // Neither the inner landscape Home nor a portrait StandBy is drawn here.
                LauncherSurface.ROTATING -> DuneWallpaper()
                LauncherSurface.HOME -> {
                LauncherScreen(state, model, widgets, homeRequests.intValue,
                    onLaunch = { launchApp(it) }, onMakeDefault = ::makeDefault, onAppInfo = ::appInfo,
                    isDefaultHome = defaultHome.value, deviceStatus = deviceStatus, onStatusMode = ::setStatusMode, onWallpaperPreview = ::previewWallpaper,
                    searchRequests = searchRequests.intValue,
                    onLaunchFrom = ::launchApp, onGoogleSearch = ::openGoogleSearch,
                    appearance = appearance.state,
                    onAppearanceMode = { cancelAppearanceLocation(); appearance.setMode(it, systemDark()) },
                    onAppearanceManual = { place, lat, lon -> cancelAppearanceLocation(); appearance.setManual(place, lat, lon, systemDark()) },
                    onAppearanceDeviceLocation = ::useAppearanceLocation,
                    onAppearanceClear = { cancelAppearanceLocation(); appearance.clearLocation(systemDark()) },
                    showFirstRun = showFirstRun.value,
                    onFinishFirstRun = ::finishFirstRun,
                    onShadeSetup = ::showShadeSetup)
                }
                }
            }
        }
        FoldRenderExperiment.attach(this)
        if (restoreShadeDialog) window.decorView.post { if (!isFinishing && !isDestroyed) showShadeSetup() }
    }

    override fun onStart() {
        super.onStart(); widgets.host.startListening()
        panelOrientation.start()
        if (!timeReceiverRegistered) {
            ContextCompat.registerReceiver(this, timeReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_DATE_CHANGED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            timeReceiverRegistered = true
        }
        appearance.refresh(systemDark())
    }
    override fun onStop() {
        panelOrientation.stop()
        if (timeReceiverRegistered) { unregisterReceiver(timeReceiver); timeReceiverRegistered = false }
        widgets.host.stopListening(); super.onStop()
    }
    override fun onDestroy() {
        shadeSetupDialog?.dismiss()
        permissionGuideDialog?.dismiss()
        cancelAppearanceLocation()
        super.onDestroy()
    }
    override fun onResume() {
        super.onResume()
        if (returningFromShadeSettings) {
            returningFromShadeSettings = false
        }
        model.refresh(); appearance.refresh(systemDark()); updateDefaultHome()
        panelOrientation.refresh()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        panelOrientation.refresh()
    }

    internal fun openSystemShade(panel: ShadePanel) {
        when (SystemShadeAccessibilityService.open(this, panel)) {
            ShadeOpenResult.OPENED -> Unit
            ShadeOpenResult.SERVICE_DISABLED -> showShadeSetup()
            ShadeOpenResult.SERVICE_STARTING -> Toast.makeText(this,
                launcherText("Shade gestures are starting. Swipe down again."), Toast.LENGTH_SHORT).show()
            ShadeOpenResult.ACTION_REJECTED -> Toast.makeText(this,
                launcherText("Android couldn’t open the system panel."), Toast.LENGTH_SHORT).show()
        }
    }

    internal fun showShadeSetup() {
        if (!requireFeatureLicense()) return
        if (shadeSetupDialog?.isShowing == true) return
        shadeSetupDialog = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.setup_accessibility)
            .setMessage(getString(R.string.shade_service_description) + "\n\n" + getString(R.string.setup_restricted_help))
            .setNeutralButton(R.string.setup_app_details) { _, _ -> openPermissionAppDetails() }
            .setNegativeButton(R.string.cancel_action, null)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                try {
                    returningFromShadeSettings = true
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: android.content.ActivityNotFoundException) {
                    returningFromShadeSettings = false
                    Toast.makeText(this, launcherText("Accessibility settings are unavailable."), Toast.LENGTH_LONG).show()
                }
            }
            .also { dialog -> dialog.setOnDismissListener {
                shadeSetupDialog = null
            } }
            .show()
    }

    private fun finishFirstRun() {
        setupExperience.finish()
        showFirstRun.value = false
        panelOrientation.setStandbyAllowed(licensing.activated && licensing.standbyEnabled)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setStatusMode(model.state.value.verticalStatus)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        widgets.save(outState)
        outState.putBoolean(SHADE_DIALOG_VISIBLE, shadeSetupDialog?.isShowing == true && !returningFromShadeSettings)
        outState.putBoolean(SHADE_SETTINGS_PENDING, returningFromShadeSettings)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        FoldRenderExperiment.onNewIntent(this, intent)
        consumeFoldPreview(intent)
        if (intent.getStringExtra("duo_destination") == "search") searchRequests.intValue++
        else if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.getStringExtra("duo_destination") == "home") homeRequests.intValue++
        intent.removeExtra("duo_destination")
    }

    private fun consumeFoldPreview(intent: Intent) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            intent.getBooleanExtra("duo_fold_preview", false)) foldPreviewRequests.intValue++
        intent.removeExtra("duo_fold_preview")
    }

    @Deprecated("Widget configuration uses the platform host request-code API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!widgets.onActivityResult(requestCode, resultCode)) super.onActivityResult(requestCode, resultCode, data)
    }

    private fun launchApp(app: AppEntry, bounds: android.graphics.Rect? = null) {
        try {
            val user = getSystemService(UserManager::class.java).getUserForSerialNumber(app.userSerial)
                ?: throw IllegalStateException("Profile is unavailable")
            getSystemService(LauncherApps::class.java).startMainActivity(app.component, user, screenBounds(bounds), launchOptions(bounds))
        } catch (_: Exception) { Toast.makeText(this, getString(R.string.app_unavailable, app.label), Toast.LENGTH_SHORT).show(); model.refresh() }
    }

    private fun screenBounds(bounds: android.graphics.Rect?): android.graphics.Rect? = bounds?.takeUnless { it.isEmpty }?.let {
        val location = IntArray(2); window.decorView.getLocationOnScreen(location)
        android.graphics.Rect(it).apply { offset(location[0], location[1]) }
    }
    private fun launchOptions(bounds: android.graphics.Rect?): Bundle? = bounds?.takeUnless { it.isEmpty }?.let {
        android.app.ActivityOptions.makeScaleUpAnimation(window.decorView, it.left, it.top, it.width(), it.height()).toBundle()
    }
    private fun openGoogleSearch(bounds: android.graphics.Rect?): Boolean {
        for (candidate in systemSearchIntents(this)) {
            try {
                startActivity(candidate.apply { sourceBounds = screenBounds(bounds) }, launchOptions(bounds))
                return true
            } catch (_: android.content.ActivityNotFoundException) { }
              catch (_: SecurityException) { }
        }
        return false
    }

    internal fun showRestoreSystemHome() {
        // Never expose HOME settings as an unlicensed way to choose MiDuo.
        // Recheck the actual system role, not a potentially stale Compose snapshot.
        if (!getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)) {
            updateDefaultHome()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.restore_system_home).setMessage(R.string.restore_home_confirm)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                // The user chooses Home in system UI. Never disable or clear either launcher.
                openSafeSystemSettings(Settings.ACTION_HOME_SETTINGS)
            }
            .setNeutralButton(R.string.navigation_system) { _, _ -> openSafeSystemSettings(Settings.ACTION_SETTINGS) }
            .setNegativeButton(R.string.cancel_action, null).show()
    }

    internal fun showNavigationSetup() {
        if (!requireFeatureLicense()) return
        if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            android.provider.Settings.Global.getInt(contentResolver, "force_fsg_nav_bar", 0) != 1) {
            if (!SystemShadeAccessibilityService.showWirelessSetup()) showShadeSetup()
            return
        }
        // One menu, not an introduction followed by the same service recovery menu.
        if (!SystemShadeAccessibilityService.showNavigationRecovery()) showShadeSetup()
    }

    internal fun showPermissionGuide() {
        if (permissionGuideDialog?.isShowing == true) return
        permissionGuideDialog = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.setup_permissions_title)
            .setMessage(getString(R.string.setup_restricted_help) + "\n\n" +
                getString(R.string.setup_permissions_help) + "\n\n" + getString(R.string.navigation_system_permission_help))
            .setNeutralButton(R.string.setup_app_details) { _, _ -> openPermissionAppDetails() }
            .setPositiveButton(R.string.close_menu, null)
            .create().also { dialog ->
                dialog.setOnDismissListener { permissionGuideDialog = null }
                dialog.show()
            }
    }

    private fun openPermissionAppDetails() {
        if (!requireFeatureLicense()) return
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:$packageName"))) }
        catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.system_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun openSafeSystemSettings(action: String) {
        try { startActivity(Intent(action)) }
        catch (_: android.content.ActivityNotFoundException) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(this, R.string.system_settings_unavailable, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun makeDefault() {
        if (!requireFeatureLicense()) return
        // Samsung may immediately cancel a role request; its Home settings is reliable.
        try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        catch (_: android.content.ActivityNotFoundException) {
            val role = getSystemService(RoleManager::class.java)
            if (role.isRoleAvailable(RoleManager.ROLE_HOME)) startActivity(role.createRequestRoleIntent(RoleManager.ROLE_HOME))
            else startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    private fun updateDefaultHome() {
        defaultHome.value = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME)
    }

    private fun systemDark() = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun useAppearanceLocation() {
        cancelAppearanceLocation()
        appearance.locationStatus("Waiting for approximate device location…")
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            requestAppearanceLocation(keepPending = true)
        else {
            appearancePermissionGeneration = appearanceLocationGeneration
            runCatching { locationPermission.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION) }
                .onFailure { finishAppearanceLocation("Location permission couldn’t be requested. Using the system theme.") }
        }
    }

    private fun requestAppearanceLocation(keepPending: Boolean = false) {
        val generation = ++appearanceLocationGeneration
        val manager = getSystemService(LocationManager::class.java)
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            finishAppearanceLocation("Location permission isn’t available. Using the system theme."); return
        }
        val cached = runCatching { manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }?.takeIf { System.currentTimeMillis() - it.time <= 15 * 60_000 } }.getOrNull()
        if (cached != null) {
            if (generation == appearanceLocationGeneration) appearance.setDeviceLocation(cached.latitude, cached.longitude, systemDark())
            finishAppearanceLocation(null); return
        }
        val provider = runCatching { when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) -> LocationManager.PASSIVE_PROVIDER
            else -> null
        } }.getOrNull() ?: run { finishAppearanceLocation("No approximate location provider is available. Using the system theme."); return }
        val cancellation = CancellationSignal()
        appearanceLocationCancellation = cancellation
        window.decorView.postDelayed({
            if (generation == appearanceLocationGeneration && appearanceLocationCancellation === cancellation) {
                cancellation.cancel(); finishAppearanceLocation("Location timed out. Using the system theme until you try again or enter a place.")
            }
        }, 10_000)
        runCatching { manager.getCurrentLocation(provider, cancellation, ContextCompat.getMainExecutor(this)) { location ->
            if (generation != appearanceLocationGeneration || isDestroyed) return@getCurrentLocation
            if (location != null) appearance.setDeviceLocation(location.latitude, location.longitude, systemDark())
            finishAppearanceLocation(if (location == null) "Location is unavailable. Using the system theme." else null)
        } }.onFailure { finishAppearanceLocation("Location is unavailable. Using the system theme.") }
    }

    private fun cancelAppearanceLocation() {
        appearanceLocationGeneration++
        appearancePermissionGeneration = -1
        appearanceLocationCancellation?.cancel(); appearanceLocationCancellation = null
        if (::appearance.isInitialized) appearance.locationStatus(null)
    }

    private fun finishAppearanceLocation(message: String?) {
        appearanceLocationGeneration++
        appearancePermissionGeneration = -1
        appearanceLocationCancellation = null
        appearance.locationStatus(message)
    }

    private fun setStatusMode(vertical: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (vertical) controller.hide(WindowInsetsCompat.Type.statusBars()) else controller.show(WindowInsetsCompat.Type.statusBars())
        // Launcher-only immersive navigation: no persistent gesture pill on Home, while
        // Android can still reveal transient navigation from an intentional edge swipe.
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    private fun previewWallpaper() {
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, DuneWallpaperService::class.java)))
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.wallpaper_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun appInfo(app: AppEntry) {
        try {
            val user = getSystemService(UserManager::class.java).getUserForSerialNumber(app.userSerial)
                ?: throw IllegalStateException("Profile is unavailable")
            getSystemService(LauncherApps::class.java).startAppDetailsActivity(app.component, user, null, null)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.app_unavailable, app.label), Toast.LENGTH_SHORT).show()
            model.refresh()
        }
    }

    private companion object {
        const val SHADE_DIALOG_VISIBLE = "duo.shade.dialog_visible"
        const val SHADE_SETTINGS_PENDING = "duo.shade.settings_pending"
    }
}

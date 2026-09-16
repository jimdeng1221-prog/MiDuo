package com.jake.duolauncher

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.ComponentName
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class WidgetSetupStatus { BINDING, CONFIGURING }

class WidgetController(
    private val activity: ComponentActivity,
    private val model: LauncherModel,
    private val onExternalSetupChanged: (Boolean) -> Unit = {},
) {
    val host: android.appwidget.AppWidgetHost = ZeroPaddingWidgetHost(activity, 1024)
    val manager = AppWidgetManager.getInstance(activity)
    private val launcherApps = activity.getSystemService(LauncherApps::class.java)
    var failureMessage by mutableStateOf<String?>(null)
        private set
    var pendingPlacement by mutableStateOf<WidgetPlacement?>(null)
        private set
    var pendingProvider by mutableStateOf<ComponentName?>(null)
        private set
    var pendingProfile by mutableStateOf<UserHandle?>(null)
        private set
    var setupStatus by mutableStateOf<WidgetSetupStatus?>(null)
        private set
    var reconfigureWidgetId by mutableStateOf<Int?>(null)
        private set
    private var pendingId = -1
    private var pendingOriginal: WidgetPlacement? = null
    private var pendingOptions: Bundle? = null
    private val pendingStore = activity.getSharedPreferences("widget_pending", 0)
    private val reconfigureStore = activity.getSharedPreferences("widget_reconfigure_pending", 0)
    private val userManager = activity.getSystemService(UserManager::class.java)
    private var observingModel = false
    private val bind = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val returnedId = result.data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId) ?: pendingId
        if (result.resultCode == Activity.RESULT_OK && returnedId == pendingId) configure() else {
            if (result.resultCode == Activity.RESULT_OK) failureMessage = launcherText("The widget host returned an unexpected binding. Try again.")
            cancel()
        }
    }

    fun restore(bundle: Bundle?) {
        val saved = bundle?.takeIf { it.containsKey(PENDING_ID) } ?: storedPending()
        val restoredId = saved?.getInt(PENDING_ID, -1) ?: -1
        val restoredPlacement = saved?.let { readPlacement(it, PENDING_PLACEMENT) }
        val hasOriginal = saved?.containsKey(PENDING_ORIGINAL) == true
        val restoredOriginal = saved?.let { readPlacement(it, PENDING_ORIGINAL) }
        val restoredProvider = saved?.getString(PENDING_PROVIDER)?.let(ComponentName::unflattenFromString)
            ?: restoredId.takeIf { it >= 0 }?.let(manager::getAppWidgetInfo)?.provider
        val restoredProfileSerial = saved?.getLong(PENDING_PROFILE_SERIAL, -1L) ?: -1L
        val restoredProfile = restoredProfileSerial.takeIf { it >= 0L }?.let(userManager::getUserForSerialNumber)
            ?: restoredId.takeIf { it >= 0 }?.let(manager::getAppWidgetInfo)?.profile
        val restoredStatus = saved?.getString(PENDING_STATUS)?.let { runCatching { WidgetSetupStatus.valueOf(it) }.getOrNull() }
        val restoredOptions = saved?.getBundle(PENDING_OPTIONS)
        val alreadyCommitted = restoredPlacement?.let { model.placement(it.slot) == it } == true
        val validPending = restoredId >= 0 && restoredPlacement?.id == restoredId &&
            restoredId in host.appWidgetIds && restoredProvider != null && restoredProfile != null &&
            restoredStatus != null && (!hasOriginal || restoredOriginal != null)
        if (alreadyCommitted) {
            // The process may stop after Home is persisted but before the durable
            // transaction is cleared. The retained binding is already complete.
            clearPending()
        } else if (validPending) {
            pendingId = restoredId
            pendingPlacement = restoredPlacement
            pendingOriginal = restoredOriginal
            pendingProvider = restoredProvider
            pendingProfile = restoredProfile
            setupStatus = restoredStatus
            pendingOptions = restoredOptions
            persistPending()
            onExternalSetupChanged(true)
        } else {
            // A malformed bundle may point at a widget already owned by Home or Undo.
            // Clear the transaction, but only delete an ID known to be unretained.
            if (restoredId >= 0 && restoredId !in model.retainedWidgetIds) host.deleteAppWidgetId(restoredId)
            clearPending()
        }
        reconcileHostIds()
        if (!observingModel) {
            observingModel = true
            activity.lifecycleScope.launch { model.state.collectLatest { reconcileHostIds() } }
        }
        restoreReconfigure(bundle)
    }

    fun save(bundle: Bundle) {
        bundle.putInt(PENDING_ID, pendingId)
        pendingPlacement?.let { writePlacement(bundle, PENDING_PLACEMENT, it) }
        pendingOriginal?.let { writePlacement(bundle, PENDING_ORIGINAL, it) }
        pendingProvider?.let { bundle.putString(PENDING_PROVIDER, it.flattenToString()) }
        pendingProfile?.let { profile -> bundle.putLong(PENDING_PROFILE_SERIAL, userManager.getSerialNumberForUser(profile)) }
        setupStatus?.let { bundle.putString(PENDING_STATUS, it.name) }
        pendingOptions?.let { bundle.putBundle(PENDING_OPTIONS, it) }
        reconfigureWidgetId?.let { bundle.putInt(RECONFIGURE_ID, it) }
    }

    fun clearFailure() { failureMessage = null }
    fun label(id: Int): String = manager.getAppWidgetInfo(id)?.loadLabel(activity.packageManager) ?: "Widget"
    fun providers(profile: UserHandle): List<AppWidgetProviderInfo> =
        manager.getInstalledProvidersForProfile(profile)
    fun personalProviders(): List<AppWidgetProviderInfo> = providers(Process.myUserHandle())
    internal fun defaultProvider(id: Int): AppWidgetProviderInfo? {
        val candidates = DEFAULT_NATIVE_PROVIDERS[id] ?: return null
        val providers = personalProviders()
        return candidates.firstNotNullOfOrNull { component ->
            providers.firstOrNull { it.provider.flattenToString() == component }
        }
    }

    /** No prompts during composition: unresolved providers remain interactive setup cards. */
    internal fun bindDefaultWidget(slot: Int, size: WidgetContentSize, interactive: Boolean = false): Boolean {
        val placement = model.placement(slot) ?: return false
        val provider = defaultProvider(placement.id) ?: return false
        if (pendingPlacement != null || reconfigureWidgetId != null) return true
        if (interactive) { add(placement, provider, contentSize = size); return true }
        val optional = provider.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0 &&
            provider.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0
        if (provider.configure != null && !optional) return true
        val id = host.allocateAppWidgetId()
        try {
            val bound = manager.bindAppWidgetIdIfAllowed(id, provider.profile, provider.provider,
                exactWidgetSizeOptions(size.widthDp, size.heightDp))
            if (!bound || !model.completeDefaultWidget(placement, id)) host.deleteAppWidgetId(id)
        } catch (_: Exception) { host.deleteAppWidgetId(id) }
        return true
    }
    fun providersForPackage(packageName: String, profile: UserHandle = Process.myUserHandle()): List<AppWidgetProviderInfo> =
        manager.getInstalledProvidersForPackage(packageName, profile)

    fun canReconfigure(id: Int): Boolean {
        val info = manager.getAppWidgetInfo(id) ?: return false
        return id >= 0 && model.state.value.widgetPlacements.any { it.id == id } && info.configure != null &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0
    }

    /** Opens configuration for an existing binding without changing its ID or Home layout. */
    fun reconfigure(id: Int): Boolean {
        if (pendingPlacement != null || reconfigureWidgetId != null || !canReconfigure(id)) return false
        reconfigureWidgetId = id
        reconfigureStore.edit().putInt(RECONFIGURE_ID, id).apply()
        return launchReconfigure()
    }

    fun finishPendingReconfigure(): Boolean = reconfigureWidgetId?.let { launchReconfigure() } ?: false
    fun cancelPendingReconfigure() = clearReconfigure()

    fun restoreDescriptor(slot: Int) = model.state.value.layout.widgetRestore(slot)

    /** Starts exact-provider rebinding for an imported placeholder; never substitutes another profile. */
    fun rebindRestoredWidget(slot: Int, grid: WidgetGridSizing? = null,
        contentSize: WidgetContentSize? = null): Boolean {
        val placement = model.placement(slot)?.takeIf { it.id == NEEDS_BINDING_WIDGET } ?: return false
        val restore = restoreDescriptor(slot) ?: return false
        if (restore.isWork && restore.sourceScope != layoutBackupScope(activity)) return false
        val profile = if (restore.isWork) userManager.getUserForSerialNumber(restore.userSerial)
            else Process.myUserHandle()
        if (profile == null) return false
        if (restore.isWork && (profile == Process.myUserHandle() || profile !in launcherApps.profiles ||
                !isSupportedWorkProfile(launcherApps, profile))) return false
        val component = ComponentName.unflattenFromString(restore.providerComponent) ?: return false
        val provider = runCatching { manager.getInstalledProvidersForProfile(profile) }
            .getOrNull()?.firstOrNull { it.provider == component } ?: return false
        add(placement, provider, grid, contentSize)
        return true
    }

    fun sizing(provider: AppWidgetProviderInfo, grid: WidgetGridSizing): WidgetSpanConstraints? {
        val density = activity.resources.displayMetrics.density
        fun dp(pixels: Int) = pixels / density
        return widgetSpanConstraints(WidgetProviderSizing(
            minWidthDp = dp(provider.minWidth), minHeightDp = dp(provider.minHeight),
            minResizeWidthDp = dp(provider.minResizeWidth), minResizeHeightDp = dp(provider.minResizeHeight),
            maxResizeWidthDp = dp(provider.maxResizeWidth), maxResizeHeightDp = dp(provider.maxResizeHeight),
            targetCellWidth = provider.targetCellWidth, targetCellHeight = provider.targetCellHeight,
            horizontalPaddingDp = 0f,
            verticalPaddingDp = 0f,
            resizeMode = provider.resizeMode,
        ), grid)
    }

    fun setBuiltin(placement: WidgetPlacement) {
        failureMessage = null
        if (model.placement(placement.slot) == placement) return
        if (!model.placeWidget(placement)) failureMessage = NO_ROOM
    }

    fun setBuiltin(slot: Int, id: Int) = setBuiltin(model.placement(slot)?.copy(id = id)
        ?: legacyPlacement(slot, id))

    fun add(placement: WidgetPlacement, provider: AppWidgetProviderInfo, grid: WidgetGridSizing? = null,
        contentSize: WidgetContentSize? = null) {
        if (reconfigureWidgetId != null) {
            failureMessage = launcherText("Finish or cancel the open widget settings first.")
            return
        }
        cancel()
        failureMessage = null
        pendingId = host.allocateAppWidgetId()
        pendingPlacement = placement.copy(id = pendingId)
        pendingOriginal = model.placement(placement.slot)
        pendingProvider = provider.provider
        pendingProfile = provider.profile
        setupStatus = WidgetSetupStatus.BINDING
        pendingOptions = contentSize?.let { exactWidgetSizeOptions(it.widthDp, it.heightDp) }
            ?: grid?.let { initialSizeOptions(placement, it) }
        persistPending()
        try {
            if (manager.bindAppWidgetIdIfAllowed(pendingId, provider.profile, provider.provider, pendingOptions)) configure()
            else {
                onExternalSetupChanged(true)
                bind.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.profile)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, pendingOptions))
            }
        } catch (_: Exception) { fail() }
    }

    fun add(slot: Int, provider: AppWidgetProviderInfo) = add(model.placement(slot)
        ?: legacyPlacement(slot, EMPTY_WIDGET), provider)

    private fun configure() {
        if (pendingId < 0) return
        val info = manager.getAppWidgetInfo(pendingId) ?: return fail()
        val optionalConfiguration = info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0 &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0
        if (info.configure == null || optionalConfiguration) complete()
        else try {
            setupStatus = WidgetSetupStatus.CONFIGURING
            persistPending()
            onExternalSetupChanged(true)
            host.startAppWidgetConfigureActivityForResult(activity, pendingId, 0, CONFIGURE, null)
        }
        catch (_: Exception) { fail() }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode == RECONFIGURE) {
            clearReconfigure()
            return true
        }
        if (requestCode != CONFIGURE) return false
        if (resultCode == Activity.RESULT_OK) complete() else cancel()
        return true
    }

    private fun complete() {
        val placement = pendingPlacement ?: return cancel()
        val originalStillPresent = model.placement(placement.slot) == pendingOriginal
        val committed = pendingId >= 0 && originalStillPresent &&
            if (pendingOriginal?.id in DEFAULT_NATIVE_PROVIDERS)
                model.completeDefaultWidget(pendingOriginal!!, pendingId) else model.placeWidget(placement)
        if (!committed) {
            failureMessage = if (!originalStillPresent) CHANGED else NO_ROOM
            cancel()
            return
        }
        clearPending()
        reconcileHostIds()
    }

    fun remove(slot: Int) {
        model.removePlacement(DropTarget.Widget(slot))
        reconcileHostIds()
    }

    private fun reconcileHostIds() {
        if (!model.canPruneWidgetIds) return
        val retained = model.retainedWidgetIds + pendingId + listOfNotNull(reconfigureWidgetId)
        host.appWidgetIds.filter { it !in retained }.forEach(host::deleteAppWidgetId)
    }

    private fun cancel() {
        if (pendingId >= 0 && pendingId !in model.retainedWidgetIds) host.deleteAppWidgetId(pendingId)
        clearPending()
    }

    fun finishPendingSetup() {
        if (pendingId < 0) return
        val info = manager.getAppWidgetInfo(pendingId)
        if (info != null) configure()
        else {
            val provider = pendingProvider ?: return fail()
            val profile = pendingProfile ?: return fail()
            setupStatus = WidgetSetupStatus.BINDING
            persistPending()
            onExternalSetupChanged(true)
            try {
                bind.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, profile)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, pendingOptions))
            } catch (_: Exception) { fail() }
        }
    }

    fun cancelPendingSetup() = cancel()

    private fun clearPending() {
        pendingId = -1
        pendingPlacement = null
        pendingOriginal = null
        pendingProvider = null
        pendingProfile = null
        setupStatus = null
        pendingOptions = null
        pendingStore.edit().clear().apply()
        onExternalSetupChanged(false)
    }

    private fun fail() {
        failureMessage = launcherText("This widget could not be added. Try another widget.")
        cancel()
    }

    private fun restoreReconfigure(bundle: Bundle?) {
        val id = bundle?.takeIf { it.containsKey(RECONFIGURE_ID) }?.getInt(RECONFIGURE_ID)
            ?: (reconfigureStore.all[RECONFIGURE_ID] as? Number)?.toInt()
        if (id != null && pendingPlacement == null && id in host.appWidgetIds && canReconfigure(id)) {
            reconfigureWidgetId = id
            reconfigureStore.edit().putInt(RECONFIGURE_ID, id).apply()
            onExternalSetupChanged(true)
        } else clearReconfigure(notify = pendingPlacement == null)
    }

    private fun launchReconfigure(): Boolean {
        val id = reconfigureWidgetId ?: return false
        if (!canReconfigure(id)) {
            failureMessage = launcherText("This widget can no longer be configured.")
            clearReconfigure()
            return false
        }
        onExternalSetupChanged(true)
        return try {
            host.startAppWidgetConfigureActivityForResult(activity, id, 0, RECONFIGURE, null)
            true
        } catch (_: Exception) {
            failureMessage = launcherText("This widget could not open its settings.")
            clearReconfigure()
            false
        }
    }

    private fun clearReconfigure(notify: Boolean = true) {
        reconfigureWidgetId = null
        reconfigureStore.edit().clear().apply()
        if (notify) onExternalSetupChanged(false)
    }

    private fun writePlacement(bundle: Bundle, key: String, value: WidgetPlacement) {
        bundle.putIntArray(key, intArrayOf(value.slot, value.id, value.page, value.column, value.row, value.spanX, value.spanY))
    }

    private fun readPlacement(bundle: Bundle, key: String): WidgetPlacement? {
        val v = bundle.getIntArray(key) ?: return null
        if (v.size != 7) return null
        return WidgetPlacement(v[0], v[1], v[2], v[3], v[4], v[5], v[6])
    }

    private fun persistPending() {
        val placement = pendingPlacement ?: return
        val provider = pendingProvider ?: return
        val profile = pendingProfile ?: return
        val status = setupStatus ?: return
        val editor = pendingStore.edit().clear().putInt(PENDING_ID, pendingId)
            .putString(PENDING_PLACEMENT, encodePlacement(placement))
            .putString(PENDING_PROVIDER, provider.flattenToString())
            .putLong(PENDING_PROFILE_SERIAL, userManager.getSerialNumberForUser(profile))
            .putString(PENDING_STATUS, status.name)
        pendingOptions?.let { options ->
            editor.putInt(PENDING_WIDTH, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH))
                .putInt(PENDING_HEIGHT, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT))
        }
        pendingOriginal?.let { editor.putString(PENDING_ORIGINAL, encodePlacement(it)) }
        editor.apply()
    }

    private fun storedPending(): Bundle? = runCatching {
        if (!pendingStore.contains(PENDING_ID)) null else Bundle().apply {
            putInt(PENDING_ID, pendingStore.getInt(PENDING_ID, -1))
            pendingStore.getString(PENDING_PLACEMENT, null)?.let { decodePlacement(it)?.let { value -> writePlacement(this, PENDING_PLACEMENT, value) } }
            pendingStore.getString(PENDING_ORIGINAL, null)?.let { value ->
                // Preserve the key even if corrupt so restore rejects the transaction.
                putIntArray(PENDING_ORIGINAL, decodePlacement(value)?.let(::placementArray) ?: intArrayOf())
            }
            putString(PENDING_PROVIDER, pendingStore.getString(PENDING_PROVIDER, null))
            putLong(PENDING_PROFILE_SERIAL, pendingStore.getLong(PENDING_PROFILE_SERIAL, -1L))
            putString(PENDING_STATUS, pendingStore.getString(PENDING_STATUS, null))
            if (pendingStore.contains(PENDING_WIDTH) && pendingStore.contains(PENDING_HEIGHT)) {
                putBundle(PENDING_OPTIONS, sizeOptions(pendingStore.getInt(PENDING_WIDTH, 1), pendingStore.getInt(PENDING_HEIGHT, 1)))
            }
        }
    }.getOrElse {
        // Preserve a recoverable numeric ID so restore can delete only an orphan;
        // every other corrupt field is deliberately treated as invalid.
        (pendingStore.all[PENDING_ID] as? Number)?.toInt()?.let { id -> Bundle().apply { putInt(PENDING_ID, id) } }
    }

    private fun initialSizeOptions(placement: WidgetPlacement, grid: WidgetGridSizing): Bundle {
        val size = grid.contentSize(placement.column, placement.row, placement.spanX, placement.spanY)
        return exactWidgetSizeOptions(size.widthDp, size.heightDp)
    }

    private fun sizeOptions(width: Int, height: Int) = exactWidgetSizeOptions(width.toFloat(), height.toFloat())

    private fun encodePlacement(value: WidgetPlacement) = placementArray(value).joinToString(",")
    private fun decodePlacement(value: String): WidgetPlacement? = runCatching {
        val v = value.split(',').map(String::toInt)
        if (v.size != 7) null else WidgetPlacement(v[0], v[1], v[2], v[3], v[4], v[5], v[6])
    }.getOrNull()
    private fun placementArray(value: WidgetPlacement) =
        intArrayOf(value.slot, value.id, value.page, value.column, value.row, value.spanX, value.spanY)

    private fun legacyPlacement(slot: Int, id: Int): WidgetPlacement = if (slot % 3 == 2)
        WidgetPlacement(slot, id, if (slot == 2) -1 else slot / 3, 0, if (slot == 2) 0 else GRID_ROWS, 4, if (slot == 2) 6 else 4)
    else WidgetPlacement(slot, id, slot / 3, (slot % 3) * 2, 0, 2, 2)

    companion object {
        private const val CONFIGURE = 701
        private const val RECONFIGURE = 702
        private const val RECONFIGURE_ID = "reconfigureWidget"
        private const val PENDING_ID = "pendingWidget"
        private const val PENDING_PLACEMENT = "pendingWidgetPlacement"
        private const val PENDING_ORIGINAL = "pendingWidgetOriginal"
        private const val PENDING_PROVIDER = "pendingWidgetProvider"
        private const val PENDING_PROFILE_SERIAL = "pendingWidgetProfileSerial"
        private const val PENDING_STATUS = "pendingWidgetStatus"
        private const val PENDING_OPTIONS = "pendingWidgetOptions"
        private const val PENDING_WIDTH = "pendingWidgetWidth"
        private const val PENDING_HEIGHT = "pendingWidgetHeight"
        private val NO_ROOM get() = launcherText("There isn't room for this widget here.")
        private val CHANGED get() = launcherText("Home changed while the widget was being configured. Choose a space again.")
    }
}

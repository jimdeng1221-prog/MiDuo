package com.jake.duolauncher

import android.app.Application
import android.content.ComponentName
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator

private const val LEADING_PAGE_INITIALIZED = "leading_page_initialized"

data class AppEntry(
    val id: String,
    val label: String,
    val icon: Bitmap,
    val component: ComponentName = ComponentName.unflattenFromString(parseProfileAppId(id)?.component ?: id)
        ?: ComponentName("", ""),
    val user: UserHandle = Process.myUserHandle(),
    val userSerial: Long = 0,
    val profileLabel: String = "Personal",
    val isWork: Boolean = false,
    val available: Boolean = true,
) {
    val packageName: String get() = component.packageName
}

data class LauncherState(
    val apps: List<AppEntry> = emptyList(),
    val profiles: List<AppProfile> = emptyList(),
    val folders: List<FolderEntry> = emptyList(),
    val homeSlots: List<String?> = emptyList(),
    val leadingSlots: List<String?> = List(HOME_CELLS) { null },
    val editRevision: Int = 0,
    val canUndoEdit: Boolean = false,
    val dock: List<String?> = List(4) { null },
    val widgetPlacements: List<WidgetPlacement> = DEFAULT_WIDGET_PLACEMENTS,
    val widgetRestores: List<WidgetRestore> = emptyList(),
    val googleSearch: Boolean = true,
    val compact: LayoutPreset = LayoutPreset(),
    val expanded: LayoutPreset = LayoutPreset(),
    val labels: Boolean = true,
    val verticalStatus: Boolean = true,
    val loading: Boolean = true,
    val error: String? = null,
) {
    val sharedPreset: LayoutPreset get() = compact
    val order: List<String> get() = homeSlots.filterNotNull()
    val widgets: List<Int> get() = layout.widgets
    val layout: HomeLayout get() = HomeLayout(homeSlots, dock, widgetPlacements, folders, widgetRestores, leadingSlots)
    val homePages get() = layout.pageCount
}

class LauncherModel(application: Application) : AndroidViewModel(application) {
    private data class RefreshedApps(val entries: List<AppEntry>, val profiles: List<AppProfile>,
        val authoritativeProfiles: Set<Long>, val removedProfiles: Set<Long>)
    private data class UndoImportSettings(val sharedPreset: LayoutPreset, val labels: Boolean,
        val googleSearch: Boolean, val verticalStatus: Boolean)
    private val prefs = application.getSharedPreferences("launcher", 0)
    private val launcherApps = application.getSystemService(LauncherApps::class.java)
    private val userManager = application.getSystemService(UserManager::class.java)
    private val appCatalogPrefs = application.getSharedPreferences("app_catalog", 0)
    private val legacyRaw = prefs.getString("state", null)
    private val sourceSchema = runCatching { JSONObject(legacyRaw ?: "{}").optInt("schema", 1) }.getOrDefault(1)
    private var needsMigration = sourceSchema < 2
    private var statePayloadInvalid = false
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()
    private var undoLayout: Pair<HomeLayout, HomeLayout>? = null
    private var undoImportSettings: UndoImportSettings? = null
    private var refreshing = false
    private var refreshPending = false
    private val invalidatedPackages = mutableSetOf<Pair<Long, String>>()
    private val removedPackages = mutableSetOf<Pair<Long, String>>()
    private val unavailablePackages = mutableSetOf<Pair<Long, String>>()
    // Accessed only in the serialized IO refresh. Returning Home reuses existing bitmaps.
    private val iconCache = mutableMapOf<String, AppEntry>()
    private var iconConfiguration = ""
    internal var completedRefreshes = 0
        private set
    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = refresh(packageName, user)
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            removedPackages += userManager.getSerialNumberForUser(user) to packageName
            refresh(packageName, user)
        }
        override fun onPackageChanged(packageName: String, user: UserHandle) = refresh(packageName, user)
        override fun onPackagesAvailable(packages: Array<out String>, user: android.os.UserHandle, replacing: Boolean) {
            packages.forEach {
                val key = userManager.getSerialNumberForUser(user) to it
                invalidatedPackages += key; unavailablePackages -= key
            }; refresh()
        }
        override fun onPackagesUnavailable(packages: Array<out String>, user: android.os.UserHandle, replacing: Boolean) {
            // A package update temporarily hides activities; don't erase its pins or dock slot.
            packages.forEach {
                val key = userManager.getSerialNumberForUser(user) to it
                invalidatedPackages += key; unavailablePackages += key
            }; refresh()
        }
    }

    init { launcherApps.registerCallback(callback); refresh() }

    fun refresh(invalidatedPackage: String? = null, user: UserHandle = Process.myUserHandle()) {
        invalidatedPackage?.let { invalidatedPackages += userManager.getSerialNumberForUser(user) to it }
        if (refreshing) { refreshPending = true; return }
        refreshing = true
        val invalidated = invalidatedPackages.toSet()
        val removed = removedPackages.toSet()
        val temporarilyUnavailable = unavailablePackages.toSet()
        invalidatedPackages.clear()
        removedPackages.clear()
        val resources = getApplication<Application>().resources
        val configuration = resources.configuration.let { "${it.densityDpi}|${it.locales.toLanguageTags()}|${it.uiMode}" }
        viewModelScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) {
                    if (configuration != iconConfiguration) { iconCache.clear(); iconConfiguration = configuration }
                    iconCache.keys.removeAll { key -> parseProfileAppId(key)?.let { identity ->
                        val serial = identity.userSerial ?: userManager.getSerialNumberForUser(Process.myUserHandle())
                        serial to (ComponentName.unflattenFromString(identity.component)?.packageName ?: "") in invalidated
                    } == true }
                    val collator = Collator.getInstance()
                    val application = getApplication<Application>()
                    val personal = Process.myUserHandle()
                    val personalSerial = userManager.getSerialNumberForUser(personal)
                    val associatedSerials = userManager.userProfiles.mapTo(mutableSetOf(), userManager::getSerialNumberForUser)
                    val handles = launcherApps.profiles
                        .filter { profile ->
                            val serial = userManager.getSerialNumberForUser(profile)
                            serial in associatedSerials && (serial == personalSerial || isSupportedWorkProfile(launcherApps, profile))
                        }
                        .distinctBy(userManager::getSerialNumberForUser)
                    val profiles = handles.map { profile ->
                        val serial = userManager.getSerialNumberForUser(profile)
                        val isPersonal = serial == personalSerial
                        val quiet = !isPersonal && runCatching { userManager.isQuietModeEnabled(profile) }.getOrDefault(false)
                        val unlocked = runCatching { userManager.isUserUnlocked(profile) }.getOrDefault(isPersonal)
                        AppProfile(serial, if (isPersonal) "Personal" else "Work", isPersonal, !isPersonal,
                            quiet, unlocked, !quiet && unlocked)
                    }
                    val cachedBeforeProfiles = loadCachedApps().filterNot { entry -> entry.userSerial to entry.packageName in removed }
                    val removedProfileSerials = removedAssociatedProfileSerials(
                        cachedBeforeProfiles.filter(AppEntry::isWork).mapTo(mutableSetOf(), AppEntry::userSerial), associatedSerials)
                    val cached = cachedBeforeProfiles.filterNot { it.isWork && it.userSerial in removedProfileSerials }
                    val authoritativeProfiles = mutableSetOf<Long>()
                    authoritativeProfiles += removedProfileSerials
                    val live = handles.flatMap { profile ->
                        val serial = userManager.getSerialNumberForUser(profile)
                        val descriptor = profiles.first { it.userSerial == serial }
                        val activityList = if (descriptor.available) runCatching { launcherApps.getActivityList(null, profile) }.getOrNull() else null
                        if (activityList == null) emptyList() else activityList.also { authoritativeProfiles += serial }.mapNotNull { info ->
                            if (info.componentName.packageName == application.packageName) return@mapNotNull null
                            val component = info.componentName
                            val id = profileAppId(component.flattenToString(), serial, personalSerial)
                            val label = info.label.toString()
                            iconCache[id]?.takeIf { it.label == label && it.available } ?: run {
                                val icon = runCatching { info.getBadgedIcon(0) }.getOrElse { application.packageManager.defaultActivityIcon }
                                AppEntry(id, label, launcherIcon(icon), component, profile, serial, descriptor.label,
                                    descriptor.isWork, available = true).also { iconCache[id] = it }
                            }
                        }
                    }
                    val liveIds = live.mapTo(mutableSetOf(), AppEntry::id)
                    val profileBySerial = profiles.associateBy(AppProfile::userSerial)
                    val unavailable = cached.filter { it.id !in liveIds }.mapNotNull { cachedEntry ->
                        val profile = profileBySerial[cachedEntry.userSerial]
                        val key = cachedEntry.userSerial to cachedEntry.packageName
                        // A successful profile query is authoritative except while Android explicitly
                        // reports a package unavailable (for example during an update).
                        if (cachedEntry.userSerial in authoritativeProfiles && key !in temporarilyUnavailable) null
                        else cachedEntry.copy(user = profile?.let { p -> handles.firstOrNull { userManager.getSerialNumberForUser(it) == p.userSerial } } ?: personal,
                            profileLabel = profile?.label ?: cachedEntry.profileLabel, available = false)
                    }
                    val entries = (live + unavailable).distinctBy(AppEntry::id)
                        .sortedWith { a, b -> collator.compare(a.label, b.label) }
                    saveCachedApps(entries)
                    iconCache.keys.retainAll(entries.map { it.id }.toSet())
                    RefreshedApps(entries, (profiles + unavailable.map { AppProfile(it.userSerial, it.profileLabel, false, true,
                        quiet = true, unlocked = false, available = false) }).distinctBy(AppProfile::userSerial),
                        authoritativeProfiles.toSet(), removedProfileSerials)
                }
                mutable.update { old ->
                    val entries = apps.entries
                    val profiles = apps.profiles
                    val dock = if (!prefs.getBoolean("initialized", false)) initialDock(entries) else old.dock
                    val installed = entries.map { it.id }
                    val legacyPins = if (needsMigration) migrateHomePins(old.order, installed, suggestedPins(entries, dock))
                        else old.homeSlots
                    val pins = if (sourceSchema < 6 && needsMigration) migrateSchema5Apps(legacyPins) else legacyPins
                    val availableIds = entries.mapTo(mutableSetOf(), AppEntry::id)
                    val authoritative = apps.authoritativeProfiles
                    val removedIds = removedAppIds(old.homeSlots.filterNotNull() + old.leadingSlots.filterNotNull() +
                        old.dock.filterNotNull() + old.folders.flatMap { it.appIds }, availableIds,
                        authoritative, temporarilyUnavailable, removed, userManager.getSerialNumberForUser(Process.myUserHandle()),
                        apps.removedProfiles)
                    val validPins = pins.map { it?.takeUnless(removedIds::contains) }
                    val validDock = dock.map { it?.takeUnless(removedIds::contains) }
                    val reconciledExisting = reconcileFolders(HomeLayout(validPins, validDock, old.widgetPlacements, old.folders,
                        old.widgetRestores, old.leadingSlots), removedIds)
                    val reconciled = if (!prefs.getBoolean("initialized", false) && !statePayloadInvalid && legacyRaw == null)
                        phase22AppLayout(reconciledExisting, entries.map { Phase22AppCandidate(it.id, it.packageName, it.label, it.isWork) },
                            freshInstall = true, english = getApplication<Application>().resources.configuration.locales[0].language != "zh")
                    else reconciledExisting
                    old.copy(apps = entries, profiles = profiles, homeSlots = reconciled.slots, leadingSlots = reconciled.leadingSlots,
                        dock = reconciled.dock, folders = reconciled.folders,
                        canUndoEdit = old.canUndoEdit && old.layout == reconciled, loading = false,
                        error = if (statePayloadInvalid) old.error else null)
                }
                if (needsMigration && legacyRaw != null && !prefs.contains("state_v1_backup"))
                    prefs.edit().putString("state_v1_backup", legacyRaw).apply()
                needsMigration = false
                persist()
                completedRefreshes++
            } catch (_: Exception) {
                mutable.update { it.copy(loading = false, error = "Apps could not be loaded. Tap to retry.") }
            } finally {
                refreshing = false
                if (refreshPending) { refreshPending = false; refresh() }
            }
        }
    }

    private fun loadCachedApps(): List<AppEntry> = runCatching {
        val application = getApplication<Application>()
        val personal = Process.myUserHandle()
        val array = JSONArray(appCatalogPrefs.getString("apps", "[]"))
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            val identity = parseProfileAppId(id) ?: error("Invalid cached app identity")
            val component = ComponentName.unflattenFromString(identity.component) ?: error("Invalid cached component")
            val serial = item.getLong("serial").takeIf { it >= 0 } ?: error("Invalid cached profile")
            val user = userManager.getUserForSerialNumber(serial) ?: personal
            val isWork = item.optBoolean("work", identity.userSerial != null)
            val baseIcon = application.packageManager.defaultActivityIcon
            val icon = runCatching { application.packageManager.getUserBadgedIcon(baseIcon, user) }.getOrDefault(baseIcon)
            AppEntry(id, item.getString("label"), launcherIcon(icon), component, user, serial,
                item.optString("profile", if (isWork) "Work" else "Personal"), isWork, available = false)
        }
    }.getOrDefault(emptyList())

    private fun saveCachedApps(apps: List<AppEntry>) {
        val array = JSONArray().also { result -> apps.forEach { app -> result.put(JSONObject()
            .put("id", app.id).put("label", app.label).put("serial", app.userSerial)
            .put("profile", app.profileLabel).put("work", app.isWork)) } }
        appCatalogPrefs.edit().putString("apps", array.toString()).apply()
    }

    private fun initialDock(apps: List<AppEntry>): List<String?> {
        return freshInstallDock(apps.map { Phase22AppCandidate(it.id, it.packageName, it.label, it.isWork) })
    }

    private fun suggestedPins(apps: List<AppEntry>, dock: List<String?>): List<String> {
        val groups = listOf(
            listOf("com.samsung.android.calendar", "com.google.android.calendar"),
            listOf("com.sec.android.app.camera", "com.android.camera2", "com.google.android.GoogleCamera"),
            listOf("com.sec.android.gallery3d", "com.google.android.apps.photos"),
            listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock"),
            listOf("com.google.android.gm", "com.samsung.android.email.provider"),
            listOf("com.google.android.apps.maps"),
            listOf("com.samsung.android.app.notes", "com.google.android.keep"),
            listOf("com.sec.android.app.myfiles", "com.google.android.documentsui"),
            listOf("com.android.settings"),
            listOf("com.sec.android.app.popupcalculator", "com.google.android.calculator"),
            listOf("com.android.vending"), listOf("com.google.android.youtube"),
            listOf("com.samsung.android.app.contacts", "com.google.android.contacts"),
            listOf("com.google.android.apps.docs"), listOf("com.google.android.apps.walletnfcrel"),
            listOf("com.sec.android.app.shealth"),
        )
        return groups.mapNotNull { choices -> choices.firstNotNullOfOrNull { pkg ->
            apps.firstOrNull { !it.isWork && it.packageName == pkg && it.id !in dock }?.id
        } }.distinct()
    }

    fun setPinned(id: String, pinned: Boolean) {
        if (statePayloadInvalid) return
        if (mutable.value.folders.any { id in it.appIds }) return
        mutable.update { old ->
            val enable = pinned && old.apps.any { it.id == id }
            old.copy(homeSlots = if (enable && id in old.leadingSlots) old.homeSlots else pinHomeApp(old.homeSlots, id,
                enable, old.widgetPlacements.flatMapTo(mutableSetOf()) { it.coveredIndices() }.filterTo(mutableSetOf()) { it >= 0 }),
                leadingSlots = if (enable) old.leadingSlots else old.leadingSlots.map { it?.takeUnless(id::equals) },
                canUndoEdit = false)
        }
        persist()
    }

    fun turnOnWork(userSerial: Long): Boolean = runCatching {
        val user = userManager.getUserForSerialNumber(userSerial) ?: return false
        if (userManager.getSerialNumberForUser(Process.myUserHandle()) == userSerial || user !in launcherApps.profiles) return false
        userManager.requestQuietModeEnabled(false, user).also { refresh() }
    }.getOrDefault(false)

    fun setDock(slot: Int, id: String?) {
        if (statePayloadInvalid) return
        if (slot !in mutable.value.dock.indices) return
        if (id != null && (isReservedFolderId(id) || mutable.value.folders.any { id in it.appIds })) return
        mutable.update { old -> old.copy(canUndoEdit = false,
            homeSlots = if (id == null) old.homeSlots else old.homeSlots.map { it?.takeUnless(id::equals) }.dropLastWhile { it == null },
            leadingSlots = if (id == null) old.leadingSlots else old.leadingSlots.map { it?.takeUnless(id::equals) },
            dock = old.dock.mapIndexed { index, value ->
                when { index == slot -> id; value == id && id != null -> null; else -> value }
            }) }
        persist()
    }

    fun move(id: String, offset: Int) {
        val old = mutable.value
        val from = old.layout.indexOfShortcut(id) ?: return
        val page = homeCellPage(from)
        val target = if (page == -1) homeCellIndex(-1,
            (homeCellLocal(from) + offset).coerceIn(0, HOME_CELLS - 1))
        else (from + offset).coerceIn(0, old.homeSlots.lastIndex)
        applyDrop(id, DropTarget.Home(target))
    }

    fun applyDrop(id: String, target: DropTarget): Boolean {
        val old = mutable.value
        val folder = old.layout.folder(id)
        if (old.apps.none { it.id == id } && folder == null) return false
        if (target is DropTarget.Folder) return folder == null && addAppToFolder(target.id, id)
        if (folder != null && target !is DropTarget.Home) return false
        return commitLayout(dropApp(old.layout, id, target))
    }

    fun createFolder(firstAppId: String, secondAppId: String, targetIndex: Int, title: String = "Folder"): String? {
        val installed = mutable.value.apps.mapTo(mutableSetOf(), AppEntry::id)
        if (firstAppId !in installed || secondAppId !in installed) return null
        val id = newFolderId()
        return id.takeIf { commitLayout(com.jake.duolauncher.createFolder(mutable.value.layout, FolderEntry(id, title, emptyList()),
            firstAppId, secondAppId, targetIndex)) }
    }
    fun renameFolder(folderId: String, title: String) = commitLayout(com.jake.duolauncher.renameFolder(mutable.value.layout, folderId, title))
    fun dissolveFolder(folderId: String) = commitLayout(com.jake.duolauncher.dissolveFolder(mutable.value.layout, folderId))
    fun addAppToFolder(folderId: String, appId: String, index: Int? = null): Boolean {
        if (mutable.value.apps.none { it.id == appId }) return false
        return commitLayout(com.jake.duolauncher.addAppToFolder(mutable.value.layout, folderId, appId, index))
    }
    fun removeAppFromFolder(folderId: String, appId: String, target: DropTarget) =
        commitLayout(com.jake.duolauncher.removeAppFromFolder(mutable.value.layout, folderId, appId, target))
    fun moveFolderApp(folderId: String, appId: String, index: Int) =
        commitLayout(com.jake.duolauncher.moveFolderApp(mutable.value.layout, folderId, appId, index))
    fun folder(id: String) = mutable.value.layout.folder(id)

    fun applyImportedLayout(preview: LayoutImportPreview): Boolean {
        if (statePayloadInvalid) return false
        val old = mutable.value
        if (old.layout == preview.layout && old.sharedPreset == preview.sharedPreset &&
            old.labels == preview.labels && old.googleSearch == preview.googleSearch && old.verticalStatus == preview.verticalStatus) return false
        undoLayout = old.layout to preview.layout
        undoImportSettings = UndoImportSettings(old.sharedPreset, old.labels, old.googleSearch, old.verticalStatus)
        mutable.value = old.copy(homeSlots = preview.layout.slots, leadingSlots = preview.layout.leadingSlots, dock = preview.layout.dock,
            widgetPlacements = preview.layout.widgetPlacements, folders = preview.layout.folders,
            widgetRestores = preview.layout.widgetRestores, compact = preview.sharedPreset, expanded = preview.sharedPreset,
            labels = preview.labels, googleSearch = preview.googleSearch, verticalStatus = preview.verticalStatus,
            editRevision = old.editRevision + 1, canUndoEdit = true)
        persist()
        return true
    }

    fun moveWidget(from: Int, to: Int): Boolean {
        val target = mutable.value.layout.placement(to) ?: return false
        return moveWidgetTo(from, target.page * HOME_CELLS + target.row * GRID_COLUMNS + target.column)
    }
    fun moveWidgetTo(slot: Int, index: Int) = commitLayout(moveWidget(mutable.value.layout, slot, index))
    fun resizeWidget(slot: Int, spanX: Int, spanY: Int) = commitLayout(resizeWidget(mutable.value.layout, slot, spanX, spanY))
    fun placeWidget(placement: WidgetPlacement) = commitLayout(placeWidget(mutable.value.layout, placement))
    fun applyLeadingDashboard() = commitLayout(withLeadingDashboard(mutable.value.layout))
    fun applyPhase22AppLayout(): Boolean {
        val apps = mutable.value.apps.map { Phase22AppCandidate(it.id, it.packageName, it.label, it.isWork) }
        return commitLayout(phase22AppLayout(mutable.value.layout, apps))
    }

    fun applyRecommendedAppLayout(): Boolean {
        if (statePayloadInvalid || !OfflineLicenseStore(getApplication()).activated) return false
        val old = mutable.value
        val apps = old.apps.filter { it.available }.map { Phase22AppCandidate(it.id, it.packageName, it.label, it.isWork) }
        if (apps.isEmpty()) return false
        val leadingIds = old.leadingSlots.filterNotNull().toSet()
        val retained = leadingIds + old.folders.filter { it.id in leadingIds }.flatMap { it.appIds }
        val dock = freshInstallDock(apps.filter { it.id !in retained })
        val layout = phase22AppLayout(old.layout.copy(dock = dock), apps, freshInstall = true,
            english = getApplication<Application>().resources.configuration.locales[0].language != "zh")
        undoLayout = old.layout to layout
        undoImportSettings = UndoImportSettings(old.sharedPreset, old.labels, old.googleSearch, old.verticalStatus)
        val preset = old.sharedPreset.copy(iconSize = 48f)
        mutable.value = old.copy(homeSlots = layout.slots, dock = layout.dock, folders = layout.folders,
            compact = preset, expanded = preset, editRevision = old.editRevision + 1, canUndoEdit = true)
        persist()
        return true
    }

    fun applyMultiPageTestLayout(): Boolean {
        val apps = mutable.value.apps.map { Phase22AppCandidate(it.id, it.packageName, it.label, it.isWork) }
        return commitLayout(multiPageTestLayout(mutable.value.layout, apps))
    }

    // Native binding is completion of the preset, not a separate edit. Keep its Undo snapshot.
    fun completeDefaultWidget(original: WidgetPlacement, boundId: Int): Boolean {
        if (statePayloadInvalid || placement(original.slot) != original) return false
        val old = mutable.value
        val next = placeWidget(old.layout, original.copy(id = boundId))
        if (next == old.layout) return false
        undoLayout = undoLayout?.takeIf { it.second == old.layout }?.let { it.first to next }
        mutable.value = old.copy(widgetPlacements = next.widgetPlacements,
            widgetRestores = next.widgetRestores, canUndoEdit = undoLayout != null)
        persist()
        return true
    }
    fun placement(slot: Int) = mutable.value.layout.placement(slot)
    fun nextWidgetSlot() = (mutable.value.widgetPlacements.maxOfOrNull { it.slot } ?: -1) + 1
    fun removePlacement(source: DropTarget) = commitLayout(removePlacement(mutable.value.layout, source))
    private fun commitLayout(next: HomeLayout): Boolean {
        if (statePayloadInvalid) return false
        val old = mutable.value
        if (old.layout == next) return false
        undoLayout = old.layout to next
        undoImportSettings = null
        mutable.value = old.copy(homeSlots = next.slots, leadingSlots = next.leadingSlots, dock = next.dock,
            widgetPlacements = next.widgetPlacements, folders = next.folders,
            widgetRestores = next.widgetRestores,
            editRevision = old.editRevision + 1, canUndoEdit = true)
        persist()
        return true
    }

    fun undoEdit(): Boolean {
        val (before, after) = undoLayout ?: return false
        val old = mutable.value
        if (!old.canUndoEdit || old.layout != after) return false
        val installed = (old.apps.map { it.id } + before.folders.map { it.id }).toSet()
        val settings = undoImportSettings
        mutable.value = old.copy(homeSlots = reconcileHomeSlots(before.slots, installed),
            leadingSlots = before.leadingSlots.map { it?.takeIf(installed::contains) },
            dock = before.dock.map { it?.takeIf(installed::contains) }, widgetPlacements = before.widgetPlacements, folders = before.folders,
            widgetRestores = before.widgetRestores, compact = settings?.sharedPreset ?: old.sharedPreset,
            expanded = settings?.sharedPreset ?: old.sharedPreset, labels = settings?.labels ?: old.labels,
            googleSearch = settings?.googleSearch ?: old.googleSearch, verticalStatus = settings?.verticalStatus ?: old.verticalStatus,
            canUndoEdit = false,
            editRevision = old.editRevision + 1)
        undoLayout = null
        undoImportSettings = null
        persist()
        return true
    }
    fun setLabels(value: Boolean) { if (statePayloadInvalid) return; undoLayout = null; undoImportSettings = null; mutable.update { it.copy(labels = value, canUndoEdit = false) }; persist() }
    fun setVerticalStatus(value: Boolean) { if (statePayloadInvalid) return; undoLayout = null; undoImportSettings = null; mutable.update { it.copy(verticalStatus = value, canUndoEdit = false) }; persist() }
    fun setGoogleSearch(value: Boolean) { if (statePayloadInvalid) return; undoLayout = null; undoImportSettings = null; mutable.update { it.copy(googleSearch = value, canUndoEdit = false) }; persist() }
    fun setPreset(value: LayoutPreset) {
        if (statePayloadInvalid) return
        undoLayout = null; undoImportSettings = null
        val shared = value.sanitized()
        mutable.update { it.copy(compact = shared, expanded = shared, canUndoEdit = false) }
        persist()
    }
    val retainedWidgetIds get() = (mutable.value.widgetPlacements.map { it.id } +
        (if (mutable.value.canUndoEdit) undoLayout?.first?.widgetPlacements.orEmpty().map { it.id } else emptyList())).filter { it >= 0 }.toSet()
    val canPruneWidgetIds get() = !statePayloadInvalid
    fun setWidget(slot: Int, id: Int) {
        if (statePayloadInvalid) return
        val old = mutable.value
        val existing = old.layout.placement(slot)
        val next = when {
            id == EMPTY_WIDGET -> removePlacement(old.layout, DropTarget.Widget(slot))
            existing != null -> old.layout.copy(widgetPlacements = old.widgetPlacements.map { if (it.slot == slot) it.copy(id = id) else it },
                widgetRestores = old.widgetRestores.filterNot { it.slot == slot })
            else -> placeWidget(old.layout, migrateSchema5Widgets(List(slot) { EMPTY_WIDGET } + id).single())
        }
        mutable.update { it.copy(widgetPlacements = next.widgetPlacements, widgetRestores = next.widgetRestores,
            canUndoEdit = false, editRevision = it.editRevision + 1) }
        undoLayout = null
        undoImportSettings = null
        persist()
    }

    internal fun restoreLayout(layout: HomeLayout) {
        if (statePayloadInvalid) return
        val installed = (mutable.value.apps.map { it.id } + layout.folders.map { it.id }).toSet()
        mutable.update { it.copy(homeSlots = reconcileHomeSlots(layout.slots, installed),
            leadingSlots = layout.slotsForPage(-1).map { id -> id?.takeIf { it in installed || isFolderId(it) } },
            dock = layout.dock.map { id -> id?.takeIf { it in installed || isFolderId(it) } }, widgetPlacements = layout.widgetPlacements,
            folders = layout.folders, widgetRestores = layout.widgetRestores,
            canUndoEdit = false, editRevision = it.editRevision + 1) }
        undoLayout = null
        undoImportSettings = null
        persist()
    }

    private fun persist() {
        if (needsMigration || statePayloadInvalid) return
        val s = mutable.value
        fun preset(p: LayoutPreset) = JSONObject().put("iconSize", p.iconSize).put("rowGap", p.rowGap)
            .put("dockWidth", p.dockWidth).put("dockPosition", p.dockPosition).put("dockAlignToGrid", p.dockAlignToGrid)
        val widgets = JSONArray().also { array -> s.widgetPlacements.forEach { w -> array.put(JSONObject()
            .put("slot", w.slot).put("id", w.id).put("page", w.page).put("column", w.column).put("row", w.row)
            .put("spanX", w.spanX).put("spanY", w.spanY)) } }
        val folders = JSONArray().also { array -> s.folders.forEach { folder -> array.put(JSONObject()
            .put("id", folder.id).put("title", folder.title).put("apps", JSONArray(folder.appIds))) } }
        val restores = JSONArray().also { array -> s.widgetRestores.forEach { restore -> array.put(JSONObject()
            .put("slot", restore.slot).put("provider", restore.providerComponent).put("userSerial", restore.userSerial)
            .put("title", restore.title).put("profileLabel", restore.profileLabel).put("work", restore.isWork)
            .put("sourceScope", restore.sourceScope)) } }
        val data = JSONObject().put("schema", 8).put("pinned", JSONArray(s.order)).put("homeSlots", JSONArray(s.homeSlots))
            .put("artworkDefaultsRevision", 2)
            .put("leadingSlots", JSONArray(s.leadingSlots)).put("dock", JSONArray(s.dock))
            .put("widgets", widgets).put("labels", s.labels)
            .put("folders", folders)
            .put("restores", restores)
            .put("googleSearch", s.googleSearch)
            .put("verticalStatus", s.verticalStatus)
            .put("sharedPreset", preset(s.sharedPreset))
            // Retain the legacy aliases until the planned schema-10 migration so old
            // backup/debug tooling can still inspect a schema-8 test build.
            .put("compact", preset(s.sharedPreset)).put("expanded", preset(s.sharedPreset))
        val editor = prefs.edit()
        if (legacyRaw != null && sourceSchema == 2 && !prefs.contains("state_v2_backup"))
            editor.putString("state_v2_backup", legacyRaw)
        if (legacyRaw != null && sourceSchema < 4 && !prefs.contains("state_v3_backup"))
            editor.putString("state_v3_backup", legacyRaw)
        if (legacyRaw != null && sourceSchema < 5 && !prefs.contains("state_v4_backup"))
            editor.putString("state_v4_backup", legacyRaw)
        if (legacyRaw != null && sourceSchema < 6 && !prefs.contains("state_v5_backup"))
            editor.putString("state_v5_backup", legacyRaw)
        if (legacyRaw != null && sourceSchema < 7 && !prefs.contains("state_v6_backup"))
            editor.putString("state_v6_backup", legacyRaw)
        if (legacyRaw != null && sourceSchema < 8 && !prefs.contains("state_v7_backup"))
            editor.putString("state_v7_backup", legacyRaw)
        editor.putString("state", data.toString())
            .putBoolean("initialized", true)
            .putBoolean(LEADING_PAGE_INITIALIZED, true)
            .apply()
    }

    private fun load(): LauncherState = runCatching {
        val j = JSONObject(prefs.getString("state", "{}") ?: "{}")
        fun preset(key: String, default: LayoutPreset): LayoutPreset {
            val p = j.optJSONObject(key) ?: return default
            val loaded = LayoutPreset(p.optDouble("iconSize", default.iconSize.toDouble()).toFloat(),
                p.optDouble("rowGap", default.rowGap.toDouble()).toFloat(),
                p.optDouble("dockWidth", default.dockWidth.toDouble()).toFloat(),
                p.optDouble("dockPosition", default.dockPosition.toDouble()).toFloat(),
                p.optBoolean("dockAlignToGrid", true)).sanitized()
            return upgradeArtworkDefault(upgradePreset(loaded, j.optInt("schema", 1), key == "expanded"),
                j.optInt("artworkDefaultsRevision", 0))
        }
        val order = j.optJSONArray(if (j.optInt("schema", 1) >= 2) "pinned" else "order") ?: JSONArray()
        val cells = j.optJSONArray("homeSlots").takeIf { j.optInt("schema", 1) >= 4 } ?: order
        val schema = j.optInt("schema", 1)
        require(schema <= 8) { "Unsupported saved-state schema $schema" }
        val rawSlots = List(cells.length()) { cells.optString(it).takeIf { id -> id.isNotBlank() && id != "null" } }
        val legacySlots = normalizeHomeSlots(rawSlots)
        val rawLeadingSlots = if (schema >= 8) {
            val leading = j.optJSONArray("leadingSlots") ?: error("Schema 8 requires a leading slot array")
            require(leading.length() == HOME_CELLS)
            List(HOME_CELLS) { leading.optString(it).takeIf { id -> id.isNotBlank() && id != "null" } }
        } else List(HOME_CELLS) { null }
        val loadedDock = List(4) { j.optJSONArray("dock")?.optString(it)?.takeIf { it.isNotBlank() && it != "null" } }
        val widgetArray = j.optJSONArray("widgets")
        val placements = if (schema >= 6) {
            require(widgetArray != null) { "Schema $schema requires a widget placement array" }
            fun strictInt(objectValue: JSONObject, key: String): Int {
                val number = objectValue.get(key) as? Number ?: error("$key must be an integer")
                val value = number.toDouble()
                require(value.isFinite() && value % 1.0 == 0.0 && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) {
                    "$key must be a finite integer"
                }
                return value.toInt()
            }
            List(widgetArray.length()) { index ->
                val w = widgetArray.getJSONObject(index)
                WidgetPlacement(strictInt(w, "slot"), strictInt(w, "id"), strictInt(w, "page"), strictInt(w, "column"), strictInt(w, "row"),
                    strictInt(w, "spanX"), strictInt(w, "spanY"))
            }.also { loaded ->
                require(loaded.map { it.slot }.distinct().size == loaded.size) { "Widget placement slots must be unique" }
                loaded.forEach { placement ->
                    val baseGeometry = placement.slot >= 0 && placement.id != EMPTY_WIDGET && placement.page >= -1 &&
                        placement.column >= 0 && placement.row >= 0 && placement.spanX in 1..GRID_COLUMNS &&
                        placement.spanY in 1..GRID_ROWS && placement.column + placement.spanX <= GRID_COLUMNS
                    val insideGrid = placement.row + placement.spanY <= widgetRowsForPage(placement.page)
                    val migratedOverflow = placement.page > 0 && placement.slot / 3 == placement.page && placement.slot % 3 == 2 &&
                        placement.column == 0 && placement.row == GRID_ROWS && placement.spanX == GRID_COLUMNS && placement.spanY == 4
                    require(baseGeometry && (insideGrid || migratedOverflow)) { "Invalid widget placement" }
                }
            }
        } else {
            val ids = if (schema < 5) List(3) { index ->
                (widgetArray?.optInt(index, -1) ?: -1).let {
                    if (it < 0) listOf(CLOCK_WIDGET, DATE_WIDGET, INFO_WIDGET)[index] else it
                }
            } else List(widgetArray?.length() ?: 0) { widgetArray!!.optInt(it, EMPTY_WIDGET) }
            migrateSchema5Widgets(ids)
        }.filterNot { placement -> schema < 8 && placement == WidgetPlacement(2, INFO_WIDGET, -1, 0, 0, 4, 6) }
        val folders = if (schema >= 7) {
            val array = j.optJSONArray("folders") ?: error("Schema 7 requires a folder array")
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val apps = item.getJSONArray("apps")
                FolderEntry(item.getString("id"), item.getString("title"), List(apps.length()) { apps.getString(it) })
            }.also { loaded ->
                require(loaded.map(FolderEntry::id).distinct().size == loaded.size)
                require(loaded.flatMap(FolderEntry::appIds).distinct().size == loaded.sumOf { it.appIds.size })
                loaded.forEach { folder ->
                    require(isFolderId(folder.id) && folder.title.isNotBlank() && folder.appIds.size >= 2)
                    require(folder.appIds.none { it.isBlank() || isReservedFolderId(it) })
                }
                val children = loaded.flatMapTo(mutableSetOf(), FolderEntry::appIds)
                val folderIds = loaded.mapTo(mutableSetOf(), FolderEntry::id)
                val rawFolderRefs = (rawSlots + rawLeadingSlots).filterNotNull().filter(::isReservedFolderId)
                require(rawFolderRefs.all(::isFolderId))
                require(rawFolderRefs.size == folderIds.size && rawFolderRefs.toSet() == folderIds)
                require((rawSlots + rawLeadingSlots).none { it in children } &&
                    loadedDock.none { it in children || (it != null && isReservedFolderId(it)) })
            }
        } else emptyList()
        if (schema >= 8) {
            val leadingIds = rawLeadingSlots.filterNotNull()
            require(leadingIds.distinct().size == leadingIds.size) {
                "An unfolded-only shortcut appears more than once"
            }
            val leadingApps = leadingIds.filterNot(::isReservedFolderId)
            val otherApps = rawSlots.filterNotNull().filterNot(::isReservedFolderId) +
                loadedDock.filterNotNull() + folders.flatMap(FolderEntry::appIds)
            require(leadingApps.none { it in otherApps }) {
                "An unfolded-only app shortcut appears on another surface"
            }
            val occupiedLeadingCells = rawLeadingSlots.indices
                .filterTo(mutableSetOf()) { rawLeadingSlots[it] != null }
                .mapTo(mutableSetOf()) { homeCellIndex(-1, it) }
            require(placements.filter { it.page == -1 }.none { placement ->
                placement.coveredIndices().any { it in occupiedLeadingCells }
            }) { "An unfolded-only shortcut overlaps a widget" }
        }
        val restores = if (schema >= 7) {
            val array = j.optJSONArray("restores") ?: JSONArray()
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                WidgetRestore(item.getInt("slot"), item.getString("provider"), item.getLong("userSerial"),
                    item.getString("title"), item.getString("profileLabel"), item.optBoolean("work", false),
                    item.optString("sourceScope").takeIf { it.isNotBlank() && it != "null" })
            }.also { loaded ->
                require(loaded.map(WidgetRestore::slot).distinct().size == loaded.size)
                loaded.forEach { restore ->
                    require(restore.slot >= 0 && restore.userSerial >= 0 && restore.title.isNotBlank() &&
                        restore.profileLabel.isNotBlank() && ComponentName.unflattenFromString(restore.providerComponent) != null)
                }
                require(placements.filter { it.id == NEEDS_BINDING_WIDGET }.map { it.slot }.toSet() == loaded.map { it.slot }.toSet())
            }
        } else emptyList()
        val sharedPreset = if (j.has("sharedPreset")) preset("sharedPreset", LayoutPreset())
            else preset("compact", LayoutPreset())
        val loaded = LauncherState(homeSlots = if (schema in 2..5) migrateSchema5Apps(legacySlots) else legacySlots,
            leadingSlots = rawLeadingSlots,
            dock = loadedDock,
            widgetPlacements = placements, folders = folders, widgetRestores = restores,
            googleSearch = j.optBoolean("googleSearch", true),
            labels = j.optBoolean("labels", true), compact = sharedPreset,
            expanded = sharedPreset, verticalStatus = j.optBoolean("verticalStatus", true))
        if (prefs.getBoolean(LEADING_PAGE_INITIALIZED, false)) loaded else {
            val seeded = withDefaultLeadingWidgets(loaded.layout)
            if (seeded == loaded.layout) {
                // Existing content is already the user's decision, so it is safe to record
                // immediately. A later deletion must not recreate any defaults.
                prefs.edit().putBoolean(LEADING_PAGE_INITIALIZED, true).apply()
            }
            // When defaults were added, the marker is committed together with their serialized
            // layout in persist(). If the process stops first, loading safely repeats the seed.
            loaded.copy(leadingSlots = seeded.leadingSlots, widgetPlacements = seeded.widgetPlacements)
        }
    }.getOrElse {
        statePayloadInvalid = legacyRaw != null
        LauncherState(loading = false, error = "Saved Home layout could not be read; it was left unchanged.")
    }

    override fun onCleared() { launcherApps.unregisterCallback(callback) }
}

/** Render adaptive layers through our rounded-square mask, preserving original app artwork. */
private fun launcherIcon(drawable: Drawable): Bitmap {
    if (drawable !is AdaptiveIconDrawable) return drawable.toBitmap(144, 144)
    val bitmap = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.clipPath(Path().apply { addRoundRect(0f, 0f, 144f, 144f, 34f, 34f, Path.Direction.CW) })
    drawable.setBounds(0, 0, 144, 144)
    drawable.background?.draw(canvas)
    drawable.foreground?.draw(canvas)
    return bitmap
}

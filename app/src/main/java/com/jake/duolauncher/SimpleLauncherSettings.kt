package com.jake.duolauncher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun FirstRunSetupSheet(isDefaultHome: Boolean, onMakeDefault: () -> Unit,
    onAddWidget: () -> Unit, onExplore: () -> Unit, onSkip: () -> Unit) {
    val activity = androidx.activity.compose.LocalActivity.current as MainActivity
    Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).verticalScroll(rememberScrollState())
        .navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MiDuo", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = onSkip) { Icon(Icons.Rounded.Close, stringResource(R.string.close_menu)) }
        }
        RecommendedSetup(isDefaultHome, onMakeDefault, activity::showShadeSetup, activity::showNavigationSetup, firstRun = true)
        SettingsEntry(Icons.Rounded.Widgets, R.string.editor_widgets, onClick = onAddWidget)
        if (isDefaultHome) SettingsEntry(Icons.Rounded.Home, R.string.restore_system_home,
            tag = "restore-system-home", onClick = activity::showRestoreSystemHome)
        Button(onClick = onExplore, modifier = Modifier.fillMaxWidth().testTag("setup-explore")) {
            Text(stringResource(R.string.editor_done))
        }
    }
}

@Composable
internal fun SimpleLauncherSettings(state: LauncherState, model: LauncherModel, isDefaultHome: Boolean,
    wallpaper: Boolean, onClose: () -> Unit, onMakeDefault: () -> Unit, onShadeSetup: () -> Unit,
    onWidgets: () -> Unit, onWallpaper: () -> Unit, onExport: () -> Unit, onImport: () -> Unit,
    backgrounds: LauncherBackgroundController, onSystemWallpaper: () -> Unit) {
    val activity = androidx.activity.compose.LocalActivity.current as MainActivity
    val license = activity.licensing
    var more by remember { mutableStateOf(false) }
    val settingsScroll = rememberScrollState()
    LaunchedEffect(more) { settingsScroll.scrollTo(0) }
    if (more) ModalDialogBackHandler { more = false }
    var confirmRecommended by remember { mutableStateOf(false) }
    if (confirmRecommended) AlertDialog(onDismissRequest = { confirmRecommended = false },
        title = { Text(stringResource(R.string.recommended_layout)) },
        text = { Text(stringResource(R.string.recommended_layout_detail)) },
        confirmButton = { TextButton(onClick = {
            if (activity.requireFeatureLicense()) model.applyRecommendedAppLayout()
            confirmRecommended = false; onClose()
        }) { Text(stringResource(R.string.recommended_layout_apply)) } },
        dismissButton = { TextButton(onClick = { confirmRecommended = false }) { Text(stringResource(R.string.cancel_action)) } })
    Column(Modifier.fillMaxWidth().heightIn(max = 650.dp)
        .navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (more) TextButton(onClick = { more = false }, modifier = Modifier.testTag("settings-more-back")) {
                Text(stringResource(R.string.settings_back))
            }
            Text(stringResource(if (more) R.string.more_settings else if (wallpaper) R.string.editor_wallpaper else R.string.editor_settings),
                Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.close_menu)) }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(settingsScroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (more) {
            if (license.activated) {
                SettingsEntry(Icons.Rounded.Apps, R.string.recommended_layout,
                    tag = "apply-recommended-layout", onClick = { confirmRecommended = true })
                SettingsEntry(Icons.Rounded.Save, R.string.backup_save, onClick = onExport)
                SettingsEntry(Icons.Rounded.Restore, R.string.backup_restore, R.string.backup_review_detail, onClick = onImport)
            }
            MiDuoAbout()
            Text(stringResource(R.string.help_about), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.editor_help), style = MaterialTheme.typography.bodyMedium)
        } else if (wallpaper && license.activated) {
            SettingsEntry(Icons.Rounded.PhotoLibrary, R.string.wallpaper_choose, onClick = backgrounds::choosePhoto)
            if (backgrounds.previewPending) {
                backgrounds.previewBitmap?.let { bitmap ->
                    androidx.compose.foundation.Image(bitmap = bitmap.asImageBitmap(),
                        contentDescription = null, modifier = Modifier.fillMaxWidth().height(160.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                }
                SettingsEntry(Icons.Rounded.Check, R.string.wallpaper_apply, onClick = backgrounds::applyPreview)
                TextButton(onClick = backgrounds::cancelPreview) { Text(stringResource(R.string.cancel_action)) }
            }
            if (backgrounds.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            (backgrounds.errorMessage ?: backgrounds.successMessage)?.let { Text(it) }
            SettingsEntry(Icons.Rounded.Restore, R.string.wallpaper_reset, onClick = backgrounds::reset)
            SettingsEntry(Icons.Rounded.Wallpaper, R.string.wallpaper_system, onClick = onSystemWallpaper)
        } else {
            RecommendedSetup(isDefaultHome, onMakeDefault, onShadeSetup, activity::showNavigationSetup)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            LicenseActivationPanel(license)
            if (license.activated) {
            FoldAnimationSetting(license.foldAnimationEnabled, license::setFoldAnimation)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.miduo_standby), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.miduo_standby_detail), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = license.standbyEnabled, onCheckedChange = license::setStandby,
                    modifier = Modifier.testTag("settings-standby"))
            }
            SettingsEntry(Icons.Rounded.Widgets, R.string.editor_widgets, onClick = onWidgets)
            SettingsEntry(Icons.Rounded.Wallpaper, R.string.editor_wallpaper, onClick = onWallpaper)
            if (state.canUndoEdit) SettingsEntry(Icons.Rounded.Undo, R.string.undo_layout, onClick = { model.undoEdit(); onClose() })
            }
            if (isDefaultHome) SettingsEntry(Icons.Rounded.Home, R.string.restore_system_home, R.string.restore_system_home_detail,
                tag = "restore-system-home", onClick = activity::showRestoreSystemHome)
            SettingsEntry(Icons.Rounded.MoreHoriz, R.string.more_settings, tag = "settings-more", onClick = { more = true })
        }
        }
    }
}

@Composable
internal fun FoldAnimationSetting(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val title = stringResource(R.string.miduo_fold_animation)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.miduo_fold_animation_detail), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = enabled, onCheckedChange = onChange,
            modifier = Modifier.testTag("settings-fold-animation").semantics {
                contentDescription = title
            })
    }
}

@Composable
internal fun RecommendedSetup(isDefaultHome: Boolean, onMakeDefault: () -> Unit,
    onAccessibility: () -> Unit, onGestures: () -> Unit, firstRun: Boolean = false) {
    val activity = androidx.activity.compose.LocalActivity.current as MainActivity
    val activated = activity.licensing.activated
    val connected by produceState(SystemShadeAccessibilityService.isConnected()) {
        while (true) { value = SystemShadeAccessibilityService.isConnected(); kotlinx.coroutines.delay(1000) }
    }
    Text(stringResource(R.string.setup_recommended), style = MaterialTheme.typography.titleLarge)
    if (firstRun) {
        Text(stringResource(R.string.setup_explanation), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.setup_restricted_notice), style = MaterialTheme.typography.bodySmall)
    }
    TextButton(onClick = activity::showPermissionGuide, modifier = Modifier.testTag("setup-permission-guide")) {
        Text(stringResource(R.string.setup_permissions_title))
    }
    SettingsEntry(Icons.Rounded.Home, R.string.setup_default, R.string.setup_default_detail,
        if (!activated) R.string.miduo_requires_activation else if (isDefaultHome) R.string.setup_enabled else R.string.setup_needed,
        "setup-default", { if (activity.requireFeatureLicense()) onMakeDefault() })
    SettingsEntry(Icons.Rounded.AccessibilityNew, R.string.setup_accessibility, R.string.setup_accessibility_detail,
        if (!activated) R.string.miduo_requires_activation else if (connected) R.string.setup_enabled else R.string.setup_needed,
        "setup-accessibility", { if (activity.requireFeatureLicense()) onAccessibility() })
    // Do not report gestures as verified merely because the setting or service is enabled.
    SettingsEntry(Icons.Rounded.SwipeUp, R.string.setup_gestures, R.string.setup_gestures_detail,
        status = if (!activated) R.string.miduo_requires_activation else null,
        tag = "setup-gestures", onClick = { if (activity.requireFeatureLicense()) onGestures() })
}

@Composable
internal fun SettingsEntry(icon: ImageVector, title: Int, detail: Int? = null, status: Int? = null,
    tag: String = "settings-entry-$title", onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().duoGlass(DuoGlassRole.SettingsRow, RoundedCornerShape(20.dp), backdrop = false).testTag(tag)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                detail?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
            }
            status?.let { Spacer(Modifier.width(6.dp)); Text(stringResource(it), style = MaterialTheme.typography.labelSmall) }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp))
        }
    }
}

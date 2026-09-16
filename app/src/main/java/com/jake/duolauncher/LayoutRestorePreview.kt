package com.jake.duolauncher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun LayoutRestorePreview(preview: LayoutImportPreview, onRestore: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, modifier = Modifier.testTag("layout-restore-preview"),
        title = { Text(launcherFormat(R.string.backup_preview)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(launcherFormat(R.string.backup_counts, preview.appCount, preview.folderCount, preview.widgetCount))
                if (preview.layout.leadingSlots.any { it != null } || preview.layout.widgetPlacements.any { it.page == -1 })
                    Text(launcherFormat(R.string.backup_leading), style = MaterialTheme.typography.bodySmall)
                Text(launcherFormat(R.string.backup_settings))
                Text(launcherFormat(R.string.backup_no_photo),
                    style = MaterialTheme.typography.bodySmall)
                if (preview.missingApps.isNotEmpty()) {
                    Text(launcherFormat(R.string.backup_missing, preview.missingApps.size), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error)
                    preview.missingApps.forEach { saved ->
                        val label = saved.substringAfterLast('(').removeSuffix(")").takeIf { it.isNotBlank() } ?: "Unavailable app"
                        Text(launcherFormat(R.string.backup_empty_slot, label))
                    }
                }
                if (preview.profileIssues.isNotEmpty()) {
                    Text(launcherFormat(R.string.backup_profile), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error)
                }
                val reconnect = preview.layout.widgetPlacements.count { it.id == NEEDS_BINDING_WIDGET }
                if (reconnect > 0) Text(launcherFormat(R.string.backup_widget_reconnect, reconnect))
                Text(launcherFormat(R.string.backup_confirm_help), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { Button(onClick = onRestore, modifier = Modifier.testTag("layout-restore-apply")) { Text(launcherText("Restore")) } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.testTag("layout-restore-cancel")) { Text(launcherText("Cancel")) } })
}

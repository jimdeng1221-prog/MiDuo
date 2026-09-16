package com.jake.duolauncher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private fun openMiDuoWebsite(context: Context) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mymiduo.xyz")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: android.content.ActivityNotFoundException) {
        Toast.makeText(context, R.string.miduo_browser_unavailable, Toast.LENGTH_LONG).show()
    }
}

@Composable
internal fun MiDuoAbout() {
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.miduo_about), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("miduo-about"))
    Text(stringResource(R.string.miduo_author), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.miduo_version, version), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.miduo_copyright), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.miduo_attribution), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { openMiDuoWebsite(context) }) { Text(stringResource(R.string.miduo_website)) }
    TextButton(onClick = { showLicenses = !showLicenses }, modifier = Modifier.testTag("miduo-open-licenses")) {
        Text(stringResource(R.string.miduo_licenses))
    }
    if (showLicenses) {
        val licenses by produceState("") {
            value = withContext(Dispatchers.IO) {
                listOf("THIRD-PARTY-NOTICES.txt", "MIT-DuoLauncher.txt", "GlassProjection-MIT.txt", "Apache-2.0.txt",
                    "BouncyCastle-LICENSE.txt", "BoringSSL-LICENSE.txt", "Conscrypt-NOTICE.txt")
                    .joinToString("\n\n") { file -> context.assets.open("licenses/$file").bufferedReader().use { it.readText() } }
            }
        }
        // Legal texts stay in their original language; the navigation is bilingual.
        SelectionContainer { Text(licenses, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("miduo-license-text")) }
    }
}

@Composable
internal fun LicenseActivationPanel(store: OfflineLicenseStore, onActivated: () -> Unit = {}) {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<ActivationFailure?>(null) }
    var busy by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Text(stringResource(if (store.activated) R.string.miduo_activated else R.string.miduo_activate),
        style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("license-status"))
    if (!store.activated) Text(stringResource(R.string.miduo_activation_detail), style = MaterialTheme.typography.bodyMedium)
    if (store.deviceCode == null) Text(stringResource(R.string.miduo_missing_device))
    if (!store.activated) {
        OutlinedTextField(value = code, onValueChange = { if (it.length <= 80) { code = it; failure = null } },
            label = { Text(stringResource(R.string.miduo_enter_code)) }, minLines = 2, maxLines = 3, enabled = !busy,
            isError = failure != null, modifier = Modifier.fillMaxWidth().testTag("license-code-input"))
        Text(stringResource(R.string.miduo_activation_privacy), style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = !busy, modifier = Modifier.testTag("license-consent"))
            Text(stringResource(R.string.miduo_activation_consent), style = MaterialTheme.typography.bodySmall)
        }
        failure?.let { reason -> Text(stringResource(when (reason) {
            ActivationFailure.BOUND -> R.string.miduo_code_bound
            ActivationFailure.NETWORK -> R.string.miduo_network_error
            ActivationFailure.SERVER -> R.string.miduo_server_error
            ActivationFailure.RATE_LIMIT -> R.string.miduo_rate_limit
            ActivationFailure.SAVE -> R.string.miduo_save_error
            ActivationFailure.DEVICE -> R.string.miduo_missing_device
            else -> R.string.miduo_invalid_code
        }), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("license-error"))
        }
        Button(onClick = {
            busy = true; failure = null
            scope.launch {
                try {
                    failure = store.redeem(code)
                    if (failure == null) { code = ""; onActivated() }
                } finally { busy = false }
            }
        }, enabled = store.deviceCode != null && code.isNotBlank() && consent && !busy,
            modifier = Modifier.testTag("license-activate")) {
            Text(stringResource(if (busy) R.string.miduo_activating else R.string.miduo_activate_button))
        }
        TextButton(onClick = { openMiDuoWebsite(context) }, modifier = Modifier.testTag("license-website")) {
            Text(stringResource(R.string.miduo_get_code))
        }
    }
    Text(stringResource(R.string.miduo_binding_detail), style = MaterialTheme.typography.bodySmall)
}

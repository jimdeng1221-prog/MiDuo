package com.jake.duolauncher

import android.app.AlertDialog
import android.content.Context

/** Result dialog shared by the actual setup overlay and instrumented UI verification. */
internal fun createWirelessAdbOutcome(context: Context, verified: Boolean, needsConnection: Boolean,
    message: Int, onEnable: () -> Unit, onClose: () -> Unit): AlertDialog =
    AlertDialog.Builder(context)
        .setTitle(if (verified) R.string.wireless_success_title else if (needsConnection) R.string.wireless_paired_title else R.string.wireless_failure_title)
        .setMessage(if (verified) R.string.wireless_success_message else if (message == R.string.wireless_authorized) R.string.wireless_grant_denied else message)
        .setPositiveButton(if (verified) R.string.navigation_enable else R.string.wireless_return_setup) { _, _ -> if (verified) onEnable() }
        .setNegativeButton(R.string.close_menu) { _, _ -> onClose() }
        .create()

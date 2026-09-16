package com.jake.duolauncher

internal fun localAdbPort(text: String): Int? =
    text.trim().takeIf { it.matches(Regex("[0-9]{1,5}")) }?.toIntOrNull()?.takeIf { it in 1024..65535 }

internal fun validAdbPairingCode(text: String) = text.matches(Regex("[0-9]{6}"))

// Fixed command, no caller-supplied package, host, script or permission.
internal const val MIDUO_NAVIGATION_GRANT =
    "pm grant com.jake.duolauncher android.permission.WRITE_SECURE_SETTINGS"

internal fun wirelessSetupAllowed(licensed: Boolean, defaultHome: Boolean, serviceConnected: Boolean) =
    licensed && defaultHome && serviceConnected

package com.jake.duolauncher

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class OfflineLicenseStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("miduo_offline_license", Context.MODE_PRIVATE)
    val deviceCode = OfflineLicense.deviceCode(Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID), context.packageName)
    var activated by mutableStateOf(OfflineLicense.verify(preferences.getString("signed_license", "").orEmpty(), deviceCode))
        private set
    var standbyEnabled by mutableStateOf(preferences.getBoolean("standby_enabled", false))
        private set
    var foldAnimationEnabled by mutableStateOf(preferences.getBoolean("fold_animation_enabled", true))
        private set
    fun activate(code: String): Boolean {
        if (!OfflineLicense.verify(code, deviceCode)) return false
        // Store the signed proof, never a trusted boolean; every process start verifies again.
        if (!preferences.edit().putString("signed_license", OfflineLicense.normalize(code)).commit()) return false
        activated = true
        return true
    }
    suspend fun redeem(code: String): ActivationFailure? {
        val device = deviceCode ?: return ActivationFailure.DEVICE
        return try {
            val receipt = ActivationClient.redeem(code, device)
            if (activate(receipt)) null else ActivationFailure.SAVE
        } catch (e: ActivationException) { e.reason }
    }
    fun setStandby(enabled: Boolean) {
        if (!activated) return
        if (preferences.edit().putBoolean("standby_enabled", enabled).commit()) standbyEnabled = enabled
    }
    fun setFoldAnimation(enabled: Boolean) {
        if (!activated) return
        if (preferences.edit().putBoolean("fold_animation_enabled", enabled).commit()) foldAnimationEnabled = enabled
    }
}

internal class LicensedSheetState(private val backing: MutableState<String>, private val activated: () -> Boolean) : MutableState<String> {
    override var value: String
        get() = licensedSheet(backing.value, activated())
        set(value) { backing.value = licensedSheet(value, activated()) }
    override fun component1() = value
    override fun component2(): (String) -> Unit = { value = it }
}

/** Gate all app/folder action selections, including accessibility long-click callbacks. */
internal class LicensedSelectionState(private val backing: MutableState<String?>,
    private val activated: () -> Boolean, private val onLocked: () -> Unit) : MutableState<String?> {
    override var value: String?
        get() = if (activated()) backing.value else null
        set(value) {
            if (value != null && !activated()) { backing.value = null; onLocked() }
            else backing.value = value
        }
    override fun component1() = value
    override fun component2(): (String?) -> Unit = { value = it }
}

package com.jake.duolauncher

import android.content.pm.LauncherApps
import android.os.Build
import android.os.UserHandle
import android.os.UserManager

/** Persisted app identity. Personal-profile IDs intentionally retain the legacy component string. */
data class ProfileAppIdentity(val component: String, val userSerial: Long?)

data class AppProfile(
    val userSerial: Long,
    val label: String,
    val isPersonal: Boolean,
    val isWork: Boolean,
    val quiet: Boolean,
    val unlocked: Boolean,
    val available: Boolean,
)

private const val PROFILE_APP_PREFIX = "duo-profile:v1:"

fun profileAppId(component: String, userSerial: Long, personalSerial: Long): String {
    require(component.isNotBlank())
    require(userSerial >= 0)
    return if (userSerial == personalSerial) component else "$PROFILE_APP_PREFIX$userSerial:$component"
}

fun parseProfileAppId(id: String): ProfileAppIdentity? {
    if (!id.startsWith(PROFILE_APP_PREFIX)) return id.takeIf(String::isNotBlank)?.let { ProfileAppIdentity(it, null) }
    val separator = id.indexOf(':', PROFILE_APP_PREFIX.length)
    if (separator <= PROFILE_APP_PREFIX.length || separator == id.lastIndex) return null
    val serialText = id.substring(PROFILE_APP_PREFIX.length, separator)
    if (serialText.any { !it.isDigit() }) return null
    val serial = serialText.toLongOrNull()?.takeIf { it >= 0 } ?: return null
    return ProfileAppIdentity(id.substring(separator + 1), serial)
}

/** A successful associated-profile enumeration is authoritative even when a profile is quiet or locked. */
fun removedAssociatedProfileSerials(cachedWorkSerials: Set<Long>, associatedSerials: Set<Long>): Set<Long> =
    cachedWorkSerials - associatedSerials

fun removedAppIds(
    savedIds: Collection<String>,
    availableIds: Set<String>,
    authoritativeProfiles: Set<Long>,
    temporarilyUnavailable: Set<Pair<Long, String>>,
    confirmedRemoved: Set<Pair<Long, String>>,
    personalSerial: Long,
    definitivelyRemovedProfiles: Set<Long> = emptySet(),
): Set<String> = savedIds.filterTo(mutableSetOf()) { id ->
    if (isReservedFolderId(id)) return@filterTo false
    val identity = parseProfileAppId(id) ?: return@filterTo false
    val serial = identity.userSerial ?: personalSerial
    val packageName = identity.component.substringBefore('/').takeIf(String::isNotBlank) ?: return@filterTo false
    serial in definitivelyRemovedProfiles || serial to packageName in confirmedRemoved ||
        (serial in authoritativeProfiles && id !in availableIds && serial to packageName !in temporarilyUnavailable)
}

/** API 35 identifies managed profiles exactly; earlier LauncherApps exposed associated managed profiles. */
fun isSupportedWorkProfile(launcherApps: LauncherApps, user: UserHandle): Boolean =
    Build.VERSION.SDK_INT < 35 || runCatching {
        launcherApps.getLauncherUserInfo(user)?.userType == UserManager.USER_TYPE_PROFILE_MANAGED
    }.getOrDefault(false)

package com.jake.duolauncher

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle

internal const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"

/** Public search entry point; no query is submitted and no private Google component is named. */
internal fun googleSearchIntent() = Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH)
    .setPackage(GOOGLE_APP_PACKAGE)
    .putExtra(SearchManager.QUERY, "")
    .putExtra(SearchManager.APP_DATA, Bundle().apply { putString("source", "launcher-search") })

/** Exported system search activities only; never a private OEM component or browser fallback. */
internal fun systemSearchIntents(context: android.content.Context): List<Intent> {
    val intent = Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH)
    return context.packageManager.queryIntentActivities(intent, 0)
        .filter { match ->
            val info = match.activityInfo
            info.exported && info.enabled && info.packageName != GOOGLE_APP_PACKAGE &&
                info.applicationInfo.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                    android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 &&
                (info.permission == null || context.checkSelfPermission(info.permission) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
        .sortedBy { if (it.activityInfo.packageName == "com.android.quicksearchbox") 0 else 1 }
        .map { Intent(intent).setComponent(android.content.ComponentName(it.activityInfo.packageName, it.activityInfo.name)) }
}


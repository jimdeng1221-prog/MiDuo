package com.jake.duolauncher

import android.app.Application

class DuoApplication : Application() {
    override fun onCreate() { super.onCreate(); uiResources = resources }
    companion object {
        internal var uiResources: android.content.res.Resources? = null
            private set
    }
}

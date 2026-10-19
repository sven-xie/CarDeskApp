package com.sven.cardesk

import android.app.Application
import com.sven.cardesk.core.system.HiddenApiAccess
import com.sven.cardesk.core.theme.ThemeController
import com.sven.cardesk.utils.Utils

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Utils.init(this)
        HiddenApiAccess.allow()
        ThemeController.applyStoredTheme(this)
    }
}

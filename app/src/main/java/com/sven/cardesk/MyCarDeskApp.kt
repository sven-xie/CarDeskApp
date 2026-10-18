package com.sven.cardesk

import android.app.Application
import com.sven.cardesk.core.system.HiddenApiAccess
import com.sven.cardesk.core.theme.ThemeController

class MyCarDeskApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HiddenApiAccess.allow()
        ThemeController.applyStoredTheme(this)
    }
}

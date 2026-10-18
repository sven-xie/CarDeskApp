package com.sven.cardesk.core.system

import android.util.Log

/**
 * The virtual app windows need a small number of hidden framework methods:
 * - InputManager.injectInputEvent
 * - MotionEvent.setDisplayId
 *
 * In a platform build these are normally available. This exemption is a fallback for
 * system images that enforce hidden API filtering for platform-signed apps.
 */
object HiddenApiAccess {

    private const val TAG = "HiddenApiAccess"

    fun allow() {
        runCatching {
            val vmRuntime = Class.forName("dalvik.system.VMRuntime")
            val runtime = vmRuntime.getMethod("getRuntime").invoke(null)
            val setExemptions = vmRuntime.getMethod(
                "setHiddenApiExemptions",
                Array<String>::class.java
            )
            setExemptions.invoke(
                runtime,
                arrayOf(
                    "Landroid/hardware/input/InputManager;",
                    "Landroid/view/MotionEvent;",
                    "Landroid/view/InputEvent;",
                    "Landroid/app/ActivityTaskManager;",
                    "Landroid/app/ActivityManager;"
                )
            )
        }.onFailure {
            Log.d(TAG, "Hidden API exemptions are unavailable", it)
        }
    }
}

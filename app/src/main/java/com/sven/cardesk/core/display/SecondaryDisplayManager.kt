package com.sven.cardesk.core.display

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.sven.cardesk.SecondaryLauncherActivity
import kotlin.math.roundToInt

object SecondaryDisplayManager {

    private const val TAG = "SecondaryDisplay"
    const val WINDOW_DISPLAY_PREFIX = "MyCarDesk-window-"

    data class DisplayInfo(
        val display: Display,
        val name: String,
        val width: Int,
        val height: Int
    )

    sealed interface LaunchResult {
        data class LaunchedSecondary(val displayName: String) : LaunchResult
        data class LaunchedPrimaryDebug(val appName: String) : LaunchResult
        data object NoSecondary : LaunchResult
        data object NoLaunchIntent : LaunchResult
        data class Failed(val message: String) : LaunchResult
    }

    fun getSecondaryDisplay(context: Context): Display? {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return null
        return manager.displays
            .asSequence()
            .filter { it.displayId != Display.DEFAULT_DISPLAY }
            .filter { !it.name.startsWith(WINDOW_DISPLAY_PREFIX) }
            .filter { it.isValid && it.state == Display.STATE_ON }
            .sortedBy { it.displayId }
            .firstOrNull()
    }

    fun getDisplayInfo(context: Context): DisplayInfo? {
        val display = getSecondaryDisplay(context) ?: return null
        val metrics = displayMetrics(context, display)
        return DisplayInfo(
            display = display,
            name = display.name,
            width = metrics.widthPixels,
            height = metrics.heightPixels
        )
    }

    fun launchApp(
        context: Context,
        packageName: String,
        label: String,
        widthRatio: Float
    ): LaunchResult {
        val packageManager = context.packageManager
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return LaunchResult.NoLaunchIntent
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val display = getSecondaryDisplay(context)
        if (display == null) {
            return if (isDebuggable(context)) {
                runCatching { context.startActivity(launchIntent) }
                    .fold(
                        onSuccess = { LaunchResult.LaunchedPrimaryDebug(label) },
                        onFailure = { LaunchResult.Failed(it.message ?: "Unable to start app") }
                    )
            } else {
                LaunchResult.NoSecondary
            }
        }

        val options = ActivityOptions.makeBasic()
            .setLaunchDisplayId(display.displayId)
            .setLaunchBounds(boundsFor(context, display, widthRatio))

        return runCatching {
            context.startActivity(launchIntent, options.toBundle())
            LaunchResult.LaunchedSecondary(display.name)
        }.getOrElse {
            Log.w(TAG, "Unable to launch $packageName on display ${display.displayId}", it)
            LaunchResult.Failed(it.message ?: "Unable to start app")
        }
    }

    fun ensureSecondaryLauncher(context: Context): Boolean {
        val display = getSecondaryDisplay(context) ?: return false
        val intent = Intent(context, SecondaryLauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId)
        return runCatching {
            context.startActivity(intent, options.toBundle())
            true
        }.getOrDefault(false)
    }

    /**
     * Finish tasks hosted by one of CarDesk's virtual displays before the
     * display is released. Without this, ActivityTaskManager moves the task
     * to the default display and the app appears full-screen over the desktop.
     */
    @Suppress("PrivateApi", "DiscouragedPrivateApi")
    fun finishTasksOnDisplay(displayId: Int) {
        runCatching {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val service = atmClass.getMethod("getService").invoke(null) ?: return
            val getTasks = service.javaClass.methods.firstOrNull { method ->
                method.name == "getTasks" &&
                    method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
            } ?: return

            val args = arrayOfNulls<Any>(getTasks.parameterTypes.size)
            args[0] = 20
            for (index in 1 until args.size) {
                args[index] = when (getTasks.parameterTypes[index]) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType ->
                        if (index == getTasks.parameterTypes.lastIndex) displayId else 0
                    else -> null
                }
            }
            val tasks = runCatching {
                (getTasks.invoke(service, *args) as? List<*>)?.filterIsInstance<Any>().orEmpty()
            }.getOrDefault(emptyList())

            val removeTask = service.javaClass.methods.firstOrNull { method ->
                method.name == "removeTask" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: return

            tasks.forEach { task ->
                if (taskDisplayId(task) == displayId) {
                    readInt(task, "taskId")?.let { taskId ->
                        runCatching { removeTask.invoke(service, taskId) }
                    }
                }
            }
        }
    }

    /**
     * Best-effort live resize of the running task on the secondary display.
     * Some Android builds do not expose this as a public API; failure is logged and does not
     * affect the next launch, which always uses the latest width ratio.
     */
    fun applyWindowWidth(context: Context, widthRatio: Float) {
        val display = getSecondaryDisplay(context) ?: return
        val bounds = boundsFor(context, display, widthRatio)
        runCatching { resizeTopTask(display.displayId, bounds) }
            .onFailure { Log.d(TAG, "Live secondary resize is not available", it) }
    }

    fun boundsFor(context: Context, display: Display, widthRatio: Float): Rect {
        val metrics = displayMetrics(context, display)
        val width = (metrics.widthPixels * widthRatio.coerceIn(0.2f, 0.8f)).roundToInt()
            .coerceAtLeast((metrics.widthPixels * 0.2f).roundToInt())
        return Rect(0, 0, width, metrics.heightPixels)
    }

    private fun displayMetrics(context: Context, display: Display): DisplayMetrics {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayContext = context.createDisplayContext(display)
            val windowManager = displayContext.getSystemService(WindowManager::class.java)
            val bounds = windowManager.currentWindowMetrics.bounds
            DisplayMetrics().apply {
                widthPixels = bounds.width()
                heightPixels = bounds.height()
                density = context.resources.displayMetrics.density
                densityDpi = context.resources.displayMetrics.densityDpi
            }
        } else {
            @Suppress("DEPRECATION")
            DisplayMetrics().also { display.getRealMetrics(it) }
        }
    }

    private fun isDebuggable(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    @Suppress("PrivateApi", "DiscouragedPrivateApi")
    private fun resizeTopTask(displayId: Int, bounds: Rect) {
        val atmClass = Class.forName("android.app.ActivityTaskManager")
        val service = atmClass.getMethod("getService").invoke(null) ?: return
        val getTasks = service.javaClass.methods.firstOrNull { method ->
            method.name == "getTasks" &&
                method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
        } ?: return

        val args = arrayOfNulls<Any>(getTasks.parameterTypes.size)
        args[0] = 20
        for (index in 1 until args.size) {
            args[index] = when (getTasks.parameterTypes[index]) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> if (index == getTasks.parameterTypes.lastIndex) displayId else 0
                else -> null
            }
        }
        val tasks = runCatching {
            (getTasks.invoke(service, *args) as? List<*>)?.filterIsInstance<Any>().orEmpty()
        }.getOrDefault(emptyList())

        val task = tasks.firstOrNull { taskDisplayId(it) == displayId } ?: return
        val taskId = readInt(task, "taskId") ?: return
        val resizeTask = service.javaClass.methods.firstOrNull { method ->
            method.name == "resizeTask" &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                method.parameterTypes[1] == Rect::class.java
        } ?: return
        resizeTask.invoke(service, taskId, bounds, 0)
    }

    private fun taskDisplayId(task: Any): Int? = readInt(task, "displayId")

    private fun readInt(instance: Any, fieldName: String): Int? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            runCatching {
                val field = type.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.getInt(instance)
            }
            type = type.superclass
        }
        return null
    }
}

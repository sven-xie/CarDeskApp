package com.sven.cardesk.widget

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.AttributeSet
import android.util.Log
import android.view.InputEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.sven.cardesk.R
import com.sven.cardesk.core.display.SecondaryDisplayManager
import com.sven.cardesk.databinding.ViewAppWindowContentBinding

/**
 * Hosts a third-party app inside one desktop window.
 *
 * The app is launched into an app-owned virtual display. The virtual display output is rendered
 * into the TextureView, and touch events are forwarded to the virtual display. This keeps every
 * Activity of the app inside this window.
 */
class AppWindowHostView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onChooseApp: (() -> Unit)? = null
    var onAppChanged: ((String?) -> Unit)? = null

    private val content = ViewAppWindowContentBinding.inflate(
        LayoutInflater.from(context),
        this,
        true
    )
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val packageManager = context.packageManager
    private val densityDpi = resources.displayMetrics.densityDpi

    private var virtualDisplay: VirtualDisplay? = null
    private var outputSurface: Surface? = null
    private var requestedPackage: String? = null
    private var requestedLabel: String? = null
    private var currentPackage: String? = null
    private var displayWidth = 0
    private var displayHeight = 0
    private var resizeDeferred = false

    val currentPackageName: String?
        get() = currentPackage ?: requestedPackage

    val currentLabel: String?
        get() = requestedLabel

    private val inputManager: Any? = context.getSystemService(Context.INPUT_SERVICE)
    private val injectInputMethod = runCatching {
        inputManager?.javaClass?.methods?.firstOrNull {
            it.name == "injectInputEvent" && it.parameterTypes.size == 2
        }
    }.getOrNull()
    private val setDisplayIdMethod = runCatching {
        MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }.getOrNull() ?: runCatching {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }.getOrNull()

    init {
        isClickable = true
        isFocusable = true
        content.appPlaceholder.setOnClickListener { onChooseApp?.invoke() }
        content.appTexture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                surfaceTexture.setDefaultBufferSize(width, height)
                outputSurface?.release()
                outputSurface = Surface(surfaceTexture)
                displayWidth = width
                displayHeight = height
                if (requestedPackage != null && !resizeDeferred) {
                    createVirtualDisplay()
                    launchRequestedApp()
                }
            }

            override fun onSurfaceTextureSizeChanged(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                surfaceTexture.setDefaultBufferSize(width, height)
                if (!resizeDeferred) {
                    resizeVirtualDisplay(width, height)
                }
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                releaseVirtualDisplay()
                outputSurface?.release()
                outputSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }
    }

    fun launchApp(packageName: String, label: String? = null) {
        requestedPackage = packageName
        requestedLabel = label ?: resolveLabel(packageName)
        // The TextureView must be visible to obtain a SurfaceTexture; the
        // virtual display is created from that surface in onSurfaceTextureAvailable.
        content.appTexture.visibility = View.VISIBLE
        onAppChanged?.invoke(requestedLabel)
        if (currentPackage == packageName && virtualDisplay != null) {
            launchRequestedApp()
            return
        }
        if (virtualDisplay != null) {
            stopCurrentApp()
            virtualDisplay?.display?.displayId?.let { SecondaryDisplayManager.finishTasksOnDisplay(it) }
            releaseVirtualDisplay()
        }
        if (virtualDisplay == null && outputSurface != null) {
            createVirtualDisplay()
        }
        launchRequestedApp()
    }

    fun clearApp() {
        stopCurrentApp()
        requestedPackage = null
        requestedLabel = null
        virtualDisplay?.display?.displayId?.let { SecondaryDisplayManager.finishTasksOnDisplay(it) }
        releaseVirtualDisplay()
        showPlaceholder(
            context.getString(R.string.window_empty_title),
            context.getString(R.string.window_empty_subtitle)
        )
        onAppChanged?.invoke(null)
    }

    fun setResizeDeferred(deferred: Boolean) {
        resizeDeferred = deferred
        if (deferred) return
        if (virtualDisplay == null &&
            outputSurface != null &&
            requestedPackage != null &&
            width > 0 &&
            height > 0
        ) {
            createVirtualDisplay()
            launchRequestedApp()
        } else {
            resizeVirtualDisplay(width, height)
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        displayWidth = width
        displayHeight = height
        content.appTexture.surfaceTexture?.setDefaultBufferSize(width, height)
        if (resizeDeferred) return
        if (virtualDisplay == null &&
            outputSurface != null &&
            requestedPackage != null &&
            width > 0 &&
            height > 0
        ) {
            createVirtualDisplay()
            launchRequestedApp()
        } else {
            resizeVirtualDisplay(width, height)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (content.appTexture.isAvailable && outputSurface == null && width > 0 && height > 0) {
            val surfaceTexture = content.appTexture.surfaceTexture ?: return
            surfaceTexture.setDefaultBufferSize(width, height)
            outputSurface = Surface(surfaceTexture)
            displayWidth = width
            displayHeight = height
            if (requestedPackage != null && !resizeDeferred) {
                createVirtualDisplay()
                launchRequestedApp()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        releaseVirtualDisplay()
        outputSurface?.release()
        outputSurface = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (currentPackage == null || virtualDisplay == null) {
            return super.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        forwardTouch(event)
        return true
    }

    private fun createVirtualDisplay() {
        val surface = outputSurface ?: return
        if (displayWidth <= 0 || displayHeight <= 0) return
        val width = displayWidth
        val height = displayHeight
        val name = "${SecondaryDisplayManager.WINDOW_DISPLAY_PREFIX}${hashCode()}"
        val secureFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
        val compatibleFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        val privateFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY

        virtualDisplay = createVirtualDisplay(name, width, height, surface, secureFlags)
            ?: createVirtualDisplay(name, width, height, surface, compatibleFlags)
            ?: createVirtualDisplay(name, width, height, surface, privateFlags)

        if (virtualDisplay == null) {
            showPlaceholder(
                context.getString(R.string.window_display_failed),
                context.getString(R.string.window_empty_subtitle)
            )
        }
    }

    private fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        surface: Surface,
        flags: Int
    ): VirtualDisplay? {
        return runCatching {
            displayManager?.createVirtualDisplay(
                name,
                width,
                height,
                densityDpi,
                surface,
                flags
            )
        }.onFailure {
            Log.w(TAG, "Unable to create virtual display with flags $flags", it)
        }.getOrNull()
    }

    private fun resizeVirtualDisplay(width: Int, height: Int) {
        if (resizeDeferred || width <= 0 || height <= 0) return
        val display = virtualDisplay ?: return
        runCatching { display.resize(width, height, densityDpi) }
            .onFailure { Log.d(TAG, "Unable to resize virtual display", it) }
    }

    private fun launchRequestedApp() {
        val packageName = requestedPackage ?: return
        val label = requestedLabel ?: resolveLabel(packageName)
        val display = virtualDisplay ?: return
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            showPlaceholder(
                context.getString(R.string.window_start_failed),
                packageName
            )
            onAppChanged?.invoke(null)
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic()
            .setLaunchDisplayId(display.display.displayId)
            .setLaunchBounds(Rect(0, 0, displayWidth, displayHeight))
        try {
            context.startActivity(launchIntent, options.toBundle())
            currentPackage = packageName
            content.appTexture.visibility = VISIBLE
            content.appPlaceholder.visibility = GONE
            onAppChanged?.invoke(label)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to launch $packageName in window", error)
            showPlaceholder(
                context.getString(R.string.window_start_failed),
                packageName
            )
            onAppChanged?.invoke(null)
        }
    }

    private fun stopCurrentApp() {
        val packageName = currentPackage ?: requestedPackage ?: return
        val manager = context.getSystemService(ActivityManager::class.java) ?: return
        runCatching {
            manager.javaClass.methods.firstOrNull { method ->
                method.name == "forceStopPackage" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == String::class.java
            }?.invoke(manager, packageName)
        }
    }

    private fun releaseVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        currentPackage = null
    }

    private fun showPlaceholder(title: String, subtitle: String) {
        content.appTexture.visibility = View.GONE
        content.appPlaceholder.visibility = VISIBLE
        content.placeholderTitle.text = title
        content.placeholderSubtitle.text = subtitle
    }

    private fun forwardTouch(event: MotionEvent) {
        val display = virtualDisplay ?: return
        if (displayWidth <= 0 || displayHeight <= 0 || width <= 0 || height <= 0) return
        val scaleX = displayWidth.toFloat() / width.toFloat()
        val scaleY = displayHeight.toFloat() / height.toFloat()
        val setDisplayId = setDisplayIdMethod
        val injectInput = injectInputMethod
        if (setDisplayId == null || injectInput == null) {
            Log.d(TAG, "Input injection is unavailable; system build is required")
            return
        }
        val copy = MotionEvent.obtain(event)
        if (scaleX != 1f || scaleY != 1f) {
            val matrix = Matrix().apply { setScale(scaleX, scaleY) }
            copy.transform(matrix)
        }
        runCatching {
            setDisplayId.invoke(copy, display.display.displayId)
            injectInput.invoke(inputManager, copy, 0)
        }.onFailure { Log.d(TAG, "Unable to forward touch to virtual display", it) }
        copy.recycle()
    }

    private fun resolveLabel(packageName: String): String = runCatching {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private companion object {
        const val TAG = "AppWindowHost"
    }
}

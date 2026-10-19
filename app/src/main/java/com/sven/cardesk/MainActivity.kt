package com.sven.cardesk

import android.Manifest
import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sven.cardesk.core.background.DesktopBackgroundController
import com.sven.cardesk.core.location.LocationProvider
import com.sven.cardesk.core.system.PerformanceMonitor
import com.sven.cardesk.core.weather.WeatherInfo
import com.sven.cardesk.core.weather.WeatherRepository
import com.sven.cardesk.data.AppAdapter
import com.sven.cardesk.data.AppInfo
import com.sven.cardesk.data.AppRepository
import com.sven.cardesk.data.DesktopPreferences
import com.sven.cardesk.databinding.ActivityMainBinding
import com.sven.cardesk.databinding.DockModuleActionsBinding
import com.sven.cardesk.databinding.DockModuleClockBinding
import com.sven.cardesk.databinding.DockModuleConnectivityBinding
import com.sven.cardesk.databinding.DockModuleMediaBinding
import com.sven.cardesk.databinding.DockModulePerformanceBinding
import com.sven.cardesk.databinding.DockModuleWeatherBinding
import com.sven.cardesk.databinding.WindowAppBinding
import com.sven.cardesk.utils.DisplayUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: DesktopPreferences
    private lateinit var backgroundController: DesktopBackgroundController
    private lateinit var appAdapter: AppAdapter
    private val appRepository by lazy { AppRepository(this) }

    private lateinit var windowBindings: List<WindowAppBinding>
    private lateinit var dockBindings: DockBindings
    private val dockDividers = mutableListOf<View>()
    private var targetWindowIndex = 0
    private var allApps: List<AppInfo> = emptyList()
    private var isDrawerOpen = false
    private var isPlaying = true
    private var isDockVisible = true
    private var fullscreenWindowIndex: Int? = null
    private var dockAnimator: ValueAnimator? = null
    private var weatherJob: Job? = null
    private var lastWeather: WeatherInfo? = null
    private var currentCoordinates = LocationProvider.defaultLocation()

    private val handler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
    private val performanceMonitor by lazy { PerformanceMonitor(this) }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    private val wifiManager: WifiManager? by lazy {
        applicationContext.getSystemService(WifiManager::class.java)
    }
    private val bluetoothManager: BluetoothManager? by lazy {
        applicationContext.getSystemService(BluetoothManager::class.java)
    }

    private val statusRunnable = object : Runnable {
        override fun run() {
            updateClockAndDate()
            updatePerformance()
            updateConnectivityStatus()
            handler.postDelayed(this, 2_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = DesktopPreferences(this)
        backgroundController = DesktopBackgroundController(
            this,
            binding.backgroundImage,
            binding.backgroundVideo,
            binding.backgroundScrim
        )
        windowBindings = listOf(binding.windowLeft, binding.windowMiddle, binding.windowRight)

        setupWindows()
        setupCardRow()
        setupDockModules()
        setupDock()
        setupAppDrawer()
        setupBackNavigation()
        applyBottomBarHeight()
        updateClockAndDate()
        updateDockMediaIcon()
        requestRuntimePermissions()
        binding.dock.root.post {
            applyDockScale()
            updateDockTogglePosition()
        }
    }

    override fun onResume() {
        super.onResume()
        backgroundController.apply(preferences)
        applyStoredSecondaryWidth()
        applyBottomBarHeight()
        updateDockMediaIcon()
        updateClockAndDate()
        updateConnectivityStatus()
        updatePerformance()
        requestCurrentLocation()
        startWeatherUpdates()
        handler.removeCallbacks(statusRunnable)
        handler.post(statusRunnable)
        binding.dock.root.post { applyDockScale() }
    }

    override fun onPause() {
        super.onPause()
        weatherJob?.cancel()
        weatherJob = null
        handler.removeCallbacks(statusRunnable)
        backgroundController.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        weatherJob?.cancel()
        dockAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)
        backgroundController.release()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.dock.root.post {
            applyBottomBarHeight()
            applyDockScale()
            updateDockTogglePosition()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RUNTIME_PERMISSIONS) {
            updateConnectivityStatus()
            requestCurrentLocation()
            startWeatherUpdates()
        }
    }

    private fun setupWindows() {
        windowBindings.forEachIndexed { index, window ->
            window.appWindowHost.onChooseApp = { openAppDrawerForWindow(index) }
            window.appWindowHost.onAppChanged = {
                setWindowIcon(window, window.appWindowHost.currentPackageName)
            }
            window.windowDragHandle.setOnClickListener { showWindowMenu(index) }
            setupWindowDrag(index, window)
            setWindowIcon(window, null)
            preferences.windowPackage(index)
                ?.takeIf { it.isNotBlank() }
                ?.let { window.appWindowHost.launchApp(it) }
        }
    }

    private fun setWindowIcon(window: WindowAppBinding, packageName: String?) {
        if (packageName.isNullOrBlank()) {
            window.resizeAppIcon.setImageResource(R.drawable.ic_apps)
            window.resizeAppIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.text_secondary)
            )
            return
        }
        runCatching {
            window.resizeAppIcon.setImageDrawable(packageManager.getApplicationIcon(packageName))
            window.resizeAppIcon.imageTintList = null
        }.onFailure {
            window.resizeAppIcon.setImageResource(R.drawable.ic_apps)
        }
    }

    private fun showWindowMenu(index: Int) {
        val isFullscreen = fullscreenWindowIndex == index
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.window_menu_title)
            .setPositiveButton(
                if (isFullscreen) R.string.window_exit_fullscreen else R.string.window_fullscreen
            ) { _, _ -> toggleFullscreen(index) }
            .setNegativeButton(R.string.window_close) { _, _ -> clearWindow(index) }
            .setNeutralButton(R.string.window_cancel, null)
            .show()
    }

    private fun toggleFullscreen(index: Int) {
        beginWindowResizeTransition()
        if (fullscreenWindowIndex == index) {
            fullscreenWindowIndex = null
            windowBindings.forEach { it.root.visibility = View.VISIBLE }
            binding.handleLeft.visibility = View.VISIBLE
            binding.handleRight.visibility = View.VISIBLE
            binding.cardRow.setRatios(preferences.cardRatios)
        } else {
            fullscreenWindowIndex = index
            windowBindings.forEachIndexed { windowIndex, window ->
                window.root.visibility = if (windowIndex == index) View.VISIBLE else View.GONE
            }
            binding.handleLeft.visibility = View.GONE
            binding.handleRight.visibility = View.GONE
        }
        binding.cardRow.requestLayout()
        finishWindowResizeTransition()
    }

    private fun beginWindowResizeTransition() {
        windowBindings.forEach { it.appWindowHost.setResizeDeferred(true) }
        setResizeOverlaysVisible(true)
    }

    private fun finishWindowResizeTransition() {
        binding.cardRow.post {
            windowBindings.forEach { it.appWindowHost.setResizeDeferred(false) }
            setResizeOverlaysVisible(false)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupWindowDrag(index: Int, window: WindowAppBinding) {
        val handle = window.windowDragHandle
        var startRawX = 0f
        var dragging = false
        var moved = false
        val touchSlop = dp(6f)

        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    dragging = true
                    moved = false
                    window.root.elevation = dpFloat(18f)
                    window.root.translationZ = dpFloat(18f)
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) {
                        false
                    } else {
                        val delta = event.rawX - startRawX
                        if (abs(delta) > touchSlop) {
                            moved = true
                        }
                        window.root.translationX = delta
                        true
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        false
                    } else {
                        dragging = false
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        val delta = window.root.translationX
                        window.root.animate().translationX(0f).setDuration(160L).start()
                        window.root.elevation = 0f
                        window.root.translationZ = 0f
                        if (moved && abs(delta) > dpFloat(10f)) {
                            trySwapWindow(index, delta)
                        }
                        if (!moved) {
                            view.performClick()
                        }
                        true
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    moved = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    window.root.animate().translationX(0f).setDuration(120L).start()
                    window.root.elevation = 0f
                    window.root.translationZ = 0f
                    true
                }

                else -> false
            }
        }
    }

    private fun trySwapWindow(index: Int, translationX: Float) {
        if (windowBindings.any { it.root.width <= 0 }) return
        val card = windowBindings[index].root
        val movedLeft = card.left + translationX
        val movedRight = movedLeft + card.width

        var bestIndex = -1
        var bestOverlap = 0f
        windowBindings.forEachIndexed { otherIndex, other ->
            if (otherIndex == index) return@forEachIndexed
            val otherLeft = other.root.left.toFloat()
            val otherRight = other.root.right.toFloat()
            val overlap = min(movedRight, otherRight) - max(movedLeft, otherLeft)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestIndex = otherIndex
            }
        }
        if (bestIndex < 0) return

        val threshold = min(card.width, windowBindings[bestIndex].root.width) / 2f
        if (bestOverlap >= threshold) {
            swapWindowContents(index, bestIndex)
        }
    }

    private fun swapWindowContents(first: Int, second: Int) {
        val firstPackage = preferences.windowPackage(first)
        val secondPackage = preferences.windowPackage(second)
        preferences.setWindowPackage(first, secondPackage)
        preferences.setWindowPackage(second, firstPackage)
        applyWindowPackage(first, secondPackage)
        applyWindowPackage(second, firstPackage)
    }

    private fun applyWindowPackage(index: Int, packageName: String?) {
        val window = windowBindings[index]
        if (packageName.isNullOrBlank()) {
            window.appWindowHost.clearApp()
            setWindowIcon(window, null)
        } else {
            window.appWindowHost.launchApp(packageName)
            setWindowIcon(window, packageName)
        }
    }

    private fun clearWindow(index: Int) {
        if (fullscreenWindowIndex == index) {
            toggleFullscreen(index)
        }
        val window = windowBindings[index]
        window.appWindowHost.clearApp()
        preferences.setWindowPackage(index, null)
        setWindowIcon(window, null)
    }

    private data class DockBindings(
        val weather: DockModuleWeatherBinding,
        val actions: DockModuleActionsBinding,
        val connectivity: DockModuleConnectivityBinding,
        val performance: DockModulePerformanceBinding,
        val media: DockModuleMediaBinding,
        val clock: DockModuleClockBinding
    )

    private fun setupDockModules() {
        val bar = binding.dock.dockBar
        bar.removeAllViews()
        dockDividers.clear()

        val weather = DockModuleWeatherBinding.inflate(layoutInflater, bar, false)
        val actions = DockModuleActionsBinding.inflate(layoutInflater, bar, false)
        val connectivity = DockModuleConnectivityBinding.inflate(layoutInflater, bar, false)
        val performance = DockModulePerformanceBinding.inflate(layoutInflater, bar, false)
        val media = DockModuleMediaBinding.inflate(layoutInflater, bar, false)
        val clock = DockModuleClockBinding.inflate(layoutInflater, bar, false)

        addDockModule(bar, actions.root, 0f)
        addDockDivider(bar)
        addDockModule(bar, connectivity.root, 0f)
        addDockDivider(bar)
        addDockModule(bar, performance.root, 0f)
        addDockDivider(bar)
        addDockModule(bar, media.root, 1f)
        addDockDivider(bar)
        addDockModule(bar, weather.root, 0f)
        addDockDivider(bar)
        addDockModule(bar, clock.root, 0f)

        dockBindings = DockBindings(
            weather = weather,
            actions = actions,
            connectivity = connectivity,
            performance = performance,
            media = media,
            clock = clock
        )
    }

    private fun addDockModule(parent: LinearLayout, view: View, weight: Float) {
        val params = LinearLayout.LayoutParams(
            if (weight > 0f) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            weight
        )
        parent.addView(view, params)
    }

    private fun addDockDivider(parent: LinearLayout) {
        val divider = View(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider))
        }
        val params = LinearLayout.LayoutParams(dp(1), dp(32))
        params.marginStart = dp(8)
        params.marginEnd = dp(8)
        parent.addView(divider, params)
        dockDividers.add(divider)
    }

    private fun updateDockDividerHeights(heightPx: Int) {
        val dividerHeight = (heightPx * 0.4f).roundToInt().coerceAtLeast(1)
        dockDividers.forEach { divider ->
            val params = divider.layoutParams
            params.height = dividerHeight
            divider.layoutParams = params
        }
    }

    private fun setupCardRow() {
        binding.cardRow.setRatios(preferences.cardRatios)
        binding.cardRow.onWidthsChanged = { ratios, isFinal ->
            setResizeOverlaysVisible(!isFinal)
            if (isFinal) {
                preferences.cardRatios = ratios
                preferences.secondaryWidthRatio = ratios[2]
            }
        }
    }

    private fun setResizeOverlaysVisible(visible: Boolean) {
        windowBindings.forEach { window ->
            window.resizeOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun applyStoredSecondaryWidth() {
        val stored = preferences.secondaryWidthRatio
        if (abs(binding.cardRow.secondaryRatio - stored) > 0.01f) {
            binding.cardRow.applySecondaryRatio(stored)
        }
    }

    private fun setupDock() {
        dockBindings.actions.dockMenu.setOnClickListener { openAppDrawerForNextWindow() }
        dockBindings.actions.dockSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        dockBindings.media.dockPrevious.setOnClickListener { showComingSoon() }
        dockBindings.media.dockNext.setOnClickListener { showComingSoon() }
        dockBindings.media.dockPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            updateDockMediaIcon()
        }
        dockBindings.connectivity.dockVolumeDown.setOnClickListener { adjustVolume(-1) }
        dockBindings.connectivity.dockVolumeUp.setOnClickListener { adjustVolume(1) }
        dockBindings.connectivity.dockBluetooth.setOnClickListener { toggleBluetooth() }
        dockBindings.connectivity.dockBluetooth.setOnLongClickListener {
            openBluetoothSettings()
            true
        }
        dockBindings.connectivity.dockWifi.setOnClickListener { toggleWifi() }
        dockBindings.connectivity.dockWifi.setOnLongClickListener {
            openWifiSettings()
            true
        }
        binding.dockToggle.setOnClickListener { toggleDock() }
        updateDockMediaIcon()
    }

    private fun toggleDock() {
        dockAnimator?.cancel()
        val root = binding.dock.root
        val toggle = binding.dockToggle
        val targetHeight = dp(preferences.bottomBarHeightDp)
        val fromVisible = isDockVisible
        val startHeight = if (fromVisible) {
            root.height.takeIf { it > 0 } ?: targetHeight
        } else {
            0
        }
        val rootParams = root.layoutParams as? LinearLayout.LayoutParams ?: return
        val toggleParams = toggle.layoutParams as? FrameLayout.LayoutParams ?: return

        val toggleHeight = toggle.height.takeIf { it > 0 } ?: dp(32)
        val startMargin = toggleParams.bottomMargin
        val endMargin = if (fromVisible) {
            dp(6)
        } else {
            (targetHeight - toggleHeight).coerceAtLeast(0)
        }
        val startRotation = toggle.rotation
        val endRotation = if (fromVisible) 180f else 0f

        beginWindowResizeTransition()
        if (!fromVisible) {
            setCardRowBottomGap(0)
            isDockVisible = true
            root.visibility = View.VISIBLE
            rootParams.height = 0
            root.layoutParams = rootParams
            root.alpha = 0f
            root.translationY = targetHeight.toFloat()
            toggle.rotation = 180f
        }

        dockAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val height = if (fromVisible) {
                    startHeight * (1f - fraction)
                } else {
                    targetHeight * fraction
                }
                rootParams.height = height.roundToInt().coerceAtLeast(0)
                root.layoutParams = rootParams
                root.translationY = if (fromVisible) {
                    startHeight * fraction
                } else {
                    targetHeight * (1f - fraction)
                }
                root.alpha = if (fromVisible) 1f - fraction else fraction
                toggleParams.bottomMargin =
                    (startMargin + (endMargin - startMargin) * fraction).roundToInt()
                toggle.layoutParams = toggleParams
                toggle.rotation = startRotation + (endRotation - startRotation) * fraction
                updateDockDividerHeights(rootParams.height)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (fromVisible) {
                        root.visibility = View.GONE
                        root.alpha = 1f
                        root.translationY = 0f
                        rootParams.height = 0
                        root.layoutParams = rootParams
                        isDockVisible = false
                        toggle.contentDescription = getString(R.string.dock_expand)
                        setCardRowBottomGap(42)
                    } else {
                        root.visibility = View.VISIBLE
                        root.alpha = 1f
                        root.translationY = 0f
                        rootParams.height = targetHeight
                        root.layoutParams = rootParams
                        isDockVisible = true
                        toggle.contentDescription = getString(R.string.dock_collapse)
                        setCardRowBottomGap(0)
                    }
                    dockAnimator = null
                    updateDockTogglePosition()
                    applyDockScale()
                    finishWindowResizeTransition()
                }
            })
            start()
        }
    }

    private fun applyBottomBarHeight() {
        val targetHeight = dp(preferences.bottomBarHeightDp)
        if (isDockVisible && dockAnimator?.isRunning != true) {
            val root = binding.dock.root
            val params = root.layoutParams as? LinearLayout.LayoutParams ?: return
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = targetHeight
            root.layoutParams = params
            root.requestLayout()
            updateDockDividerHeights(targetHeight)
        }
        updateDockTogglePosition()
        binding.dock.root.post { applyDockScale() }
    }

    private fun updateDockTogglePosition() {
        if (dockAnimator?.isRunning == true) return
        val params = binding.dockToggle.layoutParams as? FrameLayout.LayoutParams ?: return
        val targetHeight = dp(preferences.bottomBarHeightDp)
        val toggleHeight = binding.dockToggle.height.takeIf { it > 0 } ?: dp(32)
        params.bottomMargin = if (isDockVisible) {
            (targetHeight - toggleHeight).coerceAtLeast(0)
        } else {
            dp(6)
        }
        binding.dockToggle.layoutParams = params
    }

    private fun setCardRowBottomGap(gapDp: Int) {
        val params = binding.cardRow.layoutParams as? LinearLayout.LayoutParams ?: return
        val gap = dp(gapDp)
        if (params.bottomMargin != gap) {
            params.bottomMargin = gap
            binding.cardRow.layoutParams = params
            binding.cardRow.requestLayout()
        }
    }

    private fun applyDockScale() {
        val dock = binding.dock
        val heightPx = dock.root.height.takeIf { it > 0 } ?: dp(preferences.bottomBarHeightDp)
        val heightScale = heightPx / dpFloat(84f)
        val widthScale = resources.displayMetrics.widthPixels / dpFloat(900f)
        val scale = min(heightScale, widthScale).coerceIn(0.42f, 1.4f)
        updateDockDividerHeights(heightPx)

        resizeView(dockBindings.actions.dockMenu, 44, 44, scale)
        resizeView(dockBindings.actions.dockSettings, 44, 44, scale)
        resizeView(dockBindings.weather.dockWeatherIconBg, 38, 38, scale)
        resizeView(dockBindings.media.dockAlbumArt, 40, 40, scale)
        resizeView(dockBindings.media.dockPrevious, 30, 30, scale)
        resizeView(dockBindings.media.dockPlayPause, 30, 30, scale)
        resizeView(dockBindings.media.dockNext, 30, 30, scale)
        resizeView(dockBindings.connectivity.dockVolumeDown, 40, 40, scale)
        resizeView(dockBindings.connectivity.dockVolumeUp, 40, 40, scale)
        resizeView(dockBindings.connectivity.dockBluetooth, 40, 40, scale)
        resizeView(dockBindings.connectivity.dockWifi, 40, 40, scale)
        resizeView(dockBindings.performance.cpuGauge, 58, 44, scale)
        resizeView(dockBindings.performance.memGauge, 58, 44, scale)
        resizeView(dockBindings.performance.tempGauge, 58, 44, scale)

        resizeIcon(dockBindings.weather.dockWeatherIcon, 19f, scale)
        (dockBindings.media.dockAlbumArt.getChildAt(0) as? ImageView)?.let { resizeIcon(it, 20f, scale) }

        setTextSize(dockBindings.media.dockMediaTitle, 13f, scale)
        setTextSize(dockBindings.media.dockMediaArtist, 10f, scale)
        setTextSize(dockBindings.weather.dockWeatherTemp, 13f, scale)
        setTextSize(dockBindings.weather.dockWeatherDesc, 9f, scale)
        setTextSize(dockBindings.clock.dockDate, 10f, scale)
        setTextSize(dockBindings.clock.dockClock, 26f, scale)

        val horizontalPadding = dp(6f * scale)
        dock.dockBar.setPadding(horizontalPadding, 0, horizontalPadding, 0)
    }

    private fun resizeView(
        view: View,
        widthDp: Int,
        heightDp: Int,
        scale: Float
    ) {
        val params = view.layoutParams ?: return
        params.width = dp(widthDp * scale)
        params.height = dp(heightDp * scale)
        view.layoutParams = params
        if (view is ImageView) {
            val padding = params.width / 4
            view.setPadding(padding, padding, padding, padding)
        }
    }

    private fun resizeIcon(view: View, baseDp: Float, scale: Float) {
        val params = view.layoutParams ?: return
        params.width = dp(baseDp * scale)
        params.height = dp(baseDp * scale)
        view.layoutParams = params
    }

    private fun setTextSize(view: TextView, baseSp: Float, scale: Float) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp * scale)
    }

    private fun adjustVolume(direction: Int) {
        val manager = audioManager ?: return
        manager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (!LocationProvider.hasPermission(this)) {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
            permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (permissions.isNotEmpty()) {
            requestPermissions(
                permissions.toTypedArray(),
                REQUEST_RUNTIME_PERMISSIONS
            )
        }
    }

    private fun requestCurrentLocation() {
        LocationProvider.requestCurrent(this) { coordinates ->
            if (coordinates != null) {
                currentCoordinates = coordinates
                lifecycleScope.launch { fetchWeatherOnce() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun toggleBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            toast(getString(R.string.dock_bt_unavailable))
            openBluetoothSettings()
            return
        }
        val adapter = runCatching { bluetoothManager?.adapter }.getOrNull()
        if (adapter == null) {
            toast(getString(R.string.dock_bt_unavailable))
            return
        }
        val enable = runCatching { !adapter.isEnabled }.getOrDefault(true)
        val changed = runCatching {
            if (enable) adapter.enable() else adapter.disable()
        }.getOrDefault(false)
        if (!changed) {
            toast(getString(R.string.dock_toggle_failed))
            openBluetoothSettings()
        } else {
            handler.postDelayed({ updateConnectivityStatus() }, 500L)
        }
    }

    @Suppress("DEPRECATION")
    private fun toggleWifi() {
        val wifi = wifiManager
        if (wifi == null) {
            toast(getString(R.string.dock_wifi_unavailable))
            return
        }
        val enable = runCatching { !wifi.isWifiEnabled }.getOrDefault(true)
        val changed = runCatching { wifi.setWifiEnabled(enable) }.getOrDefault(false)
        if (!changed) {
            toast(getString(R.string.dock_toggle_failed))
            openWifiSettings()
        } else {
            handler.postDelayed({ updateConnectivityStatus() }, 500L)
        }
    }

    private fun startWeatherUpdates() {
        weatherJob?.cancel()
        weatherJob = lifecycleScope.launch {
            while (isActive) {
                LocationProvider.getLastKnown(this@MainActivity)?.let {
                    currentCoordinates = it
                }
                fetchWeatherOnce()
                delay(if (lastWeather == null) 60_000L else WEATHER_REFRESH_MS)
            }
        }
    }

    private suspend fun fetchWeatherOnce() {
        val weather = WeatherRepository.fetchCurrent(
            this,
            currentCoordinates.latitude,
            currentCoordinates.longitude
        )
        if (weather != null) {
            lastWeather = weather
            updateWeatherUi(weather)
        } else if (lastWeather == null) {
            dockBindings.weather.dockWeatherTemp.text = getString(R.string.weather_unavailable)
            dockBindings.weather.dockWeatherDesc.text = getString(R.string.weather_unavailable)
        }
    }

    private fun updateWeatherUi(weather: WeatherInfo) {
        val isSunny = weather.iconRes == R.drawable.ic_sun
        dockBindings.weather.dockWeatherTemp.text =
            getString(R.string.weather_temperature, weather.temperatureC)
        dockBindings.weather.dockWeatherDesc.text = weather.description
        dockBindings.weather.dockWeatherIcon.setImageResource(weather.iconRes)
        dockBindings.weather.dockWeatherIcon.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (isSunny) R.color.black else R.color.white)
        )
        val background = when (weather.iconRes) {
            R.drawable.ic_sun -> R.color.accent_yellow
            R.drawable.ic_weather_thunder -> R.color.accent_purple
            R.drawable.ic_weather_rain -> R.color.accent_blue
            R.drawable.ic_weather_snow -> R.color.accent_cyan
            else -> R.color.accent_gray
        }
        dockBindings.weather.dockWeatherIconBg.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, background)
        )
        dockBindings.weather.dockWeatherIcon.contentDescription = getString(
            R.string.weather_description_city,
            getString(R.string.weather_temperature, weather.temperatureC),
            weather.description
        )
    }

    private fun openWifiSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }.onFailure {
            toast(getString(R.string.dock_wifi_unavailable))
        }
    }

    private fun openBluetoothSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }.onFailure {
            toast(getString(R.string.dock_bt_unavailable))
        }
    }

    private fun setupAppDrawer() {
        appAdapter = AppAdapter { app -> launchApp(app) }
        val spanCount = (resources.configuration.screenWidthDp / 130).coerceIn(4, 8)
        binding.appGrid.layoutManager = GridLayoutManager(this, spanCount)
        binding.appGrid.adapter = appAdapter

        binding.appDrawerClose.setOnClickListener { closeAppDrawer() }
        binding.appDrawer.setOnClickListener { closeAppDrawer() }
        binding.appSearch.doAfterTextChanged { filterApps(it?.toString().orEmpty()) }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isDrawerOpen) {
                    closeAppDrawer()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun openAppDrawerForNextWindow() {
        val emptyWindow = (0..2).firstOrNull {
            preferences.windowPackage(it).isNullOrBlank()
        }
        openAppDrawerForWindow(emptyWindow ?: targetWindowIndex)
    }

    private fun openAppDrawerForWindow(index: Int) {
        targetWindowIndex = index.coerceIn(0, 2)
        binding.appDrawerTitle.text =
            getString(R.string.apps_title_window, targetWindowIndex + 1)
        openAppDrawer()
    }

    private fun openAppDrawer() {
        if (isDrawerOpen) return
        isDrawerOpen = true
        binding.appDrawer.animate().cancel()
        binding.appDrawer.visibility = View.VISIBLE
        binding.appDrawer.alpha = 0f
        binding.appDrawer.animate().alpha(1f).setDuration(180L).start()
        binding.appSearch.setText("")
        loadAppsIfNeeded()
    }

    private fun closeAppDrawer() {
        if (!isDrawerOpen) return
        isDrawerOpen = false
        hideKeyboard()
        binding.appDrawer.animate().cancel()
        binding.appDrawer.animate()
            .alpha(0f)
            .setDuration(150L)
            .withEndAction { binding.appDrawer.visibility = View.GONE }
            .start()
    }

    private fun loadAppsIfNeeded() {
        if (allApps.isNotEmpty()) return
        lifecycleScope.launch {
            val apps = appRepository.loadLaunchableApps()
            allApps = apps
            filterApps(binding.appSearch.text?.toString().orEmpty())
        }
    }

    private fun filterApps(query: String) {
        val filtered = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter { it.label.contains(query, ignoreCase = true) }
        }
        appAdapter.submitList(filtered)
        binding.appsEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun launchApp(app: AppInfo) {
        val index = targetWindowIndex.coerceIn(0, 2)
        windowBindings[index].appWindowHost.launchApp(app.packageName, app.label)
        preferences.setWindowPackage(index, app.packageName)
        setWindowIcon(windowBindings[index], app.packageName)
        closeAppDrawer()
        toast(getString(R.string.apps_launched_window, index + 1, app.label))
    }

    private fun updateClockAndDate() {
        val now = Date()
        dockBindings.clock.dockClock.text = clockFormat.format(now)
        dockBindings.clock.dockDate.text = dateFormat.format(now)
    }

    private fun updatePerformance() {
        val snapshot = performanceMonitor.sample()
        val cpu = snapshot.cpuPercent
        dockBindings.performance.cpuGauge.setValue(cpu ?: 0, cpu?.let { "$it%" } ?: "--")
        val memory = snapshot.memoryPercent
        dockBindings.performance.memGauge.setValue(memory ?: 0, memory?.let { "$it%" } ?: "--")
        val temperature = snapshot.temperatureC?.roundToInt()?.coerceIn(0, 100)
        dockBindings.performance.tempGauge.setValue(
            temperature ?: 0,
            temperature?.let { "$it°C" } ?: "--°C"
        )
    }

    private fun updateConnectivityStatus() {
        val wifi = wifiManager
        val wifiEnabled = runCatching { wifi?.isWifiEnabled == true }.getOrDefault(false)
        applyToggleState(
            dockBindings.connectivity.dockWifi,
            wifiEnabled,
            R.color.accent_blue,
            if (wifi == null) R.string.dock_wifi_unavailable else if (wifiEnabled) {
                R.string.dock_wifi_on
            } else {
                R.string.dock_wifi_off
            }
        )

        val adapter = runCatching { bluetoothManager?.adapter }.getOrNull()
        val bluetoothEnabled = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        applyToggleState(
            dockBindings.connectivity.dockBluetooth,
            bluetoothEnabled,
            R.color.accent_blue,
            if (adapter == null) R.string.dock_bt_unavailable else if (bluetoothEnabled) {
                R.string.dock_bt_on
            } else {
                R.string.dock_bt_off
            }
        )
    }

    private fun applyToggleState(
        button: ImageView,
        enabled: Boolean,
        activeBackground: Int,
        descriptionRes: Int
    ) {
        button.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (enabled) activeBackground else R.color.card_surface_alt
            )
        )
        button.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.white else R.color.text_tertiary
            )
        )
        button.contentDescription = getString(descriptionRes)
    }

    private fun updateDockMediaIcon() {
        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val description = getString(if (isPlaying) R.string.media_pause else R.string.media_play)
        dockBindings.media.dockPlayPause.setImageResource(icon)
        dockBindings.media.dockPlayPause.contentDescription = description
    }

    private fun showPowerDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.power_dialog_title)
            .setMessage(R.string.power_dialog_message)
            .setPositiveButton(R.string.power_dialog_ok, null)
            .show()
    }

    private fun showComingSoon() {
        toast(getString(R.string.feature_coming_soon))
    }

    private fun hideKeyboard() {
        val inputMethodManager = getSystemService(InputMethodManager::class.java) ?: return
        inputMethodManager.hideSoftInputFromWindow(binding.appSearch.windowToken, 0)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = DisplayUtils.dip2px(value.toFloat())

    private fun dp(value: Float): Int = DisplayUtils.dip2px(value)

    private fun dpFloat(value: Float): Float = DisplayUtils.dip2pxF(value)

    private companion object {
        const val REQUEST_RUNTIME_PERMISSIONS = 1001
        const val WEATHER_REFRESH_MS = 10 * 60 * 1000L
    }
}

package com.sven.cardesk

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.media.AudioManager
import android.net.wifi.WifiManager
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
import android.widget.ImageButton
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
import com.sven.cardesk.core.system.PerformanceMonitor
import com.sven.cardesk.data.AppAdapter
import com.sven.cardesk.data.AppInfo
import com.sven.cardesk.data.AppRepository
import com.sven.cardesk.data.DesktopPreferences
import com.sven.cardesk.databinding.ActivityMainBinding
import com.sven.cardesk.databinding.WindowAppBinding
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
    private var targetWindowIndex = 0
    private var allApps: List<AppInfo> = emptyList()
    private var isDrawerOpen = false
    private var isPlaying = true
    private var isDockVisible = true
    private var dockAnimator: ValueAnimator? = null

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
            updateVolume()
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
        setupDock()
        setupAppDrawer()
        setupBackNavigation()
        applyBottomBarHeight()
        updateClockAndDate()
        updateDockMediaIcon()
        updateVolume()
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
        updateVolume()
        handler.removeCallbacks(statusRunnable)
        handler.post(statusRunnable)
        binding.dock.root.post { applyDockScale() }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusRunnable)
        backgroundController.release()
    }

    override fun onDestroy() {
        super.onDestroy()
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

    private fun setupWindows() {
        windowBindings.forEachIndexed { index, window ->
            window.windowTitle.text = getString(R.string.window_default_title, index + 1)
            window.appWindowHost.onChooseApp = { openAppDrawerForWindow(index) }
            window.appWindowHost.onAppChanged = { label ->
                window.windowTitle.text =
                    label ?: getString(R.string.window_default_title, index + 1)
                setWindowIcon(window, window.appWindowHost.currentPackageName)
            }
            window.windowClose.setOnClickListener { clearWindow(index) }
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

    private fun setupWindowDrag(index: Int, window: WindowAppBinding) {
        val handle = window.windowDragHandle
        var startRawX = 0f
        var dragging = false

        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    dragging = true
                    window.root.elevation = dpFloat(18f)
                    window.root.translationZ = dpFloat(18f)
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) {
                        false
                    } else {
                        window.root.translationX = event.rawX - startRawX
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
                        if (abs(delta) > dpFloat(10f)) {
                            trySwapWindow(index, delta)
                        }
                        view.performClick()
                        true
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
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
            window.windowTitle.text = getString(R.string.window_default_title, index + 1)
            setWindowIcon(window, null)
        } else {
            window.appWindowHost.launchApp(packageName)
            setWindowIcon(window, packageName)
        }
    }

    private fun clearWindow(index: Int) {
        val window = windowBindings[index]
        window.appWindowHost.clearApp()
        preferences.setWindowPackage(index, null)
        window.windowTitle.text = getString(R.string.window_default_title, index + 1)
        setWindowIcon(window, null)
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
        binding.dock.dockMenu.setOnClickListener { openAppDrawerForNextWindow() }
        binding.dock.dockSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.dock.dockPrevious.setOnClickListener { showComingSoon() }
        binding.dock.dockNext.setOnClickListener { showComingSoon() }
        binding.dock.dockPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            updateDockMediaIcon()
        }
        binding.dock.dockVolumeDown.setOnClickListener { adjustVolume(-1) }
        binding.dock.dockVolumeUp.setOnClickListener { adjustVolume(1) }
        binding.dock.dockBluetooth.setOnClickListener { openBluetoothSettings() }
        binding.dock.dockWifi.setOnClickListener { openWifiSettings() }
        binding.dockToggle.setOnClickListener { toggleDock() }
        updateDockMediaIcon()
        updateVolume()
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

        val startMargin = toggleParams.bottomMargin
        val endMargin = if (fromVisible) dp(8) else (targetHeight - dp(10)).coerceAtLeast(dp(8))
        val startRotation = toggle.rotation
        val endRotation = if (fromVisible) 180f else 0f

        if (!fromVisible) {
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
                    } else {
                        root.visibility = View.VISIBLE
                        root.alpha = 1f
                        root.translationY = 0f
                        rootParams.height = targetHeight
                        root.layoutParams = rootParams
                        isDockVisible = true
                        toggle.contentDescription = getString(R.string.dock_collapse)
                    }
                    dockAnimator = null
                    updateDockTogglePosition()
                    applyDockScale()
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
        }
        updateDockTogglePosition()
        binding.dock.root.post { applyDockScale() }
    }

    private fun updateDockTogglePosition() {
        if (dockAnimator?.isRunning == true) return
        val params = binding.dockToggle.layoutParams as? FrameLayout.LayoutParams ?: return
        val targetHeight = dp(preferences.bottomBarHeightDp)
        params.bottomMargin = if (isDockVisible) {
            (targetHeight - dp(10)).coerceAtLeast(dp(8))
        } else {
            dp(8)
        }
        binding.dockToggle.layoutParams = params
    }

    private fun applyDockScale() {
        val dock = binding.dock
        val heightPx = dock.root.height.takeIf { it > 0 } ?: dp(preferences.bottomBarHeightDp)
        val heightScale = heightPx / dpFloat(104f)
        val widthScale = resources.displayMetrics.widthPixels / 1280f
        val scale = min(heightScale, widthScale * 1.15f).coerceIn(0.62f, 1.6f)

        resizeView(dock.dockMenu, 44, 44, scale, 10f)
        resizeView(dock.dockSettings, 44, 44, scale, 10f)
        resizeView(dock.dockAlbumArt, 44, 44, scale, 0f)
        resizeView(dock.dockPrevious, 30, 30, scale, 0f)
        resizeView(dock.dockPlayPause, 30, 30, scale, 0f)
        resizeView(dock.dockNext, 30, 30, scale, 0f)
        resizeView(dock.dockVolumeDown, 30, 30, scale, 6f)
        resizeView(dock.dockVolumeUp, 30, 30, scale, 6f)
        resizeView(dock.dockBluetooth, 38, 38, scale, 9f)
        resizeView(dock.dockWifi, 38, 38, scale, 9f)

        (dock.dockAlbumArt.getChildAt(0) as? ImageView)?.let { icon ->
            val params = icon.layoutParams ?: return@let
            params.width = dp(20f * scale)
            params.height = dp(20f * scale)
            icon.layoutParams = params
        }

        setTextSize(dock.dockMediaTitle, 13f, scale)
        setTextSize(dock.dockMediaArtist, 10f, scale)
        setTextSize(dock.dockCpu, 11f, scale)
        setTextSize(dock.dockGpu, 11f, scale)
        setTextSize(dock.dockMem, 11f, scale)
        setTextSize(dock.dockTemp, 11f, scale)
        setTextSize(dock.dockBtStatus, 9f, scale)
        setTextSize(dock.dockWifiStatus, 9f, scale)
        setTextSize(dock.dockDate, 10f, scale)
        setTextSize(dock.dockClock, 20f, scale)
        setTextSize(dock.dockVolume, 11f, scale)

        val statusWidth = dp((72f * scale).coerceAtLeast(56f))
        listOf(dock.dockBtStatus, dock.dockWifiStatus).forEach { status ->
            status.layoutParams = status.layoutParams.apply { width = statusWidth }
        }
        val performanceMinWidth = dp((68f * scale).coerceAtLeast(52f))
        listOf(dock.dockCpu, dock.dockGpu, dock.dockMem, dock.dockTemp).forEach { value ->
            value.minWidth = performanceMinWidth
        }

        val horizontalPadding = dp(8f * scale)
        dock.dockBar.setPadding(horizontalPadding, 0, horizontalPadding, 0)
    }

    private fun resizeView(
        view: View,
        widthDp: Int,
        heightDp: Int,
        scale: Float,
        paddingDp: Float
    ) {
        val params = view.layoutParams ?: return
        params.width = dp(widthDp * scale)
        params.height = dp(heightDp * scale)
        view.layoutParams = params
        if (paddingDp > 0f && view is ImageButton) {
            val padding = dp(paddingDp * scale)
            view.setPadding(padding, padding, padding, padding)
        }
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
        updateVolume()
    }

    private fun updateVolume() {
        val manager = audioManager ?: return
        val maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        binding.dock.dockVolume.text = if (maxVolume > 0) {
            "${currentVolume * 100 / maxVolume}%"
        } else {
            "--"
        }
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
        binding.dock.dockClock.text = clockFormat.format(now)
        binding.dock.dockDate.text = dateFormat.format(now)
    }

    private fun updatePerformance() {
        val snapshot = performanceMonitor.sample()
        binding.dock.dockCpu.text =
            "CPU " + (snapshot.cpuPercent?.let { "$it%" } ?: "--")
        binding.dock.dockGpu.text =
            "GPU " + (snapshot.gpuPercent?.let { "$it%" } ?: "--")
        binding.dock.dockMem.text =
            "MEM " + (snapshot.memoryPercent?.let { "$it%" } ?: "--")
        binding.dock.dockTemp.text =
            snapshot.temperatureC?.let { "${it.roundToInt()}°C" } ?: "--°C"
    }

    private fun updateConnectivityStatus() {
        val wifi = wifiManager
        binding.dock.dockWifiStatus.text = when {
            wifi == null -> getString(R.string.dock_wifi_unavailable)
            !wifi.isWifiEnabled -> getString(R.string.dock_wifi_off)
            else -> getString(R.string.dock_wifi_on)
        }

        val adapter = runCatching { bluetoothManager?.adapter }.getOrNull()
        val enabled = runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        binding.dock.dockBtStatus.text = when {
            adapter == null -> getString(R.string.dock_bt_unavailable)
            enabled -> getString(R.string.dock_bt_on)
            else -> getString(R.string.dock_bt_off)
        }
    }

    private fun updateDockMediaIcon() {
        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val description = getString(if (isPlaying) R.string.media_pause else R.string.media_play)
        binding.dock.dockPlayPause.setImageResource(icon)
        binding.dock.dockPlayPause.contentDescription = description
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun dpFloat(value: Float): Float =
        value * resources.displayMetrics.density
}

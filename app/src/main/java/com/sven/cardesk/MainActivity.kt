package com.sven.cardesk

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
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
    private var isDockCollapsed = false

    private val handler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
    private val performanceMonitor by lazy { PerformanceMonitor(this) }

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
        setupDock()
        setupAppDrawer()
        setupBackNavigation()
        applyBottomBarHeight()
        updateClockAndDate()
        updateDockMediaIcon()
        requestBluetoothPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        backgroundController.apply(preferences)
        applyStoredSecondaryWidth()
        applyBottomBarHeight()
        updateDockMediaIcon()
        updateClockAndDate()
        handler.removeCallbacks(statusRunnable)
        handler.post(statusRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusRunnable)
        backgroundController.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        backgroundController.release()
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
        binding.dock.dockMaster.setOnClickListener { toggleDock() }
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
        binding.dock.dockBluetooth.setOnClickListener { toggleBluetooth() }
        binding.dock.dockWifi.setOnClickListener { toggleWifi() }
        updateDockMediaIcon()
    }

    private fun toggleDock() {
        isDockCollapsed = !isDockCollapsed
        val root = binding.dock.root
        val params = root.layoutParams as? LinearLayout.LayoutParams ?: return
        if (isDockCollapsed) {
            binding.dock.dockContent.visibility = View.GONE
            params.width = dp(72)
            params.height = dp(56)
            params.gravity = Gravity.END
            binding.dock.dockMaster.rotation = 180f
            binding.dock.dockMaster.contentDescription = getString(R.string.dock_expand)
        } else {
            binding.dock.dockContent.visibility = View.VISIBLE
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = dp(preferences.bottomBarHeightDp)
            params.gravity = Gravity.NO_GRAVITY
            binding.dock.dockMaster.rotation = 0f
            binding.dock.dockMaster.contentDescription = getString(R.string.dock_collapse)
        }
        root.layoutParams = params
        root.requestLayout()
    }

    private fun applyBottomBarHeight() {
        if (isDockCollapsed) return
        val root = binding.dock.root
        val params = root.layoutParams as? LinearLayout.LayoutParams ?: return
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = dp(preferences.bottomBarHeightDp)
        params.gravity = Gravity.NO_GRAVITY
        root.layoutParams = params
        root.requestLayout()
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

    @Suppress("DEPRECATION")
    private fun toggleWifi() {
        val wifi = wifiManager ?: run {
            toast(getString(R.string.dock_wifi_unavailable))
            return
        }
        val ok = runCatching { wifi.setWifiEnabled(!wifi.isWifiEnabled) }.getOrDefault(false)
        if (!ok) {
            toast(getString(R.string.dock_toggle_failed))
        }
        handler.postDelayed({ updateConnectivityStatus() }, 800L)
    }

    @Suppress("DEPRECATION")
    private fun toggleBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                REQUEST_BLUETOOTH_PERMISSION
            )
            return
        }
        val adapter = runCatching { bluetoothManager?.adapter }.getOrNull() ?: run {
            toast(getString(R.string.dock_bt_unavailable))
            return
        }
        val enabled = runCatching { adapter.isEnabled }.getOrDefault(false)
        val ok = runCatching {
            if (enabled) adapter.disable() else adapter.enable()
        }.getOrDefault(false)
        if (!ok) {
            toast(getString(R.string.dock_toggle_failed))
        }
        handler.postDelayed({ updateConnectivityStatus() }, 1_200L)
    }

    private fun requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPermissions(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
            REQUEST_BLUETOOTH_PERMISSION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSION) {
            updateConnectivityStatus()
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

    private fun dpFloat(value: Float): Float =
        value * resources.displayMetrics.density

    private companion object {
        const val REQUEST_BLUETOOTH_PERMISSION = 1001
    }
}

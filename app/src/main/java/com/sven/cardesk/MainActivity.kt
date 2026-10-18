package com.sven.cardesk

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sven.cardesk.core.background.DesktopBackgroundController
import com.sven.cardesk.data.AppAdapter
import com.sven.cardesk.data.AppInfo
import com.sven.cardesk.data.AppRepository
import com.sven.cardesk.data.DesktopPreferences
import com.sven.cardesk.databinding.ActivityMainBinding
import com.sven.cardesk.databinding.WindowAppBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

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

    private val handler = Handler(Looper.getMainLooper())
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 30_000L)
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
        updateClock()
    }

    override fun onResume() {
        super.onResume()
        backgroundController.apply(preferences)
        applyStoredSecondaryWidth()
        updateDockMediaIcon()
        handler.removeCallbacks(clockRunnable)
        handler.post(clockRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockRunnable)
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
            window.windowChooseApp.setOnClickListener { openAppDrawerForWindow(index) }
            window.appWindowHost.onChooseApp = { openAppDrawerForWindow(index) }
            window.appWindowHost.onAppChanged = { label ->
                window.windowTitle.text =
                    label ?: getString(R.string.window_default_title, index + 1)
            }
            preferences.windowPackage(index)?.let { packageName ->
                window.appWindowHost.launchApp(packageName)
            }
        }
    }

    private fun setupCardRow() {
        binding.cardRow.setRatios(preferences.cardRatios)
        binding.cardRow.onWidthsChanged = { ratios, isFinal ->
            if (isFinal) {
                preferences.cardRatios = ratios
                preferences.secondaryWidthRatio = ratios[2]
            }
        }
    }

    private fun applyStoredSecondaryWidth() {
        val stored = preferences.secondaryWidthRatio
        if (abs(binding.cardRow.secondaryRatio - stored) > 0.01f) {
            binding.cardRow.applySecondaryRatio(stored)
        }
    }

    private fun setupDock() {
        binding.dock.dockSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.dock.dockApps.setOnClickListener { openAppDrawerForNextWindow() }
        binding.dock.dockPower.setOnClickListener { showPowerDialog() }
        binding.dock.dockLayers.setOnClickListener { showComingSoon() }
        binding.dock.dockBrightness.setOnClickListener { showComingSoon() }
        binding.dock.dockRecents.setOnClickListener { showComingSoon() }

        binding.dock.dockPrevious.setOnClickListener { showComingSoon() }
        binding.dock.dockNext.setOnClickListener { showComingSoon() }
        binding.dock.dockPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            updateDockMediaIcon()
        }
        updateDockMediaIcon()
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
        closeAppDrawer()
        toast(getString(R.string.apps_launched_window, index + 1, app.label))
    }

    private fun updateClock() {
        val now = Date()
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        binding.topClock.text = clockFormat.format(now)
        binding.dock.dockClock.text = clockFormat.format(now)
        binding.dateText.text = dateFormat.format(now)
        binding.greetingText.text = getString(
            when {
                hour < 12 -> R.string.greeting_morning
                hour < 18 -> R.string.greeting_afternoon
                else -> R.string.greeting_evening
            }
        )
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
}

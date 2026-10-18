package com.sven.cardesk

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.ImageViewCompat
import com.sven.cardesk.core.display.SecondaryDisplayManager
import com.sven.cardesk.core.theme.ThemeController
import com.sven.cardesk.data.DesktopPreferences
import com.sven.cardesk.databinding.ActivitySettingsBinding
import kotlin.math.roundToInt
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferences: DesktopPreferences

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { applyBackground(it) }
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { applyBackground(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(
            WindowInsetsCompat.Type.systemBars()
        )

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = DesktopPreferences(this)

        binding.settingsToolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        setupTheme()
        setupBackground()
        setupSecondary()
        setupBottomBar()
        setupAbout()
        updateBackgroundUi()
    }

    override fun onResume() {
        super.onResume()
        setupThemeSelection()
        updateSecondaryStatus()
        updateBackgroundUi()
    }

    override fun onPause() {
        super.onPause()
        (binding.backgroundPreviewImage.drawable as? AnimatedImageDrawable)?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        (binding.backgroundPreviewImage.drawable as? AnimatedImageDrawable)?.stop()
    }

    private fun setupTheme() {
        setupThemeSelection()
        binding.themeSwitch.setOnCheckedChangeListener { _, checked ->
            ThemeController.setDarkTheme(this, checked)
        }
    }

    private fun setupThemeSelection() {
        binding.themeSwitch.isChecked = preferences.isDarkTheme
    }

    private fun setupBackground() {
        binding.pickImageButton.setOnClickListener {
            pickImage.launch(arrayOf("image/*"))
        }
        binding.pickVideoButton.setOnClickListener {
            pickVideo.launch(arrayOf("video/*"))
        }
        binding.resetBackgroundButton.setOnClickListener {
            preferences.clearBackground()
            updateBackgroundUi()
        }
    }

    private fun applyBackground(uri: Uri) {
        val mimeType = contentResolver.getType(uri).orEmpty()
        val fileName = displayName(uri)?.lowercase(Locale.getDefault()).orEmpty()
        val type = when {
            mimeType.startsWith("image/") -> DesktopPreferences.BackgroundType.IMAGE
            mimeType.startsWith("video/") -> DesktopPreferences.BackgroundType.VIDEO
            fileName.endsWith(".gif") ||
                fileName.endsWith(".webp") ||
                fileName.endsWith(".png") ||
                fileName.endsWith(".jpg") ||
                fileName.endsWith(".jpeg") -> DesktopPreferences.BackgroundType.IMAGE
            fileName.endsWith(".mp4") ||
                fileName.endsWith(".webm") ||
                fileName.endsWith(".mkv") ||
                fileName.endsWith(".mov") -> DesktopPreferences.BackgroundType.VIDEO
            else -> {
                toast(getString(R.string.settings_background_unavailable))
                return
            }
        }
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        preferences.backgroundUri = uri.toString()
        preferences.backgroundType = type
        updateBackgroundUi()
        val label = displayName(uri) ?: getString(
            if (type == DesktopPreferences.BackgroundType.VIDEO) {
                R.string.settings_background_type_video
            } else {
                R.string.settings_background_type_image
            }
        )
        toast(getString(R.string.settings_background_selected, label))
    }

    private fun updateBackgroundUi() {
        val uri = preferences.backgroundUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val type = preferences.backgroundType
        when {
            uri == null || type == null -> {
                binding.backgroundSummary.text = getString(R.string.settings_background_default)
                binding.backgroundPreviewImage.setImageDrawable(null)
                binding.backgroundPreviewImage.visibility = View.GONE
                binding.backgroundPreviewLabel.visibility = View.VISIBLE
                binding.backgroundPreviewLabel.text = getString(R.string.settings_background_default)
            }

            type == DesktopPreferences.BackgroundType.VIDEO -> {
                binding.backgroundSummary.text = getString(
                    R.string.settings_background_selected,
                    displayName(uri) ?: getString(R.string.settings_background_type_video)
                )
                binding.backgroundPreviewImage.scaleType = ImageView.ScaleType.CENTER
                ImageViewCompat.setImageTintList(
                    binding.backgroundPreviewImage,
                    ColorStateList.valueOf(
                        ContextCompat.getColor(this, R.color.text_secondary)
                    )
                )
                binding.backgroundPreviewImage.setImageResource(R.drawable.ic_video)
                binding.backgroundPreviewImage.visibility = View.VISIBLE
                binding.backgroundPreviewLabel.visibility = View.VISIBLE
                binding.backgroundPreviewLabel.text =
                    displayName(uri) ?: getString(R.string.settings_background_type_video)
            }

            else -> {
                binding.backgroundSummary.text = getString(
                    R.string.settings_background_selected,
                    displayName(uri) ?: getString(R.string.settings_background_type_image)
                )
                binding.backgroundPreviewImage.scaleType = ImageView.ScaleType.CENTER_CROP
                ImageViewCompat.setImageTintList(binding.backgroundPreviewImage, null)
                binding.backgroundPreviewLabel.visibility = View.GONE
                try {
                    val drawable = ImageDecoder.decodeDrawable(
                        ImageDecoder.createSource(contentResolver, uri)
                    )
                    binding.backgroundPreviewImage.setImageDrawable(drawable)
                    binding.backgroundPreviewImage.visibility = View.VISIBLE
                    if (drawable is AnimatedImageDrawable) {
                        drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                        binding.backgroundPreviewImage.post { drawable.start() }
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to decode background preview", error)
                    binding.backgroundPreviewImage.visibility = View.GONE
                    binding.backgroundPreviewLabel.visibility = View.VISIBLE
                    binding.backgroundPreviewLabel.text = getString(
                        R.string.settings_background_unavailable
                    )
                }
            }
        }
    }

    private fun setupSecondary() {
        val ratio = preferences.secondaryWidthRatio
        binding.secondaryWidthSeek.min = 20
        binding.secondaryWidthSeek.max = 80
        binding.secondaryWidthSeek.progress = (ratio * 100f).roundToInt()
        updateSecondaryWidthLabel(binding.secondaryWidthSeek.progress)
        binding.secondaryWidthSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    updateSecondaryWidthLabel(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    preferences.secondaryWidthRatio = seekBar.progress / 100f
                }
            }
        )
        updateSecondaryStatus()
    }

    private fun updateSecondaryWidthLabel(progress: Int) {
        binding.secondaryWidthValue.text =
            getString(R.string.settings_secondary_width_value, progress)
    }

    private fun setupBottomBar() {
        val height = preferences.bottomBarHeightDp
        binding.bottomBarHeightSeek.min = DesktopPreferences.MIN_BOTTOM_BAR_HEIGHT
        binding.bottomBarHeightSeek.max = DesktopPreferences.MAX_BOTTOM_BAR_HEIGHT
        binding.bottomBarHeightSeek.progress = height
        updateBottomBarHeightLabel(height)
        binding.bottomBarHeightSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    updateBottomBarHeightLabel(progress)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    preferences.bottomBarHeightDp = seekBar.progress
                }
            }
        )
    }

    private fun updateBottomBarHeightLabel(height: Int) {
        binding.bottomBarHeightValue.text =
            getString(R.string.settings_bottom_bar_height_value, height)
    }

    private fun updateSecondaryStatus() {
        val info = SecondaryDisplayManager.getDisplayInfo(this)
        val virtualWindows = getString(R.string.settings_virtual_windows, 3)
        binding.secondaryStatusText.text = if (info == null) {
            virtualWindows
        } else {
            virtualWindows + "\n" + getString(
                R.string.settings_secondary_status,
                getString(R.string.settings_secondary_connected_size, info.width, info.height)
            )
        }
    }

    private fun setupAbout() {
        val packageInfo = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }.getOrNull()
        val version = packageInfo?.versionName ?: "1.0"
        binding.versionText.text = getString(R.string.settings_version, version)
        binding.packageText.text = getString(R.string.settings_package, packageName)
    }

    private fun displayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "SettingsActivity"
    }
}

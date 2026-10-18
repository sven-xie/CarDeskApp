package com.sven.cardesk

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sven.cardesk.core.background.DesktopBackgroundController
import com.sven.cardesk.data.DesktopPreferences
import com.sven.cardesk.databinding.ActivitySecondaryLauncherBinding

class SecondaryLauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySecondaryLauncherBinding
    private lateinit var preferences: DesktopPreferences
    private lateinit var backgroundController: DesktopBackgroundController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(
            WindowInsetsCompat.Type.systemBars()
        )
        binding = ActivitySecondaryLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = DesktopPreferences(this)
        backgroundController = DesktopBackgroundController(
            this,
            binding.backgroundImage,
            binding.backgroundVideo,
            binding.backgroundScrim
        )
    }

    override fun onResume() {
        super.onResume()
        backgroundController.apply(preferences)
    }

    override fun onPause() {
        super.onPause()
        backgroundController.release()
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundController.release()
    }
}

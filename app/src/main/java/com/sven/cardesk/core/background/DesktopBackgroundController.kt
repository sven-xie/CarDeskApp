package com.sven.cardesk.core.background

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.ImageView
import com.sven.cardesk.data.DesktopPreferences
import com.sven.cardesk.widget.VideoBackgroundView

class DesktopBackgroundController(
    private val context: Context,
    private val imageView: ImageView,
    private val videoView: VideoBackgroundView,
    private val scrim: View
) {

    fun apply(preferences: DesktopPreferences) {
        val uri = preferences.backgroundUri?.toUriOrNull()
        when {
            uri == null -> showDefault()
            preferences.backgroundType == DesktopPreferences.BackgroundType.VIDEO -> showVideo(uri)
            else -> showImage(uri)
        }
        scrim.visibility = View.VISIBLE
    }

    fun showDefault() {
        stopVideo()
        stopAnimatedImage()
        imageView.setImageDrawable(null)
        imageView.visibility = View.GONE
        videoView.visibility = View.GONE
    }

    fun release() {
        stopVideo()
        stopAnimatedImage()
    }

    private fun showImage(uri: Uri) {
        stopVideo()
        stopAnimatedImage()
        imageView.visibility = View.VISIBLE
        videoView.visibility = View.GONE
        try {
            val drawable: Drawable = ImageDecoder.decodeDrawable(
                ImageDecoder.createSource(context.contentResolver, uri)
            )
            imageView.setImageDrawable(drawable)
            if (drawable is AnimatedImageDrawable) {
                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                imageView.post { drawable.start() }
            }
        } catch (error: Exception) {
            Log.w(TAG, "Unable to decode background image", error)
            showDefault()
        }
    }

    private fun showVideo(uri: Uri) {
        stopAnimatedImage()
        imageView.setImageDrawable(null)
        imageView.visibility = View.GONE
        videoView.visibility = View.VISIBLE
        try {
            videoView.setVideoUri(uri)
        } catch (error: Exception) {
            Log.w(TAG, "Unable to play background video", error)
            videoView.visibility = View.GONE
        }
    }

    private fun stopVideo() {
        videoView.stop()
        videoView.visibility = View.GONE
    }

    private fun stopAnimatedImage() {
        (imageView.drawable as? AnimatedImageDrawable)?.stop()
    }

    private fun String.toUriOrNull(): Uri? = runCatching { Uri.parse(this) }.getOrNull()

    private companion object {
        const val TAG = "DesktopBackground"
    }
}

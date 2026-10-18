package com.sven.cardesk.widget

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import kotlin.math.max

class VideoBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val textureView = TextureView(context)
    private var mediaPlayer: MediaPlayer? = null
    private var outputSurface: Surface? = null
    private var pendingUri: Uri? = null
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        isClickable = false
        isFocusable = false
        addView(
            textureView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                outputSurface = Surface(surfaceTexture)
                startPlayback()
            }

            override fun onSurfaceTextureSizeChanged(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                updateTransform()
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                releasePlayer()
                outputSurface?.release()
                outputSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }
    }

    fun setVideoUri(uri: Uri?) {
        pendingUri = uri
        releasePlayer()
        if (uri != null && textureView.isAvailable) {
            startPlayback()
        }
        visibility = if (uri == null) GONE else VISIBLE
    }

    fun stop() {
        releasePlayer()
    }

    fun clear() {
        pendingUri = null
        releasePlayer()
        textureView.setTransform(null)
        visibility = GONE
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateTransform()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (textureView.isAvailable && outputSurface == null && width > 0 && height > 0) {
            val surfaceTexture = textureView.surfaceTexture ?: return
            outputSurface = Surface(surfaceTexture)
            startPlayback()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        releasePlayer()
        outputSurface?.release()
        outputSurface = null
    }

    private fun startPlayback() {
        val uri = pendingUri ?: return
        val surface = outputSurface ?: return
        val player = MediaPlayer()
        mediaPlayer = player
        player.setSurface(surface)
        player.isLooping = true
        player.setVolume(0f, 0f)
        player.setOnVideoSizeChangedListener { _, width, height ->
            videoWidth = width
            videoHeight = height
            updateTransform()
        }
        player.setOnPreparedListener {
            it.start()
            updateTransform()
        }
        player.setOnErrorListener { _, _, _ ->
            releasePlayer()
            true
        }
        try {
            player.setDataSource(context, uri)
            player.prepareAsync()
            visibility = VISIBLE
        } catch (error: Exception) {
            releasePlayer()
            visibility = GONE
        }
    }

    private fun releasePlayer() {
        val player = mediaPlayer ?: return
        mediaPlayer = null
        runCatching { player.setOnPreparedListener(null) }
        runCatching { player.setOnVideoSizeChangedListener(null) }
        runCatching { player.setOnErrorListener(null) }
        runCatching { player.stop() }
        runCatching { player.release() }
    }

    private fun updateTransform() {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f || videoWidth <= 0 || videoHeight <= 0) {
            return
        }
        val scale = max(viewWidth / videoWidth, viewHeight / videoHeight)
        val dx = (viewWidth - videoWidth * scale) / 2f
        val dy = (viewHeight - videoHeight * scale) / 2f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        textureView.setTransform(matrix)
    }
}

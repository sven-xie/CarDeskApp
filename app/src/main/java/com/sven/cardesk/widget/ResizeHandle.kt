package com.sven.cardesk.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.sven.cardesk.R
import com.sven.cardesk.utils.DisplayUtils
import kotlin.math.min

class ResizeHandle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnDragListener {
        fun onStart(handle: ResizeHandle)
        fun onMove(handle: ResizeHandle, deltaX: Float)
        fun onEnd(handle: ResizeHandle)
    }

    var dragListener: OnDragListener? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var lastRawX = 0f
    private var isDragging = false

    init {
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.drag_handle_desc)
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val widthPx = DisplayUtils.dip2pxF(4f)
        val heightPx = min(height * 0.32f, DisplayUtils.dip2pxF(52f))
        val left = (width - widthPx) / 2f
        val top = (height - heightPx) / 2f
        rect.set(left, top, left + widthPx, top + heightPx)
        paint.color = if (isDragging) {
            ContextCompat.getColor(context, R.color.accent_red)
        } else {
            ContextCompat.getColor(context, R.color.text_tertiary)
        }
        paint.alpha = if (isDragging) 255 else 150
        canvas.drawRoundRect(rect, widthPx / 2f, widthPx / 2f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                lastRawX = event.rawX
                parent?.requestDisallowInterceptTouchEvent(true)
                dragListener?.onStart(this)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                val delta = event.rawX - lastRawX
                lastRawX = event.rawX
                dragListener?.onMove(this, delta)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) return false
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                dragListener?.onEnd(this)
                performClick()
                invalidate()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                dragListener?.onEnd(this)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

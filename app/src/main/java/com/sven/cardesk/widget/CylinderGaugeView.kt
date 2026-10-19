package com.sven.cardesk.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.sven.cardesk.R
import com.sven.cardesk.utils.DisplayUtils
import kotlin.math.min

/**
 * A compact vertical cylinder gauge used in the bottom operation bar.
 * The fill height represents 0..100 percent; the label/value are drawn to the right.
 */
class CylinderGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.card_surface_alt)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val trackRect = RectF()
    private val fillRect = RectF()

    private var label = "CPU"
    private var percent = 0
    private var valueText = "--"

    init {
        if (attrs != null) {
            val typed = context.obtainStyledAttributes(
                attrs,
                R.styleable.CylinderGaugeView,
                defStyleAttr,
                0
            )
            label = typed.getString(R.styleable.CylinderGaugeView_gaugeLabel) ?: label
            fillPaint.color = typed.getColor(
                R.styleable.CylinderGaugeView_gaugeColor,
                ContextCompat.getColor(context, R.color.accent_cyan)
            )
            typed.recycle()
        } else {
            fillPaint.color = ContextCompat.getColor(context, R.color.accent_cyan)
        }
        contentDescription = label
    }

    fun setValue(progressPercent: Int, text: String) {
        percent = progressPercent.coerceIn(0, 100)
        valueText = text
        contentDescription = "$label $text"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val cylinderWidth = min(width * 0.22f, DisplayUtils.dip2pxF(12f)).coerceAtLeast(DisplayUtils.dip2pxF(6f))
        val top = DisplayUtils.dip2pxF(3f)
        val bottom = height - DisplayUtils.dip2pxF(3f)
        if (bottom <= top) return
        val left = DisplayUtils.dip2pxF(1f)
        val radius = cylinderWidth / 2f

        trackRect.set(left, top, left + cylinderWidth, bottom)
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)

        val fillHeight = (bottom - top) * percent / 100f
        if (fillHeight > 0f) {
            fillRect.set(left, bottom - fillHeight, left + cylinderWidth, bottom)
            canvas.drawRoundRect(fillRect, radius, radius, fillPaint)
        }

        val textLeft = left + cylinderWidth + DisplayUtils.dip2pxF(5f)
        labelPaint.textSize = height * 0.22f
        valuePaint.textSize = height * 0.26f
        val labelY = top + height * 0.38f
        val valueY = bottom - height * 0.12f
        canvas.drawText(label, textLeft, labelY, labelPaint)
        canvas.drawText(valueText, textLeft, valueY, valuePaint)
    }
}

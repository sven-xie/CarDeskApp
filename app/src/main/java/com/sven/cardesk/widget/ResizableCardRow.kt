package com.sven.cardesk.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.sven.cardesk.R
import kotlin.math.max

class ResizableCardRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    var onWidthsChanged: ((ratios: FloatArray, isFinal: Boolean) -> Unit)? = null

    private val cards = ArrayList<View>(3)
    private var leftHandle: ResizeHandle? = null
    private var rightHandle: ResizeHandle? = null
    private var currentRatios = floatArrayOf(1f / 3f, 1f / 3f, 1f / 3f)

    private var dragStartWidths = FloatArray(3)
    private var dragDelta = 0f
    private var draggingLeft = false

    init {
        orientation = HORIZONTAL
        isBaselineAligned = false
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        val left = findViewById<View>(R.id.windowLeft)
        val middle = findViewById<View>(R.id.windowMiddle)
        val right = findViewById<View>(R.id.windowRight)
        cards.clear()
        if (left != null && middle != null && right != null) {
            cards.add(left)
            cards.add(middle)
            cards.add(right)
        }

        leftHandle = findViewById<ResizeHandle>(R.id.handleLeft).also { handle ->
            handle?.dragListener = createDragListener(left = true)
        }
        rightHandle = findViewById<ResizeHandle>(R.id.handleRight).also { handle ->
            handle?.dragListener = createDragListener(left = false)
        }
    }

    val ratios: FloatArray
        get() = currentRatios.copyOf()

    val secondaryRatio: Float
        get() = currentRatios[2]

    fun setRatios(newRatios: FloatArray, notify: Boolean = false) {
        val normalized = normalize(newRatios)
        currentRatios = normalized
        applyWeights(normalized)
        if (notify) {
            onWidthsChanged?.invoke(normalized.copyOf(), true)
        }
    }

    fun applySecondaryRatio(targetRatio: Float, notify: Boolean = true) {
        val target = targetRatio.coerceIn(0.2f, 0.8f)
        val remaining = 1f - target
        val otherTotal = currentRatios[0] + currentRatios[1]
        val first = if (otherTotal > 0.01f) {
            currentRatios[0] / otherTotal * remaining
        } else {
            remaining / 2f
        }
        val second = remaining - first
        setRatios(floatArrayOf(first, second, target), notify)
    }

    private fun createDragListener(left: Boolean) = object : ResizeHandle.OnDragListener {
        override fun onStart(handle: ResizeHandle) {
            if (cards.size != 3) return
            draggingLeft = left
            dragDelta = 0f
            dragStartWidths = FloatArray(3) { index ->
                cards[index].width.toFloat().takeIf { it > 0f }
                    ?: (currentRatios[index] * measuredCardWidth())
            }
        }

        override fun onMove(handle: ResizeHandle, deltaX: Float) {
            if (cards.size != 3) return
            dragDelta += deltaX
            val total = dragStartWidths.sum().takeIf { it > 0f } ?: return
            val widths = dragStartWidths.copyOf()

            if (draggingLeft) {
                val pairTotal = dragStartWidths[0] + dragStartWidths[1]
                val minWidth = minOf(max(total * 0.2f, dp(56f)), pairTotal * 0.45f)
                val newFirst = (dragStartWidths[0] + dragDelta).coerceIn(
                    minWidth,
                    pairTotal - minWidth
                )
                widths[0] = newFirst
                widths[1] = pairTotal - newFirst
            } else {
                val pairTotal = dragStartWidths[1] + dragStartWidths[2]
                val minWidth = minOf(max(total * 0.2f, dp(56f)), pairTotal * 0.45f)
                val newSecond = (dragStartWidths[1] + dragDelta).coerceIn(
                    minWidth,
                    pairTotal - minWidth
                )
                widths[1] = newSecond
                widths[2] = pairTotal - newSecond
            }

            val newRatios = FloatArray(3) { widths[it] / total }
            currentRatios = normalize(newRatios)
            applyWeights(currentRatios)
            onWidthsChanged?.invoke(currentRatios.copyOf(), false)
        }

        override fun onEnd(handle: ResizeHandle) {
            onWidthsChanged?.invoke(currentRatios.copyOf(), true)
        }
    }

    private fun applyWeights(ratios: FloatArray) {
        cards.forEachIndexed { index, view ->
            val params = view.layoutParams as? LayoutParams ?: return@forEachIndexed
            params.width = 0
            params.height = LayoutParams.MATCH_PARENT
            params.weight = ratios.getOrElse(index) { 1f / 3f }
            view.layoutParams = params
        }
        requestLayout()
    }

    private fun measuredCardWidth(): Float {
        val available = width - (leftHandle?.width ?: 0) - (rightHandle?.width ?: 0)
        return available.coerceAtLeast(1).toFloat()
    }

    private fun normalize(values: FloatArray): FloatArray {
        val safe = FloatArray(3) { index ->
            values.getOrElse(index) { 1f / 3f }.coerceAtLeast(0.02f)
        }
        val sum = safe.sum().takeIf { it > 0f } ?: 3f
        return FloatArray(3) { safe[it] / sum }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

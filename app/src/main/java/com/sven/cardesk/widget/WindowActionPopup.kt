package com.sven.cardesk.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Space
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.sven.cardesk.R
import com.sven.cardesk.utils.DisplayUtils
import kotlin.math.min

data class WindowAction(
    val label: String,
    val iconRes: Int,
    val iconColorRes: Int,
    val onClick: () -> Unit
)

/**
 * A small anchored popup used by the floating window handle.
 * Actions are data-driven and arranged into 1..3 columns based on the window width.
 */
class WindowActionPopup(
    private val context: Context,
    private val anchor: View,
    private val windowWidthPx: Int,
    private val actions: List<WindowAction>
) {

    private val popupWidth = min(
        windowWidthPx.takeIf { it > 0 } ?: DisplayUtils.dip2px(360f),
        DisplayUtils.dip2px(520f)
    ).coerceAtLeast(DisplayUtils.dip2px(180f))

    fun show() {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_window_menu)
            setPadding(
                DisplayUtils.dip2px(10f),
                DisplayUtils.dip2px(8f),
                DisplayUtils.dip2px(10f),
                DisplayUtils.dip2px(10f)
            )
        }

        val title = TextView(context).apply {
            text = context.getString(R.string.window_menu_title)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            textSize = 12f
            setPadding(
                DisplayUtils.dip2px(8f),
                DisplayUtils.dip2px(2f),
                DisplayUtils.dip2px(8f),
                DisplayUtils.dip2px(6f)
            )
        }
        content.addView(title)

        val widthDp = DisplayUtils.px2dip(popupWidth.toFloat())
        val columns = when {
            widthDp < 300 -> 1
            widthDp < 500 -> 2
            else -> 3
        }
        val popup = PopupWindow(
            content,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = DisplayUtils.dip2pxF(12f)

        actions.chunked(columns).forEach { rowActions ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            rowActions.forEach { action ->
                row.addView(createActionButton(action, popup))
            }
            repeat(columns - rowActions.size) {
                val spacer = Space(context)
                row.addView(
                    spacer,
                    LinearLayout.LayoutParams(0, DisplayUtils.dip2px(48f), 1f)
                )
            }
            content.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val offsetX = (anchor.width - popupWidth) / 2
        popup.showAsDropDown(
            anchor,
            offsetX,
            DisplayUtils.dip2px(6f)
        )
    }

    private fun createActionButton(action: WindowAction, popup: PopupWindow): MaterialButton {
        val margin = DisplayUtils.dip2px(4f)
        return MaterialButton(context).apply {
            text = action.label
            isAllCaps = false
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            gravity = Gravity.CENTER
            setIconResource(action.iconRes)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconSize = DisplayUtils.dip2px(18f)
            iconPadding = DisplayUtils.dip2px(6f)
            iconTint = ColorStateList.valueOf(
                ContextCompat.getColor(context, action.iconColorRes)
            )
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, R.color.card_surface_alt)
            )
            cornerRadius = DisplayUtils.dip2px(14f)
            minHeight = DisplayUtils.dip2px(48f)
            setPadding(
                DisplayUtils.dip2px(8f),
                0,
                DisplayUtils.dip2px(8f),
                0
            )
            layoutParams = LinearLayout.LayoutParams(
                0,
                DisplayUtils.dip2px(48f),
                1f
            ).apply {
                this.marginStart = margin
                this.marginEnd = margin
                topMargin = DisplayUtils.dip2px(2f)
                bottomMargin = DisplayUtils.dip2px(2f)
            }
            setOnClickListener {
                popup.dismiss()
                action.onClick()
            }
        }
    }
}

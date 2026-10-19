package com.sven.cardesk.widget

import android.content.Context
import android.util.AttributeSet

/**
 * @Author:         xwp
 * @CreateDate:     2023/1/4
 * @Version:        1.0
 */
class CommonButtonImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : androidx.appcompat.widget.AppCompatImageView(context, attrs, defStyleAttr) {

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        alpha = if (pressed) {
            0.6f
        } else {
            1.0f
        }
    }
}
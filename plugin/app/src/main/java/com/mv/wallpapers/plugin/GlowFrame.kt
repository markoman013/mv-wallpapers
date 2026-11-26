package com.mv.wallpapers.plugin

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Picture frame with the focus glow: a smooth gradient that fades out from the image
 * edge (drawn only outside the image), instead of stacked lines.
 */
class GlowFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // The theme's accent at 70%
        color = (ColorTheme.color(context, R.attr.mvGlow, 0xFFD0BCFF.toInt()) and 0x00FFFFFF) or 0xB3000000.toInt()
        maskFilter = BlurMaskFilter(5.5f * density, BlurMaskFilter.Blur.OUTER)
    }
    private val rect = RectF()

    init {
        setWillNotDraw(false)
    }

    override fun setSelected(selected: Boolean) {
        val changed = selected != isSelected
        super.setSelected(selected)
        if (changed) invalidate()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (!isSelected) return
        // The image sits inside the padding (6dp) with 16dp corners.
        rect.set(paddingLeft.toFloat(), paddingTop.toFloat(),
            (width - paddingRight).toFloat(), (height - paddingBottom).toFloat())
        canvas.drawRoundRect(rect, 16 * density, 16 * density, paint)
    }
}

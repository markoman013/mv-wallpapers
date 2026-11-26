package com.mv.wallpapers.plugin

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.style.ReplacementSpan

/** An inline chip like the ones on the photo tiles: tonal rounded box, small icon, label. */
class ChipSpan(
    private val icon: Drawable,
    private val label: String,
    private val bg: Int,
    private val fg: Int,
    private val dp: Float,
) : ReplacementSpan() {
    private fun chipPaint(p: Paint) = Paint(p).apply {
        textSize = p.textSize * 0.82f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val iconSize get() = 12 * dp

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.let { paint.getFontMetricsInt(it) }
        return (7 * dp + iconSize + 4 * dp + chipPaint(paint).measureText(label) + 8 * dp).toInt()
    }

    override fun draw(c: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val p = chipPaint(paint)
        val m = paint.fontMetrics
        // Chip as tall as the line's text, centred on it.
        val boxTop = y + m.ascent + 1 * dp
        val boxBottom = y + m.descent
        val w = getSize(paint, text, start, end, null).toFloat()
        val r = 8 * dp
        c.drawRoundRect(RectF(x, boxTop, x + w, boxBottom), r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        val mid = (boxTop + boxBottom) / 2
        val iconLeft = (x + 7 * dp).toInt()
        icon.setTint(fg)
        icon.setBounds(iconLeft, (mid - iconSize / 2).toInt(), (iconLeft + iconSize).toInt(), (mid + iconSize / 2).toInt())
        icon.draw(c)
        p.color = fg
        val pm = p.fontMetrics
        c.drawText(label, x + 7 * dp + iconSize + 4 * dp, mid - (pm.ascent + pm.descent) / 2, p)
    }
}

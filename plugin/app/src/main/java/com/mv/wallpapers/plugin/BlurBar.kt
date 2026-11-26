package com.mv.wallpapers.plugin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout

/** Something drawn under a [BlurBar]: tells the bar when it has drawn, so the blur follows it. */
interface BlurSource {
    var onDrawn: (() -> Unit)?
}

/**
 * Material-style bar that blurs what's behind it (Android 12+, built-in blur, no libraries).
 * It re-records the [sources] (grids or the preview photo) under its own area and blurs them,
 * then lays a semi-transparent surface tint on top. Older Android or blur switched off in
 * Settings: the normal solid background is used.
 */
class BlurBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    var cornerRadiusDp = 16f
    /** Side panels: round only the left corners (the right side is the screen edge). */
    var leftCornersOnly = false
    private var sources: List<View> = emptyList()
    private val solidBackground = background
    private val tint = Paint().apply {
        color = (ColorTheme.color(context, R.attr.mvSurface, 0xFF211F26.toInt()) and 0x00FFFFFF) or 0x99000000.toInt()
    }
    private val clip = Path()
    private val rect = RectF()
    private val here = IntArray(2)
    private val there = IntArray(2)
    private var node: RenderNode? = null
    /** Holds the finished blur as a texture, so it isn't re-blurred on every frame. */
    private var cache: RenderNode? = null
    private var recording = false
    private var lastSignal = 0L
    /** Only bars that were given something to blur (bottom bars) blur; panels stay solid. */
    private var blurActive = false

    init {
        setWillNotDraw(false)
    }

    /** The views behind the bar. Call again after the Settings switch changes. */
    fun blurBehind(vararg views: View) {
        sources = views.toList()
        blurActive = true
        for (v in views) (v as? BlurSource)?.onDrawn = {
            if (!recording) {
                lastSignal = android.os.SystemClock.uptimeMillis()
                postInvalidateOnAnimation()
            }
        }
        val on = Build.VERSION.SDK_INT >= 31 && enabled
        background = if (on) null else solidBackground
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        if (blurActive && Build.VERSION.SDK_INT >= 31 && enabled && canvas.isHardwareAccelerated && width > 0) drawBlur(canvas)
        super.draw(canvas)
    }

    @androidx.annotation.RequiresApi(31)
    private fun drawBlur(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val n = node ?: RenderNode("blurBar").also {
            it.setRenderEffect(RenderEffect.createBlurEffect(28 * density, 28 * density, Shader.TileMode.CLAMP))
            node = it
        }
        n.setPosition(0, 0, width, height)
        val rc = n.beginRecording(width, height)
        recording = true
        try {
            // Positions without slide/zoom: the blur is worked out once for where the panel
            // ends up, so animations just move the finished picture (no re-blur, no jump).
            layoutPosition(this, here)
            for (v in sources) {
                if (!v.isShown) continue
                layoutPosition(v, there)
                rc.save()
                rc.translate((there[0] - here[0]).toFloat(), (there[1] - here[1]).toFloat())
                v.draw(rc)
                rc.restore()
            }
        } finally {
            recording = false
            n.endRecording()
        }
        val r = cornerRadiusDp * density
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset()
        if (leftCornersOnly) clip.addRoundRect(rect, floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r), Path.Direction.CW)
        else clip.addRoundRect(rect, r, r, Path.Direction.CW)
        // Blur once into a cached layer; frames that only move or redraw other things reuse it.
        val c = cache ?: RenderNode("blurCache").also { cache = it }
        c.setPosition(0, 0, width, height)
        c.setUseCompositingLayer(true, null)
        val cc = c.beginRecording(width, height)
        cc.drawRenderNode(n)
        c.endRecording()
        val save = canvas.save()
        canvas.clipPath(clip)
        canvas.drawRenderNode(c)
        canvas.drawRect(rect, tint)
        canvas.restoreToCount(save)
        // Zoom/fade animations of tiles don't redraw the grid itself: keep following for a moment.
        if (android.os.SystemClock.uptimeMillis() - lastSignal < 300) postInvalidateOnAnimation()
    }

    companion object {
        private const val KEY = "blur_bars"

        /** Where a view sits in the window by layout alone, ignoring any animation transforms. */
        private fun layoutPosition(view: View, out: IntArray) {
            var x = 0
            var y = 0
            var v: View? = view
            while (v != null) {
                x += v.left - v.scrollX
                y += v.top - v.scrollY
                v = v.parent as? View
            }
            out[0] = x
            out[1] = y
        }
        /** Settings switch (on by default). */
        var enabled: Boolean
            // Off by default before Android 12 (no blur there anyway).
            get() = PreferencesManager.preferences.getBoolean(KEY, Build.VERSION.SDK_INT >= 31)
            set(value) { PreferencesManager.preferences.edit().putBoolean(KEY, value).apply() }
    }
}

/** The full-screen photo; tells the blur bar when it changes. */
class BlurImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ImageView(context, attrs), BlurSource {
    override var onDrawn: (() -> Unit)? = null
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        onDrawn?.invoke()
    }
}

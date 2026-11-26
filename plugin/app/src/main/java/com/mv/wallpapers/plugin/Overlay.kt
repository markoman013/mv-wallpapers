package com.mv.wallpapers.plugin

import android.animation.ValueAnimator
import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * Side panels and pop-ups drawn inside the app (not separate windows), so they get the
 * same blur as the bottom bars on any Android 12+ device. The screen behind is dimmed,
 * can't take focus while the panel is open, and gets focus back when it closes.
 * First full release: v1.0.
 */
class Overlay private constructor(
    private val activity: Activity,
    val panel: View,
    private val side: Boolean,
    private val onBack: () -> Boolean
) {
    private val content = activity.findViewById<ViewGroup>(android.R.id.content)
    private val screen = content.getChildAt(0) as ViewGroup
    private val host = FrameLayout(activity)
    private val dim = View(activity).apply { setBackgroundColor(0x80000000.toInt()) }
    private val previousFocus = activity.currentFocus
    private var closing = false
    /** Panel width known up front (it isn't measured yet on the first animation frame). */
    private val slideWidth = activity.resources.displayMetrics.widthPixels * 0.4f

    private fun open() {
        open += this
        host.addView(dim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val width = activity.resources.displayMetrics.widthPixels
        val params = if (side) FrameLayout.LayoutParams((width * 0.4f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
            else FrameLayout.LayoutParams((width * 0.5f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        host.addView(panel, params)
        content.addView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        (panel as? BlurBar)?.blurBehind(screen)
        screen.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        animate(entering = true)
    }

    /** Back inside the panel; closes it unless the panel handled Back itself. */
    fun back() {
        if (!onBack()) close()
    }

    fun close(then: (() -> Unit)? = null) {
        if (closing) return
        closing = true
        open -= this
        if (open.none { it.activity === activity }) {
            screen.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        animate(entering = false) {
            // Give focus back first: removing the panel while it has focus makes Android jump
            // to the first thing on screen (the Collections tab), which would switch tabs.
            if (open.none { it.activity === activity }) previousFocus?.takeIf { it.isShown }?.requestFocus()
            content.removeView(host)
            then?.invoke()
        }
    }

    private fun animate(entering: Boolean, end: (() -> Unit)? = null) {
        val from = if (entering) 0f else 1f
        val to = if (entering) 1f else 0f
        // While it moves, the panel is one ready-made texture: the slide only shifts it.
        panel.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        ValueAnimator.ofFloat(from, to).apply {
            duration = if (entering) 200 else 150
            interpolator = if (entering) DecelerateInterpolator(2f) else AccelerateInterpolator(2f)
            addUpdateListener {
                val f = it.animatedValue as Float
                dim.alpha = f
                if (side) panel.translationX = (1f - f) * slideWidth
                else {
                    panel.alpha = f
                    panel.scaleX = 0.94f + 0.06f * f
                    panel.scaleY = 0.94f + 0.06f * f
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    panel.setLayerType(View.LAYER_TYPE_NONE, null)
                    end?.invoke()
                }
            })
            if (entering && side) panel.translationX = slideWidth
            start()
        }
    }

    companion object {
        private val open = mutableListOf<Overlay>()

        /** A side panel on the right (menus). [onBack] returns true when it handled Back itself. */
        fun side(activity: Activity, panel: View, onBack: () -> Boolean = { false }) =
            Overlay(activity, panel, side = true, onBack = onBack).also { it.open() }

        /** A pop-up in the middle of the screen. */
        fun center(activity: Activity, panel: View, onBack: () -> Boolean = { false }) =
            Overlay(activity, panel, side = false, onBack = onBack).also { it.open() }

        fun isOpen(activity: Activity) = open.any { it.activity === activity }

        /** Call first in the activity's Back handling: true when a panel took it. */
        fun back(activity: Activity): Boolean {
            val top = open.lastOrNull { it.activity === activity } ?: return false
            top.back()
            return true
        }
    }
}

package com.mv.wallpapers.plugin

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.widget.GridView
import android.widget.LinearLayout

/**
 * A row of buttons. Coming in from another row (up/down), focus lands on the
 * button you last used in this row, or on the first one the first time.
 */
class FocusRow @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {

    private var last: View? = null

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        last = focused ?: child
    }

    /** Where focus lands when coming into this row from above or below. */
    fun entry(): View? = last?.takeIf { it.isShown && it.isFocusable && contains(it) } ?: firstFocusable()

    /** First focusable view in this row, looking inside nested groups too. */
    private fun firstFocusable(group: android.view.ViewGroup = this): View? {
        for (i in 0 until group.childCount) {
            val v = group.getChildAt(i)
            if (!v.isShown) continue
            if (v.isFocusable && v !is android.view.ViewGroup) return v
            if (v is android.view.ViewGroup) firstFocusable(v)?.let { return it }
            if (v.isFocusable) return v
        }
        return null
    }

    /** Left/right never leave the row; up/down always go to the very next section. */
    override fun focusSearch(focused: View?, direction: Int): View? {
        if (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) {
            FocusSteps.next(this, direction)?.let { return it }
        }
        val next = super.focusSearch(focused, direction)
        if ((direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) && next != null && !contains(next)) {
            return focused
        }
        return next
    }

    private fun contains(view: View): Boolean {
        var p = view.parent
        while (p != null) {
            if (p === this) return true
            p = p.parent
        }
        return false
    }

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        if (hasFocus() || (direction != View.FOCUS_UP && direction != View.FOCUS_DOWN)) {
            super.addFocusables(views, direction, focusableMode)
            return
        }
        val target = entry()
        if (target != null) views.add(target) else super.addFocusables(views, direction, focusableMode)
    }
}

/**
 * Grid that, when focus comes in from a button row, goes back to the item you
 * last had selected (the first item the first time) instead of the nearest one.
 */
class FocusGridView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    GridView(context, attrs), BlurSource {

    override var onDrawn: (() -> Unit)? = null

    /** Show the scrollbar only while the list really scrolls (not on opening or moving within the view). */
    var scrollbarOnlyWhenScrolling = false
        set(value) {
            field = value
            if (value) {
                isVerticalScrollBarEnabled = false
                setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                    override fun onScrollStateChanged(view: android.widget.AbsListView, state: Int) {}
                    override fun onScroll(view: android.widget.AbsListView, first: Int, visible: Int, total: Int) {
                        val top = getChildAt(0)?.top ?: 0
                        val moved = lastFirst >= 0 && (first != lastFirst || top != lastTop)
                        lastFirst = first
                        lastTop = top
                        if (!moved) return
                        isVerticalScrollBarEnabled = true
                        awakenScrollBars()
                        removeCallbacks(hideBar)
                        postDelayed(hideBar, 1500)
                    }
                })
            }
        }
    private var lastFirst = -1
    private var lastTop = 0

    /** New content (a collection just opened): don't count the jump to the top as scrolling. */
    fun resetScrollbar() {
        lastFirst = -1
        removeCallbacks(hideBar)
        isVerticalScrollBarEnabled = false
    }

    private val hideBar = Runnable { isVerticalScrollBarEnabled = false; invalidate() }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, null)
    }

    // Tiles may draw past the sides and bottom (zoom, behind the info bar), but not over the row above.
    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        val save = canvas.save()
        canvas.clipRect(-width.toFloat(), 0f, width * 2f, height * 3f)
        drawGroup(canvas)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
        onDrawn?.invoke()
    }

    enum class Cell { NORMAL, SKIP, GROUP }

    /** Optional: blank cells to skip, and cells that together make one wide button (a header row). */
    var cellKind: ((Int) -> Cell)? = null

    private fun kind(pos: Int) = cellKind?.invoke(pos) ?: Cell.NORMAL

    /** The header row (GROUP cells): a faint line above it, and one highlight across it when [groupLit]. */
    var groupLit = false
    var groupColor = 0xFF4A4458.toInt()
    private val groupPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    private fun drawGroup(canvas: android.graphics.Canvas) {
        // Not while the grid is still being laid out (on opening it flashed for a moment).
        if (cellKind != null && !isLayoutRequested && childCount > numColumns) {
            var l = Int.MAX_VALUE; var r = Int.MIN_VALUE; var t = 0; var b = 0
            for (i in 0 until childCount) {
                if (kind(firstVisiblePosition + i) != Cell.GROUP) continue
                val c = getChildAt(i)
                l = minOf(l, c.left); r = maxOf(r, c.right); t = c.top; b = c.bottom
            }
            if (l < r) {
                val d = resources.displayMetrics.density
                val inset = 6 * d  // GlowFrame padding: line up with the cards
                groupPaint.color = 0x33FFFFFF
                canvas.drawRect(l + inset, t + 8 * d, r - inset, t + 9 * d, groupPaint)
                if (groupLit && hasFocus()) {
                    groupPaint.color = groupColor
                    canvas.drawRoundRect(l + inset, b - 44 * d, r - inset, b.toFloat(), 12 * d, 12 * d, groupPaint)
                }
            }
        }
    }

    /** Right at the end of a row goes to the start of the next one; left at the start goes back a row. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val pos = selectedItemPosition
        val cols = numColumns.coerceAtLeast(1)
        if (cellKind != null && pos >= 0) moveAround(keyCode, pos, cols)?.let { return it }
        if (pos >= 0 && (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT)) {
            val right = keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            val edge = if (right) pos % cols == cols - 1 || pos == count - 1 else pos % cols == 0
            if (edge) {
                val target = if (right) pos + 1 else pos - 1
                if (target in 0 until count) setSelection(target)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Moves that skip blank cells and treat a header row as one stop; null = the normal move. */
    private fun moveAround(keyCode: Int, pos: Int, cols: Int): Boolean? {
        val step = when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -1
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> cols
            android.view.KeyEvent.KEYCODE_DPAD_UP -> -cols
            else -> return null
        }
        val horizontal = step == 1 || step == -1
        val here = kind(pos)
        // Inside the header row, left and right have nowhere to go.
        if (here == Cell.GROUP && horizontal) {
            val target = pos + step
            if (target in 0 until count && kind(target) == Cell.GROUP) return true
        }
        var target = pos + step
        if (!horizontal && target >= count) {
            // A shorter last row: go to its last item (when there is a row below at all).
            val lastRowStart = (count - 1) / cols * cols
            if (pos >= lastRowStart) return null
            target = count - 1
        }
        if (target !in 0 until count) return null
        if (horizontal && kind(target) == Cell.NORMAL && here == Cell.NORMAL) return null
        // Skip blank cells: on along the row when going down/right, back when going up/left.
        val dir = if (step > 0) 1 else -1
        while (target in 0 until count && kind(target) == Cell.SKIP) target += dir
        if (target !in 0 until count) return null
        // Coming into the header row from above or below: keep the column.
        setSelection(target)
        return true
    }

    /** Back goes to the first row first; returns false when already there. */
    fun backToTop(): Boolean {
        if (selectedItemPosition < numColumns.coerceAtLeast(1)) return false
        setSelection(0)
        requestFocus()
        return true
    }

    /** Leaving the grid up or down goes to the section right next to it, never past it. */
    override fun focusSearch(focused: View?, direction: Int): View? {
        val next = super.focusSearch(focused, direction)
        if ((direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) && next != null && next !== this && !isInside(next)) {
            FocusSteps.next(this, direction)?.let { return it }
        }
        return next
    }

    // The grid itself holds focus (its tiles don't), so the key search starts here, not in the child version above.
    override fun focusSearch(direction: Int): View? {
        if (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) {
            FocusSteps.next(this, direction)?.let { return it }
        }
        return super.focusSearch(direction)
    }

    private fun isInside(view: View): Boolean {
        var p = view.parent
        while (p != null) { if (p === this) return true; p = p.parent }
        return false
    }
}

/** Finds the neighbouring section (row, grid, page) above or below a view. */
object FocusSteps {
    fun next(from: View, direction: Int): View? {
        var child: View = from
        var parent = child.parent as? android.view.ViewGroup ?: return null
        while (true) {
            if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL) {
                var i = parent.indexOfChild(child)
                val step = if (direction == View.FOCUS_UP) -1 else 1
                i += step
                while (i in 0 until parent.childCount) {
                    entryOf(parent.getChildAt(i), direction)?.let { return it }
                    i += step
                }
            }
            child = parent
            parent = parent.parent as? android.view.ViewGroup ?: return null
        }
    }

    private fun entryOf(v: View, direction: Int): View? {
        if (!v.isShown) return null
        return when (v) {
            is FocusRow -> v.entry()
            is android.widget.AbsListView -> if (v.isFocusable && v.count > 0) v else null
            is android.view.ViewGroup -> {
                // Enter a group from its near edge: top part when going down, bottom part when going up.
                val kids = (0 until v.childCount).map { v.getChildAt(it) }
                val ordered = if (direction == View.FOCUS_UP && v is LinearLayout && v.orientation == LinearLayout.VERTICAL) kids.reversed() else kids
                ordered.firstNotNullOfOrNull { entryOf(it, direction) }
            }
            else -> if (v.isFocusable) v else null
        }
    }
}

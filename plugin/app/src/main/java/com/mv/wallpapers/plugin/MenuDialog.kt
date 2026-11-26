package com.mv.wallpapers.plugin

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

/**
 * Material 3 side sheet with an icon on every row, like the settings panel.
 * A danger row (delete) is red and sits below a divider.
 */
object MenuDialog {
    /** [children]: pressing the row shows these rows in the same sheet (Back returns). */
    class Row(
        val icon: Int,
        val label: String,
        val danger: Boolean = false,
        val children: (() -> List<Row>)? = null,
        /** true: the sheet stays open after the action and the list is redrawn (toggles). */
        val stay: Boolean = false,
        /** For a row with [children]: runs when Back leaves that sub-list. Return true to close the menu. */
        val onLeave: (() -> Boolean)? = null,
        /** A faint line above this row (e.g. before the seasons). */
        val dividerBefore: Boolean = false,
        val action: () -> Unit = {}
    )

    /** Up from the first row: the last row sits at the bottom of the list, not at the top. */
    fun selectLast(list: android.widget.ListView, last: Int, footer: View?) {
        val rowH = list.getChildAt(0)?.height ?: 0
        list.setSelectionFromTop(last, maxOf(0, list.height - list.paddingTop - list.paddingBottom - rowH))
        footer?.let { revealFooter(list, it) }
    }

    /** On the last row, scroll the info block under the buttons into view too. */
    fun revealFooter(list: android.widget.ListView, footer: View) {
        list.post {
            if (footer.visibility != View.VISIBLE) return@post
            val bottom = list.height - list.paddingBottom
            if (footer.parent === list) {
                val over = footer.bottom - bottom
                if (over > 0) list.smoothScrollBy(over, 150)
            } else {
                footer.measure(View.MeasureSpec.makeMeasureSpec(list.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                list.smoothScrollBy(footer.measuredHeight + list.dividerHeight, 150)
            }
        }
    }

    /** [info]: details shown under the buttons of the first list (photo or collection info). */
    fun show(activity: Activity, title: String, rows: List<Row>, subtitle: String? = null, info: CharSequence? = null) {
        val sheet = activity.layoutInflater.inflate(R.layout.dialog_sheet, null) as BlurBar
        sheet.cornerRadiusDp = 28f
        sheet.leftCornersOnly = true
        sheet.findViewById<TextView>(R.id.heading).text = title
        sheet.findViewById<TextView>(R.id.subheading).apply {
            text = subtitle ?: ""
            visibility = if (subtitle == null) View.GONE else View.VISIBLE
        }

        val list = sheet.findViewById<ListView>(R.id.list)
        val heading = sheet.findViewById<TextView>(R.id.heading)
        var overlayRef: Overlay? = null
        val stack = ArrayDeque<Pair<String, () -> List<Row>>>()
        val leaves = ArrayDeque<(() -> Boolean)?>()
        var current: () -> List<Row> = { rows }
        var entries: List<Row?> = emptyList()

        val adapter = object : BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(position: Int) = entries[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(position: Int) = if (entries[position] == null) 1 else 0
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = entries[position] != null
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = entries[position]
                    ?: return convertView ?: activity.layoutInflater.inflate(R.layout.item_divider, parent, false)
                val view = convertView ?: activity.layoutInflater.inflate(R.layout.item_option, parent, false)
                view.findViewById<ImageView>(R.id.icon).apply {
                    setImageResource(row.icon)
                    visibility = View.VISIBLE
                }
                view.findViewById<TextView>(R.id.label).apply {
                    text = row.label
                    setTextColor(if (row.danger) 0xFFF2B8B5.toInt() else 0xFFE6E0E9.toInt())
                }
                view.findViewById<View>(R.id.value).visibility = View.GONE
                return view
            }
        }
        val footer = info?.let {
            TextView(activity).apply {
                text = it
                setTextColor(activity.getColor(R.color.text_secondary))
                textSize = 13f
                setLineSpacing(3 * resources.displayMetrics.density, 1f)
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad + pad / 2, pad, pad)
            }.also { v -> list.addFooterView(v, null, false) }
        }
        list.adapter = adapter
        // Up on the first row goes to the last one, down on the last goes to the first.
        list.setOnKeyListener { _, keyCode, event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val first = entries.indexOfFirst { it != null }
            val last = entries.indexOfLast { it != null }
            if (list.selectedItemPosition >= entries.size) return@setOnKeyListener false
            val pos = list.selectedItemPosition
            when {
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && pos == first && last > first -> { selectLast(list, last, footer); true }
                keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && pos == last && last > first -> { list.setSelection(first); true }
                else -> false
            }
        }

        list.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (position == entries.indexOfLast { it != null }) footer?.let { revealFooter(list, it) }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        fun showRows(rowTitle: String, provider: () -> List<Row>, keepPosition: Boolean = false) {
            current = provider
            val rowList = provider()
            val position = list.selectedItemPosition
            val top = list.selectedView?.top ?: 0
            heading.text = rowTitle
            footer?.visibility = if (stack.isEmpty()) View.VISIBLE else View.GONE
            // A divider goes right before the first danger row.
            entries = rowList.flatMapIndexed { i, r ->
                if ((r.danger && i > 0 && !rowList[i - 1].danger) || (r.dividerBefore && i > 0)) listOf(null, r) else listOf(r)
            }
            adapter.notifyDataSetChanged()
            // Ticking a row keeps the list where it is (no jump to the top).
            if (keepPosition) list.setSelectionFromTop(position.coerceIn(0, maxOf(0, entries.size - 1)), top)
            else list.setSelection(0)
            list.requestFocus()
        }

        list.setOnItemClickListener { _, _, position, _ ->
            val row = entries.getOrNull(position) ?: return@setOnItemClickListener
            val children = row.children
            if (children != null) {
                stack.addLast(heading.text.toString() to current)
                leaves.addLast(row.onLeave)
                showRows(row.label, children)
            } else if (row.stay) {
                row.action()
                showRows(heading.text.toString(), current, keepPosition = true)
            } else {
                // Close first, then act (an action may open a pop-up).
                overlayRef?.close { row.action() }
            }
        }
        // Back inside a sub-list returns to the previous list; otherwise closes.
        val overlay = Overlay.side(activity, sheet) {
            if (stack.isNotEmpty()) {
                if (leaves.removeLast()?.invoke() == true) {
                    overlayRef?.close()
                    return@side true
                }
                val (prevTitle, prevRows) = stack.removeLast()
                showRows(prevTitle, prevRows)
                true
            } else false
        }
        overlayRef = overlay
        showRows(title, { rows })
    }
}

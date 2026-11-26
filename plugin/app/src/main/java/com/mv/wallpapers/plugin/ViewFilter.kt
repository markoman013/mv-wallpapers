package com.mv.wallpapers.plugin

/** The "Show" button: all, selected, not selected, and (home only) hidden collections. */
enum class ViewFilter(val label: String) {
    ALL("Show: all"),
    SELECTED("Show: selected"),
    NOT_SELECTED("Show: not selected"),
    HIDDEN("Show: hidden");

    /** [home]: collections can be hidden; inside a collection there's no Hidden option. */
    fun next(home: Boolean = false): ViewFilter {
        val options = if (home) entries else entries.filter { it != HIDDEN }
        return options[(options.indexOf(this) + 1) % options.size]
    }
}

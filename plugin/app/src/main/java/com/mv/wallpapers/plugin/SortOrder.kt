package com.mv.wallpapers.plugin

/** The "Sort:" button, saved separately for the Collections tab, the Seasons tab and photo grids. */
enum class SortOrder(val label: String) {
    /** Seasons tab: spring → summer → fall → winter. */
    DEFAULT("Sort: default"),
    NEWEST("Sort: new"),
    NAME("Sort: A–Z");

    companion object {
        private const val KEY_HOME = "sort_home"
        private const val KEY_SEASONS = "sort_seasons"
        private const val KEY_PHOTOS = "sort_photos"

        /** Collections tab: A–Z ↔ new. */
        var collections: SortOrder
            get() = read(KEY_HOME, NAME).let { if (it == DEFAULT) NAME else it }
            set(v) = PreferencesManager.preferences.edit().putString(KEY_HOME, v.name).apply()

        /** Seasons tab: default ↔ new. */
        var seasons: SortOrder
            get() = read(KEY_SEASONS, DEFAULT).let { if (it == NAME) DEFAULT else it }
            set(v) = PreferencesManager.preferences.edit().putString(KEY_SEASONS, v.name).apply()

        /** Photo grids: new ↔ A–Z. */
        var photos: SortOrder
            get() = read(KEY_PHOTOS, NEWEST).let { if (it == DEFAULT) NEWEST else it }
            set(v) = PreferencesManager.preferences.edit().putString(KEY_PHOTOS, v.name).apply()

        fun nextCollections() = if (collections == NAME) NEWEST else NAME
        fun nextSeasons() = if (seasons == DEFAULT) NEWEST else DEFAULT
        fun nextPhotos() = if (photos == NEWEST) NAME else NEWEST

        private fun read(key: String, default: SortOrder) =
            runCatching { valueOf(PreferencesManager.preferences.getString(key, null) ?: "") }.getOrDefault(default)
    }
}

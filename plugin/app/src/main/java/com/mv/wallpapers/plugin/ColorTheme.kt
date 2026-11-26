package com.mv.wallpapers.plugin

import android.app.Activity
import android.content.Context
import android.util.TypedValue

/** Colour themes (Settings → Colour theme). Each is a set of Material 3 dark tones. */
enum class ColorTheme(val label: String, val style: Int) {
    LAVENDER("Lavender", R.style.Theme_ProjectivyWallpaperProvider),
    AMBER("Amber", R.style.Theme_MV_Amber),
    GREEN("Green", R.style.Theme_MV_Green),
    BLUE("Blue", R.style.Theme_MV_Blue),
    ROSE("Rose", R.style.Theme_MV_Rose);

    companion object {
        private const val KEY = "color_theme"

        var current: ColorTheme
            get() = runCatching { valueOf(PreferencesManager.preferences.getString(KEY, null) ?: "LAVENDER") }.getOrDefault(LAVENDER)
            set(value) { PreferencesManager.preferences.edit().putString(KEY, value.name).commit() }  // saved before the restart

        /** Call before setContentView. */
        fun apply(activity: Activity) = activity.setTheme(current.style)

        /** A theme colour for code that draws by hand (glow, blur tint). */
        fun color(context: Context, attr: Int, fallback: Int): Int {
            val v = TypedValue()
            return if (context.theme.resolveAttribute(attr, v, true)) v.data else fallback
        }
    }
}

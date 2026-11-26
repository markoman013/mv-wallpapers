package com.mv.wallpapers.plugin

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plugin settings, stored on the device. They survive app updates.
 * export()/import() let Projectivy back up and restore them.
 */
object PreferencesManager {
    lateinit var preferences: SharedPreferences

    fun init(context: Context) {
        if (!::preferences.isInitialized) {
            // Same file androidx PreferenceManager used in v1.0.1–1.0.4, so settings carry over.
            val app = context.applicationContext
            preferences = app.getSharedPreferences("${app.packageName}_preferences", Context.MODE_PRIVATE)
            migrate()
        }
    }

    /** Renamed collections: carry over choices made under the old names. */
    private val renamed = mapOf("cities" to "architecture", "beach" to "sea")

    private fun migrate() {
        var disabled = preferences.getStringSet("disabled_collections", null)?.toSet() ?: emptySet()
        var excluded = preferences.getStringSet("excluded_photos", null)?.toSet() ?: emptySet()
        val before = disabled to excluded
        renamed.forEach { (old, new) ->
            if (old in disabled) disabled = disabled - old + new
            excluded = excluded.map {
                if (it.startsWith("wallpapers/$old/")) "wallpapers/$new/" + it.removePrefix("wallpapers/$old/") else it
            }.toSet()
        }
        if (before != (disabled to excluded)) {
            preferences.edit()
                .putStringSet("disabled_collections", disabled)
                .putStringSet("excluded_photos", excluded)
                .apply()
        }
    }

    fun export(): String {
        val root = JSONObject()
        preferences.all.forEach { (key, value) ->
            when (value) {
                is Set<*> -> root.put(key, JSONObject().put("set", JSONArray(value.map { it.toString() })))
                is Int -> root.put(key, JSONObject().put("int", value))
                is Long -> root.put(key, JSONObject().put("long", value))
                is Float -> root.put(key, JSONObject().put("float", value.toDouble()))
                is Boolean -> root.put(key, JSONObject().put("bool", value))
                is String -> root.put(key, JSONObject().put("str", value))
            }
        }
        return root.toString()
    }

    fun import(json: String): Boolean {
        return try {
            val root = JSONObject(json)
            val editor = preferences.edit()
            root.keys().forEach { key ->
                val o = root.optJSONObject(key) ?: return@forEach
                when {
                    o.has("set") -> {
                        val a = o.getJSONArray("set")
                        editor.putStringSet(key, (0 until a.length()).map { a.getString(it) }.toSet())
                    }
                    o.has("int") -> editor.putInt(key, o.getInt("int"))
                    o.has("long") -> editor.putLong(key, o.getLong("long"))
                    o.has("float") -> editor.putFloat(key, o.getDouble("float").toFloat())
                    o.has("bool") -> editor.putBoolean(key, o.getBoolean("bool"))
                    o.has("str") -> editor.putString(key, o.getString("str"))
                }
            }
            editor.apply()
            true
        } catch (e: Exception) {
            Log.e("MVWallpapers", "Could not import settings", e)
            false
        }
    }
}

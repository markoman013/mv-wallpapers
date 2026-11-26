package com.mv.wallpapers.plugin

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.IWallpaperProviderService
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper

class WallpaperProviderService : Service() {
    override fun onCreate() {
        super.onCreate()
        PreferencesManager.init(this)
    }

    override fun onBind(intent: Intent): IBinder = binder

    private val binder = object : IWallpaperProviderService.Stub() {
        override fun getWallpapers(event: Event?): List<Wallpaper> {
            if (event !is Event.TimeElapsed) return emptyList()
            return try {
                WallpaperLibrary.nextBatch(applicationContext)
            } catch (e: Exception) {
                Log.e("MVWallpapers", "Could not build wallpaper batch", e)
                emptyList()
            }
        }

        override fun getPreferences(): String = PreferencesManager.export()

        override fun setPreferences(params: String) {
            PreferencesManager.import(params)
        }
    }
}

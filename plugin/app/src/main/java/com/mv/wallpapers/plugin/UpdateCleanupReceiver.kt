package com.mv.wallpapers.plugin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Android tells the app when it has just been updated: delete the downloaded APK then. */
class UpdateCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) UpdateChecker.cleanup(context)
    }
}

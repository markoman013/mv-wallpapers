package com.mv.wallpapers.plugin

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Checks GitHub releases for a newer plugin and installs it with the system installer. */
object UpdateChecker {
    private const val LATEST = "https://api.github.com/repos/markoman013/mv-wallpapers/releases/latest"

    data class Update(val version: String, val apkUrl: String, val fileName: String, val bytes: Long)

    /** Returns the newer release, or null if up to date / offline. Call off the main thread. */
    fun check(): Update? {
        return try {
            val connection = URL(LATEST).openConnection() as HttpURLConnection
            val body = try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
            val root = JSONObject(body)
            val version = root.getString("tag_name").substringAfter("v", "")
            // Compare build numbers (from the release notes); fall back to version names.
            val build = Regex("Build: (\\d+)").find(root.optString("body", ""))?.groupValues?.get(1)?.toIntOrNull()
            val newer = if (build != null) build > BuildConfig.VERSION_CODE else isNewer(version, BuildConfig.VERSION_NAME)
            if (!newer) return null
            val assets = root.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.getString("name")
                if (name.endsWith(".apk")) {
                    return Update(version, a.getString("browser_download_url"), name, a.optLong("size", 0))
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Deletes downloaded update files. Runs on every start and right after the app is updated. */
    fun cleanup(context: android.content.Context) {
        File(context.cacheDir, "updates").deleteRecursively()
    }

    /** Downloads the APK into the cache. Call off the main thread. */
    fun download(activity: Activity, update: Update, progress: (Int) -> Unit): File {
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, update.fileName)
        val connection = URL(update.apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: update.bytes
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) progress((done * 100 / total).toInt())
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        return file
    }

    /** Opens Android's installer. First time, sends the user to allow installs from this app. */
    fun install(activity: Activity, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Allow MV Wallpapers to install apps, then press Update again", Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(activity, "Settings > Apps > Special app access > Install unknown apps", Toast.LENGTH_LONG).show()
            }
            return
        }
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
    }
}

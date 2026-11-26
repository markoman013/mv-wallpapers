package com.mv.wallpapers.plugin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.http.HttpResponseCache
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Tiny image loader: memory cache + Android's built-in HTTP disk cache. No libraries. */
object ImageLoader {
    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    private const val THUMB_CACHE = 15L * 1024 * 1024
    private const val FULL_CACHE = 15L * 1024 * 1024
    private var fullDir: File? = null

    fun init(context: Context) {
        if (fullDir == null) fullDir = File(context.cacheDir, "full").apply { mkdirs() }
        if (HttpResponseCache.getInstalled() == null) {
            try {
                HttpResponseCache.install(File(context.cacheDir, "http"), THUMB_CACHE)
            } catch (_: Exception) {
            }
        }
    }

    /** Loads [url] into [view], decoded down to about [maxWidth] px. Falls back to [fallbackUrl]. */
    fun load(
        view: ImageView, url: String, maxWidth: Int, fallbackUrl: String? = null,
        memoryCache: Boolean = true, keepCurrent: Boolean = false
    ) {
        if (view.tag == url && view.drawable != null) return // already showing it
        view.tag = url
        cache.get(url)?.let {
            view.setImageBitmap(it)
            return
        }
        if (!keepCurrent) view.setImageDrawable(null)
        executor.execute {
            var bitmap = fetch(url, maxWidth)
            if (bitmap == null && fallbackUrl != null) bitmap = fetch(fallbackUrl, maxWidth)
            if (bitmap != null && memoryCache) cache.put(url, bitmap)
            main.post { if (view.tag == url && bitmap != null) view.setImageBitmap(bitmap) }
        }
    }

    private fun download(url: String, useCaches: Boolean): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.useCaches = useCaches
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /** Full-size photos: a small cache of their own, oldest dropped first, so they can't push out thumbnails. */
    private fun fullPhoto(url: String): ByteArray? {
        val dir = fullDir ?: return download(url, useCaches = false)
        val file = File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length)
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis())
            return try { file.readBytes() } catch (_: Exception) { null }
        }
        val bytes = download(url, useCaches = false) ?: return null
        synchronized(this) {
            try {
                file.writeBytes(bytes)
                val files = dir.listFiles()?.sortedBy { it.lastModified() }?.toMutableList() ?: return bytes
                var total = files.sumOf { it.length() }
                while (total > FULL_CACHE && files.size > 1) {
                    val oldest = files.removeAt(0)
                    total -= oldest.length()
                    oldest.delete()
                }
            } catch (_: Exception) {
            }
        }
        return bytes
    }

    private fun fetch(url: String, maxWidth: Int): Bitmap? {
        return try {
            val bytes = (if ("/thumbs/" in url) download(url, useCaches = true) else fullPhoto(url)) ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            null
        }
    }
}

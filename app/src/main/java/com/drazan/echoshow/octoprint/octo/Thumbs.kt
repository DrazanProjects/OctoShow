package com.drazan.echoshow.octoprint.octo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Pictures of files: the slicer previews OctoPrint serves, and for files that
 * have none, the ones this display has [drawn] itself.
 */
class Thumbs(private val api: OctoApi, val drawn: Previews) {

    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** The picture for [file], if there is one yet. Drawings come in the given color. */
    suspend fun preview(file: PrintFile, tint: Int): Bitmap? {
        file.thumbnail?.let { return load(it) }
        val key = drawn.key(file) + ":" + tint
        cache.get(key)?.let { return it }
        return drawn.load(file, tint)?.also { cache.put(key, it) }
    }

    /**
     * The part as far as it has been printed, for a file this display has drawn
     * itself: which it does for one being printed whether or not the slicer gave
     * it a picture, since a slicer's is only ever of the finished part. See
     * [Previews.grown].
     */
    suspend fun grown(file: PrintFile, fraction: Float, layer: Int?, tint: Int, hot: Int): Previews.Growth? =
        drawn.grown(file, fraction, layer, tint, hot)

    suspend fun load(link: String): Bitmap? {
        cache.get(link)?.let { return it }
        val url = api.resolve(link) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                api.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val bytes = r.body?.bytes() ?: return@use null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }.getOrNull()?.also { cache.put(link, it) }
        }
    }
}

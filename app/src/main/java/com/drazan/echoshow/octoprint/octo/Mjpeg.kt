package com.drazan.echoshow.octoprint.octo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads a webcam's MJPEG stream (multipart/x-mixed-replace) into bitmaps.
 *
 * The Echo Show's CPU is modest and so is the Pi's, so frames are decoded at
 * most [maxFps] times a second, downsampled when the camera is much larger
 * than the panel, and written into a small ring of reused bitmaps to keep the
 * garbage collector quiet. The flow ends with an [IOException] when the stream
 * stalls or drops; the caller decides when to reconnect.
 */
fun mjpegFrames(
    client: OkHttpClient,
    url: HttpUrl,
    maxFps: Int = 8,
    targetWidth: Int = 960,
): Flow<Bitmap> = callbackFlow {
    val call = client.newBuilder()
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
        .newCall(Request.Builder().url(url).build())

    val open = AtomicBoolean(true)
    val reader = Thread({
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Camera answered ${response.code}")
                val source = response.body?.source() ?: throw IOException("Camera sent nothing")
                val decoder = FrameDecoder(targetWidth)
                val minGap = 1000L / maxFps.coerceAtLeast(1)
                var last = 0L
                while (open.get()) {
                    val jpeg = source.nextJpeg() ?: break
                    val now = System.currentTimeMillis()
                    if (now - last < minGap) continue
                    val frame = decoder.decode(jpeg) ?: continue
                    last = now
                    trySend(frame)
                }
            }
            close(IOException("Camera stream ended"))
        } catch (e: Exception) {
            close(e as? IOException ?: IOException(e))
        }
    }, "mjpeg-reader")
    reader.start()

    awaitClose {
        open.set(false)
        call.cancel()
    }
}.conflate().flowOn(Dispatchers.IO)

/**
 * A still from the camera every [everyMillis]: what a display nobody is
 * touching shows instead of the live stream. A camera can push megabytes a
 * second, and an always-on screen has no business pulling that all night;
 * the regular requests also keep camera-streamer from dozing off.
 */
fun snapshotFrames(
    client: OkHttpClient,
    url: HttpUrl,
    everyMillis: Long = 4_000,
    targetWidth: Int = 960,
): Flow<Bitmap> = flow {
    val decoder = FrameDecoder(targetWidth)
    while (true) {
        val jpeg = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Camera answered ${response.code}")
            val body = response.body?.source() ?: throw IOException("Camera sent nothing")
            // a still is one picture, and whatever goes on past the size of one is not a still
            if (body.request(MAX_FRAME + 1)) throw IOException("Camera frame too large")
            body.readByteArray()
        }
        decoder.decode(jpeg)?.let { emit(it) }
        delay(everyMillis)
    }
}.flowOn(Dispatchers.IO)

private val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte()).toByteString()
private val EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte()).toByteString()
private const val MAX_FRAME = 8L * 1024 * 1024
private const val MAX_HEADER_LINE = 4096L

/**
 * Pulls the next JPEG out of the multipart stream. Uses the part's
 * Content-Length when the server sends one and falls back to scanning for the
 * JPEG end marker when it doesn't. Null at end of stream.
 */
internal fun BufferedSource.nextJpeg(): ByteArray? {
    var length = -1L
    // Part headers: boundary line, header lines, blank line. Tolerate leading blank lines.
    var sawHeader = false
    while (true) {
        if (exhausted()) return null
        // a line that never ends is not a header, and is not kept while waiting to find out
        val line = readUtf8LineStrict(MAX_HEADER_LINE)
        if (line.isEmpty()) {
            if (sawHeader) break else continue
        }
        sawHeader = true
        val colon = line.indexOf(':')
        if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
            length = line.substring(colon + 1).trim().toLongOrNull() ?: -1L
        }
    }

    if (length in 1..MAX_FRAME) return readByteArray(length)

    // No usable length: find start and end markers ourselves.
    val start = indexWithin(SOI, 0)
    if (start < 0) return null
    skip(start)
    val end = indexWithin(EOI, 2)
    if (end < 0) return null
    return readByteArray(end + 2)
}

/**
 * Where [marker] first turns up at or after [from], reading on until it does: -1 if
 * the stream ends first. Everything read on the way has to be held, so once that is
 * more than any frame could be this gives up with an [IOException] instead.
 */
private fun BufferedSource.indexWithin(marker: ByteString, from: Long): Long {
    var searchFrom = from
    while (true) {
        val at = buffer.indexOf(marker, searchFrom.coerceAtMost(buffer.size))
        if (at >= 0) return at
        if (buffer.size > MAX_FRAME) throw IOException("Camera frame too large")
        // what has been searched needn't be again, bar a marker cut in two at the end of it
        searchFrom = maxOf(from, buffer.size - marker.size + 1)
        if (!request(buffer.size + 1)) return -1
    }
}

/** Decodes into a rotating set of bitmaps so steady-state streaming allocates nothing. */
private class FrameDecoder(private val targetWidth: Int) {
    private val ring = arrayOfNulls<Bitmap>(3)
    private var next = 0
    private val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }

    fun decode(jpeg: ByteArray): Bitmap? {
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Halve while the result still (nearly) covers the panel: a camera streaming several
        // megapixels decodes many times faster at the size it will actually be shown.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth * 0.85f) sample *= 2

        val slot = next
        next = (next + 1) % ring.size
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inMutable = true
            inBitmap = ring[slot]
        }
        val out = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        } catch (_: IllegalArgumentException) {
            // the reusable bitmap didn't fit (camera changed resolution): start that slot afresh
            opts.inBitmap = null
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        }
        ring[slot] = out
        return out
    }
}

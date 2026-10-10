package com.drazan.echoshow.octoprint.octo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Previews this display draws for itself, for files whose G-code carries no
 * slicer thumbnail. They live on the display only: the file is read from
 * OctoPrint, drawn here, and the picture kept in the app's own storage.
 * Nothing is ever written back to the server.
 */
class Previews(private val api: OctoApi, private val dir: File, private val scratch: File) {

    enum class Outcome {
        DRAWN,
        /** The file has nothing in it to draw (or is too big to try); remembered so it isn't fetched again. */
        NOTHING,
        /** Couldn't be fetched just now. */
        FAILED,
        /** Stopped part-way because the caller said so. */
        ABANDONED,
    }

    private val oneAtATime = Mutex()

    init {
        dir.mkdirs()
    }

    /** Changes when the file is replaced by a new upload, so a stale picture is never shown for it. */
    fun key(file: PrintFile): String =
        MessageDigest.getInstance("SHA-1")
            .digest("${file.origin}:${file.path}:${file.date}:${file.size}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun picture(file: PrintFile) = File(dir, key(file) + ".png")
    private fun blank(file: PrintFile) = File(dir, key(file) + ".none")
    private fun shapeFile(file: PrintFile) = File(dir, key(file) + ".shape")
    private fun routeFile(file: PrintFile) = File(dir, key(file) + ".route")

    /**
     * Whether everything for showing the part being printed was kept with its picture:
     * its shape, and the order it is laid in. Drawings from before those were kept have less.
     */
    fun hasShape(file: PrintFile): Boolean = shapeFile(file).isFile && routeFile(file).isFile

    /** Drawn, or known to have nothing to draw: either way there is no reason to fetch it again. */
    fun settled(file: PrintFile): Boolean = picture(file).isFile || blank(file).isFile

    /**
     * The stored picture in the given color. What is stored is only how bright
     * each bit of surface is; the color comes from the display's filament setting.
     */
    suspend fun load(file: PrintFile, tint: Int): Bitmap? = withContext(Dispatchers.IO) {
        val stored = picture(file).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) } ?: return@withContext null
        val w = stored.width
        val h = stored.height
        val pixels = IntArray(w * h)
        stored.getPixels(pixels, 0, w, 0, 0, w, h)
        val r = tint shr 16 and 0xFF
        val g = tint shr 8 and 0xFF
        val b = tint and 0xFF
        for (i in pixels.indices) {
            val p = pixels[i]
            val light = p and 0xFF
            pixels[i] = (p and 0xFF000000.toInt()) or (r * light / 255 shl 16) or (g * light / 255 shl 8) or (b * light / 255)
        }
        Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * The part as it stands part-way through being printed: the [part] itself,
     * and separately the layer being [laying] on top of it, so that layer can be
     * made to glow and move while the rest holds still. [box] is where in the
     * picture that layer is, as fractions of its size; null when there is none.
     * [whole] is the finished part from the same spot, for showing what is still
     * to come.
     *
     * When the drawing is of one particular [layer] and the order that layer is
     * laid in is known, [laying] is only what the layer really lays, and [order]
     * says when: a picture that is most opaque, [FIRST], where the nozzle starts
     * and least, [LAST], where it finishes. [trail] is where the nozzle is from
     * the start of the layer to the end, as in [GcodePreview.Picture.trail].
     *
     * [dated] marks a drawing made before any order was kept: it can be shown,
     * but is worth making again.
     */
    class Growth(
        val whole: Bitmap, val part: Bitmap, val laying: Bitmap, val box: RectF?,
        val layer: Int? = null, val order: Bitmap? = null, val trail: FloatArray? = null,
        val dated: Boolean = false,
    ) {
        companion object {
            const val FIRST = 255
            /** Well clear of nothing at all, so that blurring towards the layer's edge never reads as "laid last". */
            const val LAST = 16
        }
    }

    /**
     * The part as it stands while [layer] is being printed, in the given color,
     * with that layer in [hot]. Failing a layer, or any record of the order this
     * file's layers are laid in, it is the part with [fraction] of its height
     * printed. Null when there is no shape to draw it from.
     */
    suspend fun grown(file: PrintFile, fraction: Float, layer: Int?, tint: Int, hot: Int): Growth? {
        val shape = withContext(Dispatchers.IO) { shapeOf(file) } ?: return null
        val whole = wholeOf(file, tint) ?: return null
        val dated = withContext(Dispatchers.IO) { !routeFile(file).isFile }
        val route = if (layer == null) null else withContext(Dispatchers.IO) { layingOf(file, layer) }
        return withContext(Dispatchers.Default) {
            // An order that was kept but doesn't read back whole is no reason to show nothing:
            // the part is then drawn by how much of its height is done, like one with no order.
            val p = route?.let { runCatching { GcodePreview.render(shape, it, it.layers.single(), SIZE) }.getOrNull() }
                // one layer more than is finished: the one on top is the one being printed
                ?: GcodePreview.render(shape, SIZE, (shape.top * fraction.coerceIn(0f, 1f) + LAYER_MM).coerceAtMost(shape.top))
                ?: return@withContext null
            val reached = p.reached
            val n = p.size
            val part = IntArray(n * n)
            val laying = IntArray(n * n)
            val r = tint shr 16 and 0xFF
            val g = tint shr 8 and 0xFF
            val b = tint and 0xFF
            var left = n
            var top = n
            var right = -1
            var bottom = -1
            for (i in part.indices) {
                val cover = p.alpha[i].toInt() and 0xFF
                if (cover == 0) continue
                val light = p.light[i].toInt() and 0xFF
                part[i] = (cover shl 24) or (r * light / 255 shl 16) or (g * light / 255 shl 8) or (b * light / 255)
                val fresh = p.fresh[i].toInt() and 0xFF
                // with the order known, a spot the nozzle never visits is not part of the layer
                if (fresh == 0 || (reached != null && reached[i].toInt() == 0)) continue
                laying[i] = (cover * fresh / 255 shl 24) or (hot and 0xFFFFFF)
                val x = i % n
                val y = i / n
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
            var order: Bitmap? = null
            if (reached != null && right >= 0) {
                var moments = IntArray(n * n) { if (laying[it] != 0) reached[it].toInt() and 0xFF else 0 }
                // The screen blends neighbouring pixels, and blending with nothing at the layer's
                // edge would nibble at it: so each time is carried a few pixels past the edge.
                repeat(3) { moments = spread(moments, n) }
                val steps = Growth.FIRST - Growth.LAST
                order = Bitmap.createBitmap(
                    IntArray(n * n) { i ->
                        val m = moments[i]
                        if (m == 0) 0 else ((Growth.FIRST - (m - 1) * steps / (GcodePreview.MOMENTS - 1)) shl 24) or 0xFFFFFF
                    },
                    n, n, Bitmap.Config.ARGB_8888,
                )
            }
            Growth(
                whole = whole,
                part = Bitmap.createBitmap(part, n, n, Bitmap.Config.ARGB_8888),
                laying = Bitmap.createBitmap(laying, n, n, Bitmap.Config.ARGB_8888),
                box = if (right < 0) null else RectF(left / n.toFloat(), top / n.toFloat(), (right + 1f) / n, (bottom + 1f) / n),
                layer = route?.layers?.single()?.number.takeIf { reached != null },
                order = order,
                trail = p.trail.takeIf { order != null },
                dated = dated,
            )
        }
    }

    /** Gives every pixel that has no time the earliest of those next to it. */
    private fun spread(src: IntArray, n: Int): IntArray {
        val out = src.copyOf()
        for (y in 0 until n) for (x in 0 until n) {
            if (src[y * n + x] != 0) continue
            var best = 0
            for (yy in maxOf(0, y - 1)..minOf(n - 1, y + 1)) for (xx in maxOf(0, x - 1)..minOf(n - 1, x + 1)) {
                val v = src[yy * n + xx]
                if (v != 0 && (best == 0 || v < best)) best = v
            }
            out[y * n + x] = best
        }
        return out
    }

    /** The last shape read, since the same job asks for it again at every layer. */
    private var held: Pair<String, GcodePreview.Shape>? = null
    /** And the last finished picture, in the color it was wanted in, for the same reason. */
    private var heldWhole: Triple<String, Int, Bitmap>? = null

    private suspend fun wholeOf(file: PrintFile, tint: Int): Bitmap? {
        val key = key(file)
        heldWhole?.takeIf { it.first == key && it.second == tint }?.let { return it.third }
        return load(file, tint)?.also { heldWhole = Triple(key, tint, it) }
    }

    private fun shapeOf(file: PrintFile): GcodePreview.Shape? {
        val key = key(file)
        held?.takeIf { it.first == key }?.let { return it.second }
        val from = shapeFile(file).takeIf { it.isFile } ?: return null
        return runCatching {
            DataInputStream(GZIPInputStream(from.inputStream().buffered())).use { src ->
                if (src.readInt() != SHAPE_FORMAT) return null
                val originX = src.readFloat()
                val originY = src.readFloat()
                val cell = src.readFloat()
                val cols = src.readInt()
                val rows = src.readInt()
                val top = src.readFloat()
                if (cols !in 1..MAX_SIDE || rows !in 1..MAX_SIDE) return null
                GcodePreview.Shape(originX, originY, cell, cols, rows, top, FloatArray(cols * rows) { src.readUnsignedShort() / 100f })
            }
        }.getOrNull()?.also { held = key to it }
    }

    private fun saveShape(shape: GcodePreview.Shape, to: File) {
        val part = File(to.path + ".part")
        DataOutputStream(GZIPOutputStream(part.outputStream().buffered())).use { out ->
            out.writeInt(SHAPE_FORMAT)
            out.writeFloat(shape.originX)
            out.writeFloat(shape.originY)
            out.writeFloat(shape.cell)
            out.writeInt(shape.cols)
            out.writeInt(shape.rows)
            out.writeFloat(shape.top)
            // hundredths of a millimetre: finer than any layer, and half the size of the floats
            for (h in shape.height) out.writeShort((h * 100f).roundToInt().coerceIn(0, 0xFFFF))
        }
        if (!part.moveOver(to)) part.delete()
    }

    /**
     * Layer [number] of the order [file] is laid in, with the map it is drawn on. Null
     * when that layer lays nothing of the part, or no order was kept for the file.
     *
     * Only the one layer is read. A tall part has a great many, each of them is wanted
     * once, and this display has little memory to hold the rest in.
     */
    private fun layingOf(file: PrintFile, number: Int): GcodePreview.Route? {
        val from = routeFile(file).takeIf { it.isFile } ?: return null
        return runCatching {
            RandomAccessFile(from, "r").use { src ->
                if (src.readInt() != ROUTE_FORMAT) return null
                val originX = src.readFloat()
                val originY = src.readFloat()
                val cell = src.readFloat()
                val cols = src.readInt()
                val rows = src.readInt()
                val count = src.readInt()
                if (count !in 0..MAX_LAYERS || cols !in 1..MAX_SIDE || rows !in 1..MAX_SIDE) return null
                // every layer's particulars come first, then every layer's squares in the same order
                val index = ByteBuffer.wrap(ByteArray(count * ROUTE_ENTRY_BYTES).also { src.readFully(it) })
                var at = src.filePointer
                repeat(count) {
                    val layer = index.int
                    val z = index.float
                    val left = index.short.toInt()
                    val top = index.short.toInt()
                    val width = index.short.toInt()
                    val height = index.short.toInt()
                    val length = index.int
                    if (length !in 0..MAX_LAYER_BYTES) return null
                    if (layer == number) {
                        if (left < 0 || top < 0 || width < 1 || height < 1 || left + width > cols || top + height > rows) return null
                        val packed = ByteArray(length)
                        src.seek(at)
                        src.readFully(packed)
                        val one = GcodePreview.Route.Layer(layer, z, left, top, width, height, packed)
                        return GcodePreview.Route(originX, originY, cell, cols, rows, listOf(one))
                    }
                    at += length
                }
                null
            }
        }.getOrNull()
    }

    private fun saveRoute(route: GcodePreview.Route, to: File) {
        val part = File(to.path + ".part")
        DataOutputStream(part.outputStream().buffered()).use { out ->
            out.writeInt(ROUTE_FORMAT)
            out.writeFloat(route.originX)
            out.writeFloat(route.originY)
            out.writeFloat(route.cell)
            out.writeInt(route.cols)
            out.writeInt(route.rows)
            out.writeInt(route.layers.size)
            for (layer in route.layers) {
                out.writeInt(layer.number)
                out.writeFloat(layer.z)
                out.writeShort(layer.left)
                out.writeShort(layer.top)
                out.writeShort(layer.width)
                out.writeShort(layer.height)
                out.writeInt(layer.packed.size)
            }
            for (layer in route.layers) out.write(layer.packed)
        }
        if (!part.moveOver(to)) part.delete()
    }

    /** Takes the place of [to], which a drawing being made again already has. */
    private fun File.moveOver(to: File): Boolean {
        to.delete()
        return renameTo(to)
    }

    /**
     * Fetches the file's G-code and draws it.
     *
     * @param gentle asked throughout the download: while it says yes the transfer
     *   is held to a trickle, because the Pi serving it is busy running a print.
     * @param keepGoing asked throughout: when it says no, the work is dropped.
     * @param again draw it even though there is a picture already, if that picture
     *   came without the part's shape or the order it is laid in.
     */
    suspend fun draw(
        file: PrintFile, gentle: () -> Boolean, keepGoing: () -> Boolean, again: Boolean = false,
    ): Outcome = oneAtATime.withLock {
        if (settled(file) && !(again && picture(file).isFile && !hasShape(file))) return Outcome.DRAWN
        val context = coroutineContext
        val alive = { context.isActive && keepGoing() }
        val source = File(scratch, "preview-source.gcode")
        withContext(Dispatchers.IO) {
            try {
                when (fetch(file, source, gentle, alive)) {
                    Fetch.STOPPED -> return@withContext Outcome.ABANDONED
                    Fetch.TOO_BIG -> {
                        blank(file).createNewFile()
                        return@withContext Outcome.NOTHING
                    }
                    Fetch.DONE -> Unit
                }
                val read = withContext(Dispatchers.Default) { GcodePreview.read({ source.inputStream() }, alive) }
                val shape = read?.shape
                val drawn = shape?.let { withContext(Dispatchers.Default) { GcodePreview.render(it, SIZE, keepGoing = alive) } }
                if (read == null || shape == null || drawn == null) {
                    if (!alive()) return@withContext Outcome.ABANDONED
                    // a picture that is already there stays; there was just no shape to add to it
                    if (!picture(file).isFile) blank(file).createNewFile()
                    return@withContext Outcome.NOTHING
                }
                saveShape(shape, shapeFile(file))
                saveRoute(read.route, routeFile(file))
                save(drawn, picture(file))
                Outcome.DRAWN
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w("Previews", "Couldn't draw ${file.path}", e)
                Outcome.FAILED
            } catch (_: OutOfMemoryError) {
                // More than this display can hold in its head. Set down as having nothing to
                // draw, so that it isn't fetched again only to end the same way.
                if (!picture(file).isFile) blank(file).createNewFile()
                Outcome.NOTHING
            } finally {
                source.delete()
            }
        }
    }

    private enum class Fetch { DONE, STOPPED, TOO_BIG }

    private fun fetch(file: PrintFile, to: File, gentle: () -> Boolean, alive: () -> Boolean): Fetch {
        val request = Request.Builder().url(api.url("downloads/files/${file.origin}/${file.path}")).build()
        api.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("OctoPrint answered ${response.code}")
            val body = response.body ?: throw IOException("OctoPrint sent nothing")
            if (body.contentLength() > MAX_BYTES) return Fetch.TOO_BIG

            to.outputStream().use { out ->
                val input = body.byteStream()
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                var paced = 0L
                var pacedSince = System.nanoTime()
                while (true) {
                    if (!alive()) return Fetch.STOPPED
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    total += n
                    if (total > MAX_BYTES) return Fetch.TOO_BIG

                    if (gentle()) {
                        // sleep off whatever arrived faster than the trickle allows
                        paced += n
                        val earliest = pacedSince + paced * 1_000_000_000L / GENTLE_BYTES_PER_SECOND
                        val ahead = (earliest - System.nanoTime()) / 1_000_000L
                        if (ahead > 0) Thread.sleep(ahead.coerceAtMost(400))
                    } else {
                        paced = 0
                        pacedSince = System.nanoTime()
                    }
                }
            }
        }
        return Fetch.DONE
    }

    private fun save(picture: GcodePreview.Picture, to: File) {
        val pixels = IntArray(picture.size * picture.size) { i ->
            val light = picture.light[i].toInt() and 0xFF
            val cover = picture.alpha[i].toInt() and 0xFF
            (cover shl 24) or (light shl 16) or (light shl 8) or light
        }
        val bitmap = Bitmap.createBitmap(pixels, picture.size, picture.size, Bitmap.Config.ARGB_8888)
        // written under another name first, so a half-written picture is never mistaken for a finished one
        val part = File(to.path + ".part")
        part.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!part.moveOver(to)) {
            part.delete()
            throw IOException("Couldn't store the preview")
        }
    }

    private companion object {
        const val SIZE = 320
        const val SHAPE_FORMAT = 1
        const val ROUTE_FORMAT = 1
        /** What a route's list of layers says of each: number, height, where its squares sit, how many bytes they pack to. */
        const val ROUTE_ENTRY_BYTES = 4 + 4 + 4 * 2 + 4
        /** More than any part has; a list claiming more is a damaged file. */
        const val MAX_LAYERS = 100_000
        /** Likewise for how many squares a kept map has along a side, and how many bytes one layer packs to. */
        const val MAX_SIDE = 4096
        const val MAX_LAYER_BYTES = 4 * 1024 * 1024
        /** A usual layer. Only decides how thick the glowing top of a part-printed picture is. */
        const val LAYER_MM = 0.2f
        /** Larger than any sensible print; guards the display's storage against something odd. */
        const val MAX_BYTES = 300L * 1024 * 1024
        /** About what OctoPrint's own G-code viewer pulls; slow enough not to trouble a print. */
        const val GENTLE_BYTES_PER_SECOND = 1_200_000L
    }
}

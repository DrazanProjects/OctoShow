package com.drazan.echoshow.octoprint.octo

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws a picture of a part from nothing but its G-code, for files the slicer
 * didn't put a thumbnail in.
 *
 * The file is read twice. The first pass finds where the part sits and how
 * thick its layers are; the second lays every extruded line into a [Stack] of
 * the build plate, slice by slice, and notes how high the plastic reaches on
 * each small square of the bed. That is then looked at from above and to one
 * side, like a slicer's preview, turned so the part fills the picture, and
 * shaded by which way each bit of surface faces.
 *
 * Because the stack knows every layer, the air under an overhang stays air, a
 * pocket in the side of a part stays a pocket, and a part being printed shows
 * what is really inside it. Supports and brims are drawn too, dimmer than the
 * part. Skirts and purge lines are left out when the slicer labels them.
 *
 * The result is brightness and coverage only. Color is added when it is shown,
 * so the part appears in whatever filament color the display is set to.
 *
 * The second pass also notes the order each layer is laid down in, so that a
 * layer being printed can be shown filling in the way the nozzle really goes
 * over it: see [GcodePreview.Route].
 */
object GcodePreview {

    /**
     * [width] x [height] pixels: how bright the surface is, and how much of the pixel it
     * covers. [fresh] is how much of what shows there is the layer being laid down,
     * which only a picture of the part half-printed has any of. [shadow] is how far the
     * bed is darkened around the part, where the part doesn't cover it.
     *
     * A picture drawn for one layer of a [Route] also says when in that layer the
     * nozzle gets to each pixel of it, in [reached]: 0 for never, otherwise 1..255
     * from the layer's start to its end. [trail] is where in the picture the nozzle
     * is at each of those 255 moments, as (across, down) pairs of fractions of its size.
     */
    class Picture(
        val width: Int, val height: Int, val light: ByteArray, val alpha: ByteArray, val fresh: ByteArray,
        val shadow: ByteArray, val reached: ByteArray? = null, val trail: FloatArray? = null,
    ) {
        /** Pixel [i] with its shadow under it, as a grey with an alpha: A, then the grey three times. */
        fun shown(i: Int): Int {
            val cover = alpha[i].toInt() and 0xFF
            val dark = shadow[i].toInt() and 0xFF
            val all = cover + dark * (255 - cover) / 255
            if (all == 0) return 0
            val grey = (light[i].toInt() and 0xFF) * cover / all
            return (all shl 24) or (grey shl 16) or (grey shl 8) or grey
        }
    }

    /**
     * The part itself: every layer of it, and how high the plastic reaches on each square
     * of the bed. Kept alongside its picture so the part can be drawn again as it stands
     * part-way through being printed, and from other sides.
     */
    class Shape(
        val originX: Float, val originY: Float, val cell: Float,
        val cols: Int, val rows: Int,
        /** How tall the finished part is, in millimetres. */
        val top: Float,
        val height: FloatArray,
        val stack: Stack,
    ) {
        private val views = arrayOfNulls<View>(TURNS)

        /**
         * Where it is looked at from, [turn] quarter turns round from the side that shows
         * it best. Worked out from the shape, so a stored one needn't keep it.
         */
        fun view(turn: Int = 0): View = views[turn] ?: View.of(this, turn).also { views[turn] = it }
    }

    /**
     * The plastic slice by slice, from the bed up: for every [slice] millimetres of
     * height, a bit for each square of the bed saying whether there is any there.
     * [part] is the model itself; [extra] is what is printed to help it, supports and
     * brims. A slice with none of it has no array at all.
     */
    class Stack(val cols: Int, val rows: Int, val slices: Int, val slice: Float) {
        /** How many longs one row of bits takes. */
        val words = (cols + 63) / 64
        val part = arrayOfNulls<LongArray>(slices)
        val extra = arrayOfNulls<LongArray>(slices)

        /** Which slice height [z] is in. A height right on the line between two belongs to the lower. */
        fun of(z: Float): Int = (z / slice - 0.001f).toInt()

        fun solid(gx: Int, gy: Int, s: Int): Boolean {
            if (gx < 0 || gy < 0 || gx >= cols || gy >= rows || s < 0 || s >= slices) return false
            val at = gy * words + (gx ushr 6)
            return (part[s]?.let { it[at] ushr gx and 1L != 0L } ?: false) || (extra[s]?.let { it[at] ushr gx and 1L != 0L } ?: false)
        }

        /**
         * Neighbouring lines can leave hairline gaps where they didn't quite meet on the
         * grid. Grow every slice by a square, then shrink it back: gaps close, edges stay put.
         */
        fun closeGaps() {
            val grown = LongArray(words * rows)
            val across = LongArray(words * rows)
            for (bits in part) {
                if (bits == null) continue
                spread(bits, across, grown, wider = true)
                spread(grown, across, bits, wider = false)
            }
        }

        /** Each square of [to] is the squares of [from] around it, any of them if [wider] and otherwise all. */
        private fun spread(from: LongArray, across: LongArray, to: LongArray, wider: Boolean) {
            for (y in 0 until rows) {
                val row = y * words
                for (w in 0 until words) {
                    val v = from[row + w]
                    val left = (v shl 1) or (if (w > 0) from[row + w - 1] ushr 63 else 0L)
                    val right = (v ushr 1) or (if (w < words - 1) from[row + w + 1] shl 63 else 0L)
                    across[row + w] = if (wider) v or left or right else v and left and right
                }
            }
            for (y in 0 until rows) {
                val row = y * words
                val up = if (y > 0) row - words else row
                val down = if (y < rows - 1) row + words else row
                for (w in 0 until words) {
                    val a = across[up + w]
                    val b = across[row + w]
                    val c = across[down + w]
                    to[row + w] = if (wider) a or b or c else a and b and c
                }
            }
        }
    }

    /**
     * Where a part is looked at from, and how it sits in the picture. A long part is
     * turned to lie across the picture whichever way the slicer put it on the bed,
     * and the framing is of the plastic itself: a bar laid corner to corner covers a
     * nearly square patch of bed, and framing that left it a sliver in an empty picture.
     *
     * [right] and [up] are the middle of what shows, measured along the picture's own
     * axes, and [wide] and [tall] how far it spans along them. [long], [broad] and
     * [high] are the model's own size, for saying how big it really is.
     */
    class View(
        val azimuth: Double, val right: Float, val up: Float, val wide: Float, val tall: Float,
        val long: Float, val broad: Float, val high: Float,
    ) {
        companion object {
            fun of(shape: Shape, turn: Int): View {
                val stack = shape.stack
                // where the model itself stands, at any height: supports and brims don't say which way it lies
                val foot = LongArray(stack.words * stack.rows)
                for (bits in stack.part) if (bits != null) for (i in foot.indices) foot[i] = foot[i] or bits[i]

                var n = 0
                var sumX = 0.0; var sumY = 0.0; var sumXX = 0.0; var sumYY = 0.0; var sumXY = 0.0
                forEachSquare(shape, foot) { x, y ->
                    n++
                    sumX += x; sumY += y; sumXX += x * x; sumYY += y * y; sumXY += x * y
                }
                val spreadX = if (n > 0) sumXX / n - sumX / n * (sumX / n) else 0.0
                val spreadY = if (n > 0) sumYY / n - sumY / n * (sumY / n) else 0.0
                val spreadXY = if (n > 0) sumXY / n - sumX / n * (sumY / n) else 0.0
                val lie = 0.5 * atan2(2 * spreadXY, spreadX - spreadY)
                val half = hypot((spreadX - spreadY) / 2, spreadXY)
                val mean = (spreadX + spreadY) / 2
                val isLong = mean + half >= LONG_PART * LONG_PART * max(mean - half, 1e-6)

                val azimuth = turn * PI / 2 + if (!isLong) AZIMUTH else {
                    // along its length with a little of its end showing, from whichever side is nearest the usual view
                    listOf(-lie - SKEW, -lie + SKEW)
                        .flatMap { listOf(it - PI, it, it + PI) }
                        .filter { abs(it) <= PI / 2 }
                        .minByOrNull { abs(it - AZIMUTH) } ?: AZIMUTH
                }

                // how big it is: along its own length if it has one, otherwise along the bed
                val alongX = if (isLong) cos(lie) else 1.0
                val alongY = if (isLong) sin(lie) else 0.0
                var minA = Double.MAX_VALUE; var maxA = -Double.MAX_VALUE
                var minB = Double.MAX_VALUE; var maxB = -Double.MAX_VALUE
                forEachSquare(shape, foot) { x, y ->
                    val a = x * alongX + y * alongY
                    val b = y * alongX - x * alongY
                    if (a < minA) minA = a
                    if (a > maxA) maxA = a
                    if (b < minB) minB = b
                    if (b > maxB) maxB = b
                }

                // the picture's right and up, as in render
                val sinA = sin(azimuth).toFloat(); val cosA = cos(azimuth).toFloat()
                val sinE = sin(ELEVATION).toFloat(); val cosE = cos(ELEVATION).toFloat()
                val ux = sinA * sinE; val uy = cosA * sinE
                var minR = Float.MAX_VALUE; var maxR = -Float.MAX_VALUE
                var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE
                for (gy in 0 until shape.rows) for (gx in 0 until shape.cols) {
                    val h = shape.height[gy * shape.cols + gx]
                    if (h <= 0f) continue
                    val x = shape.originX + (gx + 0.5f) * shape.cell
                    val y = shape.originY + (gy + 0.5f) * shape.cell
                    val r = x * cosA - y * sinA
                    val u = x * ux + y * uy
                    if (r < minR) minR = r
                    if (r > maxR) maxR = r
                    if (u < minU) minU = u
                    if (u + h * cosE > maxU) maxU = u + h * cosE
                }
                val edge = shape.cell * 2
                return View(
                    azimuth, (minR + maxR) / 2, (minU + maxU) / 2, maxR - minR + edge, maxU - minU + edge,
                    // a file labelled as nothing but supports has no model to measure
                    if (n > 0) (maxA - minA).toFloat() + shape.cell else 0f,
                    if (n > 0) (maxB - minB).toFloat() + shape.cell else 0f,
                    shape.top,
                )
            }

            /** Every square of bed set in [bits], as its middle in millimetres. */
            private inline fun forEachSquare(shape: Shape, bits: LongArray, each: (Double, Double) -> Unit) {
                val words = shape.stack.words
                for (gy in 0 until shape.rows) for (gx in 0 until shape.cols) {
                    if (bits[gy * words + (gx ushr 6)] ushr gx and 1L == 0L) continue
                    each((shape.originX + (gx + 0.5f) * shape.cell).toDouble(), (shape.originY + (gy + 0.5f) * shape.cell).toDouble())
                }
            }
        }
    }

    /**
     * The order the part is laid down in: for every layer, a map of the bed saying
     * when in that layer the nozzle first reaches each spot. The map is of the same
     * stretch of bed as the [Shape]'s, in squares [ROUTE_CELLS] times the size.
     *
     * "When" is counted the way OctoPrint's Dashboard plugin counts how far through
     * a layer a print is, so that its figure can be turned straight back into a
     * place: movement commands sent since the layer began, out of all the layer
     * has. The layers are the plugin's too. It marks each one in the files it
     * stores, and a file it never marked has no layers here.
     */
    class Route(
        val originX: Float, val originY: Float, val cell: Float,
        val cols: Int, val rows: Int,
        val layers: List<Layer>,
    ) {
        /**
         * One layer: its [number] as the plugin counts them, from 1, and the height
         * [z] it is laid at. Only the [width] x [height] squares around its plastic
         * are kept, the first of them at ([left], [top]), and those [packed] small.
         */
        class Layer(
            val number: Int, val z: Float,
            val left: Int, val top: Int, val width: Int, val height: Int,
            val packed: ByteArray,
        ) {
            /**
             * Unpacked: a byte for each square, 0 where the layer lays nothing and
             * otherwise 1..255 for its start through to its end. After those, where
             * the nozzle is at each of the 255 moments, as (x, y) pairs of bytes that
             * run 0..255 across the same squares.
             */
            fun unpacked(): ByteArray {
                val out = ByteArray(width * height + 2 * MOMENTS)
                val inflater = Inflater()
                try {
                    inflater.setInput(packed)
                    var got = 0
                    while (got < out.size && !inflater.finished()) {
                        val n = inflater.inflate(out, got, out.size - got)
                        if (n == 0 && inflater.needsInput()) break
                        got += n
                    }
                } finally {
                    inflater.end()
                }
                // packed as how each square differs from the one before it
                for (i in 1 until width * height) if (i % width != 0) out[i] = (out[i] + out[i - 1]).toByte()
                return out
            }
        }
    }

    /** What reading a file gives: the part, and the order it is laid down in. */
    class Reading(val shape: Shape, val route: Route)

    /** Reads the part out of its G-code and draws it finished. Null as for [read]. */
    fun draw(
        open: () -> InputStream, width: Int = WIDTH, height: Int = HEIGHT, keepGoing: () -> Boolean = { true },
    ): Picture? = shape(open, keepGoing)?.let { render(it, width, height, keepGoing = keepGoing) }

    /**
     * Draws [shape] as it stands once the print has reached [upTo] millimetres, from
     * [turn] quarter turns round. The framing is the finished part's whatever the height,
     * so pictures of different stages lie exactly over one another.
     */
    fun render(
        shape: Shape, width: Int = WIDTH, height: Int = HEIGHT, upTo: Float = shape.top, turn: Int = 0,
        keepGoing: () -> Boolean = { true },
    ): Picture? = render(HeightMap(shape, upTo), shape.view(turn), width, height, keepGoing)

    /**
     * Draws [shape] as it stands while [layer] of [route] is being laid: built up
     * to that layer and no further, with the layer itself marked and the order it
     * goes down in given by [Picture.reached] and [Picture.trail].
     */
    fun render(
        shape: Shape, route: Route, layer: Route.Layer, width: Int = WIDTH, height: Int = HEIGHT, turn: Int = 0,
        keepGoing: () -> Boolean = { true },
    ): Picture? = render(HeightMap(shape, layer.z - KEPT_TO_MM, laying = true), shape.view(turn), width, height, keepGoing, route, layer)

    /** The part alone. Null as for [read]. */
    fun shape(open: () -> InputStream, keepGoing: () -> Boolean = { true }): Shape? = read(open, keepGoing)?.shape

    /**
     * @param open opens the G-code from the start; called twice.
     * @param keepGoing checked regularly; returning false abandons the work.
     * @return null when the file has nothing printable in it, or the work was abandoned.
     */
    fun read(open: () -> InputStream, keepGoing: () -> Boolean = { true }): Reading? {
        val survey = Survey()
        if (!open().use { walk(it, keepGoing, survey) }) return null
        survey.close()
        val labelled = survey.labelled.count > 0
        val extent = if (labelled) survey.labelled else survey.everything
        if (extent.count == 0 || extent.maxZ <= 0f) return null

        val bed = HeightMap.over(extent, (if (labelled) survey.labelledLevels else survey.levels).thickness())
        val tracer = Tracer(bed, survey.moves)
        val painter = object : Sink {
            override fun line(
                x0: Float, y0: Float, x1: Float, y1: Float, z: Float, width: Float, kind: Int, from: Float, to: Float,
            ) {
                if (kind != LEFT_OUT || !labelled) {
                    bed.lay(x0, y0, x1, y1, z, width, extra = kind == EXTRA && labelled)
                    tracer.lay(x0, y0, x1, y1, z, width, from, to)
                }
            }

            override fun move() = tracer.move()
            override fun layer(number: Int) = tracer.begin(number)
        }
        if (!open().use { walk(it, keepGoing, painter) }) return null
        bed.closeGaps()
        if (!keepGoing()) return null
        return Reading(Shape(bed.originX, bed.originY, bed.cell, bed.cols, bed.rows, bed.top, bed.height, bed.stack), tracer.route())
    }

    /* ---------------- reading the G-code ---------------- */

    private interface Sink {
        /**
         * One extruded line. [kind] is what the slicer labelled it as: [PART], [EXTRA] or
         * [LEFT_OUT]. It is the stretch [from]..[to] of the movement command it came from:
         * all of it, 0 to 1, unless that was an arc, which arrives in pieces.
         */
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, z: Float, width: Float, kind: Int, from: Float, to: Float)

        /** A movement command, whether or not it lays anything. Comes ahead of its own [line]s. */
        fun move() {}

        /** The Dashboard plugin's mark that layer [number] starts here. */
        fun layer(number: Int) {}
    }

    private class Extent {
        var count = 0
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = 0f

        fun add(x0: Float, y0: Float, x1: Float, y1: Float, z: Float) {
            count++
            minX = min(minX, min(x0, x1)); maxX = max(maxX, max(x0, x1))
            minY = min(minY, min(y0, y1)); maxY = max(maxY, max(y0, y1))
            maxZ = max(maxZ, z)
        }
    }

    /**
     * How much plastic is laid at each height, to tell how thick a file's layers are.
     * Not from the moves between them: a seam that climbs into its layer makes far more
     * small rises than the file has layers, each with next to nothing laid at its height.
     */
    private class Levels {
        private val laid = FloatArray(MAX_LEVELS)

        fun add(z: Float, length: Float) {
            val level = (z * 100f).roundToInt()
            if (level in laid.indices) laid[level] += length
        }

        /** The largest step between heights that much is laid at, so that no layer is left with a gap under it. */
        fun thickness(): Float {
            val most = laid.maxOrNull() ?: 0f
            var last = 0
            var largest = 0
            for (level in laid.indices) {
                if (laid[level] <= 0f || laid[level] < most / 40f) continue
                val step = level - last
                if (step in (largest + 1)..MAX_LAYER_STEP) largest = step
                last = level
            }
            return (if (largest > 0) largest else MAX_LAYER_STEP) / 100f
        }
    }

    private class Survey : Sink {
        val labelled = Extent()
        val everything = Extent()
        val labelledLevels = Levels()
        val levels = Levels()
        /** How many movement commands each layer has, by its number: what the plugin divides by. */
        val moves = HashMap<Int, Int>()
        private var layer = 0
        private var count = 0

        override fun line(
            x0: Float, y0: Float, x1: Float, y1: Float, z: Float, width: Float, kind: Int, from: Float, to: Float,
        ) {
            val length = hypot(x1 - x0, y1 - y0)
            everything.add(x0, y0, x1, y1, z)
            levels.add(z, length)
            if (kind != LEFT_OUT) {
                labelled.add(x0, y0, x1, y1, z)
                labelledLevels.add(z, length)
            }
        }

        override fun move() {
            count++
        }

        override fun layer(number: Int) {
            close()
            layer = number
        }

        /** The last layer ends with the file. */
        fun close() {
            if (layer > 0) moves[layer] = count
            count = 0
        }
    }

    /**
     * Follows the moves in a file and reports each one that lays plastic.
     * Hand-rolled over raw bytes: these files run to a hundred megabytes and
     * the device reading them is slow, so no line is ever turned into a String.
     * Returns false if [keepGoing] said to stop.
     */
    private fun walk(input: InputStream, keepGoing: () -> Boolean, sink: Sink): Boolean {
        val buffer = ByteArray(1 shl 16)
        var filled = 0
        var at = 0
        val line = ByteArray(256)

        var x = 0f; var y = 0f; var z = 0f; var e = 0.0
        var absolute = true
        var absoluteE = true
        var width = DEFAULT_WIDTH
        var kind = LEFT_OUT       // what the section the slicer has labelled us into is
        var lines = 0

        while (true) {
            // next line into `line`
            var n = 0
            var ended = false
            while (true) {
                if (at == filled) {
                    filled = input.read(buffer)
                    at = 0
                    if (filled <= 0) { ended = true; break }
                }
                val b = buffer[at++]
                if (b == NEWLINE) break
                if (n < line.size) line[n++] = b
            }
            if (ended && n == 0) return true
            if (++lines and 0x3FFF == 0 && !keepGoing()) return false

            var i = 0
            while (i < n && (line[i] == SPACE || line[i] == TAB)) i++
            if (i >= n) { if (ended) return true else continue }

            when (line[i]) {
                SEMICOLON -> {
                    // ";TYPE:Outer wall" (Orca, Prusa, Cura), "; FEATURE: Outer wall" (Bambu), ";WIDTH:0.42"
                    var j = i + 1
                    while (j < n && line[j] == SPACE) j++
                    if (startsWith(line, j, n, "TYPE:")) kind = kindOf(line, j + 5, n)
                    else if (startsWith(line, j, n, "FEATURE:")) kind = kindOf(line, j + 8, n)
                    else if (startsWith(line, j, n, "WIDTH:")) {
                        val w = number(line, j + 6, n)
                        if (w > 0.1f && w < 2f) width = w
                    }
                }
                G, G_LOWER -> {
                    val code = integer(line, i + 1, n)
                    when (code) {
                        0, 1, 2, 3 -> {
                            // The plugin counts a line as a move when it starts "G" and one of
                            // these digits, then a gap: so not lower case, and not "G01".
                            val after = if (i + 2 < n) line[i + 2] else NEWLINE
                            if (line[i] == G && (after == SPACE || after == TAB || after == RETURN || after == NEWLINE)) sink.move()
                            var nx = x; var ny = y; var nz = z
                            var de = 0.0
                            var hasE = false
                            var ci = 0f; var cj = 0f
                            var j = i + 1
                            while (j < n) {
                                val c = line[j]
                                if (c == SEMICOLON) break
                                val letter = c.toInt() or 0x20      // lower-case
                                if (letter in 'a'.code..'z'.code && j + 1 < n) {
                                    val v = number(line, j + 1, n)
                                    when (letter) {
                                        'x'.code -> nx = if (absolute) v else x + v
                                        'y'.code -> ny = if (absolute) v else y + v
                                        'z'.code -> nz = if (absolute) v else z + v
                                        'e'.code -> {
                                            hasE = true
                                            de = if (absoluteE) v - e else v.toDouble()
                                            e = if (absoluteE) v.toDouble() else e + v
                                        }
                                        'i'.code -> ci = v
                                        'j'.code -> cj = v
                                    }
                                }
                                j++
                                while (j < n && isNumberChar(line[j])) j++
                            }
                            val laying = hasE && de > 0.0 && (nx != x || ny != y)
                            if (laying) {
                                if (code >= 2 && (ci != 0f || cj != 0f)) {
                                    arc(x, y, nx, ny, ci, cj, clockwise = code == 2, z = nz, width = width, kind = kind, sink = sink)
                                } else {
                                    sink.line(x, y, nx, ny, nz, width, kind, 0f, 1f)
                                }
                            }
                            x = nx; y = ny; z = nz
                        }
                        90 -> absolute = true
                        91 -> absolute = false
                        92 -> {
                            // set position: almost always "G92 E0"
                            var j = i + 1
                            while (j < n) {
                                val c = line[j]
                                if (c == SEMICOLON) break
                                val letter = c.toInt() or 0x20
                                if (letter in 'a'.code..'z'.code && j + 1 < n) {
                                    val v = number(line, j + 1, n)
                                    when (letter) {
                                        'x'.code -> x = v
                                        'y'.code -> y = v
                                        'z'.code -> z = v
                                        'e'.code -> e = v.toDouble()
                                    }
                                }
                                j++
                                while (j < n && isNumberChar(line[j])) j++
                            }
                        }
                    }
                }
                M, M_LOWER -> when (integer(line, i + 1, n)) {
                    82 -> absoluteE = true
                    83 -> absoluteE = false
                    117 -> if (startsWith(line, i, n, LAYER_MARK)) {
                        val number = integer(line, i + LAYER_MARK.length, n)
                        if (number in 1..MAX_LAYERS) sink.layer(number)
                    }
                }
            }
            if (ended) return true
        }
    }

    /** An arc move, as short straight pieces. [ci], [cj] are the centre's offset from the start. */
    private fun arc(
        x0: Float, y0: Float, x1: Float, y1: Float, ci: Float, cj: Float,
        clockwise: Boolean, z: Float, width: Float, kind: Int, sink: Sink,
    ) {
        val cx = x0 + ci
        val cy = y0 + cj
        val radius = hypot(ci, cj)
        val a0 = atan2((y0 - cy).toDouble(), (x0 - cx).toDouble())
        var sweep = atan2((y1 - cy).toDouble(), (x1 - cx).toDouble()) - a0
        if (clockwise) { if (sweep >= 0) sweep -= 2 * PI } else { if (sweep <= 0) sweep += 2 * PI }
        val pieces = ceil(abs(sweep) * radius / ARC_PIECE_MM).toInt().coerceIn(1, 360)
        var px = x0
        var py = y0
        for (k in 1..pieces) {
            val a = a0 + sweep * k / pieces
            val qx = if (k == pieces) x1 else cx + radius * cos(a).toFloat()
            val qy = if (k == pieces) y1 else cy + radius * sin(a).toFloat()
            sink.line(px, py, qx, qy, z, width, kind, (k - 1f) / pieces, k.toFloat() / pieces)
            px = qx; py = qy
        }
    }

    /** What a slicer's section label says is being laid. "Skirt/Brim" is one label, and comes out as a skirt. */
    private fun kindOf(line: ByteArray, from: Int, to: Int): Int {
        for (word in NOT_DRAWN) if (contains(line, from, to, word)) return LEFT_OUT
        for (word in HELPERS) if (contains(line, from, to, word)) return EXTRA
        return PART
    }

    private val NOT_DRAWN = listOf("skirt", "custom", "prime", "purge", "wipe", "tower")
    private val HELPERS = listOf("support", "brim")

    private fun startsWith(line: ByteArray, from: Int, to: Int, word: String): Boolean {
        if (from + word.length > to) return false
        for (k in word.indices) if (line[from + k].toInt() != word[k].code) return false
        return true
    }

    private fun contains(line: ByteArray, from: Int, to: Int, lower: String): Boolean {
        var s = from
        while (s + lower.length <= to) {
            var k = 0
            while (k < lower.length && (line[s + k].toInt() or 0x20) == lower[k].code) k++
            if (k == lower.length) return true
            s++
        }
        return false
    }

    private fun isNumberChar(b: Byte): Boolean = b in ZERO..NINE || b == DOT || b == MINUS || b == PLUS

    private fun integer(line: ByteArray, from: Int, to: Int): Int {
        var i = from
        var v = 0
        if (i >= to || line[i] !in ZERO..NINE) return -1
        while (i < to && line[i] in ZERO..NINE) { v = v * 10 + (line[i] - ZERO); i++ }
        return v
    }

    private fun number(line: ByteArray, from: Int, to: Int): Float {
        var i = from
        var negative = false
        if (i < to && (line[i] == MINUS || line[i] == PLUS)) { negative = line[i] == MINUS; i++ }
        var whole = 0.0
        while (i < to && line[i] in ZERO..NINE) { whole = whole * 10 + (line[i] - ZERO); i++ }
        if (i < to && line[i] == DOT) {
            i++
            var scale = 0.1
            while (i < to && line[i] in ZERO..NINE) { whole += (line[i] - ZERO) * scale; scale *= 0.1; i++ }
        }
        return (if (negative) -whole else whole).toFloat()
    }

    /* ---------------- the height map ---------------- */

    private class HeightMap(
        val originX: Float,
        val originY: Float,
        val cell: Float,
        val cols: Int,
        val rows: Int,
        /** The finished part's height. */
        val top: Float,
        var height: FloatArray,
        val stack: Stack,
    ) {
        /** A slightly softened copy, for judging which way a surface faces. */
        lateinit var smooth: FloatArray
        /** 0..1: how far a spot sits below its surroundings. Hollows and inside corners are darker. */
        lateinit var hollow: FloatArray
        /** 0..1 on the bed: how far the part's footprint reaches, softly, for the shadow it stands in. */
        lateinit var ground: FloatArray
        /** The highest slice printed so far. */
        var last = stack.slices - 1
        /** Printing has only got to [last], so what is in that slice is the layer being laid, not a surface of the finished part. */
        var cut = false

        /**
         * [shape] as it stands with only the first [upTo] millimetres of it printed.
         * Whether its top is a layer being [laying] is taken from how far short of
         * the finished part that is, unless the caller knows.
         */
        constructor(shape: Shape, upTo: Float, laying: Boolean = upTo < shape.top - UNFINISHED_MM) : this(
            shape.originX, shape.originY, shape.cell, shape.cols, shape.rows, shape.top,
            shape.height.copyOf(), shape.stack,
        ) {
            last = stack.of(upTo).coerceIn(0, stack.slices - 1)
            cut = laying
            if (last < stack.slices - 1) heightsUpTo(last)
            shade()
            ground = blur(FloatArray(height.size) { if (shape.height[it] > 0f) 1f else 0f }, (GROUND_BLUR_MM / cell).toInt().coerceIn(2, 14))
        }

        companion object {
            /** An empty map of the bed under [extent], with room around it, for layers [thickness] mm thick. */
            fun over(extent: Extent, thickness: Float): HeightMap {
                val w = extent.maxX - extent.minX + 2 * MARGIN_MM
                val d = extent.maxY - extent.minY + 2 * MARGIN_MM
                val cell = max(max(w, d) / GRID, MIN_CELL_MM)
                val cols = ceil(w / cell).toInt().coerceAtLeast(2)
                val rows = ceil(d / cell).toInt().coerceAtLeast(2)
                val slice = max(thickness, extent.maxZ / MAX_SLICES)
                val slices = ceil(extent.maxZ / slice - 0.001f).toInt().coerceAtLeast(1)
                val map = HeightMap(
                    extent.minX - MARGIN_MM, extent.minY - MARGIN_MM, cell, cols, rows, slices * slice,
                    FloatArray(cols * rows), Stack(cols, rows, slices, slice),
                )
                map.thickness = thickness
                return map
            }
        }

        private var thickness = 0f

        /**
         * Presses one extruded line into the map, as wide as it was printed and down
         * through its layer's thickness, into the [Stack.extra] slices if it is [extra].
         */
        fun lay(x0: Float, y0: Float, x1: Float, y1: Float, z: Float, width: Float, extra: Boolean) {
            val highest = stack.of(z).coerceIn(0, stack.slices - 1)
            val lowest = stack.of(max(z - thickness, 0f) + stack.slice * 0.01f).coerceIn(0, highest)
            val slices = if (extra) stack.extra else stack.part
            for (s in lowest..highest) if (slices[s] == null) slices[s] = LongArray(stack.words * rows)
            val reachesTo = (highest + 1) * stack.slice
            val gx0 = (x0 - originX) / cell
            val gy0 = (y0 - originY) / cell
            val gx1 = (x1 - originX) / cell
            val gy1 = (y1 - originY) / cell
            val reach = max(width * 0.55f / cell, 0.5f)
            val r = ceil(reach).toInt()
            val reach2 = (reach + 0.35f) * (reach + 0.35f)
            val steps = ceil(hypot(gx1 - gx0, gy1 - gy0) / 0.7f).toInt().coerceAtLeast(1)
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                val cx = gx0 + (gx1 - gx0) * t
                val cy = gy0 + (gy1 - gy0) * t
                val ix = cx.toInt()
                val iy = cy.toInt()
                for (yy in iy - r..iy + r) {
                    if (yy < 0 || yy >= rows) continue
                    val dy = yy + 0.5f - cy
                    val row = yy * cols
                    for (xx in ix - r..ix + r) {
                        if (xx < 0 || xx >= cols) continue
                        val dx = xx + 0.5f - cx
                        if (dx * dx + dy * dy > reach2) continue
                        if (height[row + xx] < reachesTo) height[row + xx] = reachesTo
                        val at = yy * stack.words + (xx ushr 6)
                        for (slice in lowest..highest) slices[slice]!![at] = slices[slice]!![at] or (1L shl xx)
                    }
                }
            }
        }

        /**
         * Neighbouring lines can leave hairline gaps where they didn't quite meet on the grid.
         * Grow every spot to its tallest neighbour, then shrink back: gaps close, edges stay put.
         */
        fun closeGaps() {
            height = pick(pick(height, tallest = true), tallest = false)
            stack.closeGaps()
        }

        /**
         * How high the plastic reaches on each square with only the slices up to [last]
         * printed. Cutting the finished heights off would not do: a square whose plastic is
         * all still to come has nothing on it yet, however high that plastic will stand.
         */
        private fun heightsUpTo(last: Int) {
            val words = stack.words
            val seen = LongArray(words * rows)
            height.fill(0f)
            for (s in last downTo 0) {
                val part = stack.part[s]
                val extra = stack.extra[s]
                if (part == null && extra == null) continue
                val top = (s + 1) * stack.slice
                for (i in seen.indices) {
                    var found = ((part?.get(i) ?: 0L) or (extra?.get(i) ?: 0L)) and seen[i].inv()
                    if (found == 0L) continue
                    seen[i] = seen[i] or found
                    val row = i / words * cols
                    val first = i % words * 64
                    while (found != 0L) {
                        val x = first + java.lang.Long.numberOfTrailingZeros(found)
                        if (x < cols) height[row + x] = top
                        found = found and (found - 1)
                    }
                }
            }
        }

        /** Works out what the shading needs, from the heights as they now are. */
        fun shade() {
            smooth = blur(height, 2)
            val around = blur(height, HOLLOW_REACH_CELLS)
            hollow = FloatArray(height.size) { ((around[it] - height[it]) / HOLLOW_DEPTH_MM).coerceIn(0f, 1f) }
        }

        private fun pick(src: FloatArray, tallest: Boolean): FloatArray {
            val out = FloatArray(src.size)
            for (y in 0 until rows) for (x in 0 until cols) {
                var v = src[y * cols + x]
                for (yy in max(0, y - 1)..min(rows - 1, y + 1)) for (xx in max(0, x - 1)..min(cols - 1, x + 1)) {
                    val s = src[yy * cols + xx]
                    v = if (tallest) max(v, s) else min(v, s)
                }
                out[y * cols + x] = v
            }
            return out
        }

        /** Box blur of the given radius, done as one pass along rows and one along columns. */
        private fun blur(src: FloatArray, radius: Int): FloatArray {
            val mid = FloatArray(src.size)
            val out = FloatArray(src.size)
            for (y in 0 until rows) {
                var sum = 0f
                var count = 0
                for (x in -radius until cols) {
                    val add = x + radius
                    if (add < cols) { sum += src[y * cols + add]; count++ }
                    val drop = x - radius - 1
                    if (drop >= 0) { sum -= src[y * cols + drop]; count-- }
                    if (x >= 0) mid[y * cols + x] = sum / count
                }
            }
            for (x in 0 until cols) {
                var sum = 0f
                var count = 0
                for (y in -radius until rows) {
                    val add = y + radius
                    if (add < rows) { sum += mid[add * cols + x]; count++ }
                    val drop = y - radius - 1
                    if (drop >= 0) { sum -= mid[drop * cols + x]; count-- }
                    if (y >= 0) out[y * cols + x] = sum / count
                }
            }
            return out
        }

        fun at(map: FloatArray, gx: Int, gy: Int): Float =
            if (gx < 0 || gy < 0 || gx >= cols || gy >= rows) 0f else map[gy * cols + gx]
    }

    /* ---------------- the order it is laid in ---------------- */

    /**
     * Notes the order each layer goes down in, as its lines are pressed into the
     * height map: see [Route]. [moves] is how many movement commands each layer
     * has in all, which the first pass through the file counted.
     */
    private class Tracer(bed: HeightMap, private val moves: Map<Int, Int>) {
        private val originX = bed.originX
        private val originY = bed.originY
        private val cell = bed.cell * ROUTE_CELLS
        private val cols = (bed.cols + ROUTE_CELLS - 1) / ROUTE_CELLS
        private val rows = (bed.rows + ROUTE_CELLS - 1) / ROUTE_CELLS
        private val layers = ArrayList<Route.Layer>()
        private val deflater = Deflater()
        private var kept = 0

        // the layer being read
        private var number = 0
        private var total = 0
        private var sent = 0
        /**
         * The layer's height is that of the highest plastic in it. Most of it is laid there,
         * but a slicer that joins its seams with a scarf starts each wall down at the layer
         * before and climbs.
         */
        private var highest = 0f
        /** 0 where nothing has been laid yet, otherwise 1..255: see [Route.Layer.unpacked]. */
        private val reached = ByteArray(cols * rows)
        private var left = cols
        private var top = rows
        private var right = -1
        private var bottom = -1
        private val trailX = FloatArray(MOMENTS)
        private val trailY = FloatArray(MOMENTS)
        private val trailKnown = BooleanArray(MOMENTS)

        fun begin(layer: Int) {
            close()
            number = layer
            total = moves[layer] ?: 0
        }

        fun move() {
            sent++
        }

        fun lay(x0: Float, y0: Float, x1: Float, y1: Float, z: Float, width: Float, from: Float, to: Float) {
            if (total <= 0) return
            // how far through the layer this line starts and ends, as the plugin would put it
            val began = ((sent - 1 + from) / total).coerceIn(0f, 1f)
            val ended = ((sent - 1 + to) / total).coerceIn(0f, 1f)
            if (z > highest) highest = z

            val gx0 = (x0 - originX) / cell
            val gy0 = (y0 - originY) / cell
            val gx1 = (x1 - originX) / cell
            val gy1 = (y1 - originY) / cell
            val reach = max(width * 0.55f / cell, 0.5f)
            val r = ceil(reach).toInt()
            val reach2 = (reach + 0.35f) * (reach + 0.35f)
            val steps = ceil(hypot(gx1 - gx0, gy1 - gy0) / 0.7f).toInt().coerceAtLeast(1)
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                val cx = gx0 + (gx1 - gx0) * t
                val cy = gy0 + (gy1 - gy0) * t
                val moment = 1 + ((began + (ended - began) * t) * (MOMENTS - 1)).roundToInt()
                val ix = cx.toInt()
                val iy = cy.toInt()
                for (yy in iy - r..iy + r) {
                    if (yy < 0 || yy >= rows) continue
                    val dy = yy + 0.5f - cy
                    val row = yy * cols
                    for (xx in ix - r..ix + r) {
                        if (xx < 0 || xx >= cols) continue
                        val dx = xx + 0.5f - cx
                        if (dx * dx + dy * dy > reach2) continue
                        val had = reached[row + xx].toInt() and 0xFF
                        if (had == 0 || moment < had) reached[row + xx] = moment.toByte()
                        if (xx < left) left = xx
                        if (xx > right) right = xx
                        if (yy < top) top = yy
                        if (yy > bottom) bottom = yy
                    }
                }
            }

            // where the nozzle is at each of the moments this line lasts through
            val first = ceil(began * (MOMENTS - 1)).toInt()
            val last = (ended * (MOMENTS - 1)).toInt().coerceAtMost(MOMENTS - 1)
            for (m in first..last) {
                val t = if (ended > began) ((m.toFloat() / (MOMENTS - 1) - began) / (ended - began)).coerceIn(0f, 1f) else 1f
                trailX[m] = gx0 + (gx1 - gx0) * t
                trailY[m] = gy0 + (gy1 - gy0) * t
                trailKnown[m] = true
            }
            // A moment that passed while nothing of the part was being laid finds the nozzle
            // at the start of the next line that is.
            if (first > last && !trailKnown[last]) {
                trailX[last] = gx0
                trailY[last] = gy0
                trailKnown[last] = true
            }
        }

        fun route(): Route {
            close()
            deflater.end()
            return Route(originX, originY, cell, cols, rows, layers)
        }

        /** Puts away the layer that was being read, if anything of the part was laid in it. */
        private fun close() {
            if (right >= 0 && kept < ROUTE_MAX_BYTES) {
                // a square of room all round, for the spreading below to go into
                val l = max(left - 1, 0)
                val t = max(top - 1, 0)
                val w = min(right + 1, cols - 1) - l + 1
                val h = min(bottom + 1, rows - 1) - t + 1
                val out = ByteArray(w * h + 2 * MOMENTS)
                for (y in 0 until h) for (x in 0 until w) out[y * w + x] = earliestAround(l + x, t + y)

                // The nozzle is somewhere at every moment: between lines of the part, it is
                // taken to be where it last laid one, and before the first, where that starts.
                var known = trailKnown.indexOfFirst { it }
                for (m in 0 until MOMENTS) {
                    if (trailKnown[m]) known = m
                    if (known < 0) continue
                    out[w * h + 2 * m] = ((trailX[known] - l) / w * 255f).roundToInt().coerceIn(0, 255).toByte()
                    out[w * h + 2 * m + 1] = ((trailY[known] - t) / h * 255f).roundToInt().coerceIn(0, 255).toByte()
                }

                // Neighbours are laid at nearly the same moment, so what packs small is how
                // each square differs from the one before it.
                for (y in 0 until h) for (x in w - 1 downTo 1) out[y * w + x] = (out[y * w + x] - out[y * w + x - 1]).toByte()
                val packed = pack(out)
                kept += packed.size
                layers += Route.Layer(number, highest, l, t, w, h, packed)
            }
            if (right >= 0) for (y in top..bottom) reached.fill(0, y * cols + left, y * cols + right + 1)
            left = cols; top = rows; right = -1; bottom = -1
            trailKnown.fill(false)
            highest = 0f
            sent = 0
            total = 0
        }

        /**
         * When a square is reached, or failing that the earliest of the squares around
         * it: the height map closes hairline gaps between lines, and what is plastic
         * there has to count as laid here.
         */
        private fun earliestAround(x: Int, y: Int): Byte {
            val own = reached[y * cols + x]
            if (own.toInt() != 0) return own
            var best = 0
            for (yy in max(0, y - 1)..min(rows - 1, y + 1)) for (xx in max(0, x - 1)..min(cols - 1, x + 1)) {
                val v = reached[yy * cols + xx].toInt() and 0xFF
                if (v != 0 && (best == 0 || v < best)) best = v
            }
            return best.toByte()
        }

        private fun pack(bytes: ByteArray): ByteArray {
            deflater.reset()
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream(bytes.size / 4 + 64)
            val buffer = ByteArray(8192)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        }
    }

    /* ---------------- looking at it ---------------- */

    /**
     * Looks at the part from in front and above, from the side [view] says, with no
     * perspective. For every pixel a line of sight is followed down through the space
     * above the bed, square by square, until it meets plastic. Going by squares, each
     * one is known to be passed through from where to where, so no layer is ever stepped
     * over, and whether the line came in from above or through the side says which face
     * of the plastic it met.
     *
     * Given the [layer] of a [route] that is being laid, the picture also says
     * when the nozzle gets to each bit of it.
     */
    private fun render(
        bed: HeightMap, view: View, width: Int, height: Int, keepGoing: () -> Boolean, route: Route? = null, layer: Route.Layer? = null,
    ): Picture? {
        val sinA = sin(view.azimuth); val cosA = cos(view.azimuth)
        val sinE = sin(ELEVATION).toFloat(); val cosE = cos(ELEVATION).toFloat()
        // the direction of sight, and the picture's own right and up, in bed coordinates
        val dx = (sinA * cosE).toFloat(); val dy = (cosA * cosE).toFloat(); val dz = -sinE
        val rx = cosA.toFloat(); val ry = (-sinA).toFloat()
        val ux = (sinA * sinE).toFloat(); val uy = (cosA * sinE).toFloat(); val uz = cosE

        // light from over the viewer's left shoulder
        val lightScale = 1f / sqrt(LIGHT_LEFT * LIGHT_LEFT + LIGHT_UP * LIGHT_UP + LIGHT_NEAR * LIGHT_NEAR)
        val lx = (-LIGHT_LEFT * rx + LIGHT_UP * ux - LIGHT_NEAR * dx) * lightScale
        val ly = (-LIGHT_LEFT * ry + LIGHT_UP * uy - LIGHT_NEAR * dy) * lightScale
        val lz = (LIGHT_UP * uz - LIGHT_NEAR * dz) * lightScale
        // the way to the lamp along the bed, and how much it rises for each millimetre of that
        val lampway = max(hypot(lx, ly), 0.01f)
        val lampX = lx / lampway
        val lampY = ly / lampway
        val lampRise = lz / lampway
        val shadowStep = max(SHADOW_REACH_MM / SHADOW_STEPS, bed.cell * 1.5f)
        // how far up the light climbs from one step to the next, in slices, halved: a floor it crosses between steps still counts
        val shadowSlices = (shadowStep * lampRise / bed.stack.slice / 2).toInt()

        val mapWidth = bed.cols * bed.cell
        val mapDepth = bed.rows * bed.cell
        val sub = SUPERSAMPLE
        val mmPerSample = max(view.wide / width, view.tall / height) / (1f - 2 * PICTURE_MARGIN) / sub
        // the point the middle of the picture looks through
        val cx = view.right * rx + view.up * ux
        val cy = view.right * ry + view.up * uy
        val cz = view.up * uz

        val stack = bed.stack
        val partSlices = stack.part
        val extraSlices = stack.extra
        val words = stack.words
        val cols = bed.cols
        val rows = bed.rows
        val cell = bed.cell
        val tops = bed.height
        val thick = stack.slice
        val reachCells = max((WALL_REACH_MM / cell).toInt(), 2)
        val coverSlices = (COVERED_MM / thick).toInt()

        val light = ByteArray(width * height)
        val alpha = ByteArray(width * height)
        val fresh = ByteArray(width * height)
        val shadow = ByteArray(width * height)
        val order = if (route != null) layer?.unpacked() else null
        val reached = if (order != null) ByteArray(width * height) else null
        // nothing stands higher than where printing has got to
        val ceiling = (bed.last + 1) * thick
        val samplesWide = width * sub
        val samplesTall = height * sub

        for (py in 0 until height) {
            if (py and 15 == 0 && !keepGoing()) return null
            for (px in 0 until width) {
                var hits = 0
                var laying = 0
                var earliest = 0
                var sum = 0f
                var dark = 0f
                for (sy in 0 until sub) for (sx in 0 until sub) {
                    val s = (px * sub + sx + 0.5f - samplesWide / 2f) * mmPerSample
                    val t = (samplesTall / 2f - (py * sub + sy + 0.5f)) * mmPerSample
                    val qx = cx + s * rx + t * ux
                    val qy = cy + s * ry + t * uy
                    val qz = cz + t * uz
                    // the stretch of this line of sight that lies between the top of the part and the bed
                    var from = (qz - ceiling) / sinE
                    var to = qz / sinE
                    val atBed = to
                    // ...and over the map at all
                    if (dx != 0f) {
                        val a = (bed.originX - qx) / dx
                        val b = (bed.originX + mapWidth - qx) / dx
                        from = max(from, min(a, b)); to = min(to, max(a, b))
                    }
                    if (dy != 0f) {
                        val a = (bed.originY - qy) / dy
                        val b = (bed.originY + mapDepth - qy) / dy
                        from = max(from, min(a, b)); to = min(to, max(a, b))
                    }

                    var found = false
                    var meetsAt = 0f
                    var side = false
                    var sliceHit = 0
                    var isExtra = false
                    var foundX = 0
                    var foundY = 0
                    // where this line of sight first went under plastic, and how closely, 0..1
                    var underFrom = -1f
                    var covered = false
                    var closeness = 0f

                    if (from < to) {
                        var tau = from
                        val startX = (qx + tau * dx - bed.originX) / cell
                        val startY = (qy + tau * dy - bed.originY) / cell
                        var gx = startX.toInt().coerceIn(0, cols - 1)
                        var gy = startY.toInt().coerceIn(0, rows - 1)
                        val stepX = if (dx > 0f) 1 else -1
                        val stepY = if (dy > 0f) 1 else -1
                        val eachX = if (dx != 0f) cell / abs(dx) else Float.MAX_VALUE
                        val eachY = if (dy != 0f) cell / abs(dy) else Float.MAX_VALUE
                        var nextX = if (dx != 0f) from + ((if (dx > 0f) gx + 1 else gx) - startX) * cell / dx else Float.MAX_VALUE
                        var nextY = if (dy != 0f) from + ((if (dy > 0f) gy + 1 else gy) - startY) * cell / dy else Float.MAX_VALUE

                        walk@ while (tau < to) {
                            val leaves = min(min(nextX, nextY), to)
                            val top = tops[gy * cols + gx]
                            if (top > 0f) {
                                val zIn = qz + tau * dz
                                val zOut = qz + leaves * dz
                                if (zOut < top) {
                                    // the slice it came into this square at; the very first square is always from above
                                    val cameIn = if (tau == from) Int.MAX_VALUE else stack.of(zIn)
                                    var slice = min(stack.of(min(zIn, top)), bed.last)
                                    val lowest = max(stack.of(zOut), 0)
                                    val at = gy * words + (gx ushr 6)
                                    while (slice >= lowest) {
                                        val part = partSlices[slice]
                                        val inPart = part != null && part[at] ushr gx and 1L != 0L
                                        val extra = !inPart && extraSlices[slice].let { it != null && it[at] ushr gx and 1L != 0L }
                                        if (inPart || extra) {
                                            found = true
                                            side = slice >= cameIn
                                            meetsAt = if (side) tau else (qz - (slice + 1) * thick) / sinE
                                            sliceHit = slice
                                            isExtra = extra
                                            foundX = gx
                                            foundY = gy
                                            break@walk
                                        }
                                        slice--
                                    }
                                    // Through this square and out, below what stands in it. Only plastic close
                                    // overhead shuts a surface in: a wall with a gear 15 mm above it is in the open.
                                    if (!covered) {
                                        val above = stack.of(min(zIn, top)) + 1
                                        val highest = min(above + coverSlices, bed.last)
                                        var over = above
                                        while (over <= highest) {
                                            if (stack.solid(gx, gy, over)) {
                                                covered = true
                                                underFrom = tau
                                                closeness = 1f - (over - above).toFloat() / (coverSlices + 1)
                                                break
                                            }
                                            over++
                                        }
                                    }
                                }
                            }
                            if (nextX < nextY) {
                                tau = nextX
                                nextX += eachX
                                gx += stepX
                                if (gx < 0 || gx >= cols) break
                            } else {
                                tau = nextY
                                nextY += eachY
                                gy += stepY
                                if (gy < 0 || gy >= rows) break
                            }
                        }
                    }

                    if (!found) {
                        // the bed, which is dark where the part stands between it and the lamp
                        val gx = floor((qx + atBed * dx + lampX * GROUND_OFFSET_MM - bed.originX) / cell).toInt()
                        val gy = floor((qy + atBed * dy + lampY * GROUND_OFFSET_MM - bed.originY) / cell).toInt()
                        dark += bed.at(bed.ground, gx, gy)
                        continue
                    }

                    val hx = qx + meetsAt * dx
                    val hy = qy + meetsAt * dy
                    val hz = qz + meetsAt * dz
                    val gx = foundX
                    val gy = foundY
                    val surface = bed.at(tops, gx, gy)

                    // which way the surface faces here, from how the height changes around it
                    val slopeX = (bed.at(bed.smooth, gx + 1, gy) - bed.at(bed.smooth, gx - 1, gy)) / (2 * cell)
                    val slopeY = (bed.at(bed.smooth, gx, gy + 1) - bed.at(bed.smooth, gx, gy - 1)) / (2 * cell)
                    // A sheer wall is simply a very steep slope, so one rule covers flat tops,
                    // ramps and walls alike, and a sloped surface made of layer steps shades smoothly.
                    val below = surface - hz > WALL_MM
                    var nx = -slopeX
                    var ny = -slopeY
                    var nz = 1f
                    // That is only so of a wall that goes all the way up to the top of its square. The
                    // height of one with air above it belongs to whatever overhangs it, which says nothing
                    // of which way the wall faces: there the plastic of the slice itself does.
                    var onItsOwn = false
                    if (below && side) {
                        val high = stack.of(surface)
                        onItsOwn = abs(nx) + abs(ny) < 0.5f ||
                            !stack.solid(gx, gy, (3 * sliceHit + high) / 4) ||
                            !stack.solid(gx, gy, (sliceHit + high) / 2) ||
                            !stack.solid(gx, gy, (sliceHit + 3 * high) / 4)
                    }
                    if (below && !side) {
                        nx = 0f; ny = 0f
                    } else if (onItsOwn) {
                        var awayX = 0f
                        var awayY = 0f
                        for (k in 0 until AROUND) {
                            val ox = AROUND_X[k]
                            val oy = AROUND_Y[k]
                            if (!stack.solid(gx + ox * reachCells, gy + oy * reachCells, sliceHit)) { awayX += ox; awayY += oy }
                            if (!stack.solid(gx + ox * reachCells / 2, gy + oy * reachCells / 2, sliceHit)) { awayX += ox; awayY += oy }
                        }
                        if (awayX != 0f || awayY != 0f) { nx = awayX; ny = awayY; nz = 0.05f * hypot(awayX, awayY) }
                        else { nx = -dx * 4f; ny = -dy * 4f }
                    }
                    val len = sqrt(nx * nx + ny * ny + nz * nz)
                    nx /= len; ny /= len; nz /= len

                    // is anything between this and the lamp?
                    var lit = true
                    var k = 1
                    while (k <= SHADOW_STEPS) {
                        val along = k * shadowStep
                        val ox = ((hx + lampX * along - bed.originX) / cell).toInt()
                        val oy = ((hy + lampY * along - bed.originY) / cell).toInt()
                        if (ox < 0 || oy < 0 || ox >= cols || oy >= rows) break
                        // the light goes under what is too high up to be in its way
                        val lightAt = hz + 0.05f + along * lampRise
                        if (tops[oy * cols + ox] > lightAt) {
                            val at = stack.of(lightAt)
                            if (stack.solid(ox, oy, at) || stack.solid(ox, oy, at - shadowSlices)) { lit = false; break }
                        }
                        k++
                    }

                    val facing = max(0f, nx * lx + ny * ly + nz * lz)
                    var bright = AMBIENT + (1f - AMBIENT) * facing * (if (lit) 1f else SHADOWED)
                    if (!below) bright *= 1f - HOLLOW_SHADE * bed.at(bed.hollow, gx, gy)
                    // walls get a touch darker towards the bed, which grounds the part
                    else if (side) bright *= 0.86f + 0.14f * (hz / bed.top).coerceIn(0f, 1f)
                    // and what is only seen from underneath something darker still
                    if (covered) bright *= 1f - COVERED_SHADE * closeness * ((meetsAt - underFrom) / COVERED_MM).coerceIn(0f, 1f)
                    if (isExtra) bright *= EXTRA_LIGHT

                    hits++
                    if (bed.cut && !side && sliceHit == bed.last) {
                        laying++
                        if (order != null) {
                            val col = ((hx - route!!.originX) / route.cell).toInt() - layer!!.left
                            val row = ((hy - route.originY) / route.cell).toInt() - layer.top
                            if (col >= 0 && row >= 0 && col < layer.width && row < layer.height) {
                                val moment = order[row * layer.width + col].toInt() and 0xFF
                                if (moment != 0 && (earliest == 0 || moment < earliest)) earliest = moment
                            }
                        }
                    }
                    sum += bright
                }
                val i = py * width + px
                if (hits > 0) {
                    light[i] = (sum / hits * 255f).toInt().coerceIn(0, 255).toByte()
                    alpha[i] = (hits * 255 / (sub * sub)).toByte()
                    fresh[i] = (laying * 255 / hits).toByte()
                    reached?.set(i, earliest.toByte())
                }
                shadow[i] = (dark / (sub * sub) * GROUND_SHADE * 255f).toInt().coerceIn(0, 255).toByte()
            }
        }

        // The nozzle's place at each moment is a point on the layer's own surface. Sight
        // lines are parallel, so where it shows is that point measured along the picture's
        // right and up.
        var trail: FloatArray? = null
        if (order != null && route != null && layer != null) {
            val wide = mmPerSample * samplesWide
            val tall = mmPerSample * samplesTall
            val squares = layer.width * layer.height
            trail = FloatArray(2 * MOMENTS) { i ->
                val m = i / 2
                val bx = route.originX + (layer.left + (order[squares + 2 * m].toInt() and 0xFF) / 255f * layer.width) * route.cell
                val by = route.originY + (layer.top + (order[squares + 2 * m + 1].toInt() and 0xFF) / 255f * layer.height) * route.cell
                if (i % 2 == 0) 0.5f + (bx * rx + by * ry - view.right) / wide
                else 0.5f - (bx * ux + by * uy + ceiling * uz - view.up) / tall
            }
        }
        return Picture(width, height, light, alpha, fresh, shadow, reached, trail)
    }

    /** The eight directions around a square, as steps along the bed. */
    private const val AROUND = 8
    private val AROUND_X = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    private val AROUND_Y = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
    /** How far around a square its slice is looked at, for which way a wall faces. */
    private const val WALL_REACH_MM = 1.2f

    private const val NEWLINE = '\n'.code.toByte()
    private const val RETURN = '\r'.code.toByte()
    private const val SPACE = ' '.code.toByte()
    private const val TAB = '\t'.code.toByte()
    private const val SEMICOLON = ';'.code.toByte()
    private const val DOT = '.'.code.toByte()
    private const val MINUS = '-'.code.toByte()
    private const val PLUS = '+'.code.toByte()
    private const val ZERO = '0'.code.toByte()
    private const val NINE = '9'.code.toByte()
    private const val G = 'G'.code.toByte()
    private const val G_LOWER = 'g'.code.toByte()
    private const val M = 'M'.code.toByte()
    private const val M_LOWER = 'm'.code.toByte()

    /** Line width assumed until the file says otherwise: a 0.4 mm nozzle's usual. */
    private const val DEFAULT_WIDTH = 0.45f
    private const val ARC_PIECE_MM = 0.6
    /** The size a picture is drawn at. Its shape is that of the panel it is shown in. */
    const val WIDTH = 400
    const val HEIGHT = 300
    /** How many sides a part can be looked at from, a quarter turn apart. */
    const val TURNS = 4
    /** What a section of a file is: the model itself, something printed to help it, or neither. */
    private const val PART = 0
    private const val EXTRA = 1
    private const val LEFT_OUT = 2
    /** Squares along the longer side of the part. Finer than the picture needs, so edges stay clean. */
    private const val GRID = 448f
    private const val MIN_CELL_MM = 0.06f
    /** Room around the part: enough for the shadow it stands in not to be cut off. */
    private const val MARGIN_MM = 4f
    /** At most this many slices, whatever the height: a tall part's are thicker than its layers, and nobody can tell. */
    private const val MAX_SLICES = 240f
    /** Heights a file is told apart at, in hundredths of a millimetre: 65 cm of them. */
    private const val MAX_LEVELS = 1 shl 16
    /** Thicker than any layer, in the same hundredths. A step this big is a gap in the part, not a layer. */
    private const val MAX_LAYER_STEP = 100
    private const val HOLLOW_REACH_CELLS = 7
    private const val HOLLOW_DEPTH_MM = 4f
    private const val HOLLOW_SHADE = 0.4f
    /** How much dimmer what is printed to help the part is than the part. */
    private const val EXTRA_LIGHT = 0.68f
    /** How far a line of sight goes under plastic before what it finds there is as dark as it gets. */
    private const val COVERED_MM = 3f
    private const val COVERED_SHADE = 0.72f
    /** What is left of the light where something stands between a surface and the lamp. */
    private const val SHADOWED = 0.3f
    /** How far along the way to the lamp a surface looks for anything in the way, and in how many steps. */
    private const val SHADOW_REACH_MM = 24f
    private const val SHADOW_STEPS = 12
    private const val GROUND_BLUR_MM = 3.5f
    private const val GROUND_SHADE = 0.5f
    /** How far from the lamp the shadow on the bed lies from under the part. */
    private const val GROUND_OFFSET_MM = 2f
    /** A part at least this many times as long as it is broad is turned to lie across the picture. */
    private const val LONG_PART = 1.6
    /** How far from lying flat across the picture such a part is turned, so that its end shows too. */
    private val SKEW = Math.toRadians(20.0)
    /** A hit this far below the top of its square is the side of something, not its top. */
    private const val WALL_MM = 0.5f
    /** Closer to the top than this and the part is as good as finished: no layer is shown as being laid. */
    private const val UNFINISHED_MM = 0.05f

    /** What the Dashboard plugin writes into a file it stores, after each of the slicer's own layer changes. */
    private const val LAYER_MARK = "M117 DASHBOARD_LAYER_INDICATOR "
    /**
     * More layers than any part has. A count is kept for every layer a file marks, and a
     * file is not to be trusted to mark only as many as it has.
     */
    private const val MAX_LAYERS = 100_000
    /** How many of the height map's squares, each way, share one square of a [Route]. */
    const val ROUTE_CELLS = 3
    /** How finely a layer's time is divided. The plugin reports in hundredths, so this is plenty. */
    const val MOMENTS = 255
    /** A file whose route has grown past this is left with what it has: the rest of it fills the plain way. */
    private const val ROUTE_MAX_BYTES = 12 * 1024 * 1024
    /**
     * Heights are stored to a hundredth of a millimetre, so a layer's own plastic can
     * come back a hair lower than the layer is. Cutting the part off this far under a
     * layer's height keeps all of that layer in view as the one being laid.
     */
    private const val KEPT_TO_MM = 0.006f

    private val AZIMUTH = Math.toRadians(32.0)
    private val ELEVATION = Math.toRadians(36.0)
    private const val LIGHT_LEFT = 0.55f
    private const val LIGHT_UP = 0.75f
    private const val LIGHT_NEAR = 0.5f
    private const val AMBIENT = 0.36f
    private const val SUPERSAMPLE = 2
    private const val PICTURE_MARGIN = 0.03f
}

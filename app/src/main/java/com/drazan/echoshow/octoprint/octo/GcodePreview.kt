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
 * The file is read twice. The first pass finds where the part sits; the second
 * lays every extruded line into a height map of the build plate: for each
 * small square of the bed, how high the plastic reaches. That height map is
 * then looked at from above and to one side, like a slicer's preview, and
 * shaded by which way each bit of surface faces.
 *
 * A height map can't show the underside of an overhang, but nor can a camera
 * looking down at the bed. Skirts, brims, supports and purge lines are left
 * out when the slicer labels them, so the picture is of the part alone.
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
     * [size] x [size] pixels: how bright the surface is, and how much of the pixel it
     * covers. [fresh] is how much of what shows there is the layer being laid down,
     * which only a picture of the part half-printed has any of.
     *
     * A picture drawn for one layer of a [Route] also says when in that layer the
     * nozzle gets to each pixel of it, in [reached]: 0 for never, otherwise 1..255
     * from the layer's start to its end. [trail] is where in the picture the nozzle
     * is at each of those 255 moments, as (across, down) pairs of fractions of its size.
     */
    class Picture(
        val size: Int, val light: ByteArray, val alpha: ByteArray, val fresh: ByteArray,
        val reached: ByteArray? = null, val trail: FloatArray? = null,
    )

    /**
     * The part itself, as the height map described above. Kept alongside its picture
     * so the part can be drawn again as it stands part-way through being printed.
     */
    class Shape(
        val originX: Float, val originY: Float, val cell: Float,
        val cols: Int, val rows: Int,
        /** How tall the finished part is, in millimetres. */
        val top: Float,
        val height: FloatArray,
    )

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
    fun draw(open: () -> InputStream, size: Int = 320, keepGoing: () -> Boolean = { true }): Picture? =
        shape(open, keepGoing)?.let { render(it, size, keepGoing = keepGoing) }

    /**
     * Draws [shape] as it stands once the print has reached [upTo] millimetres. The
     * framing is the finished part's whatever the height, so pictures of different
     * stages lie exactly over one another.
     */
    fun render(shape: Shape, size: Int = 320, upTo: Float = shape.top, keepGoing: () -> Boolean = { true }): Picture? =
        render(HeightMap(shape, upTo), size, keepGoing)

    /**
     * Draws [shape] as it stands while [layer] of [route] is being laid: built up
     * to that layer and no further, with the layer itself marked and the order it
     * goes down in given by [Picture.reached] and [Picture.trail].
     */
    fun render(
        shape: Shape, route: Route, layer: Route.Layer, size: Int = 320, keepGoing: () -> Boolean = { true },
    ): Picture? = render(HeightMap(shape, layer.z - KEPT_TO_MM, laying = true), size, keepGoing, route, layer)

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

        val bed = HeightMap.over(extent)
        val tracer = Tracer(bed, survey.moves)
        val painter = object : Sink {
            override fun line(
                x0: Float, y0: Float, x1: Float, y1: Float, z: Float, width: Float, part: Boolean, from: Float, to: Float,
            ) {
                if (part || !labelled) {
                    bed.lay(x0, y0, x1, y1, z, width)
                    tracer.lay(x0, y0, x1, y1, z, width, from, to)
                }
            }

            override fun move() = tracer.move()
            override fun layer(number: Int) = tracer.begin(number)
        }
        if (!open().use { walk(it, keepGoing, painter) }) return null
        bed.closeGaps()
        if (!keepGoing()) return null
        return Reading(Shape(bed.originX, bed.originY, bed.cell, bed.cols, bed.rows, bed.top, bed.height), tracer.route())
    }

    /* ---------------- reading the G-code ---------------- */

    private interface Sink {
        /**
         * One extruded line. [part] is true when the slicer labelled it as belonging to the
         * model. It is the stretch [from]..[to] of the movement command it came from: all
         * of it, 0 to 1, unless that was an arc, which arrives in pieces.
         */
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, z: Float, width: Float, part: Boolean, from: Float, to: Float)

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

    private class Survey : Sink {
        val labelled = Extent()
        val everything = Extent()
        /** How many movement commands each layer has, by its number: what the plugin divides by. */
        val moves = HashMap<Int, Int>()
        private var layer = 0
        private var count = 0

        override fun line(
            x0: Float, y0: Float, x1: Float, y1: Float, z: Float, width: Float, part: Boolean, from: Float, to: Float,
        ) {
            everything.add(x0, y0, x1, y1, z)
            if (part) labelled.add(x0, y0, x1, y1, z)
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
        var part = false          // inside a section the slicer labelled as the model itself
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
                    if (startsWith(line, j, n, "TYPE:")) part = isPart(line, j + 5, n)
                    else if (startsWith(line, j, n, "FEATURE:")) part = isPart(line, j + 8, n)
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
                                    arc(x, y, nx, ny, ci, cj, clockwise = code == 2, z = nz, width = width, part = part, sink = sink)
                                } else {
                                    sink.line(x, y, nx, ny, nz, width, part, 0f, 1f)
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
        clockwise: Boolean, z: Float, width: Float, part: Boolean, sink: Sink,
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
            sink.line(px, py, qx, qy, z, width, part, (k - 1f) / pieces, k.toFloat() / pieces)
            px = qx; py = qy
        }
    }

    /** Whether a slicer's section label names the part itself rather than something printed around it. */
    private fun isPart(line: ByteArray, from: Int, to: Int): Boolean {
        for (word in NOT_THE_PART) if (contains(line, from, to, word)) return false
        return true
    }

    private val NOT_THE_PART = listOf("skirt", "brim", "support", "custom", "prime", "purge", "wipe", "tower")

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
        /** The finished part's height, which is what the picture is framed around. */
        val top: Float,
        var height: FloatArray,
    ) {
        /** A slightly softened copy, for judging which way a surface faces. */
        lateinit var smooth: FloatArray
        /** 0..1: how far a spot sits below its surroundings. Hollows and inside corners are darker. */
        lateinit var hollow: FloatArray
        /**
         * Where printing has got to, when that is short of the top. Plastic that is level at
         * this height is the layer being laid, not a surface of the finished part.
         */
        var cut = Float.MAX_VALUE

        /**
         * [shape] as it stands with only the first [upTo] millimetres of it printed.
         * Whether its top is a layer being [laying] is taken from how far short of
         * the finished part that is, unless the caller knows.
         */
        constructor(shape: Shape, upTo: Float, laying: Boolean = upTo < shape.top - UNFINISHED_MM) : this(
            shape.originX, shape.originY, shape.cell, shape.cols, shape.rows, shape.top,
            FloatArray(shape.height.size) { min(shape.height[it], upTo) },
        ) {
            if (laying) cut = upTo
            shade()
        }

        companion object {
            /** An empty map of the bed under [extent], with a little room around it. */
            fun over(extent: Extent): HeightMap {
                val w = extent.maxX - extent.minX + 2 * MARGIN_MM
                val d = extent.maxY - extent.minY + 2 * MARGIN_MM
                val cell = max(max(w, d) / GRID, MIN_CELL_MM)
                val cols = ceil(w / cell).toInt().coerceAtLeast(2)
                val rows = ceil(d / cell).toInt().coerceAtLeast(2)
                return HeightMap(
                    extent.minX - MARGIN_MM, extent.minY - MARGIN_MM, cell, cols, rows, extent.maxZ,
                    FloatArray(cols * rows),
                )
            }
        }

        /** Presses one extruded line into the map, as wide as it was printed. */
        fun lay(x0: Float, y0: Float, x1: Float, y1: Float, z: Float, width: Float) {
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
                        if (dx * dx + dy * dy <= reach2 && height[row + xx] < z) height[row + xx] = z
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
     * Looks at the height map from the front, a little to the left and from
     * above, with no perspective. For every pixel a line of sight is followed
     * down through the space above the bed until it meets plastic.
     *
     * Given the [layer] of a [route] that is being laid, the picture also says
     * when the nozzle gets to each bit of it.
     */
    private fun render(
        bed: HeightMap, size: Int, keepGoing: () -> Boolean, route: Route? = null, layer: Route.Layer? = null,
    ): Picture? {
        val sinA = sin(AZIMUTH); val cosA = cos(AZIMUTH)
        val sinE = sin(ELEVATION); val cosE = cos(ELEVATION)
        // the direction of sight, and the picture's own right and up, in bed coordinates
        val dx = (sinA * cosE).toFloat(); val dy = (cosA * cosE).toFloat(); val dz = (-sinE).toFloat()
        val rx = cosA.toFloat(); val ry = (-sinA).toFloat()
        val ux = (sinA * sinE).toFloat(); val uy = (cosA * sinE).toFloat(); val uz = cosE.toFloat()

        // light from over the viewer's left shoulder
        val lightScale = 1f / sqrt(LIGHT_LEFT * LIGHT_LEFT + LIGHT_UP * LIGHT_UP + LIGHT_NEAR * LIGHT_NEAR)
        val lx = (-LIGHT_LEFT * rx + LIGHT_UP * ux - LIGHT_NEAR * dx) * lightScale
        val ly = (-LIGHT_LEFT * ry + LIGHT_UP * uy - LIGHT_NEAR * dy) * lightScale
        val lz = (LIGHT_UP * uz - LIGHT_NEAR * dz) * lightScale

        val width = bed.cols * bed.cell
        val depth = bed.rows * bed.cell
        val cx = bed.originX + width / 2
        val cy = bed.originY + depth / 2
        val cz = bed.top / 2
        val halfRight = abs(width / 2 * rx) + abs(depth / 2 * ry)
        val halfUp = abs(width / 2 * ux) + abs(depth / 2 * uy) + bed.top / 2 * uz
        val sub = SUPERSAMPLE
        val mmPerSample = 2 * max(halfRight, halfUp) / (size * (1f - 2 * PICTURE_MARGIN)) / sub

        val light = ByteArray(size * size)
        val alpha = ByteArray(size * size)
        val fresh = ByteArray(size * size)
        val order = if (route != null) layer?.unpacked() else null
        val reached = if (order != null) ByteArray(size * size) else null
        // nothing stands higher than where printing has got to
        val ceiling = min(bed.top, bed.cut)
        val step = bed.cell * 0.75f / cosE.toFloat()
        val samples = size * sub

        for (py in 0 until size) {
            if (py and 15 == 0 && !keepGoing()) return null
            for (px in 0 until size) {
                var hits = 0
                var laying = 0
                var earliest = 0
                var sum = 0f
                for (sy in 0 until sub) for (sx in 0 until sub) {
                    val s = (px * sub + sx + 0.5f - samples / 2f) * mmPerSample
                    val t = (samples / 2f - (py * sub + sy + 0.5f)) * mmPerSample
                    val qx = cx + s * rx + t * ux
                    val qy = cy + s * ry + t * uy
                    val qz = cz + t * uz
                    // the stretch of this line of sight that lies between the top of the part and the bed
                    var from = (qz - ceiling) / sinE.toFloat()
                    var to = qz / sinE.toFloat()
                    // ...and over the map at all
                    if (dx != 0f) {
                        val a = (bed.originX - qx) / dx
                        val b = (bed.originX + width - qx) / dx
                        from = max(from, min(a, b)); to = min(to, max(a, b))
                    }
                    if (dy != 0f) {
                        val a = (bed.originY - qy) / dy
                        val b = (bed.originY + depth - qy) / dy
                        from = max(from, min(a, b)); to = min(to, max(a, b))
                    }
                    if (from >= to) continue

                    var tau = from
                    var before = from
                    var found = false
                    while (tau <= to) {
                        val gx = ((qx + tau * dx - bed.originX) / bed.cell).toInt()
                        val gy = ((qy + tau * dy - bed.originY) / bed.cell).toInt()
                        val h = bed.at(bed.height, gx, gy)
                        if (h > 0f && qz + tau * dz <= h) { found = true; break }
                        before = tau
                        tau += step
                    }
                    if (!found) continue

                    // close in on where exactly it met the surface
                    var near = before
                    var far = tau
                    repeat(5) {
                        val m = (near + far) / 2
                        val gx = ((qx + m * dx - bed.originX) / bed.cell).toInt()
                        val gy = ((qy + m * dy - bed.originY) / bed.cell).toInt()
                        val h = bed.at(bed.height, gx, gy)
                        if (h > 0f && qz + m * dz <= h) far = m else near = m
                    }
                    val hx = qx + far * dx
                    val hy = qy + far * dy
                    val hz = qz + far * dz
                    val gx = ((hx - bed.originX) / bed.cell).toInt()
                    val gy = ((hy - bed.originY) / bed.cell).toInt()
                    val surface = bed.at(bed.height, gx, gy)

                    // which way the surface faces here, from how the height changes around it
                    val slopeX = (bed.at(bed.smooth, gx + 1, gy) - bed.at(bed.smooth, gx - 1, gy)) / (2 * bed.cell)
                    val slopeY = (bed.at(bed.smooth, gx, gy + 1) - bed.at(bed.smooth, gx, gy - 1)) / (2 * bed.cell)
                    // A sheer wall is simply a very steep slope, so one rule covers flat tops,
                    // ramps and walls alike, and a sloped surface made of layer steps shades smoothly.
                    val onWall = surface - hz > WALL_MM
                    var nx = -slopeX
                    var ny = -slopeY
                    var nz = 1f
                    if (onWall && abs(nx) + abs(ny) < 0.5f) { nx = -dx * 4f; ny = -dy * 4f }
                    val len = sqrt(nx * nx + ny * ny + nz * nz)
                    nx /= len; ny /= len; nz /= len

                    val facing = max(0f, nx * lx + ny * ly + nz * lz)
                    var bright = AMBIENT + (1f - AMBIENT) * facing
                    if (!onWall) bright *= 1f - HOLLOW_SHADE * bed.at(bed.hollow, gx, gy)
                    // walls get a touch darker towards the bed, which grounds the part
                    else bright *= 0.86f + 0.14f * (hz / bed.top).coerceIn(0f, 1f)

                    hits++
                    if (!onWall && surface >= bed.cut - 0.001f) {
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
                if (hits > 0) {
                    light[py * size + px] = (sum / hits * 255f).toInt().coerceIn(0, 255).toByte()
                    alpha[py * size + px] = (hits * 255 / (sub * sub)).toByte()
                    fresh[py * size + px] = (laying * 255 / hits).toByte()
                    reached?.set(py * size + px, earliest.toByte())
                }
            }
        }

        // The nozzle's place at each moment is a point on the layer's own surface. Sight
        // lines are parallel, so where it shows is that point measured along the picture's
        // right and up.
        var trail: FloatArray? = null
        if (order != null && route != null && layer != null) {
            val across = mmPerSample * samples
            val squares = layer.width * layer.height
            trail = FloatArray(2 * MOMENTS) { i ->
                val m = i / 2
                val bx = route.originX + (layer.left + (order[squares + 2 * m].toInt() and 0xFF) / 255f * layer.width) * route.cell
                val by = route.originY + (layer.top + (order[squares + 2 * m + 1].toInt() and 0xFF) / 255f * layer.height) * route.cell
                if (i % 2 == 0) 0.5f + ((bx - cx) * rx + (by - cy) * ry) / across
                else 0.5f - ((bx - cx) * ux + (by - cy) * uy + (ceiling - cz) * uz) / across
            }
        }
        return Picture(size, light, alpha, fresh, reached, trail)
    }

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
    /** Squares along the longer side of the part. Finer than the picture needs, so edges stay clean. */
    private const val GRID = 448f
    private const val MIN_CELL_MM = 0.06f
    private const val MARGIN_MM = 1.2f
    private const val HOLLOW_REACH_CELLS = 7
    private const val HOLLOW_DEPTH_MM = 4f
    private const val HOLLOW_SHADE = 0.4f
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
    private const val PICTURE_MARGIN = 0.05f
}

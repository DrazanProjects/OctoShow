package com.drazan.echoshow.octoprint.octo

import kotlin.math.max

/**
 * One reading of where the nozzle really is, taken from the printer's motors, in
 * millimetres. [millis] is the printer's own clock when it was read.
 *
 * What OctoPrint and its plugins report is what has been sent to the printer, and the
 * printer holds a queue of moves: that can be most of a layer ahead of the nozzle. The
 * only way to know where the nozzle is, is for the firmware to say. Stock firmware doesn't
 * (`M114` and `M154` report the end of the queue), so this needs one built to.
 */
class NozzleFix(val x: Float, val y: Float, val z: Float, val e: Float, val millis: Long)

/** What the firmware starts each reading with. OctoPrint's log can be filtered on it. */
const val NOZZLE_MARK = "REALPOS "

/**
 * Reads "REALPOS <x> <y> <z> <e> <millis>", which may have "Recv: " and the like before it.
 * The numbers are bare on purpose: OctoPrint takes a line with a "T:" in it for temperatures,
 * and one with "X:", "Y:" and "Z:" for a position of its own.
 */
fun parseNozzleFix(line: String): NozzleFix? {
    val at = line.indexOf(NOZZLE_MARK)
    if (at < 0) return null
    val parts = line.substring(at + NOZZLE_MARK.length).trim().split(' ')
    if (parts.size != 5) return null
    val x = parts[0].toFloatOrNull() ?: return null
    val y = parts[1].toFloatOrNull() ?: return null
    val z = parts[2].toFloatOrNull() ?: return null
    val e = parts[3].toFloatOrNull() ?: return null
    val millis = parts[4].toLongOrNull() ?: return null
    if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !e.isFinite() || millis < 0) return null
    return NozzleFix(x, y, z, e, millis)
}

/**
 * Plays readings back in step with when they were taken. They reach the display in
 * batches, about twice a second, so what shows is where the nozzle was a moment ago, drawn
 * only from real readings: nothing here guesses where it has got to since.
 */
class NozzleTrack {
    private val fixes = ArrayDeque<NozzleFix>()
    /** The display's clock less the printer's, for a reading that got here as fast as any has. */
    private var offset = Long.MAX_VALUE
    private var lastArrival = -1L

    var x = 0f
        private set
    var y = 0f
        private set
    var z = 0f
        private set
    /** Plastic is going down: the extruder moved on between the two readings around this moment. */
    var laying = false
        private set

    fun add(fix: NozzleFix, arrivedAt: Long) {
        // a printer that has been restarted has started its clock again
        if (fixes.isNotEmpty() && fix.millis < fixes.last().millis) clear()
        fixes.addLast(fix)
        if (fixes.size > KEEP_FIXES) fixes.removeFirst()
        offset = minOf(offset, arrivedAt - fix.millis)
        lastArrival = arrivedAt
    }

    fun clear() {
        fixes.clear()
        offset = Long.MAX_VALUE
        lastArrival = -1L
        laying = false
    }

    /** Whether readings are coming in at all. */
    fun isLive(now: Long): Boolean = lastArrival >= 0 && now - lastArrival <= STALE_MS

    /** Works out where the nozzle was for what is on screen at [now]. False if there is no telling. */
    fun sample(now: Long): Boolean {
        if (!isLive(now)) return false
        val wanted = now - offset - REPLAY_DELAY_MS
        while (fixes.size > 2 && fixes[1].millis <= wanted) fixes.removeFirst()
        val a = fixes.first()
        if (fixes.size < 2 || wanted <= a.millis) {
            moveTo(a.x, a.y, a.z, false)
            return true
        }
        val b = fixes[1]
        val t = if (b.millis > a.millis) ((wanted - a.millis).toFloat() / (b.millis - a.millis)).coerceIn(0f, 1f) else 1f
        moveTo(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t, b.e > a.e)
        return true
    }

    private fun moveTo(atX: Float, atY: Float, atZ: Float, extruding: Boolean) {
        x = atX
        y = atY
        z = atZ
        laying = extruding
    }

    private companion object {
        /** Long enough that the batch holding the reading for this moment has always arrived. */
        const val REPLAY_DELAY_MS = 700L
        const val KEEP_FIXES = 32
        /** With nothing for this long the firmware has stopped saying, and other figures take over. */
        const val STALE_MS = 3_000L
    }
}

/** One layer's map from a spot on the bed to when in the layer it is laid. See [GcodePreview.Route]. */
class LayerMap(private val route: GcodePreview.Route, layer: GcodePreview.Route.Layer) {
    val number = layer.number
    private val left = layer.left
    private val top = layer.top
    private val width = layer.width
    private val height = layer.height
    private val squares = layer.unpacked()

    /** When the spot at ([x], [y]) is laid: 1..255, or 0 for never. */
    fun momentAt(x: Float, y: Float): Int {
        val col = ((x - route.originX) / route.cell).toInt() - left
        val row = ((y - route.originY) / route.cell).toInt() - top
        if (col < 0 || row < 0 || col >= width || row >= height) return 0
        return squares[row * width + col].toInt() and 0xFF
    }
}

/**
 * Turns the nozzle's real position into the layer it is on and how far through it has got.
 * Only plastic going down counts: on a travel the nozzle crosses spots that are laid much
 * later, and hops up past layers it hasn't reached.
 */
class NozzleFollower {
    private val track = NozzleTrack()
    private var numbers = IntArray(0)
    private var heights = FloatArray(0)
    private var map: LayerMap? = null
    private var moment = 0
    /** How long the nozzle has been laying somewhere further on than it should have got to, in looks. */
    private var beyond = 0

    /** The layer the nozzle is on, as the Dashboard plugin numbers them; null until that is known. */
    var layer: Int? = null
        private set
    /** How high that layer is, in millimetres. */
    var height = 0f
        private set
    /** How far through that layer the nozzle has got, 0..1. */
    var done = 0f
        private set

    /** The file's layers: their numbers, and how high each is, from the bottom up. */
    fun useLayers(layerNumbers: IntArray, layerHeights: FloatArray) {
        numbers = layerNumbers
        heights = layerHeights
    }

    val knowsLayers: Boolean get() = numbers.isNotEmpty()

    /** The map of the layer asked for by [layer] has been read. */
    fun useMap(layerMap: LayerMap) {
        if (layerMap.number == layer) map = layerMap
    }

    fun add(fix: NozzleFix, arrivedAt: Long) = track.add(fix, arrivedAt)

    fun reset() {
        track.clear()
        numbers = IntArray(0)
        heights = FloatArray(0)
        map = null
        layer = null
        moment = 0
        beyond = 0
        done = 0f
    }

    /** Catches up with [now]. False when there are no readings to go on. */
    fun look(now: Long): Boolean {
        if (!track.sample(now)) return false
        if (track.laying && numbers.isNotEmpty()) {
            findLayer(track.z)
            findMoment(track.x, track.y)
        }
        return true
    }

    /** The layer at height [z], and it only ever goes up: the first as high as the nozzle is. */
    private fun findLayer(z: Float) {
        var i = 0
        while (i < heights.size - 1 && heights[i] < z - SAME_LAYER_MM) i++
        val found = numbers[i]
        if (layer.let { it == null || found > it }) {
            layer = found
            height = heights[i]
            map = null
            moment = 0
            beyond = 0
            done = 0f
        }
    }

    private fun findMoment(x: Float, y: Float) {
        val at = map?.momentAt(x, y) ?: return
        if (at <= moment) return
        // Near enough to where it was, it is simply carrying on, and the first plastic seen on a
        // layer says where it is, however far through this display came in. Much further on than
        // that, it is laying over something already there, unless it stays that way: then this
        // display has lost track.
        if (moment > 0 && at - moment > MOST_AT_ONCE && ++beyond < LOST_AFTER_LOOKS) return
        beyond = 0
        moment = at
        done = max(0, moment - 1).toFloat() / (GcodePreview.MOMENTS - 1)
    }

    private companion object {
        /** Heights are stored to a hundredth of a millimetre, and a seam can start a shade low. */
        const val SAME_LAYER_MM = 0.03f
        /** About an eighth of a layer. */
        const val MOST_AT_ONCE = 32
        /** Half a second or so at the rate the screen looks. */
        const val LOST_AFTER_LOOKS = 30
    }
}

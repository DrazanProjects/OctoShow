package com.drazan.echoshow.octoprint.octo

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

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
    /** While [laying], the lower of the two readings' heights: see [sample]. */
    var z = 0f
        private set
    /** Plastic is going down: the extruder moved on between the two readings around this moment. */
    var laying = false
        private set
    /**
     * The readings either side of this moment, and how far it is from the first to the second,
     * 0..1. The nozzle was at both, but it needn't have gone straight from one to the other.
     */
    var before: NozzleFix? = null
        private set
    var after: NozzleFix? = null
        private set
    var along = 0f
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
        before = null
        after = null
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
            moveTo(a, a, 0f, a.z, false)
            return true
        }
        val b = fixes[1]
        val t = if (b.millis > a.millis) ((wanted - a.millis).toFloat() / (b.millis - a.millis)).coerceIn(0f, 1f) else 1f
        // Plastic went down between the two readings, and it went down at the lower one's
        // height. When they differ the nozzle hopped clear of the layer between them, and the
        // extruder pushing back what it drew in before the hop doesn't lay anything up there.
        val extruding = b.e > a.e
        moveTo(a, b, t, if (extruding) minOf(a.z, b.z) else a.z + (b.z - a.z) * t, extruding)
        return true
    }

    private fun moveTo(from: NozzleFix, to: NozzleFix, t: Float, atZ: Float, extruding: Boolean) {
        before = from
        after = to
        along = t
        x = from.x + (to.x - from.x) * t
        y = from.y + (to.y - from.y) * t
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

    /**
     * Catches up with [now]. [sent] is the last layer sent to the printer, as the Dashboard
     * plugin reports it, or null before the first, or [ANY_LAYER] when nothing says, as when
     * the printer reads the file from its own SD card. False when there are no readings to go on.
     */
    fun look(now: Long, sent: Int?): Boolean {
        if (!track.sample(now)) return false
        if (track.laying && numbers.isNotEmpty()) {
            // With nothing to say which layers have been sent, only two readings at one height
            // say which layer it is. Two at different heights straddle a hop: within a quarter
            // of a second the nozzle can come down, print a little and go back up, and both
            // readings be in the air. Otherwise what has been sent rules that out, and waiting
            // for a pair at one height would only make each new layer show later.
            val from = track.before
            val to = track.after
            val level = from != null && to != null && abs(from.z - to.z) <= SAME_LAYER_MM
            if (level || sent != ANY_LAYER) findLayer(track.z, sent)
            findMoment()
        }
        return true
    }

    /**
     * The layer at height [z], and it only ever goes up: the first as high as the nozzle is.
     * Never one the printer hasn't been sent yet, though: the purge line before the first
     * layer is laid a little higher than it.
     */
    private fun findLayer(z: Float, sent: Int?) {
        if (sent == null) return
        var i = 0
        while (i < heights.size - 1 && heights[i] < z - SAME_LAYER_MM) i++
        while (i > 0 && numbers[i] > sent) i--
        val found = numbers[i]
        if (found > sent) return
        if (sent == ANY_LAYER) {
            // With nothing to say what has been sent, only plastic at a layer's own height says
            // which layer it is. A hop up or down passes through heights in between, and the
            // extruder can still come out a shade ahead across it.
            if (z < heights[i] - SAME_LAYER_MM) return
            // Just above the first layer, before any layer is known, it is as likely the purge
            // line: wait for a clearer answer.
            if (layer == null && i == 1) return
        }
        if (layer.let { it == null || found > it }) {
            layer = found
            height = heights[i]
            map = null
            moment = 0
            beyond = 0
            done = 0f
        }
    }

    /**
     * How far through the layer the nozzle has got: when the map has it at the readings either
     * side of this moment, and in between those by the time. Not the map at a point on the
     * straight line between them, which on a turn round an outline lies inside it, where the
     * infill goes later.
     */
    private fun findMoment() {
        val map = map ?: return
        val from = track.before ?: return
        val to = track.after ?: return
        // a reading above the layer is in the air, hopped up for a travel to or from there
        val first = if (from.z > height + SAME_LAYER_MM) 0 else map.momentAt(from.x, from.y)
        val second = if (to.z > height + SAME_LAYER_MM) 0 else map.momentAt(to.x, to.y)
        val at = when {
            first == 0 -> second
            // Off the part by then, or over plastic laid earlier, or further on than a quarter
            // of a second's printing gets: a travel straight after printing ends somewhere laid
            // later, and going a little way towards that every frame would never look like a jump.
            second <= first || second - first > MOST_AT_ONCE -> first
            else -> first + ((second - first) * track.along).roundToInt()
        }
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

    companion object {
        /** For [look]: any layer may have been sent, as nothing reports which. */
        const val ANY_LAYER = Int.MAX_VALUE
        /** Heights are stored to a hundredth of a millimetre, and a seam can start a shade low. */
        private const val SAME_LAYER_MM = 0.03f
        /** About an eighth of a layer. */
        private const val MOST_AT_ONCE = 32
        /** Half a second or so at the rate the screen looks. */
        private const val LOST_AFTER_LOOKS = 30
    }
}

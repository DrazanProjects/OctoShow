package com.drazan.echoshow.octoprint

import com.drazan.echoshow.octoprint.octo.GcodePreview
import com.drazan.echoshow.octoprint.octo.LayerMap
import com.drazan.echoshow.octoprint.octo.NozzleFix
import com.drazan.echoshow.octoprint.octo.NozzleFollower
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A real file printed on paper: where the nozzle is at each moment by a plain timing model of
 * the printer, the REALPOS lines the firmware would send for it, and what the display makes of
 * them, set against where the nozzle really was. Also what the figures OctoPrint and the
 * Dashboard plugin count from lines sent would have said at the same moments.
 *
 *   gradlew :app:testDebugUnitTest --tests "*NozzleReplayTest*" -Psketch=C:\path\to\file.gcode
 */
class NozzleReplayTest {

    /** One movement command, or a wait during which the printer's queue is empty. */
    private class Move(
        val t0: Double, val t1: Double,
        val x0: Float, val y0: Float, val z0: Float, val e0: Float,
        val x1: Float, val y1: Float, val z1: Float, val e1: Float,
        val layer: Int, val inLayer: Int, val moving: Boolean, val zSent: Float,
    )

    // the firmware's limits, from Configuration.h of the build
    private var maxXY = 600.0
    private var maxZ = 12.0
    private var maxE = 30.0

    private fun plan(file: File): Pair<List<Move>, Map<Int, Int>> {
        val moves = ArrayList<Move>()
        val perLayer = HashMap<Int, Int>()
        var t = 0.0
        var x = 0f; var y = 0f; var z = 0f; var e = 0f
        var f = 3000.0
        var absXYZ = true
        var absE = true
        var layer = 0
        fun wait(seconds: Double) {
            moves += Move(t, t + seconds, x, y, z, e, x, y, z, e, layer, -1, false, z)
            t += seconds
        }
        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.substringBefore(';').trim()
                if (line.isEmpty()) continue
                val words = line.split(' ', '\t').filter { it.isNotEmpty() }
                fun v(letter: Char): Double? = words.drop(1).firstOrNull { it[0].uppercaseChar() == letter }?.substring(1)?.toDoubleOrNull()
                when (words[0].uppercase()) {
                    "G0", "G1", "G2", "G3" -> {
                        v('F')?.let { f = it }
                        val nx = v('X')?.let { if (absXYZ) it.toFloat() else x + it.toFloat() } ?: x
                        val ny = v('Y')?.let { if (absXYZ) it.toFloat() else y + it.toFloat() } ?: y
                        val nz = v('Z')?.let { if (absXYZ) it.toFloat() else z + it.toFloat() } ?: z
                        val ne = v('E')?.let { if (absE) it.toFloat() else e + it.toFloat() } ?: e
                        val dx = (nx - x).toDouble(); val dy = (ny - y).toDouble(); val dz = (nz - z).toDouble(); val de = (ne - e).toDouble()
                        var path = sqrt(dx * dx + dy * dy + dz * dz)
                        val arc = words[0].uppercase() != "G1" && words[0].uppercase() != "G0"
                        if (arc) {
                            val r = hypot(v('I') ?: 0.0, v('J') ?: 0.0)
                            // Orca's spiral lift is a whole turn back to where it began
                            if (dx == 0.0 && dy == 0.0) path = hypot(2 * Math.PI * r, dz) else path = max(path, hypot(dx, dy))
                        }
                        var dur = 0.0
                        if (path > 1e-6) {
                            var speed = f / 60
                            if (dx != 0.0 || dy != 0.0) speed = min(speed, maxXY * path / max(abs(dx), abs(dy)))
                            if (dz != 0.0) speed = min(speed, maxZ * path / abs(dz))
                            if (de != 0.0) speed = min(speed, maxE * path / abs(de))
                            dur = path / speed
                            if (dx == 0.0 && dy == 0.0) dur = max(dur, 2 * sqrt(abs(dz) / 200))
                        } else if (de != 0.0) {
                            dur = max(abs(de) / min(f / 60, maxE), 2 * sqrt(abs(de) / 500))
                        }
                        val n = perLayer.getOrDefault(layer, 0)
                        perLayer[layer] = n + 1
                        if (dur > 0) moves += Move(t, t + dur, x, y, z, e, nx, ny, nz, ne, layer, n, true, v('Z')?.toFloat() ?: moves.lastOrNull()?.zSent ?: z)
                        t += dur
                        x = nx; y = ny; z = nz; e = ne
                    }
                    "G92" -> {
                        v('E')?.let { e = it.toFloat() }
                        v('Z')?.let { z = it.toFloat() }
                    }
                    "G28" -> { wait(20.0); x = 0f; y = 0f; z = 5f }
                    "G4" -> wait((v('P') ?: 0.0) / 1000 + (v('S') ?: 0.0))
                    "G90" -> absXYZ = true
                    "G91" -> absXYZ = false
                    "M82" -> absE = true
                    "M83" -> absE = false
                    // the slicer's own limits replace the firmware's for the print
                    "M203" -> { v('X')?.let { maxXY = it }; v('Z')?.let { maxZ = it }; v('E')?.let { maxE = it } }
                    "M190" -> wait(90.0)
                    "M109" -> wait(45.0)
                    "M117" -> if (words.getOrNull(1) == "DASHBOARD_LAYER_INDICATOR") words.getOrNull(2)?.toIntOrNull()?.let { layer = it }
                }
            }
        }
        return moves to perLayer
    }

    @Test
    fun followingARealFile() {
        val file = File(System.getProperty("sketch").orEmpty())
        assumeTrue("ask for it with -Psketch=<a .gcode file from OctoPrint>", file.isFile && file.extension == "gcode")

        val route = GcodePreview.read({ file.inputStream() })!!.route
        assumeTrue("the file has none of the Dashboard plugin's layer marks", route.layers.isNotEmpty())
        val (moves, perLayer) = plan(file)
        println("${moves.size} moves, ${route.layers.size} layers, %.0f min".format(moves.last().t1 / 60))

        // what the firmware sends: every 250 ms while moves are queued, quiet while waiting
        val fixes = ArrayList<NozzleFix>()
        var next = 0.0
        for (m in moves) {
            if (!m.moving) { next = max(next, m.t1); continue }
            while (next < m.t1) {
                if (next >= m.t0) {
                    val k = ((next - m.t0) / (m.t1 - m.t0)).toFloat()
                    fixes += NozzleFix(
                        m.x0 + (m.x1 - m.x0) * k, m.y0 + (m.y1 - m.y0) * k, m.z0 + (m.z1 - m.z0) * k,
                        m.e0 + (m.e1 - m.e0) * k, (next * 1000).toLong(),
                    )
                    next += 0.25
                } else next = m.t0
            }
        }
        // OctoPrint hands the lines over twice a second
        val arrival = LongArray(fixes.size) { (ceil((fixes[it].millis + 10) / 500.0) * 500).toLong() + 30 }

        val nozzle = NozzleFollower()
        nozzle.useLayers(route.layers.map { it.number }.toIntArray(), route.layers.map { it.z }.toFloatArray())
        val shownLive = HashMap<Int, Double>()
        val shownBehind = HashMap<Int, Double>()
        val shownSent = HashMap<Int, Double>()
        val heightOff = HashMap<String, Double>()
        var liveTime = 0.0
        var doneErr = 0.0
        var doneSamples = 0
        var doneBehindErr = 0.0
        var doneBehindSigned = 0.0
        val fillOff = HashMap<Int, Double>()
        var doneBehindSamples = 0
        var leadSum = 0.0
        var leadMax = 0.0
        var leadSamples = 0
        var mapOf = -1
        var fi = 0
        var k = 0
        var kBehind = 0
        var reported = 0
        val frame = 16L
        var now = 0L
        val end = (moves.last().t1 * 1000).toLong()
        fun along(m: Move, at: Double) = if (m.t1 > m.t0) ((at - m.t0) / (m.t1 - m.t0)).coerceIn(0.0, 1.0) else 1.0
        while (now < end) {
            val s = now / 1000.0
            while (k < moves.size - 1 && moves[k].t1 <= s) k++
            // the display plays the readings back this far behind, so this is where it means to show the nozzle
            val behind = s - 0.7
            while (kBehind < moves.size - 1 && moves[kBehind].t1 <= behind) kBehind++
            val m = moves[k]
            val truth = m.layer
            // lines go out one at a time as the 32-move queue makes room: what has been sent is that far on
            val sent = moves[min(k + 33, moves.size - 1)]
            val sentLayer = sent.layer

            while (fi < fixes.size && arrival[fi] <= now) { nozzle.add(fixes[fi], arrival[fi]); fi++ }
            val live = nozzle.look(now, sentLayer.takeIf { it >= 1 })
            val shown = nozzle.layer
            if (shown != null && shown != mapOf) {
                route.layers.firstOrNull { it.number == shown }?.let { nozzle.useMap(LayerMap(route, it)) }
                mapOf = shown
            }
            if (truth >= 1 && m.moving) {
                shownSent.merge(sentLayer - truth, 1.0, Double::plus)
                val lead = max(0.0, sent.t0 - s)
                leadSum += lead; leadMax = max(leadMax, lead); leadSamples++
                val layerZ = route.layers.firstOrNull { it.number == truth }?.z ?: m.z1
                val dz = sent.zSent - layerZ
                heightOff.merge(
                    when { abs(dz) < 0.01f -> "this layer"; dz > 0.3f -> "a hop or more above"; dz > 0 -> "the next layer"; else -> "below" },
                    1.0, Double::plus,
                )
                if (live && shown != null) {
                    liveTime++
                    shownLive.merge(shown - truth, 1.0, Double::plus)
                    val mb = moves[kBehind]
                    shownBehind.merge(shown - mb.layer, 1.0, Double::plus)
                    if (shown == truth && perLayer[truth] != null) {
                        doneErr += abs(nozzle.done - (m.inLayer + along(m, s)) / perLayer.getValue(truth))
                        doneSamples++
                    }
                    if (shown == mb.layer && mb.inLayer >= 0 && perLayer[mb.layer] != null) {
                        val off = nozzle.done - (mb.inLayer + along(mb, behind)) / perLayer.getValue(mb.layer)
                        doneBehindErr += abs(off)
                        doneBehindSigned += off
                        fillOff.merge((off * 20).roundToInt(), 1.0, Double::plus)
                        doneBehindSamples++
                    }
                    if (shown - truth >= 2 && reported < 4) {
                        reported++
                        val around = fixes.filter { it.millis in (now - 2200)..(now - 300) }
                        println("at %.0f s the nozzle is on layer $truth (z %.2f) and the display says $shown. Readings before:".format(s, m.z1))
                        for (r in around) println("   %.2f %.2f z %.2f e %.2f at %d".format(r.x, r.y, r.z, r.e, r.millis))
                    }
                }
            }
            now += frame
        }
        fun table(name: String, h: Map<Int, Double>) {
            val all = h.values.sum()
            println(name)
            for (d in h.keys.sorted()) println("   %+d layers: %5.1f%%".format(d, 100 * h.getValue(d) / all))
        }
        println("readings coming in for %.1f%% of the printing time".format(100 * liveTime / leadSamples))
        table("layer shown from REALPOS against the real one", shownLive)
        table("layer shown from REALPOS against the one 0.7 s before, which it means to show", shownBehind)
        table("layer sent (Dashboard) against the real one", shownSent)
        println("fill through the layer from REALPOS, when on the right layer: off by %.1f%% on average".format(100 * doneErr / max(1, doneSamples)))
        println(
            "the same against 0.7 s before: off by %.1f%% on average, %+.1f%% counting ahead as plus and behind as minus"
                .format(100 * doneBehindErr / max(1, doneBehindSamples), 100 * doneBehindSigned / max(1, doneBehindSamples)),
        )
        val fills = fillOff.values.sum()
        for (d in fillOff.keys.sorted()) println("   %+4d%%: %5.1f%%".format(d * 5, 100 * fillOff.getValue(d) / fills))
        println("what has been sent is %.1f s ahead of the nozzle on average, %.1f s at most".format(leadSum / leadSamples, leadMax))
        val hs = heightOff.values.sum()
        println("Height (OctoPrint's last Z sent) shows: " + heightOff.entries.joinToString { "${it.key} %.1f%%".format(100 * it.value / hs) })
    }
}

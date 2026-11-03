package com.drazan.echoshow.octoprint

import com.drazan.echoshow.octoprint.octo.GcodePreview
import com.drazan.echoshow.octoprint.octo.LayerMap
import com.drazan.echoshow.octoprint.octo.NozzleFix
import com.drazan.echoshow.octoprint.octo.NozzleFollower
import com.drazan.echoshow.octoprint.octo.NozzleTrack
import com.drazan.echoshow.octoprint.octo.parseNozzleFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Following where the nozzle really is, from what the firmware says. */
class NozzleTest {

    private fun fix(x: Float, millis: Long, e: Float = millis / 100f, y: Float = 110f, z: Float = 0.8f) = NozzleFix(x, y, z, e, millis)

    @Test
    fun readsTheFirmwaresLine() {
        val fix = parseNozzleFix("Recv: REALPOS 123.45 67.89 1.20 1234.56 987654")
        assertNotNull(fix)
        assertEquals(123.45f, fix!!.x, 0.001f)
        assertEquals(67.89f, fix.y, 0.001f)
        assertEquals(1.2f, fix.z, 0.001f)
        assertEquals(1234.56f, fix.e, 0.01f)
        assertEquals(987654L, fix.millis)
    }

    @Test
    fun leavesEverythingElseAlone() {
        for (line in listOf(
            "Recv: ok", "Send: N123 G1 X10 Y10*45", "Recv: X:10.00 Y:20.00 Z:0.20 E:0.00 Count X:800 Y:1600 Z:80",
            "Recv: REALPOS 1 2 3", "Recv: REALPOS a b c d e", "Recv: REALPOS NaN 2 3 4 5",
        )) assertNull(line, parseNozzleFix(line))
    }

    @Test
    fun aBatchIsPlayedBackInStepWithWhenItWasTaken() {
        val track = NozzleTrack()
        // four readings a quarter of a second apart, all arriving at once, as OctoPrint sends them
        for (i in 0..3) track.add(fix(100f + i * 10f, 10_000L + i * 250L), arrivedAt = 50_750L)
        val here = FloatArray(5)
        for (i in 0..4) {
            assertTrue(track.sample(50_750L + 200L + i * 125L))
            here[i] = track.x
        }
        for (i in 1..4) assertTrue("it should run on smoothly between readings: ${here.toList()}", here[i] >= here[i - 1])
        assertTrue("and be somewhere between them, not at the end at once: ${here.toList()}", here[2] > 100f && here[2] < 130f)
    }

    @Test
    fun itNeverGoesPastTheLastReading() {
        val track = NozzleTrack()
        track.add(fix(100f, 1_000L), arrivedAt = 5_000L)
        track.add(fix(110f, 1_250L), arrivedAt = 5_250L)
        assertTrue(track.sample(7_000L))
        assertEquals("nothing is guessed beyond what was measured", 110f, track.x, 0.01f)
    }

    @Test
    fun silenceMeansNotKnowing() {
        val track = NozzleTrack()
        track.add(fix(100f, 1_000L), arrivedAt = 5_000L)
        assertTrue(track.sample(6_000L))
        assertFalse("after three seconds the firmware has stopped saying", track.sample(8_100L))
    }

    @Test
    fun aTravelIsNotLaying() {
        val track = NozzleTrack()
        track.add(fix(100f, 1_000L, e = 5f), arrivedAt = 1_000L)
        track.add(fix(120f, 1_250L, e = 5f), arrivedAt = 1_250L)
        track.add(fix(130f, 1_500L, e = 6f), arrivedAt = 1_500L)
        track.sample(1_825L)
        assertFalse(track.laying)
        track.sample(2_075L)
        assertTrue(track.laying)
    }

    @Test
    fun aRestartedPrinterIsNotReadAsTimeGoingBackwards() {
        val track = NozzleTrack()
        track.add(fix(100f, 900_000L), arrivedAt = 5_000L)
        track.add(fix(50f, 1_000L), arrivedAt = 6_000L)
        assertTrue(track.sample(6_100L))
        assertEquals(50f, track.x, 0.01f)
    }

    /* ---------------- along the toolpath ---------------- */

    /** Layers of a 20 mm square, each an outline and lines filling it from the front to the back. */
    private val square = buildString {
        appendLine("M83")
        var layer = 0
        var z = 0.2f
        while (z <= 1.21f) {
            layer++
            appendLine("M117 DASHBOARD_LAYER_INDICATOR $layer")
            appendLine("G1 Z%.2f F600".format(z))
            appendLine(";TYPE:Solid infill")
            var y = 100.4f
            while (y < 119.8f) {
                appendLine("G1 X100.4 Y%.2f F6000".format(y))
                appendLine("G1 X119.6 Y%.2f E0.6".format(y))
                y += 0.4f
            }
            z += 0.2f
        }
    }

    private val route = GcodePreview.read({ square.byteInputStream() })!!.route

    private fun follower(): NozzleFollower = NozzleFollower().apply {
        useLayers(route.layers.map { it.number }.toIntArray(), route.layers.map { it.z }.toFloatArray())
    }

    /** Every layer has been sent to the printer. */
    private val allSent = Int.MAX_VALUE

    /**
     * Feeds the nozzle laying plastic at one spot and looks, given the map of the layer it is on
     * and [sent] as the last layer sent to the printer.
     */
    private fun NozzleFollower.at(x: Float, y: Float, z: Float, clock: LongArray, sent: Int? = allSent) {
        val t = clock[0]
        add(NozzleFix(x, y, z, t / 10f, t), arrivedAt = t + 200L)
        add(NozzleFix(x, y, z, (t + 250) / 10f, t + 250), arrivedAt = t + 450L)
        look(t + 1_100L, sent)
        layer?.let { n -> useMap(LayerMap(route, route.layers.first { it.number == n })) }
        look(t + 1_101L, sent)
        clock[0] = t + 500L
    }

    @Test
    fun theLayerComesFromTheRealHeightNotFromWhatWasSent() {
        val nozzle = follower()
        val clock = longArrayOf(1_000L)
        nozzle.at(110f, 101f, 0.8f, clock)
        assertEquals(4, nozzle.layer)
        assertEquals(0.8f, nozzle.height, 0.001f)
    }

    @Test
    fun theFillGoesWhereTheNozzleIs() {
        val nozzle = follower()
        val clock = longArrayOf(1_000L)
        nozzle.at(110f, 102f, 0.8f, clock)
        val early = nozzle.done
        repeat(12) { nozzle.at(110f, 102f + it * 1.5f, 0.8f, clock) }
        assertTrue("further back in the part is further through the layer: $early then ${nozzle.done}", nozzle.done > early + 0.5f)
    }

    @Test
    fun aTravelOverPlasticStillToComeDoesNotMoveIt() {
        val nozzle = follower()
        val clock = longArrayOf(1_000L)
        nozzle.at(110f, 104f, 0.8f, clock)
        val before = nozzle.done
        // across to the far side of the part with the extruder still, as a travel goes
        val t = clock[0]
        nozzle.add(NozzleFix(110f, 118f, 0.8f, 1f, t), arrivedAt = t)
        nozzle.add(NozzleFix(110f, 118f, 0.8f, 1f, t + 250), arrivedAt = t + 250)
        nozzle.look(t + 1_100L, allSent)
        assertEquals(before, nozzle.done, 0.0001f)
    }

    @Test
    fun aHopUpOnATravelIsNotANewLayer() {
        val nozzle = follower()
        val clock = longArrayOf(1_000L)
        nozzle.at(110f, 104f, 0.8f, clock)
        val t = clock[0]
        nozzle.add(NozzleFix(110f, 104f, 1.2f, 1f, t), arrivedAt = t)
        nozzle.add(NozzleFix(110f, 104f, 1.2f, 1f, t + 250), arrivedAt = t + 250)
        nozzle.look(t + 1_100L, allSent)
        assertEquals(4, nozzle.layer)
    }

    @Test
    fun theExtruderPushingBackAfterAHopIsNotANewLayer() {
        val nozzle = follower()
        val clock = longArrayOf(1_000L)
        nozzle.at(110f, 104f, 0.8f, clock)
        // up 0.4 for a travel with the filament drawn in, then back down and pushed out again
        val t = clock[0]
        nozzle.add(NozzleFix(112f, 106f, 1.2f, 1f, t), arrivedAt = t)
        nozzle.add(NozzleFix(114f, 108f, 0.8f, 1.8f, t + 250), arrivedAt = t + 250)
        nozzle.look(t + 1_100L, allSent)
        assertEquals("the plastic went down at the lower reading's height", 4, nozzle.layer)
    }

    @Test
    fun itIsNeverOnALayerNotSentYet() {
        val nozzle = follower()
        val clock = longArrayOf(1_000L)
        nozzle.at(110f, 104f, 1.0f, clock, sent = 4)
        assertEquals(4, nozzle.layer)
        nozzle.at(110f, 104f, 1.0f, clock, sent = 5)
        assertEquals(5, nozzle.layer)
    }

    @Test
    fun thePurgeBeforeTheFirstLayerIsNoLayer() {
        val nozzle = follower()
        val clock = longArrayOf(1_000L)
        // a purge line a shade higher than the first layer, before the first layer's mark goes out
        nozzle.at(110f, 104f, 0.28f, clock, sent = null)
        assertNull(nozzle.layer)
        nozzle.at(110f, 101f, 0.2f, clock, sent = 1)
        assertEquals(1, nozzle.layer)
    }

    @Test
    fun aNewLayerStartsFromNothing() {
        val nozzle = follower()
        val clock = longArrayOf(1_000L)
        nozzle.at(110f, 116f, 0.8f, clock)
        assertTrue(nozzle.done > 0.5f)
        nozzle.at(110f, 101f, 1.0f, clock)
        assertEquals(5, nozzle.layer)
        assertTrue("near the start of layer 5, not ${nozzle.done}", nozzle.done < 0.2f)
    }

    @Test
    fun aHopIntoANewLayerOverWhatItLaysLastStartsItFromWhereItLands() {
        val nozzle = follower()
        val clock = longArrayOf(1_000L)
        nozzle.at(110f, 116f, 0.8f, clock)
        // up and over the back of the part, where layer 5 finishes, then down at its front and pushed out
        val t = clock[0]
        nozzle.add(NozzleFix(110f, 118f, 1.4f, 1f, t), arrivedAt = t)
        nozzle.add(NozzleFix(110.5f, 101f, 1.0f, 1.8f, t + 250), arrivedAt = t + 250)
        nozzle.look(t + 1_100L, allSent)
        assertEquals(5, nozzle.layer)
        nozzle.useMap(LayerMap(route, route.layers.first { it.number == 5 }))
        nozzle.look(t + 1_101L, allSent)
        assertTrue("near the start of layer 5, not ${nozzle.done}", nozzle.done < 0.2f)
    }

    @Test
    fun aTravelStraightAfterPrintingDoesNotCreepAhead() {
        val nozzle = follower()
        val clock = longArrayOf(1_000L)
        nozzle.at(110f, 104f, 0.8f, clock)
        val before = nozzle.done
        // printed a little, then across to the back of the part before the next reading
        val t = clock[0]
        nozzle.add(NozzleFix(110f, 104.4f, 0.8f, 1f, t), arrivedAt = t)
        nozzle.add(NozzleFix(110f, 118f, 0.8f, 1.2f, t + 250), arrivedAt = t + 250)
        // frame by frame through the quarter second between the two, where each step on is small
        for (frame in 0..16) nozzle.look(t + 700L + frame * 16L, allSent)
        assertTrue("still about where it printed: $before then ${nozzle.done}", nozzle.done < before + 0.05f)
    }

    @Test
    fun theLayerNeverGoesBack() {
        val nozzle = follower()
        val clock = longArrayOf(1_000L)
        nozzle.at(110f, 104f, 1.0f, clock)
        nozzle.at(110f, 104f, 0.6f, clock)
        assertEquals(5, nozzle.layer)
    }

    @Test
    fun aDisplayThatLostTrackFindsItsWayAgain() {
        val nozzle = follower()
        val clock = longArrayOf(1_000L)
        nozzle.at(110f, 101f, 0.8f, clock)
        // far further on than it should have got to, and staying there
        repeat(40) { nozzle.at(110f, 118f, 0.8f, clock) }
        assertTrue("it should give in and believe it, not sit at ${nozzle.done}", nozzle.done > 0.7f)
    }

    @Test
    fun withoutTheFilesLayersThereIsNothingToSay() {
        val nozzle = NozzleFollower()
        nozzle.add(NozzleFix(110f, 104f, 0.8f, 1f, 1_000L), arrivedAt = 1_000L)
        nozzle.add(NozzleFix(110f, 104f, 0.8f, 2f, 1_250L), arrivedAt = 1_250L)
        assertTrue(nozzle.look(2_000L, allSent))
        assertNull(nozzle.layer)
    }
}

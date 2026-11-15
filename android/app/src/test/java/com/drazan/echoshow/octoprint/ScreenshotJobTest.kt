package com.drazan.echoshow.octoprint

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drazan.echoshow.octoprint.octo.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A walk through a job: following the nozzle where the firmware says it is, a job printed
 * from the SD card, a long one, heating up, paused, a question from the printer, and done.
 * See [ScreenWalk].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w788dp-h394dp-land-195dpi")
class ScreenshotJobTest : ScreenWalk(5001) {

    @Test
    fun aJobFromStartToFinish() = walk("printing", "file=5&progress=0.55&realpos=1") { a, vm ->
        // Firmware that says where the nozzle really is: the display goes by that, not by
        // what has been sent to the printer, which runs ahead of it. It needs the part drawn first.
        settleUntil(12_000, after = 1500) { vm.partDrawn() && vm.nozzleReporting && vm.nozzleLayer in 54..57 }
        val realLayer = vm.nozzleLayer
        assertTrue("the display should have picked up the firmware's readings", vm.nozzleReporting && realLayer != null)
        assertTrue("layer $realLayer should be the one the print has got to", realLayer in 54..57)
        shot(a, "05g-following-the-nozzle")
        scene("printing", "progress=0.3")
        settleUntil(6000) { vm.nozzleLayer == null }
        assertNull("with the readings gone it should go back to what it was told", vm.nozzleLayer)

        // Printed from the printer's SD card and known only by the card's short name: OctoPrint
        // can't read the file, so the part and its layers come from its own copy, and what the
        // Dashboard plugin says is left from an earlier job. Well into a layer, so the picture
        // shows it filling.
        scene("printing", "file=5&progress=0.762&realpos=1&sd=1&sdname=CALIBR~1.GCO")
        // The copy is drawn afresh, as the mock dates its files anew each time it lists them,
        // then its layers and the map of the one being laid are read, and only then can the
        // picture of that layer, filled as far as the nozzle has got, be made.
        settleUntil(15_000, after = 2000) {
            vm.jobDrawing?.name == CUBE && vm.nozzleLayer in 74..77 && vm.nozzleLayers == 100 && vm.partDrawn() &&
                vm.nozzleDone.value > 0f
        }
        assertEquals("OctoPrint's own copy stands in", CUBE, vm.jobDrawing?.name)
        val cardLayer = vm.nozzleLayer
        assertTrue("layer $cardLayer should be the one the print has got to, not the Dashboard's 3", cardLayer in 74..77)
        assertEquals("the copy's layers, not the Dashboard's 33", 100, vm.nozzleLayers)
        shot(a, "05h-printing-from-the-sd-card")

        // A job long enough for the time left to need its widest form. A new job on a real
        // printer forgets the last one's nozzle, and the mock starts none: wait for those
        // readings to go out of date instead.
        scene("printing", "progress=0.02&total=86000")
        settleUntil(8000, after = 800) {
            (vm.printer?.printTimeLeft ?: 0L) > 80_000L && vm.nozzleLayer == null && vm.printing(CHESSBOARD) && vm.partDrawn()
        }
        shot(a, "05c-printing-long-job")

        scene("heating")
        settleUntil(5000, after = 800) { vm.printer?.tool?.heating == true }
        shot(a, "06-heating-up")

        scene("paused", "progress=0.41")
        settleUntil(5000, after = 600) { vm.isIn(Phase.PAUSED) }
        shot(a, "07-paused")

        scene("prompt", "progress=0.41")
        settleUntil(5000, after = 600) { vm.prompt != null }
        shot(a, "08-printer-asks")
        tap(a, 350f, 300f)         // answer it
        settleUntil(3000) { vm.prompt == null }

        scene("done")
        settleUntil(5000, after = 800) { vm.outcome != null }
        shot(a, "09-finished")
    }
}

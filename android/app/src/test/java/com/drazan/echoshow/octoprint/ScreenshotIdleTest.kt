package com.drazan.echoshow.octoprint

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drazan.echoshow.octoprint.octo.JogSpeed
import com.drazan.echoshow.octoprint.octo.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * A walk through the screens around printing: the printer free, gone and in error, the files,
 * the controls idle and mid-print, settings, the camera on the whole screen, and the screen
 * going off and coming back. See [ScreenWalk].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w788dp-h394dp-land-195dpi")
class ScreenshotIdleTest : ScreenWalk(5002) {

    @Test
    fun aroundThePrinting() = walk("ready") { a, vm ->
        // the file list is fetched as the display connects, and the mock is as slow to give it as a Pi
        settleUntil(7000, after = 800) { vm.isIn(Phase.READY) && vm.files.loaded && !vm.files.loading }
        shot(a, "10-ready", load = 1500)

        scene("offline")
        settleUntil(5000, after = 500) { vm.isIn(Phase.OFFLINE) }
        shot(a, "11-printer-offline")

        // A file going onto the printer's SD card: OctoPrint calls the printer offline meanwhile.
        // The display says what is really going on, without the button that would connect it.
        scene("offline", "copying=0.42&copyprint=1&copyheat=1")
        settleUntil(5000, after = 500) { vm.cardCopy?.fraction == 0.42f }
        shot(a, "11b-copying-to-card")
        assertTrue("the copy is followed, with the print and the heating it is for",
            vm.cardCopy?.printAfter == true && vm.cardCopy?.bed == 60 && vm.cardCopy?.nozzle == 150)
        get("$mock/mock/copy?stage=progress&fraction=0.8")
        settleUntil(3000, after = 300) { vm.cardCopy?.fraction == 0.8f }
        assertEquals(0.8f, vm.cardCopy?.fraction)
        get("$mock/mock/copy?stage=failed&error=the%20printer%20stopped%20answering")
        settleUntil(3000, after = 300) { vm.cardCopy == null }
        assertTrue("a failed copy is said: ${vm.notice}",
            vm.cardCopy == null && vm.notice?.contains("SD card failed: the printer stopped answering") == true)

        scene("error")
        settleUntil(5000, after = 500) { vm.isIn(Phase.ERROR) }
        shot(a, "12-printer-error")

        // files
        scene("ready")
        settleUntil(5000, after = 300) { vm.isIn(Phase.READY) }
        tap(a, 38f, 104f)
        settleUntil(7000, after = 1000) { vm.files.loaded && !vm.files.loading }
        shot(a, "13-files", load = 1500)
        tap(a, 700f, 430f)         // Print
        settle(1000)
        shot(a, "14-start-print")
        tap(a, 760f, 310f)         // Not now
        settle(600)

        // the same file, with the Card Transfer plugin saying the printer's SD card hasn't got it:
        // the sheet offers copying it there first, or printing from OctoPrint after all
        scene("ready", "cardtransfer=missing")
        settleUntil(5000, after = 300) { vm.isIn(Phase.READY) }
        tap(a, 700f, 430f)         // Print
        settle(1000)
        shot(a, "14b-start-print-not-on-card")
        sent()                     // forget what went before
        tap(a, 589f, 266f)         // Copy to SD card and print
        settle(1500)
        assertTrue("Copy to SD card and print asks the plugin for just that",
            sent().any { it.optString("cardtransfer") == "send_and_print" })
        tap(a, 38f, 104f)          // back to the files
        settleUntil(7000, after = 1000) { vm.files.loaded && !vm.files.loading }
        tap(a, 700f, 430f)         // Print
        settle(1000)
        tap(a, 503f, 332f)         // Print from OctoPrint
        settleUntil(5000, after = 300) { vm.isIn(Phase.PRINTING) }
        assertTrue("Print from OctoPrint is allowed once, then started",
            sent().any { it.optString("cardtransfer") == "allow_local" } && vm.isIn(Phase.PRINTING))
        scene("ready")
        settleUntil(5000, after = 300) { vm.isIn(Phase.READY) }
        tap(a, 38f, 104f)          // the files again, as the walk had them
        settle(1200)

        // controls, idle and mid-print
        tap(a, 38f, 164f)
        settle(1200)
        shot(a, "15-controls")
        // three quick taps on nozzle-up should reach OctoPrint as one target, then a jog right
        repeat(3) { tap(a, 342f, 83f) }
        settle(1500)
        tap(a, 552f, 144f)
        settle(800)
        shot(a, "15b-controls-heating")
        assertEquals(3000, sent().last { it.getString("command") == "jog" }.getInt("speed"))
        // X and Y go at the speed chosen, Z at a slower one of its own
        tap(a, 560f, 305f)         // Fast
        settle(600)
        assertEquals(JogSpeed.FAST, vm.display.value.jogSpeed)
        tap(a, 552f, 144f)         // right
        settle(500)
        tap(a, 614f, 84f)          // up
        settle(800)
        assertEquals(listOf(9000, 300), sent().map { it.getInt("speed") })
        // a hot nozzle brings out the filament choices
        scene("ready", "tool=215")
        settleUntil(3000, after = 300) { (vm.printer?.tool?.actual ?: 0f) >= 200f }
        tap(a, 790f, 185f)         // 2 mm/s
        settle(600)
        tap(a, 875f, 83f)          // Extrude
        settle(800)
        assertEquals(120, sent().single { it.getString("command") == "extrude" }.getInt("speed"))
        shot(a, "15c-controls-hot")
        // both are kept, so back to what the shots further down expect
        vm.setJogSpeed(JogSpeed.NORMAL)
        vm.setExtrudeSpeed(300)
        settle(600)
        scene("printing", "progress=0.3")
        settleUntil(5000, after = 800) { vm.isIn(Phase.PRINTING) }
        shot(a, "16-controls-while-printing")

        // settings
        tap(a, 38f, 436f)
        settle(1000)
        shot(a, "17-settings")
        tap(a, 150f, 311f)         // turn the screen off: the list of times
        settle(800)
        shot(a, "17a-screen-off-times")
        tap(a, 150f, 122f)         // 2 minutes
        settle(800)
        assertEquals(2, vm.display.value.offAfterMin)
        shot(a, "17c-screen-off-chosen")
        // back to what the screen going off further down counts on
        vm.setOffAfter(5)
        settle(600)
        tap(a, 750f, 269f)         // power plug
        settle(800)
        shot(a, "17b-power-plug")
        tap(a, 880f, 40f)          // away from the sheet closes it
        settle(600)

        // camera on the whole screen
        tap(a, 38f, 44f)
        settle(800)
        tap(a, 110f, 52f)          // picture: the camera
        settle(1500)
        tap(a, 300f, 180f)         // open it full screen
        settle(1500)
        shot(a, "18-camera-full", load = 1500)

        // left alone, the screen goes off: the display looks every five seconds
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        settleUntil(8000, after = 500) { a.window.attributes.screenBrightness in 0f..0.1f }
        shot(a, "19-screen-off")
        assertTrue("the backlight should be turned down", a.window.attributes.screenBrightness in 0f..0.1f)
        // the touch that brings it back lands where Pause is, and must not press it
        tap(a, 712f, 432f)
        settleUntil(4000, after = 800) { a.window.attributes.screenBrightness < 0f }
        shot(a, "20-touched-awake")
        assertTrue("the backlight should be handed back", a.window.attributes.screenBrightness < 0f)
    }
}

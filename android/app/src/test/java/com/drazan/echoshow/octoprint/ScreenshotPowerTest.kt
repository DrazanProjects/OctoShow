package com.drazan.echoshow.octoprint

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drazan.echoshow.octoprint.octo.Link
import com.drazan.echoshow.octoprint.octo.Phase
import com.drazan.echoshow.octoprint.power.PowerStep
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A walk through switching everything off with the smart plug: when a job is done, not when
 * one ends any other way, the plug found off, starting up again, and switching off straight
 * after a print. See [ScreenWalk].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w788dp-h394dp-land-195dpi")
class ScreenshotPowerTest : ScreenWalk(5004) {

    @Test
    fun switchingOff() = walk("printing", "progress=0.3") { a, vm ->
        settleUntil(10_000, after = 1500) { vm.isIn(Phase.PRINTING) && vm.printing(CHESSBOARD) && vm.partDrawn() }

        // asked to switch off when the job is done: nothing happens until it is
        tap(a, 926f, 34f)          // power, which under a job asks about the end of it
        settle(800)
        shot(a, "20a-when-done-question")
        tap(a, 350f, 308f)         // Switch off when done
        settle(800)
        shot(a, "20b-off-when-done")
        assertTrue(vm.offWhenDone)
        assertNull(vm.power)
        scene("done", "tool=180")
        settleUntil(6000, after = 500) { vm.power is PowerStep.AfterPrint }
        shot(a, "20c-done-so-switching-off")
        // it says so first and waits; the routine itself is the one photographed further down
        assertTrue("a finished job should lead to switching off unasked", vm.power is PowerStep.AfterPrint)
        assertFalse(vm.offWhenDone)
        tap(a, 480f, 296f)         // Leave it on
        settleUntil(4000) { vm.power == null }
        assertNull("saying no during the pause should be the end of it", vm.power)

        // a job that ends any other way leaves everything on
        scene("printing", "progress=0.3")
        settleUntil(5000, after = 300) { vm.isIn(Phase.PRINTING) }
        tap(a, 926f, 34f)
        settle(600)
        tap(a, 350f, 308f)
        settle(600)
        assertTrue(vm.offWhenDone)
        scene("ready")             // gone without finishing
        // The request is dropped at once, but the display listens on for how the job ended
        // for a while longer, and a finish heard then, from a job further down, would count.
        settle(10_000)             // longer than it waits to hear how the job ended
        assertFalse(vm.offWhenDone)
        assertNull("only a job that finishes should switch things off", vm.power)

        // the plug switched off behind the display's back, long after it started: left alone
        get("$mock/cm?cmnd=Power%20Off")
        scene("dead")
        settleUntil(12_000, after = 800) { vm.plugOn == false }   // the plug is asked every five seconds
        shot(a, "21-switched-off")
        assertFalse("only starting the display switches the plug on by itself", plugIsOn())
        tap(a, 140f, 289f)         // Switch on
        settleUntil(4000, after = 500) { vm.startingUp }
        shot(a, "22-starting-up")

        // and everything switched off from here, straight after a print
        scene("done", "tool=180")  // OctoPrint is back
        settleUntil(12_000) { vm.link == Link.Online }
        scene("done", "tool=180")
        settleUntil(5000, after = 800) { vm.outcome != null }
        shot(a, "23-finished-with-plug")
        tap(a, 926f, 34f)          // power
        settle(800)
        shot(a, "24-switch-off")
        hold(a, 350f, 306f, 1600)  // hold to confirm
        release(a, 350f, 306f)
        settleUntil(4000, after = 300) { vm.power is PowerStep.Cooling }
        shot(a, "25-cooling")
        scene("done", "tool=40")   // cool enough now
        settleUntil(7000, after = 1000) { vm.power is PowerStep.ShuttingDown || vm.power is PowerStep.Settling }
        // the mock can't drop off the network the way a Pi does, so this is as far as it gets
        shot(a, "26-shutting-down")
    }
}

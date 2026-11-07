package com.drazan.echoshow.octoprint

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drazan.echoshow.octoprint.octo.Link
import com.drazan.echoshow.octoprint.octo.Phase
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The first of the walks through the app's screens: setting the display up, then a print
 * in progress, on the camera and as the part, drawn while it prints. The others are the
 * Screenshot*Test classes beside this one; see [ScreenWalk].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The Echo Show 5 as it really reports itself: 960 x 480 pixels at 195 dpi, i.e. 788 x 394 dp.
@Config(sdk = [34], qualifiers = "w788dp-h394dp-land-195dpi")
class ScreenshotTest : ScreenWalk(5000) {

    @Test
    fun settingUpAndThePart() = walk(
        "printing", connect = false,
        // A smart plug is already set up and is off as the display starts. Starting the
        // display is what brings the printer up, so it should be switched on unasked.
        beforeLaunch = {
            get("$mock/cm?cmnd=Power%20Off")
            assertFalse(plugIsOn())
        },
    ) { a, vm ->
        // first run
        settle(1500)
        shot(a, "01-setup")
        assertTrue("a plug found off as the display starts should be switched on", plugIsOn())
        // No scheme given and the port only speaks https with a self-signed certificate:
        // the same situation as a real OctoPi that has plain http switched off.
        vm.requestAccess(secure)
        settleUntil(4000) { vm.setup is SetupState.Waiting }
        shot(a, "02-setup-waiting")

        // the mock approves the key, and the display finds a print in progress, its
        // picture starting as the file's preview
        settleUntil(15_000, after = 1000) { vm.link == Link.Online && vm.isIn(Phase.PRINTING) && vm.printing(CHESSBOARD) }
        tap(a, 110f, 52f)          // picture: the camera instead
        settle(1500)
        shot(a, "03-printing-camera", load = 1500)
        hold(a, 866f, 432f, 800)   // halfway through holding Cancel
        // One picture, not shot()'s two with a wait between: the wait would see the hold
        // through to the end, and the print this walk goes on to photograph would be cancelled.
        a.window.decorView.captureRoboImage(File(out, "03b-holding-cancel.png").path)
        release(a, 866f, 432f)
        settle(600)
        tap(a, 164f, 52f)          // picture: back to the preview
        settleUntil(4000, after = 1200) { vm.partDrawn() }
        shot(a, "04-printing-model")

        scene("printing", "file=2&progress=0.58")
        settleUntil(6000, after = 1200) { vm.printing(TALL) && vm.partDrawn() }
        shot(a, "05-printing-tall-part")

        // a file the slicer put no preview in: the display fetches its G-code and draws one
        scene("printing", "file=5&progress=0.55")
        settleUntil(8000, after = 1500) { vm.printing(CUBE) && vm.partDrawn() }
        shot(a, "05b-printing-drawn-preview")

        // A job of a file with the slicer's picture, never drawn here, started while the camera
        // is up: the display draws it all the same, so the part grows the moment anyone looks.
        tap(a, 110f, 52f)          // picture: the camera
        settle(800)
        scene("printing", "file=3&progress=0.3")
        settleUntil(8000, after = 300) { vm.printing(STAND) && vm.partDrawn() }
        val stand = vm.jobFile
        assertTrue(
            "the job's drawing should be made while the camera is up",
            stand != null && stand.thumbnail != null && vm.thumbs!!.drawn.hasShape(stand),
        )
        tap(a, 164f, 52f)          // picture: the preview
        // the moment after: the part's ghost while its growth is worked out, and no line across it
        settle(200)
        a.window.decorView.captureRoboImage(File(out, "05d-switching-to-model.png").path)
        settle(2500)               // its growth, worked out only now the part is on screen
        shot(a, "05e-drawn-while-camera-was-up")
        tap(a, 464f, 52f)          // the part: its other side
        settle(2500)
        shot(a, "05f-turned-round")
    }
}

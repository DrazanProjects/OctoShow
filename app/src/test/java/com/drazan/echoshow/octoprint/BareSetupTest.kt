package com.drazan.echoshow.octoprint

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drazan.echoshow.octoprint.data.Prefs
import com.drazan.echoshow.octoprint.octo.Link
import com.drazan.echoshow.octoprint.octo.Phase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The display against an OctoPrint with nothing extra: no camera, no plugins, no
 * temperature presets, no shutdown command, a printer without a heated bed, and no
 * smart plug. Everything optional is missing at once, and every screen still has to
 * come up and make sense.
 *
 * One of the walks through the app's screens, on a copy of the mock of its own: see
 * [ScreenWalk]. The pictures land beside the others, as bare-*.png.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w788dp-h394dp-land-195dpi")
class BareSetupTest : ScreenWalk(5005) {

    private val nothing = "without=camera,dashboard,thumbs,lights,presets,shutdown,bed"

    @Test
    fun everythingOptionalMissing() = walk("printing", "file=5&progress=0.4&$nothing", plug = false, connect = false) { a, vm ->
        vm.useKey(secure, key)
        // connected, what the server has is known, and the part has been drawn from its G-code
        settleUntil(12_000, after = 1500) {
            vm.link == Link.Online && vm.isIn(Phase.PRINTING) && vm.info.version.isNotEmpty() && vm.partDrawn()
        }

        // a print under way, with no layers reported and no slicer picture to show
        assertEquals(Phase.PRINTING, vm.printer?.phase)
        assertNull("no camera is set up", vm.info.webcam)
        assertTrue("no lights are set up", vm.info.lights.isEmpty())
        assertFalse("nothing reports layers", vm.layers.known)
        assertNull("no smart plug", vm.plug)
        assertNull("no heated bed", vm.printer?.bed)
        shot(a, "bare-1-printing")

        tap(a, 38f, 164f)          // controls, mid-print
        settle(1000)
        shot(a, "bare-2-controls-printing")

        scene("ready", nothing)
        settleUntil(5000, after = 500) { vm.isIn(Phase.READY) }
        shot(a, "bare-3-controls")

        tap(a, 38f, 104f)          // files, every one of them without a slicer picture
        settleUntil(7000, after = 1000) { vm.files.loaded && !vm.files.loading }
        shot(a, "bare-4-files", load = 1500)

        tap(a, 38f, 436f)          // settings
        settle(1000)
        shot(a, "bare-5-settings")

        tap(a, 38f, 44f)           // status, with nothing printing
        settle(1000)
        shot(a, "bare-6-ready", load = 1500)

        scene("done", nothing)
        settleUntil(5000, after = 800) { vm.outcome != null }
        shot(a, "bare-7-finished")

        // The usual OctoPi with no camera plugged in: one is set up all the same, and
        // nothing answers at its address. The display has to be told afresh, since what
        // a server has is only asked once.
        scene("printing", "file=5&progress=0.4&without=deadcamera")
        runBlocking { Prefs(ApplicationProvider.getApplicationContext()).forgetServer() }
        settleUntil(3000, after = 300) { vm.server == null }
        vm.useKey(secure, key)
        settleUntil(12_000, after = 500) { vm.link == Link.Online && vm.info.webcam != null }
        assertNotNull("OctoPrint says there is a camera", vm.info.webcam)
        tap(a, 110f, 52f)          // picture: the camera
        // the first try at the stream fails at once, and the view says so
        settle(1500)
        shot(a, "bare-8-camera-not-answering", load = 1500)
    }
}

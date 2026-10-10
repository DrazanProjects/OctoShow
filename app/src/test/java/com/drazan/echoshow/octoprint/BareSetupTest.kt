package com.drazan.echoshow.octoprint

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drazan.echoshow.octoprint.data.Prefs
import com.drazan.echoshow.octoprint.octo.Phase
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration

/**
 * The display against an OctoPrint with nothing extra: no camera, no plugins, no
 * temperature presets, no shutdown command, a printer without a heated bed, and no
 * smart plug. Everything optional is missing at once, and every screen still has to
 * come up and make sense.
 *
 *   node tools/mock-octoprint/server.js      (in another terminal)
 *   gradlew :app:testDebugUnitTest --tests "*BareSetupTest*"
 *
 * The pictures land beside [ScreenshotTest]'s, as bare-*.png.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w788dp-h394dp-land-195dpi")
class BareSetupTest {

    private val mock = "http://127.0.0.1:5000"
    private val key = "MOCKKEY0123456789ABCDEF"
    private val out = File("build/outputs/screens").apply { mkdirs() }
    private val nothing = "without=camera,dashboard,thumbs,lights,presets,shutdown,bed"

    @Test
    fun everythingOptionalMissing() {
        assumeTrue("mock OctoPrint not running at $mock", scene("printing", "file=5&progress=0.4&$nothing"))
        // what is saved outlives a test in this process, and another may have left a server and a plug
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        runBlocking { prefs.forgetServer(); prefs.setPlug(null) }

        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var a: MainActivity
                scenario.onActivity { a = it }
                ShadowChoreographer.setPaused(true)
                ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
                val vm = ViewModelProvider(a)[PrinterViewModel::class.java]

                vm.useKey("127.0.0.1:5443", key)
                settle(9000)

                // a print under way, with no layers reported and no slicer picture to show
                assertEquals(Phase.PRINTING, vm.printer?.phase)
                assertNull("no camera is set up", vm.info.webcam)
                assertTrue("no lights are set up", vm.info.lights.isEmpty())
                assertFalse("nothing reports layers", vm.layers.known)
                assertNull("no smart plug", vm.plug)
                assertNull("no heated bed", vm.printer?.bed)
                shot(a, "bare-1-printing")

                tap(a, 38f, 164f)          // controls, mid-print
                settle(1500)
                shot(a, "bare-2-controls-printing")

                scene("ready", nothing)
                settle(3000)
                shot(a, "bare-3-controls")

                tap(a, 38f, 104f)          // files, every one of them without a slicer picture
                settle(6000)
                shot(a, "bare-4-files")

                tap(a, 38f, 436f)          // settings
                settle(1500)
                shot(a, "bare-5-settings")

                tap(a, 38f, 44f)           // status, with nothing printing
                settle(1500)
                shot(a, "bare-6-ready")

                scene("done", nothing)
                settle(2500)
                shot(a, "bare-7-finished")

                // The usual OctoPi with no camera plugged in: one is set up all the same, and
                // nothing answers at its address. The display has to be told afresh, since what
                // a server has is only asked once.
                scene("printing", "file=5&progress=0.4&without=deadcamera")
                runBlocking { prefs.forgetServer() }
                settle(1500)
                vm.useKey("127.0.0.1:5443", key)
                settle(9000)
                assertNotNull("OctoPrint says there is a camera", vm.info.webcam)
                tap(a, 110f, 52f)          // picture: the camera
                settle(6000)
                shot(a, "bare-8-camera-not-answering")
                tap(a, 164f, 52f)          // and back to the part, which is where other tests expect to start
                settle(800)
            }
        } finally {
            runBlocking { prefs.forgetServer(); prefs.setPlug(null) }
            scene("printing")
        }
    }

    private fun settle(millis: Long) {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            Thread.sleep(32)
        }
    }

    private fun shot(activity: Activity, name: String) {
        val file = File(out, "$name.png").path
        // one capture to set layout going, a moment for what it starts to load, then the real one
        activity.window.decorView.captureRoboImage(file)
        settle(1500)
        activity.window.decorView.captureRoboImage(file)
    }

    private fun tap(activity: Activity, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            activity.dispatchTouchEvent(event)
            event.recycle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(48))
        }
    }

    private fun scene(name: String, more: String = ""): Boolean = try {
        val c = URL("$mock/mock/set?scene=$name&$more").openConnection() as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 3000
        (c.responseCode == 200).also { c.disconnect() }
    } catch (_: Exception) {
        false
    }
}

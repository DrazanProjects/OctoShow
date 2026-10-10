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
import com.drazan.echoshow.octoprint.power.PowerStep
import androidx.compose.ui.graphics.toArgb
import com.drazan.echoshow.octoprint.ui.theme.Accents
import com.drazan.echoshow.octoprint.ui.theme.Grounds
import com.drazan.echoshow.octoprint.ui.theme.Inks
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration

/**
 * Renders every screen of the app at the Echo Show 5's size, without a device.
 *
 * This runs the real activity, view model and network code on the JVM against
 * the mock OctoPrint in tools/mock-octoprint, steps the mock through a print,
 * and writes PNGs to app/build/outputs/screens.
 *
 *   node tools/mock-octoprint/server.js      (in another terminal)
 *   gradlew :app:testDebugUnitTest --tests "*ScreenshotTest*"
 *
 * Skipped when the mock isn't running, so a plain `gradlew test` stays green.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The Echo Show 5 as it really reports itself: 960 x 480 pixels at 195 dpi, i.e. 788 x 394 dp.
@Config(sdk = [34], qualifiers = "w788dp-h394dp-land-195dpi")
class ScreenshotTest {

    private val mock = "http://127.0.0.1:5000"
    private val out = File("build/outputs/screens").apply { mkdirs() }

    @Test
    fun everyScreen() {
        assumeTrue("mock OctoPrint not running at $mock", scene("printing"))

        // A smart plug is already set up and is off as the display starts. Starting the
        // display is what brings the printer up, so it should be switched on unasked.
        runBlocking { Prefs(ApplicationProvider.getApplicationContext()).setPlug("http://127.0.0.1:5000/") }
        get("$mock/cm?cmnd=Power%20Off")
        assertFalse(plugIsOn())

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var a: MainActivity
            scenario.onActivity { a = it }
            assertEquals("the test should see the screen the way the device reports it", 195, a.resources.displayMetrics.densityDpi)
            // Give Robolectric a display refresh to pace frames by. Without it an endless
            // animation (the bead on the layer stack) runs frames back to back and never yields.
            ShadowChoreographer.setPaused(true)
            ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
            run {
                val vm = ViewModelProvider(a)[PrinterViewModel::class.java]

                // first run
                settle(1500)
                shot(a, "01-setup")
                assertTrue("a plug found off as the display starts should be switched on", plugIsOn())
                // No scheme given and the port only speaks https with a self-signed certificate:
                // the same situation as a real OctoPi that has plain http switched off.
                vm.requestAccess("127.0.0.1:5443")
                settle(2500)
                shot(a, "02-setup-waiting")
                settle(7000)   // the mock approves after six seconds

                // a print in progress; the picture starts as the file's preview
                settle(6000)
                tap(a, 110f, 52f)          // picture: the camera instead
                settle(3000)
                shot(a, "03-printing-camera")
                hold(a, 866f, 432f, 800)   // halfway through holding Cancel
                // One picture, not shot()'s two with a wait between: the wait would see the hold
                // through to the end, and the print this walk goes on to photograph would be cancelled.
                a.window.decorView.captureRoboImage(File(out, "03b-holding-cancel.png").path)
                release(a, 866f, 432f)
                settle(600)
                tap(a, 164f, 52f)          // picture: back to the preview
                settle(2500)
                shot(a, "04-printing-model")

                scene("printing", "file=2&progress=0.58")
                settle(3500)
                shot(a, "05-printing-tall-part")

                // a file the slicer put no preview in: the display fetches its G-code and draws one
                scene("printing", "file=5&progress=0.55")
                settle(6000)
                shot(a, "05b-printing-drawn-preview")

                // a job long enough for the time left to need its widest form
                scene("printing", "progress=0.02&total=86000")
                settle(3500)
                shot(a, "05c-printing-long-job")

                scene("heating")
                settle(3500)
                shot(a, "06-heating-up")

                scene("paused", "progress=0.41")
                settle(3000)
                shot(a, "07-paused")

                scene("prompt", "progress=0.41")
                settle(3000)
                shot(a, "08-printer-asks")
                tap(a, 350f, 300f)         // answer it
                settle(1500)

                scene("done")
                settle(3000)
                shot(a, "09-finished")
                tap(a, 712f, 432f)         // Bed is clear
                settle(800)

                scene("ready")
                settle(5000)               // includes fetching the file list
                shot(a, "10-ready")

                scene("offline")
                settle(3000)
                shot(a, "11-printer-offline")

                scene("error")
                settle(3000)
                shot(a, "12-printer-error")

                // files
                scene("ready")
                settle(2500)
                tap(a, 38f, 104f)
                settle(5000)
                shot(a, "13-files")
                tap(a, 700f, 430f)         // Print
                settle(1500)
                shot(a, "14-start-print")
                tap(a, 760f, 310f)         // Not now
                settle(800)

                // controls, idle and mid-print
                tap(a, 38f, 164f)
                settle(2000)
                shot(a, "15-controls")
                // three quick taps on nozzle-up should reach OctoPrint as one target, then a jog right
                repeat(3) { tap(a, 342f, 83f) }
                settle(1500)
                tap(a, 552f, 144f)
                settle(800)
                shot(a, "15b-controls-heating")
                scene("printing", "progress=0.3")
                settle(3000)
                shot(a, "16-controls-while-printing")

                // settings
                tap(a, 38f, 436f)
                settle(1500)
                shot(a, "17-settings")
                tap(a, 750f, 269f)         // power plug
                settle(800)
                shot(a, "17b-power-plug")
                tap(a, 880f, 40f)          // away from the sheet closes it
                settle(600)

                // camera on the whole screen
                tap(a, 38f, 44f)
                settle(1500)
                tap(a, 110f, 52f)          // picture: back to camera
                settle(3000)
                tap(a, 300f, 180f)         // open it full screen
                settle(3000)
                shot(a, "18-camera-full")

                // left alone, the screen goes off
                ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
                settle(7000)
                shot(a, "19-screen-off")
                assertTrue("the backlight should be turned down", a.window.attributes.screenBrightness in 0f..0.1f)
                // the touch that brings it back lands where Pause is, and must not press it
                tap(a, 712f, 432f)
                settle(3000)
                shot(a, "20-touched-awake")
                assertTrue("the backlight should be handed back", a.window.attributes.screenBrightness < 0f)

                // other colors, on the screens that show the most of them
                tap(a, 164f, 52f)          // picture: the preview
                settle(1500)
                val looks = listOf(
                    Triple("Black", "Bone", "Teal"), Triple("Navy", "White", "Amber"),
                    Triple("Charcoal", "Lemon", "Pink"), Triple("Forest", "Bone", "Lime"),
                )
                for ((ground, ink, accent) in looks) {
                    vm.setGround(Grounds.first { it.label == ground }.color.toArgb())
                    vm.setInk(Inks.first { it.label == ink }.color.toArgb())
                    vm.setAccent(Accents.first { it.label == accent }.color.toArgb())
                    val name = "colors-${ground.lowercase()}-${ink.lowercase()}-${accent.lowercase()}"
                    settle(1200)
                    shot(a, "$name-1-status")
                    tap(a, 38f, 104f)
                    settle(2500)
                    shot(a, "$name-2-files")
                    tap(a, 38f, 164f)
                    settle(1500)
                    shot(a, "$name-3-controls")
                    tap(a, 38f, 436f)
                    settle(1200)
                    tap(a, 180f, 112f)     // colors
                    settle(800)
                    shot(a, "$name-4-choosing")
                    tap(a, 880f, 40f)      // away from the sheet closes it
                    settle(600)
                    tap(a, 38f, 44f)
                    settle(1200)
                }
                vm.setGround(Grounds[0].color.toArgb())
                vm.setInk(Inks[0].color.toArgb())
                vm.setAccent(Accents[0].color.toArgb())
                settle(1200)

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
                settle(4000)
                shot(a, "20c-done-so-switching-off")
                // it says so first and waits; the routine itself is the one photographed further down
                assertTrue("a finished job should lead to switching off unasked", vm.power is PowerStep.AfterPrint)
                assertFalse(vm.offWhenDone)
                tap(a, 480f, 296f)         // Leave it on
                settle(2500)
                assertNull("saying no during the pause should be the end of it", vm.power)

                // a job that ends any other way leaves everything on
                scene("printing", "progress=0.3")
                settle(3000)
                tap(a, 926f, 34f)
                settle(600)
                tap(a, 350f, 308f)
                settle(600)
                assertTrue(vm.offWhenDone)
                scene("ready")             // gone without finishing
                settle(10_000)             // longer than it waits to hear how the job ended
                assertFalse(vm.offWhenDone)
                assertNull("only a job that finishes should switch things off", vm.power)

                // the plug switched off behind the display's back, long after it started: left alone
                get("$mock/cm?cmnd=Power%20Off")
                scene("dead")
                settle(9000)               // the plug is asked every five seconds
                shot(a, "21-switched-off")
                assertFalse("only starting the display switches the plug on by itself", plugIsOn())
                tap(a, 140f, 289f)         // Switch on
                settle(1500)
                shot(a, "22-starting-up")

                // and everything switched off from here, straight after a print
                scene("done", "tool=180")  // OctoPrint is back
                settle(9000)
                scene("done", "tool=180")
                settle(2500)
                shot(a, "23-finished-with-plug")
                tap(a, 926f, 34f)          // power
                settle(800)
                shot(a, "24-switch-off")
                hold(a, 350f, 306f, 1600)  // hold to confirm
                release(a, 350f, 306f)
                settle(1200)
                shot(a, "25-cooling")
                scene("done", "tool=40")   // cool enough now
                settle(5000)
                // the mock can't drop off the network the way a Pi does, so this is as far as it gets
                shot(a, "26-shutting-down")
            }
        }
    }

    /** Let real time pass for the network threads while keeping the UI's own clock moving. */
    private fun settle(millis: Long) {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            Thread.sleep(32)
        }
    }

    private fun shot(activity: Activity, name: String) {
        val file = File(out, "$name.png").path
        // Off a device nothing lays the window out until something asks for a picture, and
        // content that is composed during layout (list rows, the camera) only starts loading
        // then. So: one capture to set things going, a moment for them to load, then the real one.
        activity.window.decorView.captureRoboImage(file)
        settle(1500)
        activity.window.decorView.captureRoboImage(file)
    }

    private var downAt = 0L

    private fun touch(activity: Activity, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downAt = now
        val e = MotionEvent.obtain(downAt, now, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        activity.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun tap(activity: Activity, x: Float, y: Float) {
        touch(activity, MotionEvent.ACTION_DOWN, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(48))
        release(activity, x, y)
    }

    /** Press and keep pressing for [millis] of the UI's own clock. */
    private fun hold(activity: Activity, x: Float, y: Float, millis: Long) {
        touch(activity, MotionEvent.ACTION_DOWN, x, y)
        // one display frame at a time, or the animation only ever sees a single frame
        repeat((millis / 16).toInt()) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
    }

    private fun release(activity: Activity, x: Float, y: Float) {
        touch(activity, MotionEvent.ACTION_UP, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(48))
    }

    /** Put the mock printer into a named state. False if the mock can't be reached. */
    private fun scene(name: String, more: String = ""): Boolean = get("$mock/mock/set?scene=$name&$more")

    private fun plugIsOn(): Boolean = URL("$mock/cm?cmnd=Power").readText().contains("\"ON\"")

    private fun get(url: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 3000
        (c.responseCode == 200).also { c.disconnect() }
    } catch (_: Exception) {
        false
    }
}

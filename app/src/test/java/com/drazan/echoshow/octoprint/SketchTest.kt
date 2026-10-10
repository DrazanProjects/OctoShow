package com.drazan.echoshow.octoprint

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
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
 * One screen, quickly: for looking at a layout idea before it is worth the
 * four minutes [ScreenshotTest] takes to walk through everything. Same real
 * activity, same Echo Show screen, same mock; it just goes straight to the
 * screen in question and photographs each version of it.
 *
 *   node tools/mock-octoprint/server.js      (in another terminal)
 *   gradlew :app:testDebugUnitTest --tests "*SketchTest*" -Psketch=1
 *
 * Pictures land in app/build/outputs/sketches. Skipped unless asked for, since
 * it shares saved settings with the other tests when run alongside them.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w788dp-h394dp-land-195dpi")
class SketchTest {

    private val mock = "http://127.0.0.1:5000"
    private val key = "MOCKKEY0123456789ABCDEF"
    private val out = File("build/outputs/sketches").apply { mkdirs() }

    @Test
    fun thePartGrowingAsItPrints() {
        assumeTrue("ask for it with -Psketch=1", System.getProperty("sketch").orEmpty().isNotEmpty())
        assumeTrue("mock OctoPrint not running at $mock", scene(0.12f))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var a: MainActivity
            scenario.onActivity { a = it }
            ShadowChoreographer.setPaused(true)
            ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
            val vm = ViewModelProvider(a)[PrinterViewModel::class.java]

            // straight in with a key, skipping the wait for someone to approve the display
            vm.useKey("127.0.0.1:5443", key)
            vm.usePlug("127.0.0.1:5000")
            settle(7000)

            // a file with no slicer picture, so the display draws the part itself
            scene(0.40f)
            settle(6000)
            // A layer and a bit being printed, one picture every 130 ms: put end to end, the
            // frames of a moving picture. Real time has to pass as well as the display's own,
            // because how far through the layer the mock is comes in over the network.
            for (frame in 0 until 72) {
                val began = System.currentTimeMillis()
                repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
                a.window.decorView.captureRoboImage(File(out, "fill-%02d.png".format(frame)).path)
                val spare = 130 - (System.currentTimeMillis() - began)
                if (spare > 0) Thread.sleep(spare)
            }
        }
    }

    // sped up a little, so that a layer of this part takes about seven seconds
    private fun scene(progress: Float): Boolean = get("$mock/mock/set?scene=printing&file=5&progress=$progress&speed=2")

    private fun settle(millis: Long) {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            Thread.sleep(32)
        }
    }

    private fun get(url: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 3000
        (c.responseCode == 200).also { c.disconnect() }
    } catch (_: Exception) {
        false
    }
}

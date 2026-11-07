package com.drazan.echoshow.octoprint

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.drazan.echoshow.octoprint.data.DisplayPrefs
import com.drazan.echoshow.octoprint.data.Prefs
import com.drazan.echoshow.octoprint.octo.Link
import com.drazan.echoshow.octoprint.octo.Phase
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration

/**
 * What the walks through the app's screens share. Each walks the real activity, view model and
 * network code on the JVM against a copy of the mock OctoPrint of its own, on [port], so that
 * the walks can run side by side. `node tools/mock-octoprint/server.js` starts a copy for each.
 * The pictures go to app/build/outputs/screens.
 *
 *   gradlew :app:testDebugUnitTest --tests "*Screenshot*"
 *
 * A walk is skipped when its copy of the mock isn't running, so a plain `gradlew test` stays green.
 */
abstract class ScreenWalk(private val port: Int) {

    protected val mock = "http://127.0.0.1:$port"
    /** Where a display finds this copy of the mock, told no scheme: it only speaks https there. */
    protected val secure = "127.0.0.1:${port + 443}"
    protected val key = "MOCKKEY0123456789ABCDEF"
    protected val out = File("build/outputs/screens").apply { mkdirs() }

    /**
     * Puts the printer in [state] with [more], starts the app with nothing saved from before but
     * a smart plug on this copy of the mock when [plug], switched on, connects it straight away
     * unless [connect] is false, and walks it through [steps]. What is saved outlives a test in
     * this process, so it is cleared on the way out too. [beforeLaunch] runs just before the app
     * starts.
     */
    protected fun walk(
        state: String,
        more: String = "",
        plug: Boolean = true,
        connect: Boolean = true,
        beforeLaunch: () -> Unit = {},
        steps: (MainActivity, PrinterViewModel) -> Unit,
    ) {
        assumeTrue("mock OctoPrint not running at $mock", scene(state, more))
        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        runBlocking { startAfresh(prefs, plug) }
        if (plug) get("$mock/cm?cmnd=Power%20On")
        beforeLaunch()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var a: MainActivity
                scenario.onActivity { a = it }
                assertEquals("the test should see the screen the way the device reports it", 195, a.resources.displayMetrics.densityDpi)
                // Give Robolectric a display refresh to pace frames by. Without it an endless
                // animation (the bead on the layer stack) runs frames back to back and never yields.
                ShadowChoreographer.setPaused(true)
                ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
                val vm = ViewModelProvider(a)[PrinterViewModel::class.java]
                if (connect) {
                    vm.useKey(secure, key)
                    settleUntil(12_000, after = 500) { vm.link == Link.Online && vm.printer != null && vm.info.version.isNotEmpty() }
                }
                steps(a, vm)
            }
        } finally {
            runBlocking { startAfresh(prefs, plug = false) }
        }
    }

    private suspend fun startAfresh(prefs: Prefs, plug: Boolean) {
        prefs.forgetServer()
        prefs.setPlug(if (plug) "$mock/" else null)
        val d = DisplayPrefs()
        prefs.setGround(d.ground)
        prefs.setInk(d.ink)
        prefs.setAccent(d.accent)
        prefs.setOffAfter(d.offAfterMin)
        prefs.setView(d.view)
        prefs.setJogSpeed(d.jogSpeed)
        prefs.setExtrudeSpeed(d.extrudeSpeed)
    }

    /** The printer is in [phase], as far as the display knows. */
    protected fun PrinterViewModel.isIn(phase: Phase) = printer?.phase == phase

    /** The job is the file called [name], and the display knows it. */
    protected fun PrinterViewModel.printing(name: String) = jobFile?.name == name && printer?.file?.name == name

    /** The part the job is drawn from has been drawn. */
    protected fun PrinterViewModel.partDrawn() = jobDrawing?.let { thumbs!!.drawn.hasShape(it) } == true

    /** Let real time pass for the network threads while keeping the UI's own clock moving. */
    protected fun settle(millis: Long) {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) tick()
    }

    /**
     * Let real time pass, as [settle] does, until [ready], then [after] more for the screen to
     * catch up. Most of the waiting is for the mock and the network threads, which a fast
     * machine has long since heard from by the time a fixed wait would end. Never longer than
     * [most]: a step that isn't ready by then carries on, and what comes after says whether that
     * mattered.
     */
    protected fun settleUntil(most: Long, after: Long = 0, ready: () -> Boolean) {
        val until = System.currentTimeMillis() + most
        while (!ready()) {
            if (System.currentTimeMillis() >= until) {
                println("${javaClass.simpleName}: waited the whole $most ms at line ${Throwable().stackTrace[1].lineNumber}")
                return
            }
            tick()
        }
        settle(after)
    }

    private fun tick() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        Thread.sleep(32)
    }

    protected fun shot(activity: Activity, name: String, load: Long = 500) {
        val file = File(out, "$name.png").path
        // Off a device nothing lays the window out until something asks for a picture, and
        // content that is composed during layout (list rows, the camera) only starts loading
        // then. So: one capture to set things going, [load] for them to load, then the real one.
        activity.window.decorView.captureRoboImage(file)
        settle(load)
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

    protected fun tap(activity: Activity, x: Float, y: Float) {
        touch(activity, MotionEvent.ACTION_DOWN, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(48))
        release(activity, x, y)
    }

    /** Press and keep pressing for [millis] of the UI's own clock. */
    protected fun hold(activity: Activity, x: Float, y: Float, millis: Long) {
        touch(activity, MotionEvent.ACTION_DOWN, x, y)
        // one display frame at a time, or the animation only ever sees a single frame
        repeat((millis / 16).toInt()) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }
    }

    protected fun release(activity: Activity, x: Float, y: Float) {
        touch(activity, MotionEvent.ACTION_UP, x, y)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(48))
    }

    /** Put the mock printer into a named state. False if the mock can't be reached. */
    protected fun scene(name: String, more: String = ""): Boolean = get("$mock/mock/set?scene=$name&$more")

    protected fun plugIsOn(): Boolean = URL("$mock/cm?cmnd=Power").readText().contains("\"ON\"")

    /** What the display has told the printer to do since the last time this was asked. */
    protected fun sent(): List<JSONObject> =
        JSONArray(URL("$mock/mock/sent").readText()).let { list -> List(list.length()) { list.getJSONObject(it) } }

    protected fun get(url: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 3000
        (c.responseCode == 200).also { c.disconnect() }
    } catch (_: Exception) {
        false
    }

    protected companion object {
        // the mock's files that the walks print
        const val CHESSBOARD = "CHESSBOARD_top_front_left_PLA_8h8m.gcode"
        const val TALL = "Spiral_vase_tall_PLA_3h40m.gcode"
        const val STAND = "Phone stand (angled)_PLA_2h14m.gcode"
        const val CUBE = "calibration_cube_PLA_22m.gcode"
    }
}

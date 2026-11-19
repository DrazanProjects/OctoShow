package com.drazan.echoshow.octoprint

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.os.UserManager
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.drazan.echoshow.octoprint.octo.Link
import com.drazan.echoshow.octoprint.power.PowerStep
import com.drazan.echoshow.octoprint.ui.App
import com.drazan.echoshow.octoprint.ui.theme.paletteOf
import com.drazan.echoshow.octoprint.ui.theme.usePalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private val vm: PrinterViewModel by viewModels()

    /** Gone dark after a spell without touches: nothing drawn, the backlight as low as it goes. */
    private var screenOff by mutableStateOf(false)
    private var lastTouch = SystemClock.elapsedRealtime()
    /** The touch that wakes the screen shouldn't also press whatever is under it. */
    private var swallowGesture = false

    /** Whether the app proper is up: at boot it waits for its storage to be unlocked. */
    private var started = false
    private var unlockWatch: BroadcastReceiver? = null

    /**
     * Started at boot, the app is up before the Echo Show has joined Wi-Fi. Rather than open
     * on "Can't reach OctoPrint", it keeps the boot picture until OctoPrint first answers, or
     * [BOOT_HOLD_MS] at most.
     */
    private var holdingBootPicture by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // this is a wall display: never sleep
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // hide status + nav bars; swipe-from-edge briefly shows them
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()

        // As the home screen this is started while the Echo Show is still booting, before
        // the app's own storage (saved server, plug, display settings) is unlocked. Until
        // then the window background shows the picture the boot animation ended on, and
        // nothing here may touch storage.
        if (storageUnlocked()) start() else waitForUnlock()
    }

    private fun storageUnlocked() = getSystemService(UserManager::class.java).isUserUnlocked

    private fun waitForUnlock() {
        holdingBootPicture = true
        val watch = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = startOnce()
        }
        unlockWatch = watch
        ContextCompat.registerReceiver(
            this, watch, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // it may have opened between the check and the registration
        if (storageUnlocked()) startOnce()
    }

    private fun startOnce() {
        stopWatching()
        if (!started) start()
    }

    private fun stopWatching() {
        unlockWatch?.let { unregisterReceiver(it) }
        unlockWatch = null
    }

    override fun onDestroy() {
        stopWatching()
        super.onDestroy()
    }

    private fun start() {
        started = true
        if (!holdingBootPicture) dropBootPicture()

        provisionFrom(intent)

        lifecycleScope.launch { vm.display.collect { usePalette(paletteOf(Color(it.ground), Color(it.ink), Color(it.accent))) } }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val after = vm.display.value.offAfterMin
                    val idle = SystemClock.elapsedRealtime() - lastTouch
                    switchScreen(off = after > 0 && idle >= after * 60_000L)
                    delay(5_000)
                }
            }
        }

        setContent {
            if (holdingBootPicture) {
                // nothing composed: the window background, the boot picture, shows through
                LaunchedEffect(Unit) {
                    withTimeoutOrNull(BOOT_HOLD_MS) { snapshotFlow { heardBack() }.first { it } }
                    dropBootPicture()
                    holdingBootPicture = false
                }
            } else {
                // a finished print or a question from the printer is worth lighting up for,
                // and so is a switch-off that left everything on
                val stillOn = vm.power is PowerStep.Failed
                LaunchedEffect(vm.outcome, vm.prompt, stillOn) {
                    if (vm.outcome != null || vm.prompt != null || stillOn) wake()
                }
                App(vm, screenOff)
            }
        }
    }

    /** Whether the app has something better to show than the boot picture. */
    private fun heardBack(): Boolean {
        if (!vm.ready) return false
        if (vm.server == null) return true          // nothing set up yet: the setup screen
        return vm.link != Link.Connecting && vm.link != Link.Unreachable
    }

    /** The boot picture has done its job; anything the app leaves undrawn is black, as before. */
    private fun dropBootPicture() {
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (started) provisionFrom(intent)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouch = SystemClock.elapsedRealtime()
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            swallowGesture = screenOff
            if (screenOff) switchScreen(off = false)
        }
        if (swallowGesture) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swallowGesture = false
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // re-hide the bars if focus returns (e.g. after a system dialog)
        if (hasFocus) hideBars()
    }

    private fun wake() {
        lastTouch = SystemClock.elapsedRealtime()
        switchScreen(off = false)
    }

    private fun switchScreen(off: Boolean) {
        if (screenOff == off) return
        screenOff = off
        window.attributes = window.attributes.apply {
            screenBrightness = if (off) lowestBrightness else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    /**
     * As low as the backlight goes. An app can't power the panel down and still be
     * told about the touch that should bring it back, so "off" is a black screen at
     * this level. Android 11 doesn't clamp a window's brightness to the range the
     * system allows: ask for less than its minimum and the request is dropped,
     * leaving the backlight where it was.
     */
    private val lowestBrightness: Float by lazy {
        val res = Resources.getSystem()
        val id = res.getIdentifier("config_screenBrightnessSettingMinimum", "integer", "android")
        val floor = if (id != 0) res.getInteger(id) / 255f else 0f
        maxOf(LOWEST_BRIGHTNESS, floor)
    }

    private fun hideBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Debug builds can be pointed at a server over adb instead of through the
     * setup screen:
     *
     *   adb shell am start -n com.drazan.echoshow.octoprint/.MainActivity \
     *       --es url http://192.168.1.50 --es key <access key>
     *
     * and, on its own or alongside, at the smart plug the printer is powered through:
     *
     *       --es plug 192.168.1.60
     */
    private fun provisionFrom(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        intent?.getStringExtra("plug")?.let { vm.usePlug(it) }
        val url = intent?.getStringExtra("url") ?: return
        val key = intent.getStringExtra("key") ?: return
        vm.useKey(url, key)
    }

    private companion object {
        const val LOWEST_BRIGHTNESS = 0.01f
        /** Long enough for Wi-Fi and OctoPrint after a boot; past it, the app says what's wrong. */
        const val BOOT_HOLD_MS = 20_000L
    }
}

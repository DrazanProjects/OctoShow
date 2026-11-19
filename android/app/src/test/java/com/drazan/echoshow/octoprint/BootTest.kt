package com.drazan.echoshow.octoprint

import android.app.Application
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Looper
import android.os.UserManager
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drazan.echoshow.octoprint.data.Prefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/**
 * The Echo Show starts the app as its home screen while it is still booting, before the
 * app's storage is unlocked. Until then the app shows only the boot picture and leaves the
 * view model (and everything that reads storage) alone; once Android says storage is open,
 * the app starts as usual.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w788dp-h394dp-land-195dpi")
class BootTest {

    @Test
    fun waitsForStorageThenStarts() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // saved settings outlive a test in this process; start with no server set up
        runBlocking { Prefs(app).forgetServer() }
        val users = shadowOf(app.getSystemService(UserManager::class.java))
        users.setUserUnlocked(false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ShadowChoreographer.setPaused(true)
                ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
                scenario.onActivity { a ->
                    assertEquals("nothing drawn but the boot picture", 0, content(a).childCount)
                    assertTrue(a.window.decorView.background is LayerDrawable)
                }

                users.setUserUnlocked(true)
                app.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
                shadowOf(Looper.getMainLooper()).idle()

                lateinit var a: MainActivity
                scenario.onActivity { a = it }
                assertEquals("the app is up", 1, content(a).childCount)
                // with nothing set up there is no OctoPrint to wait for: the boot picture goes
                // as soon as the saved settings are read, well before the 20 s limit
                val until = System.currentTimeMillis() + 10_000
                while (a.window.decorView.background !is ColorDrawable && System.currentTimeMillis() < until) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
                    Thread.sleep(32)
                }
                assertTrue("boot picture dropped", a.window.decorView.background is ColorDrawable)
            }
        } finally {
            users.setUserUnlocked(true)
        }
    }

    private fun content(a: MainActivity) = a.findViewById<ViewGroup>(android.R.id.content)
}

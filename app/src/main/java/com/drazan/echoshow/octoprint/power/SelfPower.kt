package com.drazan.echoshow.octoprint.power

import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Lets the display switch itself off. Android keeps that from ordinary apps,
 * but it will do it for a debugging session, and where debugging over the
 * network is switched on the display can open one to itself.
 *
 * The first time, Android asks on screen whether to allow it. The answer is
 * remembered against the key kept in [keys].
 */
class SelfPower(private val keys: File, private val port: Int = 5555) {

    /** Whether the display would do as it is told. */
    suspend fun available(): Boolean = try {
        session { it.shell("true").exitCode == 0 }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** Throws [IOException] when the display can't be told. Otherwise it is on its way down. */
    suspend fun shutDown() {
        try {
            session { adb ->
                // Asked something harmless first, so that a refusal is told apart from the
                // connection dying under the real request, which is what success looks like.
                if (adb.shell("true").exitCode != 0) throw IOException("The display's shell isn't answering")
                // Not "svc power shutdown", for all that it is the tidier request: on the Echo
                // Show it leaves the display in a state its button can't start it from, and
                // it takes pulling the plug to get it back. After this one the button works.
                runCatching { adb.shell("reboot -p") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e)
        }
    }

    private suspend fun <T> session(block: (Dadb) -> T): T = withContext(Dispatchers.IO) {
        // long enough to answer the question on screen the first time, short enough not to hang
        Dadb.create("127.0.0.1", port, keyPair(), connectTimeout = 2_000, socketTimeout = 12_000).use(block)
    }

    private fun keyPair(): AdbKeyPair {
        val private = File(keys, "adbkey")
        val public = File(keys, "adbkey.pub")
        if (!private.exists() || !public.exists()) {
            keys.mkdirs()
            AdbKeyPair.generate(private, public)
        }
        return AdbKeyPair.read(private, public)
    }
}

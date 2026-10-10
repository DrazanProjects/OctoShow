package com.drazan.echoshow.octoprint

import com.drazan.echoshow.octoprint.octo.LocalDns
import com.drazan.echoshow.octoprint.octo.OctoApi
import com.drazan.echoshow.octoprint.octo.ServerTrust
import com.drazan.echoshow.octoprint.octo.assemble
import com.drazan.echoshow.octoprint.octo.mdnsAsk
import com.drazan.echoshow.octoprint.octo.nextJpeg
import com.drazan.echoshow.octoprint.octo.normalizeBase
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okio.Buffer
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * How the display gets on speaking terms with a server: https with a
 * self-signed certificate, and the camera's stream format.
 *
 * The certificate tests need the mock from tools/mock-octoprint running and
 * are skipped without it.
 */
class ConnectionTest {

    private val secure = normalizeBase("https://127.0.0.1:5443")!!

    private fun mockIsUp(): Boolean = try {
        (URL("http://127.0.0.1:5000/plugin/appkeys/probe").openConnection() as HttpURLConnection)
            .apply { connectTimeout = 1000 }.responseCode == 204
    } catch (_: Exception) {
        false
    }

    private fun reach(trust: ServerTrust) = runBlocking { OctoApi(secure, null, trust = trust).keyOffice() }

    @Test
    fun aSelfSignedServerIsLearnedDuringSetupAndRecognisedAfter() {
        assumeTrue(mockIsUp())
        val first = ServerTrust(pinned = null, learning = true)
        assertEquals(secure, reach(first))
        val print = first.learned
        assertNotNull("the certificate seen at setup should be remembered", print)

        val later = ServerTrust(pinned = print, learning = false)
        assertEquals(secure, reach(later))
        assertFalse(later.changed)
        assertNull("nothing new to learn once pinned", later.learned)
    }

    @Test
    fun aDifferentCertificateIsRefusedAndFlagged() {
        assumeTrue(mockIsUp())
        val wrong = ServerTrust(pinned = "00".repeat(32), learning = false)
        assertTrue(runCatching { reach(wrong) }.exceptionOrNull() is IOException)
        assertTrue(wrong.changed)
    }

    @Test
    fun aSelfSignedServerIsNotTrustedOutsideSetup() {
        assumeTrue(mockIsUp())
        val strict = ServerTrust(pinned = null, learning = false)
        assertTrue(runCatching { reach(strict) }.exceptionOrNull() is IOException)
        assertFalse(strict.changed)
    }

    @Test
    fun plainHttpToAnHttpsPortFailsCleanly() {
        assumeTrue(mockIsUp())
        val plain = OctoApi(normalizeBase("http://127.0.0.1:5443")!!, null)
        assertTrue(runCatching { runBlocking { plain.keyOffice() } }.exceptionOrNull() is IOException)
    }

    @Test
    fun theKeyGoesToOctoPrintAndNowhereElse() {
        assumeTrue(mockIsUp())
        // The mock's two ports are one machine. Set up on the https one, the plain one stands
        // for anything else that lives beside OctoPrint, such as a camera on a port of its own.
        val api = OctoApi(secure, "MOCKKEY0123456789ABCDEF", trust = ServerTrust(pinned = null, learning = true))
        fun answer(url: String) = api.client.newCall(Request.Builder().url(url).build()).execute().use { it.code }
        assertEquals("OctoPrint's own address is sent the key", 200, answer("https://127.0.0.1:5443/api/version"))
        assertEquals("another port on the same machine is not", 403, answer("http://127.0.0.1:5000/api/version"))
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun findsFramesByTheirMarkersWhenTheCameraGivesNoLength() {
        val stream = Buffer()
        repeat(2) { stream.writeUtf8("--frame\r\nContent-Type: image/jpeg\r\n\r\n").write(jpeg).writeUtf8("\r\n") }
        assertTrue(jpeg.contentEquals(stream.nextJpeg()))
        assertTrue(jpeg.contentEquals(stream.nextJpeg()))
        assertNull("and then the stream is over", stream.nextJpeg())
    }

    @Test
    fun aFrameThatNeverEndsIsGivenUpOn() {
        // the start of a picture and then ten megabytes with no end to it
        val endless = Buffer().writeUtf8("--frame\r\nContent-Type: image/jpeg\r\n\r\n").write(jpeg, 0, 2)
        val filler = ByteArray(1 shl 20)
        repeat(10) { endless.write(filler) }
        assertTrue(runCatching { endless.nextJpeg() }.exceptionOrNull() is IOException)
    }

    @Test
    fun aHeaderLineThatNeverEndsIsGivenUpOn() {
        val endless = Buffer().writeUtf8("x".repeat(200_000))
        assertTrue(runCatching { endless.nextJpeg() }.exceptionOrNull() is IOException)
    }

    /**
     * Splits a recording of a real camera stream into frames.
     *   set MJPEG_SAMPLE=path\to\stream.bin   (a few seconds saved from /webcam/?action=stream)
     */
    @Test
    fun splitsARecordedCameraStreamIntoWholeJpegs() {
        val sample = System.getenv("MJPEG_SAMPLE")?.let(::File)
        assumeTrue(sample != null && sample.isFile)
        val source = sample!!.source().buffer()
        var frames = 0
        // the recording stops mid-frame, so stop asking once less than a frame is left
        while (!source.exhausted()) {
            val jpeg = try {
                source.nextJpeg() ?: break
            } catch (_: IOException) {
                break
            }
            assertEquals(0xFF.toByte(), jpeg[0])
            assertEquals(0xD8.toByte(), jpeg[1])
            assertEquals(0xFF.toByte(), jpeg[jpeg.size - 2])
            assertEquals(0xD9.toByte(), jpeg[jpeg.size - 1])
            frames++
        }
        println("frames in sample: $frames")
        assertTrue(frames > 5)
    }

    /**
     * The same steps against whatever OctoPrint is really on this network.
     * Read-only: it discovers, connects and asks two questions that need no key.
     *   set LIVE=1
     */
    @Test
    fun liveServer() {
        assumeTrue(System.getenv("LIVE") == "1")
        val found = assemble(mdnsAsk("_octoprint._tcp.local", 12, listenMillis = 3000)).firstOrNull()
        println("found: $found")
        assumeTrue(found != null)

        val dns = LocalDns()
        val announced = normalizeBase(found!!.url)!!
        val https = announced.newBuilder().scheme("https").port(443).build()
        for (base in listOf(announced, https)) {
            val trust = ServerTrust(pinned = null, learning = true)
            val api = OctoApi(base, null, dns, trust)
            val office = runCatching { runBlocking { api.keyOffice() } }
            println("$base -> ${office.getOrNull() ?: office.exceptionOrNull()}  certificate ${trust.learned}")
            if (office.getOrNull() != null) {
                println("  key-less request accepted? ${runBlocking { api.keyAccepted() }} (expected false)")
                val pinned = OctoApi(base, null, dns, ServerTrust(trust.learned, learning = false))
                println("  again with the certificate pinned: ${runBlocking { pinned.keyOffice() }}")
            }
        }
    }
}

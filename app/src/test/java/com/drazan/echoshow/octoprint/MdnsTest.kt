package com.drazan.echoshow.octoprint

import com.drazan.echoshow.octoprint.octo.MdnsCodec
import com.drazan.echoshow.octoprint.octo.MdnsRecord
import com.drazan.echoshow.octoprint.octo.assemble
import com.drazan.echoshow.octoprint.octo.mdnsAsk
import com.drazan.echoshow.octoprint.octo.resolveLocal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress

class MdnsTest {

    /** Builds DNS packets the way a responder does, including name compression. */
    private class Packet {
        val out = ByteArrayOutputStream()
        private val offsets = HashMap<String, Int>()

        fun u16(v: Int) = apply { out.write(v ushr 8 and 0xFF); out.write(v and 0xFF) }
        fun u32(v: Int) = apply { u16(v ushr 16); u16(v and 0xFFFF) }

        /** Writes a name, pointing back at any suffix already written. */
        fun name(name: String) = apply {
            var rest = name
            while (rest.isNotEmpty()) {
                val known = offsets[rest]
                if (known != null) {
                    u16(0xC000 or known)
                    return@apply
                }
                offsets[rest] = out.size()
                val label = rest.substringBefore('.')
                out.write(label.length)
                out.write(label.toByteArray())
                rest = rest.substringAfter('.', "")
            }
            out.write(0)
        }

        fun record(name: String, type: Int, body: Packet.() -> Unit) = apply {
            name(name)
            u16(type); u16(0x8001); u32(120)
            val lengthAt = out.size()
            u16(0)
            body()
            val bytes = out.toByteArray()
            val size = bytes.size - lengthAt - 2
            bytes[lengthAt] = (size ushr 8).toByte()
            bytes[lengthAt + 1] = size.toByte()
            out.reset()
            out.write(bytes)
        }
    }

    private fun avahiStyleResponse(): ByteArray {
        val service = "_octoprint._tcp.local"
        val instance = "OctoPrint instance on octopi.$service"
        return Packet().apply {
            u16(0x1234); u16(0x8400); u16(0); u16(1); u16(0); u16(3)
            record(service, 12) { name(instance) }
            record(instance, 33) { u16(0); u16(0); u16(80); name("octopi.local") }
            record(instance, 16) {
                for (entry in listOf("path=/", "uuid=abc", "version=1.11.7")) {
                    out.write(entry.length)
                    out.write(entry.toByteArray())
                }
            }
            record("octopi.local", 1) { out.write(byteArrayOf(192.toByte(), 168.toByte(), 1, 77)) }
        }.out.toByteArray()
    }

    @Test
    fun readsAResponseWithCompressedNames() {
        val records = MdnsCodec.parse(avahiStyleResponse())
        assertEquals(4, records.size)
        val srv = records.filterIsInstance<MdnsRecord.Srv>().single()
        assertEquals("octopi.local", srv.target)
        assertEquals(80, srv.port)
        assertEquals("192.168.1.77", records.filterIsInstance<MdnsRecord.A>().single().address.hostAddress)
        assertEquals("/", records.filterIsInstance<MdnsRecord.Txt>().single().entries["path"])
    }

    @Test
    fun offersTheServerByNameOnTheDefaultPort() {
        val found = assemble(MdnsCodec.parse(avahiStyleResponse())).single()
        assertEquals("OctoPrint instance on octopi", found.name)
        assertEquals("http://octopi.local/", found.url)
    }

    @Test
    fun survivesGarbage() {
        val good = avahiStyleResponse()
        // every truncation of a real packet, and a pointer that loops on itself
        for (cut in 0 until good.size) MdnsCodec.parse(good, cut)
        val loop = byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0, 0xC0.toByte(), 12, 0, 1, 0, 1)
        assertTrue(MdnsCodec.parse(loop).isEmpty())
    }

    /**
     * One record whose name is a chain of [links] sixty-letter labels, each reached from the
     * one before by a pointer: the trick that lets a small packet spell out a very long name.
     */
    private fun chained(links: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v ushr 8 and 0xFF); out.write(v and 0xFF) }
        u16(1); u16(0x8400); u16(0); u16(1); u16(0); u16(0)
        val chainAt = 12 + 2 + 10 + 2
        u16(0xC000 or chainAt)                          // the record's name
        u16(12); u16(0x8001); u16(0); u16(120); u16(2)  // a PTR, two bytes long
        u16(0xC000 or chainAt)                          // and its target
        for (link in 0 until links) {
            out.write(60)
            repeat(60) { out.write('a'.code) }
            if (link == links - 1) out.write(0) else u16(0xC000 or (chainAt + (link + 1) * 63))
        }
        return out.toByteArray()
    }

    @Test
    fun aNameIsNotUnfoldedPastTheLengthANameMayBe() {
        assertEquals("three links make a long name, but a name", 1, MdnsCodec.parse(chained(3)).size)
        assertTrue("six make something no name is allowed to be", MdnsCodec.parse(chained(6)).isEmpty())
    }

    @Test
    fun anAnnouncementCannotPointSomewhereElse() {
        // The host named in an announcement goes into an address the display offers to connect
        // to. A slash in it would make that address some other machine's.
        val service = "_octoprint._tcp.local"
        val address = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
        fun announcing(host: String) = assemble(
            listOf(
                MdnsRecord.Ptr(service, "x.$service"),
                MdnsRecord.Srv("x.$service", host, 80),
                MdnsRecord.A(host, address),
            )
        )
        assertEquals("http://octopi.local/", announcing("octopi.local").single().url)
        assertTrue(announcing("elsewhere.example/x.local").isEmpty())
    }

    @Test
    fun buildsAOneQuestionQuery() {
        val q = MdnsCodec.query("octopi.local", 1, id = 7)
        assertEquals(12 + 1 + 6 + 1 + 5 + 1 + 4, q.size)
        assertEquals(7, q[1].toInt())
        assertEquals(1, q[5].toInt())
    }

    /**
     * Asks the real network. Not part of a normal run:
     *   set MDNS_LIVE=1 && gradlew :app:testDebugUnitTest --tests "*MdnsTest*"
     */
    @Test
    fun liveProbe() {
        assumeTrue(System.getenv("MDNS_LIVE") == "1")
        val records = mdnsAsk("_octoprint._tcp.local", 12, listenMillis = 3000)
        println("records: $records")
        val servers = assemble(records)
        println("servers: $servers")
        for (s in servers) {
            val host = s.url.removePrefix("http://").substringBefore('/').substringBefore(':')
            println("resolve $host -> ${resolveLocal(host)}")
        }
    }
}

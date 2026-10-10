package com.drazan.echoshow.octoprint.octo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.Dns
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/*
 * Just enough multicast DNS to find OctoPrint and keep finding it.
 *
 * OctoPrint's bundled Discovery plugin announces itself as _octoprint._tcp, and
 * the Pi answers to a name like octopi.local. Android 11 (what an unlocked Echo
 * Show runs) cannot look up .local names itself, and its service-discovery API
 * may hand back an IPv6 link-local address that no URL can carry. So this asks
 * the question directly.
 *
 * Queries go out from an ordinary socket rather than port 5353, which makes
 * them "one-shot" queries (RFC 6762 §5.1): responders answer straight back to
 * that socket, so there is no multicast group to join and no Wi-Fi multicast
 * lock to hold.
 */

private const val MDNS_PORT = 5353
private val MDNS_GROUP: InetAddress = InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 251.toByte()))

private const val TYPE_A = 1
private const val TYPE_PTR = 12
private const val TYPE_TXT = 16
private const val TYPE_SRV = 33

private const val OCTOPRINT_SERVICE = "_octoprint._tcp.local"

// Anyone on the network can answer a question, so answers are only taken in measure.
/** The longest a domain name is allowed to be. Compression can otherwise unfold one to many times its packet. */
private const val MAX_NAME = 255
private const val MAX_RECORDS_PER_PACKET = 64
private const val MAX_RECORDS_PER_QUESTION = 512
/** What a host name and the path after it may be made of, when they come off the network to be offered as an address. */
private val HOST_NAME = Regex("[a-z0-9]([a-z0-9.-]{0,252}[a-z0-9])?")
private val URL_PATH = Regex("[A-Za-z0-9._~/-]{0,200}")

internal sealed interface MdnsRecord {
    val name: String

    data class A(override val name: String, val address: InetAddress) : MdnsRecord
    data class Ptr(override val name: String, val target: String) : MdnsRecord
    data class Srv(override val name: String, val target: String, val port: Int) : MdnsRecord
    data class Txt(override val name: String, val entries: Map<String, String>) : MdnsRecord
}

internal object MdnsCodec {

    fun query(name: String, type: Int, id: Int = (1..0xFFFF).random()): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write(v ushr 8 and 0xFF)
            out.write(v and 0xFF)
        }
        u16(id)
        u16(0)      // a standard query
        u16(1)      // one question
        u16(0); u16(0); u16(0)
        name.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size.coerceAtMost(63))
            out.write(bytes, 0, bytes.size.coerceAtMost(63))
        }
        out.write(0)
        u16(type)
        u16(1)      // class IN
        return out.toByteArray()
    }

    /** Every record in a response, from all three sections. Malformed packets yield what was readable. */
    fun parse(packet: ByteArray, length: Int = packet.size): List<MdnsRecord> {
        val records = ArrayList<MdnsRecord>()
        try {
            if (length < 12) return records
            val r = Reader(packet, length)
            r.pos = 4
            val questions = r.u16()
            val total = r.u16() + r.u16() + r.u16()
            repeat(questions) {
                r.name()
                r.pos += 4
            }
            repeat(total) {
                if (records.size >= MAX_RECORDS_PER_PACKET) return records
                val name = r.name()
                val type = r.u16()
                r.pos += 6                      // class and TTL
                val size = r.u16()
                val end = r.pos + size
                if (end > length) return records
                when (type) {
                    TYPE_A -> if (size == 4) {
                        records += MdnsRecord.A(name, InetAddress.getByAddress(packet.copyOfRange(r.pos, end)))
                    }
                    TYPE_PTR -> records += MdnsRecord.Ptr(name, r.name())
                    TYPE_SRV -> {
                        r.pos += 4              // priority and weight
                        val port = r.u16()
                        records += MdnsRecord.Srv(name, r.name(), port)
                    }
                    TYPE_TXT -> {
                        val entries = LinkedHashMap<String, String>()
                        while (r.pos < end) {
                            val n = packet[r.pos].toInt() and 0xFF
                            val text = String(packet, r.pos + 1, n.coerceAtMost(end - r.pos - 1), Charsets.UTF_8)
                            r.pos += 1 + n
                            val eq = text.indexOf('=')
                            if (eq > 0) entries[text.substring(0, eq)] = text.substring(eq + 1)
                        }
                        records += MdnsRecord.Txt(name, entries)
                    }
                }
                r.pos = end
            }
        } catch (_: IndexOutOfBoundsException) {
            // truncated or hostile packet: keep whatever parsed cleanly
        }
        return records
    }

    private class Reader(val data: ByteArray, val length: Int) {
        var pos = 0

        fun u16(): Int {
            if (pos + 2 > length) throw IndexOutOfBoundsException()
            val v = (data[pos].toInt() and 0xFF shl 8) or (data[pos + 1].toInt() and 0xFF)
            pos += 2
            return v
        }

        /** A possibly compressed domain name, without a trailing dot, in the case it was sent. */
        fun name(): String {
            val out = StringBuilder()
            var at = pos
            var jumped = false
            var hops = 0
            while (true) {
                if (at >= length) throw IndexOutOfBoundsException()
                val n = data[at].toInt() and 0xFF
                if (n == 0) {
                    if (!jumped) pos = at + 1
                    break
                }
                if (n and 0xC0 == 0xC0) {
                    if (at + 1 >= length || ++hops > 16) throw IndexOutOfBoundsException()
                    if (!jumped) pos = at + 2
                    jumped = true
                    at = (n and 0x3F shl 8) or (data[at + 1].toInt() and 0xFF)
                    continue
                }
                if (at + 1 + n > length) throw IndexOutOfBoundsException()
                if (out.isNotEmpty()) out.append('.')
                out.append(String(data, at + 1, n, Charsets.UTF_8))
                if (out.length > MAX_NAME) throw IndexOutOfBoundsException()
                at += 1 + n
            }
            return out.toString()
        }
    }
}

/**
 * Sends one question out of every IPv4 network interface and collects the
 * answers that come back within [listenMillis].
 */
internal fun mdnsAsk(name: String, type: Int, listenMillis: Int): List<MdnsRecord> {
    val question = MdnsCodec.query(name, type)
    val sources = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback && it.supportsMulticast() }.getOrDefault(false) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .toList()
    }.getOrDefault(emptyList())

    val sockets = (sources.ifEmpty { listOf(null) }).mapNotNull { source ->
        val socket = runCatching { DatagramSocket(null) }.getOrNull() ?: return@mapNotNull null
        try {
            socket.bind(InetSocketAddress(source, 0))
            socket.send(DatagramPacket(question, question.size, MDNS_GROUP, MDNS_PORT))
            socket
        } catch (_: Exception) {
            // this interface can't reach the multicast group; don't leave its socket open
            socket.close()
            null
        }
    }

    val records = ArrayList<MdnsRecord>()
    val buffer = ByteArray(9000)
    val deadline = System.currentTimeMillis() + listenMillis
    try {
        // Poll the sockets in turn with short timeouts; there are only ever a handful.
        while (sockets.isNotEmpty() && records.size < MAX_RECORDS_PER_QUESTION) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            for (socket in sockets) {
                try {
                    socket.soTimeout = (left / sockets.size).toInt().coerceIn(20, 120)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    records += MdnsCodec.parse(packet.data, packet.length)
                } catch (_: SocketTimeoutException) {
                    // nothing on this interface just now
                } catch (_: Exception) {
                    // an interface went away mid-listen
                }
            }
        }
    } finally {
        sockets.forEach { runCatching { it.close() } }
    }
    return records
}

/**
 * OctoPrint instances on the local network, each emitted once as soon as it
 * can be addressed. Keeps asking, at a slowing pace, until the collector stops.
 */
fun discoverOctoPrint(): Flow<FoundServer> = flow {
    val seen = HashSet<String>()
    var pause = 500L
    while (coroutineContext.isActive) {
        val records = mdnsAsk(OCTOPRINT_SERVICE, TYPE_PTR, listenMillis = 1800)
        for (server in assemble(records)) {
            if (seen.add(server.url)) emit(server)
        }
        delay(pause)
        pause = (pause * 2).coerceAtMost(8_000)
    }
}.flowOn(Dispatchers.IO)

/**
 * Turn the PTR / SRV / TXT / A records of a response into addressable servers.
 * DNS names compare without regard to case, so everything is keyed lower-cased
 * and only the instance name keeps its capitals, for showing to people.
 */
internal fun assemble(records: List<MdnsRecord>): List<FoundServer> {
    val addresses = records.filterIsInstance<MdnsRecord.A>().associate { it.name.lowercase() to it.address }
    val texts = records.filterIsInstance<MdnsRecord.Txt>().associate { it.name.lowercase() to it.entries }
    val services = records.filterIsInstance<MdnsRecord.Srv>().associateBy { it.name.lowercase() }

    return records.filterIsInstance<MdnsRecord.Ptr>()
        .filter { it.name.equals(OCTOPRINT_SERVICE, ignoreCase = true) }
        .map { it.target }
        .distinctBy { it.lowercase() }
        .mapNotNull { instance ->
            val srv = services[instance.lowercase()] ?: return@mapNotNull null
            val target = srv.target.lowercase()
            // this goes into an address someone will be offered to touch
            if (!HOST_NAME.matches(target)) return@mapNotNull null
            // Prefer the Pi's name over its address: the name survives a new DHCP lease.
            // If the response didn't carry the address, look the name up before offering it.
            val known = addresses[target] ?: resolveLocal(target).firstOrNull() ?: return@mapNotNull null
            val host = if (target.endsWith(".local")) target else known.hostAddress ?: return@mapNotNull null
            val port = if (srv.port == 80 || srv.port <= 0) "" else ":${srv.port}"
            val path = texts[instance.lowercase()]?.get("path")?.trim('/').orEmpty()
            if (!URL_PATH.matches(path)) return@mapNotNull null
            val suffix = ".$OCTOPRINT_SERVICE"
            FoundServer(
                name = if (instance.endsWith(suffix, ignoreCase = true)) instance.dropLast(suffix.length) else instance,
                url = "http://$host$port/" + if (path.isEmpty()) "" else "$path/",
            )
        }
}

/** IPv4 addresses for a .local name, asked for up to three times. Empty if nobody answers. */
fun resolveLocal(host: String): List<InetAddress> {
    val name = host.trimEnd('.').lowercase()
    repeat(3) { attempt ->
        val found = mdnsAsk(name, TYPE_A, listenMillis = 500 + attempt * 500)
            .filterIsInstance<MdnsRecord.A>()
            .filter { it.name.equals(name, ignoreCase = true) }
            .map { it.address }
            .distinct()
        if (found.isNotEmpty()) return found
    }
    return emptyList()
}

/**
 * Name lookup for the HTTP client: .local names go to multicast DNS, the rest
 * to the system. A name's last good answer is remembered (and handed to
 * [remember] to be kept across restarts) so that one missed reply doesn't cut
 * the display off from a Pi that hasn't moved.
 */
class LocalDns(
    private val recall: (String) -> String? = { null },
    private val remember: (String, String) -> Unit = { _, _ -> },
) : Dns {
    private class Entry(val addresses: List<InetAddress>, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        val name = hostname.trimEnd('.').lowercase()
        if (!name.endsWith(".local")) return Dns.SYSTEM.lookup(hostname)

        val cached = cache[name]
        if (cached != null && System.currentTimeMillis() - cached.at < FRESH_MILLIS) return cached.addresses

        val fresh = resolveLocal(name)
        if (fresh.isNotEmpty()) {
            cache[name] = Entry(fresh, System.currentTimeMillis())
            fresh.first().hostAddress?.let { remember(name, it) }
            return fresh
        }
        // Nobody answered. Fall back on what worked before rather than failing outright.
        cached?.let { return it.addresses }
        recall(name)?.let { literal ->
            runCatching { InetAddress.getByName(literal) }.getOrNull()?.let { return listOf(it) }
        }
        throw UnknownHostException("$hostname did not answer on the local network")
    }

    private companion object {
        const val FRESH_MILLIS = 120_000L
    }
}

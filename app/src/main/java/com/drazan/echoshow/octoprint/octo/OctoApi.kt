package com.drazan.echoshow.octoprint.octo

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OctoHttpException(val code: Int, val detail: String) : IOException("HTTP $code $detail")

class Session(val name: String, val session: String)

sealed interface KeyPoll {
    data object Pending : KeyPoll
    data class Granted(val key: String) : KeyPoll
    data object Denied : KeyPoll
}

/** "192.168.1.50", "octopi.local:5000/" or a full URL, as a base URL ending in a slash. */
fun normalizeBase(input: String): HttpUrl? {
    var s = input.trim()
    if (s.isEmpty()) return null
    if (!s.contains("://")) s = "http://$s"
    val u = s.toHttpUrlOrNull() ?: return null
    return u.newBuilder()
        .encodedPath(u.encodedPath.trimEnd('/') + "/")
        .query(null)
        .fragment(null)
        .build()
}

private val SharedClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    .build()

private val Json = "application/json; charset=utf-8".toMediaType()
private const val KEY_HEADER = "X-Api-Key"

/**
 * The slice of OctoPrint's REST API this display uses.
 * Every call suspends; failures surface as [IOException] / [OctoHttpException].
 */
class OctoApi(
    val base: HttpUrl,
    private val apiKey: String?,
    dns: Dns = Dns.SYSTEM,
    val trust: ServerTrust = ServerTrust(pinned = null, learning = false),
    cacheDir: File? = null,
) {

    /**
     * Adds the access key to what is sent to OctoPrint itself and to nothing else: not to
     * another port on the same machine, where a camera often lives, and not over plain
     * http when OctoPrint was found on https. The second interceptor sees each hop as it
     * goes out, so the key doesn't follow a redirect that leads somewhere else either.
     */
    val client: OkHttpClient = SharedClient.newBuilder()
        .dns(dns)
        .sslSocketFactory(trust.socketFactory, trust.manager)
        .hostnameVerifier(trust.hostnames)
        .addInterceptor { chain ->
            val req = chain.request()
            chain.proceed(
                if (apiKey != null && isOctoPrint(req.url)) req.newBuilder().header(KEY_HEADER, apiKey).build() else req
            )
        }
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            chain.proceed(if (isOctoPrint(req.url)) req else req.newBuilder().removeHeader(KEY_HEADER).build())
        }
        .build()

    private fun isOctoPrint(url: HttpUrl): Boolean =
        url.scheme == base.scheme && url.host == base.host && url.port == base.port

    /**
     * The file listing is slow for a Pi to build and can be megabytes long, so
     * it gets a patient client with a disk cache. Each fetch is revalidated
     * against OctoPrint's ETag; when nothing changed the Pi answers 304 and the
     * listing is read back from flash instead of being generated again.
     */
    private val slowClient: OkHttpClient = client.newBuilder()
        .readTimeout(90, TimeUnit.SECONDS)
        .apply { if (cacheDir != null) cache(Cache(File(cacheDir, "octoprint-http"), 48L * 1024 * 1024)) }
        .build()

    val socketClient: OkHttpClient = client.newBuilder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    fun url(path: String): HttpUrl = base.newBuilder().addPathSegments(path).build()

    /** Resolve something OctoPrint hands back (a thumbnail, the webcam stream) against the base. */
    fun resolve(link: String): HttpUrl? = base.resolve(link)

    /* ---------------- access ---------------- */

    /**
     * Whether this server hands out app keys on request, and where it really
     * lives: a Pi that redirects http to https answers from a different base
     * than the one asked, and later POSTs must go straight there.
     * Null when something answered but it wasn't OctoPrint offering keys.
     */
    suspend fun keyOffice(): HttpUrl? =
        exec(get("plugin/appkeys/probe"), expectOk = false) { r ->
            if (r.code != 204) return@exec null
            val landed = r.request.url
            if (landed.scheme == base.scheme && landed.host == base.host && landed.port == base.port) base
            else normalizeBase(landed.toString().substringBefore("plugin/appkeys/probe")) ?: base
        }

    /** Ask OctoPrint for an access key; a logged-in user then approves it in the web UI. */
    suspend fun requestKey(app: String): String =
        exec(post("plugin/appkeys/request", JSONObject().put("app", app))) {
            JSONObject(it.text()).getString("app_token")
        }

    suspend fun pollKey(token: String): KeyPoll =
        exec(get("plugin/appkeys/request/$token"), expectOk = false) {
            when (it.code) {
                200 -> JSONObject(it.text()).str("api_key")?.let(KeyPoll::Granted) ?: KeyPoll.Denied
                202 -> KeyPoll.Pending
                else -> KeyPoll.Denied
            }
        }

    /**
     * Where this key is good: the base that answered a keyed request, which is
     * not the one asked when the server moves the request along to another path.
     * A move to another scheme, host or port is followed without the key, and so
     * ends in a refusal: the caller tries those places in their own right.
     * Throws [OctoHttpException] 403 when the server is there but refuses the key.
     */
    suspend fun keyHome(): HttpUrl =
        exec(get("api/version")) { r ->
            val landed = r.request.url
            if (landed.scheme == base.scheme && landed.host == base.host && landed.port == base.port) base
            else normalizeBase(landed.toString().substringBefore("api/version")) ?: base
        }

    /** Passive login: swaps the access key for the session the push socket wants. */
    suspend fun login(): Session =
        exec(post("api/login", JSONObject().put("passive", true))) {
            val o = JSONObject(it.text())
            val name = o.str("name")
            val session = o.str("session")
            if (name.isNullOrEmpty() || session.isNullOrEmpty()) throw OctoHttpException(403, "Not signed in")
            Session(name, session)
        }

    suspend fun version(): String =
        exec(get("api/version")) { JSONObject(it.text()).str("server").orEmpty() }

    /**
     * Whether OctoPrint still honours our key. A revoked key makes POSTs fail
     * with a misleading 400 (CSRF), so this asks with a GET, which answers 403.
     * Null when the server can't be asked at all.
     */
    suspend fun keyAccepted(): Boolean? = try {
        exec(get("api/version"), expectOk = false) {
            when {
                it.isSuccessful -> true
                it.code == 403 -> false
                else -> null
            }
        }
    } catch (_: IOException) {
        null
    }

    suspend fun settings(): JSONObject = exec(get("api/settings")) { JSONObject(it.text()) }

    /* ---------------- files ---------------- */

    suspend fun files(): List<PrintFile> {
        val req = Request.Builder()
            .url(url("api/files/local").newBuilder().addQueryParameter("recursive", "true").build())
            .cacheControl(CacheControl.Builder().noCache().build())
            .build()
        return exec(req, slowClient) { FileListParser.parse(it.body!!.byteStream()) }
    }

    suspend fun file(origin: String, path: String): PrintFile? =
        exec(get("api/files/$origin/$path")) { FileListParser.parseOne(it.body!!.byteStream()) }

    suspend fun print(file: PrintFile) = send(
        "api/files/${file.origin}/${file.path}",
        JSONObject().put("command", "select").put("print", true),
    )

    /* ---------------- job ---------------- */

    suspend fun cancel() = send("api/job", JSONObject().put("command", "cancel"))
    suspend fun pause() = send("api/job", JSONObject().put("command", "pause").put("action", "pause"))
    suspend fun resume() = send("api/job", JSONObject().put("command", "pause").put("action", "resume"))

    /* ---------------- printer ---------------- */

    suspend fun connectPrinter() = send("api/connection", JSONObject().put("command", "connect"))

    /** After an error OctoPrint ignores "connect" until the dead connection is dropped. */
    suspend fun disconnectPrinter() = send("api/connection", JSONObject().put("command", "disconnect"))

    suspend fun setToolTarget(celsius: Int) = send(
        "api/printer/tool",
        JSONObject().put("command", "target").put("targets", JSONObject().put("tool0", celsius)),
    )

    suspend fun setBedTarget(celsius: Int) = send(
        "api/printer/bed",
        JSONObject().put("command", "target").put("target", celsius),
    )

    suspend fun jog(x: Double = 0.0, y: Double = 0.0, z: Double = 0.0) = send(
        "api/printer/printhead",
        JSONObject().put("command", "jog").put("x", x).put("y", y).put("z", z),
    )

    suspend fun home(vararg axes: String) = send(
        "api/printer/printhead",
        JSONObject().put("command", "home").put("axes", JSONArray(axes.toList())),
    )

    /** Positive pushes filament out, negative pulls it back. Millimetres. */
    suspend fun extrude(mm: Int) = send(
        "api/printer/tool",
        JSONObject().put("command", "extrude").put("amount", mm),
    )

    suspend fun setFeedRate(percent: Int) = send(
        "api/printer/printhead",
        JSONObject().put("command", "feedrate").put("factor", percent),
    )

    suspend fun setFlowRate(percent: Int) = send(
        "api/printer/tool",
        JSONObject().put("command", "flowrate").put("factor", percent),
    )

    suspend fun gcode(vararg commands: String) = send(
        "api/printer/command",
        JSONObject().put("commands", JSONArray(commands.toList())),
    )

    /* ---------------- plugins ---------------- */

    /** LightControls: brightness 0..100 for the light on a GPIO pin. Needs the key of an admin. */
    suspend fun setLight(pin: Int, percent: Int) = send(
        "api/plugin/LightControls",
        JSONObject().put("command", "setLightValue").put("pin", pin).put("percentage", percent.coerceIn(0, 100)),
    )

    /** LightControls has no way to read values back; this makes it announce them on the socket. */
    suspend fun announceLights() = send("api/plugin/LightControls", JSONObject().put("command", "getLightValues"))

    /** The question the printer is currently asking, if any. */
    suspend fun activePrompt(): Prompt? = exec(get("api/plugin/action_command_prompt"), expectOk = false) {
        if (it.code != 200) return@exec null
        val o = runCatching { JSONObject(it.text()) }.getOrNull() ?: return@exec null
        val text = o.str("text") ?: return@exec null
        Prompt(text, o.optJSONArray("choices").strings())
    }

    suspend fun answerPrompt(choice: Int) = send(
        "api/plugin/action_command_prompt",
        JSONObject().put("command", "select").put("choice", choice),
    )

    /* ---------------- the Pi itself ---------------- */

    /** Whether OctoPrint has a shutdown command set up and this key is allowed to run it. */
    suspend fun canShutDown(): Boolean = exec(get("api/system/commands/core"), expectOk = false) { r ->
        if (r.code != 200) return@exec false
        val commands = runCatching { JSONArray(r.text()) }.getOrNull() ?: return@exec false
        (0 until commands.length()).any { commands.optJSONObject(it)?.str("action") == "shutdown" }
    }

    /** Shuts down the computer OctoPrint runs on, not just OctoPrint. */
    suspend fun shutDownSystem() = send("api/system/commands/core/shutdown", JSONObject())

    /* ---------------- plumbing ---------------- */

    suspend fun send(path: String, body: JSONObject) = exec(post(path, body)) { }

    private fun get(path: String) = Request.Builder().url(url(path)).build()

    private fun post(path: String, body: JSONObject) =
        Request.Builder().url(url(path)).post(body.toString().toRequestBody(Json)).build()

    /**
     * Runs the request and reads the response on OkHttp's own threads, so
     * nothing here ever touches the network from the main thread.
     */
    private suspend fun <T> exec(
        request: Request,
        with: OkHttpClient = client,
        expectOk: Boolean = true,
        read: (Response) -> T,
    ): T = suspendCancellableCoroutine { cont ->
        val call = with.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        if (expectOk && !it.isSuccessful) throw OctoHttpException(it.code, errorDetail(it))
                        read(it)
                    }
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                    return
                }
                cont.resume(result)
            }
        })
    }

    private fun Response.text(): String = body?.string().orEmpty()

    /** OctoPrint answers errors with {"error": "..."}; fall back to the status line. */
    private fun errorDetail(r: Response): String {
        val raw = runCatching { r.peekBody(4096).string() }.getOrDefault("")
        return runCatching { JSONObject(raw).str("error") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: r.message.ifBlank { "error" }
    }
}

/** A sentence for the person at the printer, not a stack trace. */
fun Throwable.forPeople(): String = when (this) {
    is OctoHttpException -> when (code) {
        401, 403 -> "OctoPrint turned down this display's access key."
        404 -> "OctoPrint couldn't find that."
        409 -> "The printer can't do that right now."
        else -> "OctoPrint answered with an error ($code)."
    }
    else -> "Couldn't reach OctoPrint."
}

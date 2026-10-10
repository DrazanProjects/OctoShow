package com.drazan.echoshow.octoprint

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drazan.echoshow.octoprint.data.DisplayPrefs
import com.drazan.echoshow.octoprint.data.Prefs
import com.drazan.echoshow.octoprint.data.Server
import com.drazan.echoshow.octoprint.octo.FoundServer
import com.drazan.echoshow.octoprint.octo.HeatPreset
import com.drazan.echoshow.octoprint.octo.KeyPoll
import com.drazan.echoshow.octoprint.octo.LayerInfo
import com.drazan.echoshow.octoprint.octo.LocalDns
import com.drazan.echoshow.octoprint.octo.Light
import com.drazan.echoshow.octoprint.octo.Link
import com.drazan.echoshow.octoprint.octo.LiveEvent
import com.drazan.echoshow.octoprint.octo.OctoApi
import com.drazan.echoshow.octoprint.octo.OctoHttpException
import com.drazan.echoshow.octoprint.octo.OctoLive
import com.drazan.echoshow.octoprint.octo.Outcome
import com.drazan.echoshow.octoprint.octo.Phase
import com.drazan.echoshow.octoprint.octo.PrintFile
import com.drazan.echoshow.octoprint.octo.Previews
import com.drazan.echoshow.octoprint.octo.PrinterSnapshot
import com.drazan.echoshow.octoprint.octo.Prompt
import com.drazan.echoshow.octoprint.octo.ServerInfo
import com.drazan.echoshow.octoprint.octo.ServerTrust
import com.drazan.echoshow.octoprint.octo.TempSample
import com.drazan.echoshow.octoprint.octo.Thumbs
import com.drazan.echoshow.octoprint.octo.Webcam
import com.drazan.echoshow.octoprint.octo.dbl
import com.drazan.echoshow.octoprint.octo.discoverOctoPrint
import com.drazan.echoshow.octoprint.octo.forPeople
import com.drazan.echoshow.octoprint.octo.normalizeBase
import com.drazan.echoshow.octoprint.octo.obj
import com.drazan.echoshow.octoprint.octo.str
import com.drazan.echoshow.octoprint.power.Plug
import com.drazan.echoshow.octoprint.power.PowerOff
import com.drazan.echoshow.octoprint.power.PowerRig
import com.drazan.echoshow.octoprint.power.PowerStep
import com.drazan.echoshow.octoprint.power.SelfPower
import com.drazan.echoshow.octoprint.power.stillAnswers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress

data class FilesState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val root: List<PrintFile> = emptyList(),
) {
    /** Every printable file in the tree, most recent upload first. */
    val newestFirst: List<PrintFile> by lazy { root.flattened().filter { !it.isFolder }.sortedByDescending { it.date } }

    /** The most recent upload: usually the thing you came to print. */
    val newest: PrintFile? get() = newestFirst.firstOrNull()
}

private fun List<PrintFile>.flattened(): List<PrintFile> = flatMap { listOf(it) + it.children.flattened() }

sealed interface SetupState {
    data object Idle : SetupState
    data class Checking(val url: String) : SetupState
    /** Asked OctoPrint for a key; someone has to say yes in its web interface. */
    data class Waiting(val url: String) : SetupState
    data class Failed(val message: String) : SetupState
}

class PrinterViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)

    /** Which finished job has already been cleared off the bed; survives restarts. */
    private val cleared = app.getSharedPreferences("cleared_job", Context.MODE_PRIVATE)

    /** Looks up .local names itself and remembers the last address each one had. */
    private val hosts = app.getSharedPreferences("local_hosts", Context.MODE_PRIVATE)
    private val dns = LocalDns(
        recall = { hosts.getString(it, null) },
        remember = { host, address -> hosts.edit().putString(host, address).apply() },
    )

    val display: StateFlow<DisplayPrefs> =
        prefs.display.stateIn(viewModelScope, SharingStarted.Eagerly, DisplayPrefs())

    /** False until preferences have been read, so setup doesn't flash up on every launch. */
    var ready by mutableStateOf(false)
        private set
    var server: Server? by mutableStateOf(null)
        private set

    var link: Link by mutableStateOf(Link.Connecting)
        private set
    var printer: PrinterSnapshot? by mutableStateOf(null)
        private set
    var layers by mutableStateOf(LayerInfo())
        private set
    var temps: List<TempSample> by mutableStateOf(emptyList())
        private set
    var info by mutableStateOf(ServerInfo())
        private set
    var files by mutableStateOf(FilesState())
        private set
    /** Details of the selected job's file: its slicer preview and estimates. */
    var jobFile: PrintFile? by mutableStateOf(null)
        private set
    var outcome: Outcome? by mutableStateOf(null)
        private set
    /** A question from the printer that is waiting for an answer. */
    var prompt: Prompt? by mutableStateOf(null)
        private set
    /** One line of feedback after an action fails; clears itself. */
    var notice: String? by mutableStateOf(null)
        private set
    /** Feed and flow overrides are not reported back by OctoPrint, so remember what we set. */
    var feedRate by mutableStateOf(100)
        private set
    var flowRate by mutableStateOf(100)
        private set

    var api: OctoApi? = null
        private set
    var thumbs: Thumbs? by mutableStateOf(null)
        private set

    /** Goes up each time the display finishes drawing a preview, so placeholders know to look again. */
    var previewsDrawn by mutableIntStateOf(0)
        private set
    /** Of the files that came without a preview: how many the display has drawn, out of how many. */
    var previewProgress: Pair<Int, Int>? by mutableStateOf(null)
        private set
    /** Files someone is looking at that need drawing; null just wakes the worker. */
    private val previewAsks = Channel<PrintFile?>(Channel.UNLIMITED)
    private val previewFailed = HashSet<String>()
    /**
     * Drawings wanted over again, because they were made before a drawing kept the part's
     * shape or the order it is laid in. Each with when it was last asked for and how often
     * it has been tried: fetching the file can fail while the Pi is busy starting a print,
     * which is worth another go a little later, but not an endless string of them.
     */
    private val shapesWanted = HashMap<String, Redraw>()

    private class Redraw(var askedAt: Long, var tries: Int = 0)

    /** The smart plug the printer and the Pi are powered through, when one is set up. */
    var plug: Plug? by mutableStateOf(null)
        private set
    /** What the plug last said about itself; null when it hasn't answered. */
    var plugOn: Boolean? by mutableStateOf(null)
        private set
    /** A switch-off being asked about, under way or just failed; null the rest of the time. */
    var power: PowerStep? by mutableStateOf(null)
        private set
    /** Asked for while a job runs: once it is done, everything is switched off without asking again. */
    var offWhenDone by mutableStateOf(false)
        private set
    /** The power has just come back and the Pi is still booting. */
    var startingUp by mutableStateOf(false)
        private set
    /** Why the plug that was last offered couldn't be used. */
    var plugProblem: String? by mutableStateOf(null)
        private set
    /** Whether this display can switch itself off once everything behind it is off. */
    var displayCanGoOff by mutableStateOf(false)
        private set
    private val selfPower = SelfPower(File(app.filesDir, "adb"))
    /** The display has only just been started, as opposed to the app being restarted on it. */
    private val justBooted = SystemClock.elapsedRealtime() < BOOT_WINDOW_MS
    /**
     * How the last job was said to have ended. Unlike [outcome] it isn't wiped by a status
     * report that still says "printing" and arrives a moment after the news itself.
     */
    private var ended: Outcome? by mutableStateOf(null)
    private var powerJob: Job? = null
    private var whenDoneJob: Job? = null
    private var startingJob: Job? = null

    private var session: CoroutineScope? = null
    private var filesStale = true
    private var filesWanted = false
    private var filesJob: Job? = null
    private var noticeJob: Job? = null
    private var jobFileJob: Job? = null

    init {
        viewModelScope.launch {
            prefs.server.collectLatest { s ->
                reset()
                server = s
                ready = true
                if (s != null) coroutineScope {
                    session = this
                    runSession(s)
                }
            }
        }
        viewModelScope.launch {
            var atStartup = true
            prefs.plug.collectLatest { url ->
                val p = url?.let(::normalizeBase)?.let(::Plug)
                plug = p
                plugOn = null
                // Starting the display is what brings the printer up: a plug found off as the
                // display boots is switched on. Found off at any other time, it is left alone.
                var bringUp = atStartup && justBooted
                atStartup = false
                if (p == null) return@collectLatest

                coroutineScope {
                    launch {
                        // Asked now and again, not only when it matters: Android forgets a
                        // debugging key that has gone unused for a week.
                        while (true) {
                            displayCanGoOff = selfPower.available()
                            delay(SELF_CHECK_MS)
                        }
                    }
                    // With OctoPrint answering, the power is plainly on. Without it, the plug is what
                    // tells a printer that was switched off from one that has gone missing.
                    while (true) {
                        if (link == Link.Online) {
                            plugOn = true
                            bringUp = false
                        } else if (powerJob?.isActive != true) {
                            val on = attempt { p.isOn() }
                            if (on == false && bringUp) {
                                switchOn()
                            } else {
                                // switched on at the plug's own button, say
                                if (plugOn == false && on == true) bootBegan()
                                plugOn = on
                            }
                            if (on != null) bringUp = false
                        }
                        delay(PLUG_POLL_MS)
                    }
                }
            }
        }
    }

    private fun reset() {
        powerJob?.cancel()
        power = null
        whenDoneJob?.cancel()
        offWhenDone = false
        session = null
        api = null
        thumbs = null
        link = Link.Connecting
        printer = null
        layers = LayerInfo()
        temps = emptyList()
        info = ServerInfo()
        files = FilesState()
        jobFile = null
        outcome = null
        prompt = null
        previewProgress = null
        previewFailed.clear()
        filesStale = true
        feedRate = 100
        flowRate = 100
    }

    /** Everything that lives for as long as one server stays configured. */
    private fun CoroutineScope.runSession(s: Server) {
        val base = normalizeBase(s.url) ?: return
        val octo = OctoApi(base, s.apiKey, dns, ServerTrust(pinned = s.cert, learning = false), getApplication<Application>().cacheDir)
        api = octo
        val drawings = Previews(octo, File(getApplication<Application>().filesDir, "previews"), getApplication<Application>().cacheDir)
        thumbs = Thumbs(octo, drawings)
        val live = OctoLive(octo)

        launch { drawPreviews(drawings) }

        launch(Dispatchers.Default) { live.run() }
        launch { live.printer.collect { onPrinter(it) } }
        launch { live.layers.collect { layers = it } }
        launch { live.temps.collect { temps = it } }
        launch { live.prompt.collect { prompt = it } }
        launch { live.events.collect { onEvent(it) } }
        launch {
            var wasOnline = false
            live.link.collectLatest { l ->
                link = l
                if (l == Link.Online) startingUp = false
                when (l) {
                    Link.Online -> if (!wasOnline) {
                        wasOnline = true
                        // a fetch that failed while the server was away deserves another go
                        previewFailed.clear()
                        previewAsks.trySend(null)
                        // a prompt raised while this display was off is still waiting
                        attempt { octo.activePrompt() }?.let { live.prompt.value = it }
                        // A Pi that has only just booted can be slow or half-ready. Without its
                        // settings there is no camera, presets or lights, so keep asking.
                        var wait = 2_000L
                        while (!loadServerInfo(octo, s)) {
                            delay(wait)
                            wait = (wait * 2).coerceAtMost(30_000L)
                        }
                    }
                    else -> wasOnline = false
                }
            }
        }
    }

    private fun onPrinter(p: PrinterSnapshot?) {
        val before = printer?.phase
        printer = p
        if (p == null) return

        // A finished job stays on OctoPrint as "100 %, operational"; show it as done even if
        // this display was off when it happened.
        if (outcome == null && p.phase == Phase.READY && (p.completion ?: 0.0) >= 100.0 &&
            p.file != null && cleared.getString("job", null) != jobId(p)
        ) {
            outcome = Outcome(Outcome.Kind.DONE, p.file.display, p.printTime, 0L)
        }
        if (p.phase.busy && outcome != null) outcome = null

        if (p.phase == Phase.READY && before != Phase.READY) wantFiles()
        // the printer has just come free: time to carry on with the back catalogue
        if (!p.phase.busy && before?.busy != false) previewAsks.trySend(null)
        trackJobFile(p)
    }

    /** One finished run of one file: a reprint takes a different time, so it counts as new. */
    private fun jobId(p: PrinterSnapshot): String = "${p.file?.origin}:${p.file?.path}:${p.printTime}"

    /** Keep [jobFile] pointing at whatever is selected, asking OctoPrint for just that one entry. */
    private fun trackJobFile(p: PrinterSnapshot) {
        val f = p.file
        if (f == null) {
            jobFile = null
            return
        }
        if (jobFile?.path == f.path && jobFile?.origin == f.origin) return
        if (jobFileJob?.isActive == true) return
        val octo = api ?: return
        jobFileJob = session?.launch {
            jobFile = runCatching { octo.file(f.origin, f.path) }.getOrNull()
                ?: PrintFile(f.name, f.display, f.path, f.origin, false, 0, 0, p.estimatedPrintTime, null, null, null)
        }
    }

    private fun onEvent(e: LiveEvent) {
        when (e) {
            LiveEvent.PrintStarted -> {
                outcome = null
                ended = null
                feedRate = 100
                flowRate = 100
            }
            is LiveEvent.PrintEnded -> {
                outcome = e.outcome
                ended = e.outcome
            }
            LiveEvent.FilesChanged -> {
                filesStale = true
                // Building the listing costs the Pi several seconds, so never do it under a
                // running print unless someone is actually looking at the file list.
                if (filesWanted || printer?.phase == Phase.READY) refreshFiles()
            }
            is LiveEvent.LightChanged -> info = info.copy(
                lights = info.lights.map { if (it.pin == e.pin) it.copy(percent = e.percent) else it }
            )
        }
    }

    /* ---------------- server info ---------------- */

    /** Runs [block], giving null if it fails for any reason other than being cancelled. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** False if the server couldn't be asked; the caller tries again. */
    private suspend fun loadServerInfo(octo: OctoApi, s: Server): Boolean {
        try {
            val version = octo.version()
            val settings = octo.settings()
            val lights = readLights(settings)
            info = ServerInfo(
                name = settings.obj("appearance")?.str("name")?.takeIf { it.isNotBlank() } ?: s.name,
                version = version,
                presets = readPresets(settings),
                webcam = readWebcam(settings),
                lights = lights,
                canShutDown = octo.canShutDown(),
            )
            // their current brightness arrives over the socket in reply
            if (lights.isNotEmpty()) attempt { octo.announceLights() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        if (printer?.phase == Phase.READY) wantFiles()
        return true
    }

    private fun readPresets(settings: JSONObject): List<HeatPreset> {
        val arr = settings.obj("temperature")?.optJSONArray("profiles") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            HeatPreset(name, o.dbl("extruder")?.toInt() ?: 0, o.dbl("bed")?.toInt() ?: 0)
        }
    }

    /** Lights set up in the LightControls plugin. Its settings page stores some values as text. */
    private fun readLights(settings: JSONObject): List<Light> {
        val arr = settings.obj("plugins")?.obj("LightControls")?.optJSONArray("light_controls") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val pin = o.opt("pin")?.toString()?.trim()?.toIntOrNull() ?: return@mapNotNull null
            Light(
                name = o.str("name")?.takeIf { it.isNotBlank() } ?: "Light",
                pin = pin,
                dimmable = o.opt("ispwm")?.toString().equals("true", ignoreCase = true),
            )
        }
    }

    private fun readWebcam(settings: JSONObject): Webcam? {
        val cam = settings.obj("webcam") ?: return null
        if (cam.has("webcamEnabled") && !cam.optBoolean("webcamEnabled", true)) return null
        val classic = settings.obj("plugins")?.obj("classicwebcam")
        val stream = cam.str("streamUrl")?.takeIf { it.isNotBlank() }
            ?: classic?.str("stream")?.takeIf { it.isNotBlank() }
            ?: return null
        // OctoPrint's own snapshot setting points at the Pi's loopback address, which is no use
        // from another device, so derive the still from the stream address instead.
        val still = when {
            "action=stream" in stream -> stream.replace("action=stream", "action=snapshot")
            stream.trimEnd('/').endsWith("/stream") -> stream.trimEnd('/').removeSuffix("stream") + "snapshot"
            else -> null
        }
        return Webcam(
            streamUrl = stream,
            snapshotUrl = still,
            flipH = cam.optBoolean("flipH", false),
            flipV = cam.optBoolean("flipV", false),
            rotate90 = cam.optBoolean("rotate90", false),
        )
    }

    /* ---------------- files ---------------- */

    /** Call when something on screen needs the file list. Fetches only if it's missing or stale. */
    fun wantFiles() {
        if (filesStale || (!files.loaded && !files.loading)) refreshFiles()
    }

    /** The file list is on screen; keep it fresh even while printing. */
    fun watchFiles(watching: Boolean) {
        filesWanted = watching
        if (watching) wantFiles()
    }

    fun refreshFiles() {
        val octo = api ?: return
        val scope = session ?: return
        if (filesJob?.isActive == true) return
        filesJob = scope.launch {
            while (true) {
                filesStale = false
                files = files.copy(loading = true, error = null)
                try {
                    files = FilesState(loaded = true, root = octo.files())
                    previewAsks.trySend(null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    filesStale = true
                    files = files.copy(loading = false, error = e.forPeople())
                    return@launch
                }
                // An upload is followed by its analysis, which changes the listing again. If that
                // happened while this one was being built, it is already out of date: go round.
                if (!filesStale || !(filesWanted || printer?.phase == Phase.READY)) return@launch
            }
        }
    }

    /* ---------------- previews drawn on the display ---------------- */

    /** Something on screen is showing [file] and it has no picture: draw it next, if now is a good time. */
    fun wantPreview(file: PrintFile) {
        if (!file.isFolder && file.thumbnail == null) previewAsks.trySend(file)
    }

    /**
     * Something on screen wants to show [file] part-printed and its drawing can't be, or
     * not well: it has the picture but not the shape behind it, or not the order the shape
     * is laid in, or it has only ever had the slicer's picture and was never drawn here.
     * The worker draws it. This can be called as often as liked: the worker hears of it
     * once in a while, and a file is given up on after a few tries.
     */
    fun wantShape(file: PrintFile) {
        if (file.isFolder) return
        val key = thumbs?.drawn?.key(file) ?: return
        val now = SystemClock.elapsedRealtime()
        val redraw = shapesWanted.getOrPut(key) { Redraw(askedAt = now - REDRAW_ASK_AGAIN_MS) }
        if (redraw.tries >= REDRAW_TRIES || now - redraw.askedAt < REDRAW_ASK_AGAIN_MS) return
        redraw.askedAt = now
        previewAsks.trySend(file)
    }

    /**
     * Whether [file] may be fetched and drawn right now. While a job runs the Pi
     * has better things to do than serve the whole library, so only the file
     * being printed is allowed; the rest waits until the printer is free.
     */
    private fun mayDraw(file: PrintFile): Boolean {
        val p = printer ?: return false
        if (link != Link.Online) return false
        return !p.phase.busy || (p.file?.path == file.path && p.file.origin == file.origin)
    }

    /** What to say in place of a picture that isn't there. */
    suspend fun previewNote(file: PrintFile): String {
        if (file.thumbnail != null) return "The preview for this file didn't load"
        val store = thumbs?.drawn ?: return ""
        return when {
            withContext(Dispatchers.IO) { store.settled(file) } -> "There is nothing in this file to draw"
            store.key(file) in previewFailed -> "This file couldn't be fetched to draw a preview"
            !mayDraw(file) -> "A preview will be drawn once the printer is free"
            else -> "Drawing a preview from the G-code"
        }
    }

    /**
     * Works through files that have no preview, one at a time: first whatever is
     * on screen, most recently asked first, then every other file, newest first.
     * Sleeps when there is nothing it may do and is woken by [previewAsks].
     */
    private suspend fun drawPreviews(store: Previews) {
        val asked = ArrayDeque<PrintFile>()
        while (true) {
            while (true) {
                val waiting = previewAsks.tryReceive()
                if (!waiting.isSuccess) break
                waiting.getOrNull()?.let(asked::addLast)
            }
            val next = asked.removeLastOrNull() ?: nextUndrawn(store)
            if (next == null) {
                previewAsks.receive()?.let(asked::addLast)
                continue
            }
            val key = store.key(next)
            val redraw = shapesWanted[key]?.takeIf { withContext(Dispatchers.IO) { !store.hasShape(next) } }
            val again = redraw != null
            if (key in previewFailed || !mayDraw(next) || (!again && withContext(Dispatchers.IO) { store.settled(next) })) continue

            redraw?.let { it.tries++ }
            val outcome = store.draw(
                file = next,
                gentle = { printer?.phase?.busy == true },
                keepGoing = { mayDraw(next) },
                again = again,
            )
            when (outcome) {
                Previews.Outcome.DRAWN -> previewsDrawn++
                // a drawing being made again still has its picture, and will be asked for again
                Previews.Outcome.FAILED -> if (!again) previewFailed += key
                Previews.Outcome.NOTHING -> previewsDrawn++
                Previews.Outcome.ABANDONED -> Unit
            }
            countPreviews(store)
        }
    }

    /** The newest file still waiting for a drawing, if the printer is free to have the library read. */
    private suspend fun nextUndrawn(store: Previews): PrintFile? {
        if (link != Link.Online || printer?.phase?.busy != false) return null
        val candidates = files.newestFirst
        countPreviews(store)
        return withContext(Dispatchers.IO) {
            candidates.firstOrNull { it.thumbnail == null && store.key(it) !in previewFailed && !store.settled(it) }
        }
    }

    private suspend fun countPreviews(store: Previews) {
        val without = files.newestFirst.filter { it.thumbnail == null }
        previewProgress = if (without.isEmpty()) null
        else withContext(Dispatchers.IO) { without.count(store::settled) } to without.size
    }

    /* ---------------- actions ---------------- */

    private fun act(block: suspend OctoApi.() -> Unit) {
        val octo = api ?: return
        val scope = session ?: return
        scope.launch {
            try {
                octo.block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(e.forPeople())
            }
        }
    }

    private fun say(text: String) {
        notice = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(4_500)
            notice = null
        }
    }

    fun pause() = act { pause() }
    fun resume() = act { resume() }
    fun cancel() = act { cancel() }
    fun print(file: PrintFile) = act { print(file) }

    /** Put the finished job away so the status screen goes back to "Ready". */
    fun dismissOutcome() {
        printer?.let { cleared.edit().putString("job", jobId(it)).apply() }
        outcome = null
    }

    fun connectPrinter() = act {
        // "connect" is ignored while a failed connection is still held open
        if (printer?.phase == Phase.ERROR) runCatching { disconnectPrinter() }
        connectPrinter()
    }

    fun setTool(celsius: Int) = act { setToolTarget(celsius.coerceIn(0, 300)) }
    fun setBed(celsius: Int) = act { setBedTarget(celsius.coerceIn(0, 110)) }
    fun preheat(p: HeatPreset) {
        // OctoPrint refuses a bed temperature outright for a printer that has no heated bed
        val bed = printer?.hasBed != false
        act {
            setToolTarget(p.tool)
            if (bed) setBedTarget(p.bed)
        }
    }
    fun coolDown() {
        val bed = printer?.hasBed != false
        act {
            setToolTarget(0)
            if (bed) setBedTarget(0)
        }
    }

    fun jog(x: Double = 0.0, y: Double = 0.0, z: Double = 0.0) = act { jog(x, y, z) }
    fun home(vararg axes: String) = act { home(*axes) }
    fun motorsOff() = act { gcode("M84") }
    fun extrude(mm: Int) = act { extrude(mm) }
    fun setFan(percent: Int) = act { gcode("M106 S${percent.coerceIn(0, 100) * 255 / 100}") }

    fun changeFeedRate(percent: Int) {
        val v = percent.coerceIn(50, 150)
        feedRate = v
        act { setFeedRate(v) }
    }

    fun changeFlowRate(percent: Int) {
        val v = percent.coerceIn(75, 125)
        flowRate = v
        act { setFlowRate(v) }
    }

    fun answerPrompt(choice: Int) {
        prompt = null
        act { answerPrompt(choice) }
    }

    fun setLight(light: Light, percent: Int) {
        info = info.copy(lights = info.lights.map { if (it.pin == light.pin) it.copy(percent = percent) else it })
        act { setLight(light.pin, percent) }
    }

    /* ---------------- power ---------------- */

    /**
     * Arm, or stand down, switching everything off as soon as the job that is
     * running is done. Only a job that finishes counts: one that is cancelled or
     * fails leaves everything on, since someone will want to look at it.
     */
    fun switchOffWhenDone(on: Boolean) {
        whenDoneJob?.cancel()
        offWhenDone = on && plug != null && printer?.phase?.busy == true
        if (!offWhenDone) return
        // whatever ended before now was some other job
        ended = null
        whenDoneJob = viewModelScope.launch {
            snapshotFlow { printer?.phase?.busy }.first { it != true }
            offWhenDone = false
            // That the printer has gone quiet and how the job ended are told separately, and
            // in either order, so the verdict is waited for rather than assumed.
            val how = withTimeoutOrNull(HOW_IT_ENDED_MS) { snapshotFlow { ended ?: outcome }.first { it != null } }
            if (how?.kind != Outcome.Kind.DONE) return@launch
            // Not at once: the printer still has its last moves to make when the job is called
            // done, and anyone standing at it gets the chance to say no.
            for (left in AFTER_PRINT_PAUSE_S downTo 1) {
                if (printer?.phase?.busy == true) {
                    power = null
                    return@launch
                }
                power = PowerStep.AfterPrint(left)
                delay(1_000)
            }
            power = null
            switchOff()
        }
    }

    /** Put the question; nothing happens until [switchOff]. */
    fun askSwitchOff() {
        if (plug == null || power != null || printer?.phase?.busy == true) return
        power = PowerStep.Confirm
        // what the question says about the display should be true as of now
        viewModelScope.launch { displayCanGoOff = selfPower.available() }
    }

    /**
     * Everything off at the wall: the nozzle cools, the Pi shuts down, and only
     * once the Pi has gone quiet does the plug cut the power. See [PowerOff].
     * The display then switches itself off too, if it is able to.
     */
    fun switchOff() {
        val plug = plug ?: return
        val octo = api ?: return
        // never under a job; the sequence checks again before it touches anything
        if (printer?.phase?.busy == true) return
        if (powerJob?.isActive == true) return
        // What is known of the printer is only as fresh as the connection it came over. With
        // OctoPrint out of touch, a job could have been started from elsewhere unseen.
        if (link != Link.Online) {
            power = PowerStep.Failed("OctoPrint isn't answering, which leaves no way to tell whether a print is running, so everything was left on.")
            return
        }
        powerJob = viewModelScope.launch {
            val rig = object : PowerRig {
                // found while the Pi is still there to be found, and kept
                private var pi: InetAddress? = null

                override fun nozzle() = printer?.tool?.actual
                override fun printing() = printer?.phase?.busy == true
                override suspend fun heatersOff() {
                    octo.setToolTarget(0)
                    octo.setBedTarget(0)
                }
                override suspend fun shutDownPi() = octo.shutDownSystem()
                override suspend fun piAnswers(): Boolean = withContext(Dispatchers.IO) {
                    val address = pi ?: runCatching { dns.lookup(octo.base.host).first() }.getOrNull()?.also { pi = it }
                    address != null && stillAnswers(address, octo.base.port)
                }
                override suspend fun plugAnswers() = attempt { plug.isOn() } != null
                override suspend fun cutPower() {
                    if (plug.switch(on = false)) throw IOException("The plug says it is still on")
                }
            }
            if (PowerOff(rig).run { power = it } != PowerStep.Off) return@launch
            plugOn = false
            // The display goes last, and only once everything behind it really is off.
            if (attempt { selfPower.shutDown() } != null) delay(DISPLAY_OFF_GRACE_MS)
            // still here, so it didn't
            power = null
            say("This display couldn't switch itself off.")
        }
    }

    /** Back out: before the hold or the pause runs out, while the nozzle cools, or after reading why it failed. */
    fun dismissPower() {
        if (power is PowerStep.ShuttingDown || power is PowerStep.Settling || power == PowerStep.Off) return
        if (power is PowerStep.AfterPrint) whenDoneJob?.cancel()
        powerJob?.cancel()
        power = null
    }

    fun switchOn() {
        val plug = plug ?: return
        viewModelScope.launch {
            if (attempt { plug.switch(on = true) } != true) {
                say("The plug didn't answer.")
                return@launch
            }
            plugOn = true
            bootBegan()
        }
    }

    /** The power has come on: for the next few minutes, silence from OctoPrint is just the Pi booting. */
    private fun bootBegan() {
        startingUp = true
        startingJob?.cancel()
        startingJob = viewModelScope.launch {
            delay(STARTUP_MS)
            startingUp = false
        }
    }

    /** Remember the plug at [address], once something there has answered as one. */
    fun usePlug(address: String, onSaved: () -> Unit = {}) {
        val base = normalizeBase(address)
        if (base == null) {
            plugProblem = "That doesn't look like an address. Try something like 192.168.1.60."
            return
        }
        viewModelScope.launch {
            plugProblem = null
            if (attempt { Plug(base).isOn() } == null) {
                plugProblem = "No Tasmota plug answered at ${hostOf(address)}."
                return@launch
            }
            prefs.setPlug(base.toString())
            onSaved()
        }
    }

    fun forgetPlug() = viewModelScope.launch { prefs.setPlug(null) }

    fun clearPlugProblem() {
        plugProblem = null
    }

    /* ---------------- preferences ---------------- */

    fun setGround(argb: Int) = viewModelScope.launch { prefs.setGround(argb) }
    fun setInk(argb: Int) = viewModelScope.launch { prefs.setInk(argb) }
    fun setAccent(argb: Int) = viewModelScope.launch { prefs.setAccent(argb) }
    fun setFilament(id: String) = viewModelScope.launch { prefs.setFilament(id) }
    fun setClock24(v: Boolean) = viewModelScope.launch { prefs.setClock24(v) }
    fun setOffAfter(min: Int) = viewModelScope.launch { prefs.setOffAfter(min) }
    fun setView(v: String) = viewModelScope.launch { prefs.setView(v) }
    fun forgetServer() = viewModelScope.launch { prefs.forgetServer() }

    /* ---------------- first-run setup ---------------- */

    var setup: SetupState by mutableStateOf(SetupState.Idle)
        private set
    var found: List<FoundServer> by mutableStateOf(emptyList())
        private set
    private var setupJob: Job? = null
    private var discoveryJob: Job? = null

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = viewModelScope.launch {
            discoverOctoPrint().collect { f ->
                if (found.none { it.url == f.url }) found = found + f
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    /**
     * The places an address might mean. Someone typing "octopi.local" hasn't
     * said http or https, and OctoPrint's own network announcement names port 80
     * even on a Pi that only answers on https, so both are worth a knock.
     *
     * https gets the first knock, so that an access key doesn't cross the network
     * in the clear where it needn't. The exception is an address someone typed
     * with "http://" on the front; one that was [announced] says http whatever
     * the Pi serves, and counts for nothing.
     */
    private fun candidates(address: String, announced: Boolean = false): List<HttpUrl> {
        val given = normalizeBase(address) ?: return emptyList()
        val typedPort = Regex(":\\d+").containsMatchIn(address.substringAfter("://"))
        fun over(scheme: String) = given.newBuilder().scheme(scheme)
            .port(if (typedPort) given.port else HttpUrl.defaultPort(scheme))
            .build()
        val httpAsked = !announced && address.trim().startsWith("http://", ignoreCase = true)
        return if (httpAsked) listOf(over("http"), over("https")) else listOf(over("https"), over("http"))
    }

    /** The first candidate that answers as OctoPrint, with the client set up to talk to it. */
    private suspend fun locate(address: String, announced: Boolean): Located {
        var answered = false
        for (base in candidates(address, announced)) {
            val octo = OctoApi(base, null, dns, ServerTrust(pinned = null, learning = true))
            try {
                val home = octo.keyOffice()
                if (home == null) {
                    answered = true
                    continue
                }
                return Located.Found(if (home == base) octo else OctoApi(home, null, dns, octo.trust))
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                // nothing there on this scheme; try the next
            }
        }
        return if (answered) Located.NotOctoPrint else Located.Silent
    }

    private sealed interface Located {
        class Found(val api: OctoApi) : Located
        data object NotOctoPrint : Located
        data object Silent : Located
    }

    private fun hostOf(address: String): String = normalizeBase(address)?.host ?: address

    /**
     * Ask the server at [address] for an access key and wait for someone to approve it.
     * [announced] is for an address that came from the server's own announcement on the
     * network and not from someone's typing.
     */
    fun requestAccess(address: String, name: String = "", announced: Boolean = false) {
        setupJob?.cancel()
        if (normalizeBase(address) == null) {
            setup = SetupState.Failed("That doesn't look like an address. Try something like 192.168.1.50.")
            return
        }
        val host = hostOf(address)
        setupJob = viewModelScope.launch {
            setup = SetupState.Checking(host)
            try {
                val octo = when (val where = locate(address, announced)) {
                    is Located.Found -> where.api
                    Located.NotOctoPrint -> {
                        setup = SetupState.Failed("Something answered at $host, but it isn't handing out access keys. Paste a key from OctoPrint's settings instead.")
                        return@launch
                    }
                    Located.Silent -> {
                        setup = SetupState.Failed("Nothing answered at $host. Check the address and that the Pi is on.")
                        return@launch
                    }
                }
                val url = octo.base.toString()
                val token = octo.requestKey(APP_NAME)
                setup = SetupState.Waiting(url)
                while (true) {
                    delay(1_200)
                    when (val r = octo.pollKey(token)) {
                        KeyPoll.Pending -> Unit
                        is KeyPoll.Granted -> {
                            prefs.saveServer(Server(url, r.key, name, octo.trust.learned))
                            setup = SetupState.Idle
                            return@launch
                        }
                        KeyPoll.Denied -> {
                            setup = SetupState.Failed("The request was declined or ran out of time. Ask again when OctoPrint is open in a browser.")
                            return@launch
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OctoHttpException) {
                setup = SetupState.Failed(
                    if (e.code == 429) "OctoPrint is limiting how often keys can be requested. Wait a minute, then ask again."
                    else "OctoPrint at $host answered with an error (${e.code})."
                )
            } catch (_: IOException) {
                setup = SetupState.Failed("The connection to $host dropped. Try again.")
            }
        }
    }

    /** Skip the approval step with a key copied out of OctoPrint's settings. */
    fun useKey(address: String, key: String, name: String = "") {
        setupJob?.cancel()
        val trimmed = key.trim()
        if (normalizeBase(address) == null || trimmed.isEmpty()) {
            setup = SetupState.Failed("Enter both the address and the key.")
            return
        }
        val host = hostOf(address)
        setupJob = viewModelScope.launch {
            setup = SetupState.Checking(host)
            // Judged by the key alone, not by the app-key plugin: someone pasting a key may be
            // doing so precisely because that plugin is switched off.
            var refused = false
            for (base in candidates(address)) {
                val octo = OctoApi(base, trimmed, dns, ServerTrust(pinned = null, learning = true))
                val home = try {
                    octo.keyHome()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OctoHttpException) {
                    if (e.code == 403) refused = true
                    continue
                } catch (_: IOException) {
                    continue
                }
                prefs.saveServer(Server(home.toString(), trimmed, name, octo.trust.learned))
                setup = SetupState.Idle
                return@launch
            }
            setup = SetupState.Failed(
                if (refused) "OctoPrint didn't accept that key."
                else "Nothing answered at $host. Check the address and that the Pi is on."
            )
        }
    }

    fun cancelSetup() {
        setupJob?.cancel()
        setup = SetupState.Idle
    }

    private companion object {
        /** Shown in OctoPrint's approval prompt and its list of application keys. */
        const val APP_NAME = "OctoShow"
        const val PLUG_POLL_MS = 5_000L
        /** How long after a job stops to wait to be told how it ended. */
        const val HOW_IT_ENDED_MS = 8_000L
        /** How soon a drawing that is wanted again and hasn't come may be asked for once more, and how often it is tried. */
        const val REDRAW_ASK_AGAIN_MS = 60_000L
        const val REDRAW_TRIES = 3
        const val AFTER_PRINT_PAUSE_S = 30
        const val SELF_CHECK_MS = 12 * 3_600_000L
        /** The app is the home screen, so it is running well inside this long after a boot. */
        const val BOOT_WINDOW_MS = 180_000L
        /** A display that is switching itself off is gone within a few seconds. */
        const val DISPLAY_OFF_GRACE_MS = 20_000L
        /** A Pi 3 takes a minute or two from power to OctoPrint answering. */
        const val STARTUP_MS = 240_000L
    }
}

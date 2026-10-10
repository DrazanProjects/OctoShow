package com.drazan.echoshow.octoprint.octo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Where we stand with the OctoPrint server itself (not the printer behind it). */
sealed interface Link {
    data object Connecting : Link
    data object Online : Link
    data object Unreachable : Link
    /** The access key was revoked or never valid; setup has to run again. */
    data object Unauthorized : Link
    /** An https server presented a different certificate from the one seen at setup. */
    data object Untrusted : Link
}

sealed interface LiveEvent {
    data object FilesChanged : LiveEvent
    data class PrintEnded(val outcome: Outcome) : LiveEvent
    data object PrintStarted : LiveEvent
    /** A light was switched somewhere else. */
    data class LightChanged(val pin: Int, val percent: Int) : LiveEvent
}

/**
 * OctoPrint's push socket: one connection that streams printer state about
 * once a second. Reconnects for as long as [run] is alive.
 */
class OctoLive(private val api: OctoApi) {

    val link = MutableStateFlow<Link>(Link.Connecting)
    val printer = MutableStateFlow<PrinterSnapshot?>(null)
    val layers = MutableStateFlow(LayerInfo())
    val temps = MutableStateFlow<List<TempSample>>(emptyList())
    val prompt = MutableStateFlow<Prompt?>(null)
    val events = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 32)

    suspend fun run() {
        while (true) {
            try {
                listen(api.login())
            } catch (e: CancellationException) {
                throw e
            } catch (e: OctoHttpException) {
                // A dead key shows up as 403 on GETs but as 400 on the login POST, so ask plainly.
                if (e.code in 400..403 && api.keyAccepted() == false) {
                    link.value = Link.Unauthorized
                    delay(15_000)
                    continue
                }
                markDown()
            } catch (_: Exception) {
                // unreachable, dropped or garbled: all handled by trying again
                markDown()
            }
            delay(RETRY_MS)
        }
    }

    private fun markDown() {
        if (api.trust.changed) {
            link.value = Link.Untrusted
        } else if (link.value !is Link.Unreachable) {
            link.value = Link.Unreachable
        }
    }

    /** Holds one socket open until it closes or fails. */
    private suspend fun listen(session: Session): Nothing = coroutineScope {
        var current = session
        var socket: WebSocket? = null
        var gotData = false

        // If OctoPrint accepts the socket but never sends state, start over.
        val watchdog = launch {
            delay(FIRST_DATA_MS)
            if (!gotData) socket?.cancel()
        }

        frames(api.socketClient, api.url("sockjs/websocket").toString()).collect { frame ->
            when (frame) {
                is Frame.Open -> socket = frame.socket
                is Frame.Text -> {
                    val msg = runCatching { JSONObject(frame.text) }.getOrNull() ?: return@collect
                    when {
                        msg.has("connected") -> {
                            // No terminal log, and only the plugins we read: keeps each update small.
                            socket?.send(SUBSCRIBE)
                            // 500 ms base interval x2: one update a second is plenty for a wall display.
                            socket?.send(JSONObject().put("throttle", 2).toString())
                            socket?.authenticate(current)
                        }
                        msg.has("reauthRequired") -> {
                            current = api.login()
                            socket?.authenticate(current)
                        }
                        msg.has("history") -> {
                            gotData = true
                            msg.obj("history")?.let { applyState(it, replaceTemps = true) }
                            link.value = Link.Online
                        }
                        msg.has("current") -> {
                            gotData = true
                            msg.obj("current")?.let { applyState(it, replaceTemps = false) }
                            link.value = Link.Online
                        }
                        msg.has("event") -> msg.obj("event")?.let(::applyEvent)
                        msg.has("plugin") -> msg.obj("plugin")?.let(::applyPlugin)
                    }
                }
            }
        }
        watchdog.cancel()
        throw IOException("Socket closed")
    }

    private fun WebSocket.authenticate(s: Session) {
        send(JSONObject().put("auth", "${s.name}:${s.session}").toString())
    }

    /* ---------------- state ---------------- */

    private fun applyState(d: JSONObject, replaceTemps: Boolean) {
        val prev = printer.value
        val state = d.obj("state")
        val flags = state?.obj("flags")
        val text = state?.str("text").orEmpty()
        fun flag(name: String) = flags?.optBoolean(name, false) == true

        val phase = when {
            flag("error") -> Phase.ERROR
            flag("cancelling") -> Phase.CANCELLING
            flag("finishing") -> Phase.FINISHING
            flag("pausing") -> Phase.PAUSING
            flag("resuming") -> Phase.RESUMING
            flag("paused") -> Phase.PAUSED
            flag("printing") -> if (text.startsWith("Starting")) Phase.STARTING else Phase.PRINTING
            flag("operational") -> Phase.READY
            text.startsWith("Offline") || text.isEmpty() -> Phase.OFFLINE
            flag("closedOrError") -> Phase.ERROR
            else -> Phase.CONNECTING
        }

        val job = d.obj("job")
        val file = job?.obj("file")?.let { f ->
            val name = f.str("name") ?: return@let null
            JobFile(
                name = name,
                display = f.str("display") ?: name,
                path = f.str("path") ?: name,
                origin = f.str("origin") ?: "local",
            )
        }
        val progress = d.obj("progress")

        var tool = prev?.tool
        var bed = prev?.bed
        val arr = d.optJSONArray("temps")
        if (arr != null && arr.length() > 0) {
            val fresh = ArrayList<TempSample>(arr.length())
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val t = e.obj("tool0")?.let { Temp(it.dbl("actual")?.toFloat(), it.dbl("target")?.toFloat() ?: 0f) }
                val b = e.obj("bed")?.let { Temp(it.dbl("actual")?.toFloat(), it.dbl("target")?.toFloat() ?: 0f) }
                if (t != null) tool = t
                if (b != null) bed = b
                fresh += TempSample(t?.actual, b?.actual)
            }
            val merged = if (replaceTemps) fresh else temps.value + fresh
            temps.value = if (merged.size > TEMP_KEEP) merged.subList(merged.size - TEMP_KEEP, merged.size).toList() else merged
        }
        if (phase == Phase.OFFLINE) {
            tool = null
            bed = null
        }

        printer.value = PrinterSnapshot(
            phase = phase,
            stateText = text,
            errorText = state?.str("error")?.takeIf { it.isNotBlank() },
            file = file,
            estimatedPrintTime = job?.dbl("estimatedPrintTime"),
            completion = progress?.dbl("completion"),
            printTime = progress?.lng("printTime"),
            printTimeLeft = progress?.lng("printTimeLeft"),
            currentZ = d.dbl("currentZ"),
            tool = tool,
            bed = bed,
        )
    }

    private fun applyEvent(e: JSONObject) {
        val p = e.obj("payload")
        fun ended(kind: Outcome.Kind) = LiveEvent.PrintEnded(
            Outcome(kind, p?.str("name").orEmpty(), p?.lng("time"), System.currentTimeMillis())
        )
        when (e.str("type")) {
            "PrintStarted" -> {
                // Forget where the last job had got to, but keep the layer count: the Dashboard
                // plugin sends the new job's count at this same moment, sometimes just before.
                layers.value = layers.value.copy(current = null, layerProgress = null)
                events.tryEmit(LiveEvent.PrintStarted)
            }
            "PrintDone" -> events.tryEmit(ended(Outcome.Kind.DONE))
            "PrintCancelled" -> events.tryEmit(ended(Outcome.Kind.CANCELLED))
            // a cancel also raises PrintFailed with reason "cancelled"; only real failures count here
            "PrintFailed" -> if (p?.str("reason") != "cancelled") events.tryEmit(ended(Outcome.Kind.FAILED))
            "UpdatedFiles", "FileAdded", "FileRemoved", "FolderAdded", "FolderRemoved",
            "MetadataAnalysisFinished" -> events.tryEmit(LiveEvent.FilesChanged)
        }
    }

    private fun applyPlugin(m: JSONObject) {
        val data = m.obj("data") ?: return
        when (m.str("plugin")) {
            "dashboard" -> applyDashboard(data)
            "LightControls" -> {
                val pin = data.intish("pin") ?: return
                events.tryEmit(LiveEvent.LightChanged(pin, data.intish("value") ?: 0))
            }
            "action_command_prompt" -> prompt.value = when (data.str("action")) {
                "show" -> Prompt(data.str("text").orEmpty(), data.optJSONArray("choices").strings())
                else -> null
            }
        }
    }

    /**
     * Layer progress from the Dashboard plugin. It sends nearly everything as
     * text, and its totalLayers counts one more than there are (the moves before
     * the first layer get a slot), which its own UI subtracts again.
     */
    private fun applyDashboard(d: JSONObject) {
        val cur = layers.value
        val current = d.intish("currentLayer") ?: cur.current
        // A layer starts from nothing, but the plugin doesn't say so: it announces the layer,
        // and its next figure for how far through comes ten moves later. Until then the last
        // layer's would stand, and the new one would look all but finished.
        val carried = if (current != cur.current && cur.layerProgress != null) 0 else cur.layerProgress
        layers.value = cur.copy(
            current = current,
            total = d.intish("totalLayers")?.let { (it - 1).coerceAtLeast(0) } ?: cur.total,
            layerProgress = d.intish("layerProgress")?.coerceIn(0, 100) ?: carried,
            fanPercent = d.intish("fanSpeed")?.coerceIn(0, 100) ?: cur.fanPercent,
        )
    }

    private companion object {
        val SUBSCRIBE: String = JSONObject()
            .put(
                "subscribe",
                JSONObject()
                    .put("state", JSONObject().put("logs", false).put("messages", false))
                    .put("events", true)
                    .put("plugins", JSONArray(listOf("dashboard", "LightControls", "action_command_prompt"))),
            )
            .toString()
        const val RETRY_MS = 4_000L
        const val FIRST_DATA_MS = 12_000L
        /** About ten minutes of history for the little heat traces. */
        const val TEMP_KEEP = 300
    }
}

/** Plugins are loose about types: "12", 12 and "12.0" all show up. "-" means unknown. */
private fun JSONObject.dblish(key: String): Double? =
    if (isNull(key)) null else opt(key)?.toString()?.trim()?.removeSuffix("%")?.toDoubleOrNull()

private fun JSONObject.intish(key: String): Int? = dblish(key)?.toInt()

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }

private sealed interface Frame {
    class Open(val socket: WebSocket) : Frame
    class Text(val text: String) : Frame
}

/** A websocket as a flow: completes on a clean close, throws on failure, cancels the socket when dropped. */
private fun frames(client: OkHttpClient, url: String): Flow<Frame> = callbackFlow {
    val socket = client.newWebSocket(
        Request.Builder().url(url).build(),
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(Frame.Open(webSocket))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                trySend(Frame.Text(text))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }
        },
    )
    awaitClose { socket.cancel() }
}.buffer(Channel.UNLIMITED)

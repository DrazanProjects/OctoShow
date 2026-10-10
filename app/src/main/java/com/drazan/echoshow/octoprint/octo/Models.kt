package com.drazan.echoshow.octoprint.octo

import org.json.JSONObject

/** What the printer is doing, collapsed from OctoPrint's state flags and state text. */
enum class Phase {
    OFFLINE, CONNECTING, ERROR, READY,
    STARTING, PRINTING, PAUSING, PAUSED, RESUMING, CANCELLING, FINISHING;

    /** A job owns the printer: moving the head by hand is off limits. */
    val busy: Boolean get() = this >= STARTING
    val canPause: Boolean get() = this == PRINTING || this == STARTING
    val canCancel: Boolean get() = this == PRINTING || this == STARTING || this == PAUSED || this == PAUSING || this == RESUMING
}

data class Temp(val actual: Float?, val target: Float) {
    val heating: Boolean get() = target > 0f && (actual ?: 0f) < target - 3f
    val on: Boolean get() = target > 0f
}

data class JobFile(
    val name: String,
    val display: String,
    val path: String,
    val origin: String,
)

data class PrinterSnapshot(
    val phase: Phase,
    val stateText: String,
    val errorText: String?,
    val file: JobFile?,
    /** Slicer / analysis estimate for the whole job, seconds. */
    val estimatedPrintTime: Double?,
    /** 0..100, by position in the file. */
    val completion: Double?,
    val printTime: Long?,
    val printTimeLeft: Long?,
    val currentZ: Double?,
    val tool: Temp?,
    val bed: Temp?,
) {
    /**
     * Whether the printer has a heated bed. One that reports a nozzle and no bed has
     * none. One that reports nothing at all, because it isn't connected, is taken to
     * have one, so the screen doesn't rearrange itself each time the printer comes and goes.
     */
    val hasBed: Boolean get() = bed != null || tool == null

    /**
     * How far along, 0..1. By time when OctoPrint has a remaining-time estimate
     * (the same sum PrintTimeGenius uses for its own percentage), because a file
     * position says little about a print whose first layers are the slow ones.
     */
    val fraction: Float
        get() {
            val spent = printTime
            val left = printTimeLeft
            if (spent != null && left != null && spent + left > 0) {
                return (spent.toFloat() / (spent + left)).coerceIn(0f, 1f)
            }
            return ((completion ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
        }
}

/** Layer progress as reported by the Dashboard plugin, when it is installed. */
data class LayerInfo(
    /** 1-based number of the layer being printed. */
    val current: Int? = null,
    val total: Int? = null,
    /** 0..100 through the current layer. */
    val layerProgress: Int? = null,
    val fanPercent: Int? = null,
) {
    val known: Boolean get() = current != null && current > 0 && total != null && total > 0
}

data class TempSample(val tool: Float?, val bed: Float?)

/** How the last job ended, kept until the next one starts. */
data class Outcome(
    val kind: Kind,
    val fileName: String,
    val seconds: Long?,
    val atMillis: Long,
) {
    enum class Kind { DONE, FAILED, CANCELLED }
}

data class PrintFile(
    val name: String,
    val display: String,
    val path: String,
    val origin: String,
    val isFolder: Boolean,
    val size: Long,
    /** Upload time, epoch seconds. */
    val date: Long,
    val estimatedPrintTime: Double?,
    val filamentMm: Double?,
    /** Relative URL of the slicer preview, when the Slicer Thumbnails plugin extracted one. */
    val thumbnail: String?,
    val lastPrintSuccess: Boolean?,
    val children: List<PrintFile> = emptyList(),
)

data class HeatPreset(val name: String, val tool: Int, val bed: Int)

data class Webcam(
    val streamUrl: String,
    /** Where a single still can be fetched, when that can be worked out from the stream address. */
    val snapshotUrl: String? = null,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val rotate90: Boolean = false,
)

data class Light(val name: String, val pin: Int, val dimmable: Boolean, val percent: Int = 0)

/** A question the printer is asking through OctoPrint, e.g. after a filament runout. */
data class Prompt(val text: String, val choices: List<String>)

/** What we learn about the server once, after connecting. */
data class ServerInfo(
    val name: String = "",
    val version: String = "",
    val presets: List<HeatPreset> = emptyList(),
    val webcam: Webcam? = null,
    val lights: List<Light> = emptyList(),
    /** OctoPrint will shut the Pi down when this display asks. */
    val canShutDown: Boolean = false,
)

/** An OctoPrint instance announced on the local network. */
data class FoundServer(val name: String, val url: String)

/* ---- org.json helpers: optString() turns JSON null into the text "null" ---- */

internal fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)
internal fun JSONObject.dbl(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() && !it.isInfinite() }
internal fun JSONObject.lng(key: String): Long? = dbl(key)?.toLong()
internal fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key)

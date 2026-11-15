package com.drazan.echoshow.octoprint.power

/*
 * Switching off from the iPhone app. The phone can't reach this display directly; both reach
 * OctoPrint, and the OctoShow plugin there (octoprint-plugin/) passes the phone's request to the
 * display and the display's progress back. See octoprint-plugin/README.md for the messages.
 */

/** What the phone can ask of this display. */
enum class RemoteAsk(val wire: String) {
    /** Switch everything off now, as if the power button here had been held. */
    OFF("off"),
    /** Switch everything off once the running print finishes. */
    OFF_WHEN_DONE("offWhenDone"),
    /** Leave everything on: back out of whatever can still be backed out of. */
    STAY("stay");

    companion object {
        fun of(wire: String?): RemoteAsk? = entries.firstOrNull { it.wire == wire }
    }
}

/** What this display tells the phone about itself, through the plugin. */
data class RemoteStatus(
    val name: String,
    /** A smart plug is set up here: without one there is nothing to switch off with. */
    val plug: Boolean,
    val plugOn: Boolean?,
    /** This display goes off too at the end. */
    val canGoOff: Boolean,
    val offWhenDone: Boolean,
    /** How far a switch-off has got, as [forRemote] puts it; null when none is under way. */
    val step: Map<String, Any>?,
)

/** A step of switching off as the phone is told it: its kind, and the figure that goes with it. */
fun PowerStep?.forRemote(): Map<String, Any>? = when (this) {
    null -> null
    PowerStep.Confirm -> mapOf("kind" to "confirm")
    is PowerStep.AfterPrint -> mapOf("kind" to "afterPrint", "seconds" to secondsLeft)
    is PowerStep.Cooling -> mapOf("kind" to "cooling", "nozzle" to nozzle)
    PowerStep.ShuttingDown -> mapOf("kind" to "shuttingDown")
    is PowerStep.Settling -> mapOf("kind" to "settling", "seconds" to secondsLeft)
    PowerStep.Off -> mapOf("kind" to "off")
    is PowerStep.Failed -> mapOf("kind" to "failed", "message" to message)
}

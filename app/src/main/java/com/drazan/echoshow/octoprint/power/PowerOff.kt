package com.drazan.echoshow.octoprint.power

import com.drazan.echoshow.octoprint.octo.OctoHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Where switching everything off has got to. */
sealed interface PowerStep {
    /** Asked for, and waiting for the hold that confirms it. */
    data object Confirm : PowerStep
    /** A job that was to end with everything switched off has finished; it starts after [secondsLeft]. */
    data class AfterPrint(val secondsLeft: Int) : PowerStep
    /** Power stays on until the nozzle is cool enough to be left without its fan. */
    data class Cooling(val nozzle: Int) : PowerStep
    /** OctoPrint has been told to shut the Pi down, and the Pi is still answering. */
    data object ShuttingDown : PowerStep
    /** Nothing answers at the Pi's address any more; the power goes after [secondsLeft]. */
    data class Settling(val secondsLeft: Int) : PowerStep
    /** The plug has cut the power. All that may be left is the display itself. */
    data object Off : PowerStep
    data class Failed(val message: String) : PowerStep
}

/** What a switch-off needs from the printer, the Pi and the plug. */
interface PowerRig {
    /** The nozzle's temperature, or null when the printer isn't reporting one. */
    fun nozzle(): Float?
    fun printing(): Boolean
    suspend fun heatersOff()
    /** Throws [OctoHttpException] when OctoPrint won't do it. */
    suspend fun shutDownPi()
    /** Whether anything at all still answers at the Pi's address. */
    suspend fun piAnswers(): Boolean
    suspend fun plugAnswers(): Boolean
    /** Throws when the plug doesn't confirm that it is off. */
    suspend fun cutPower()
}

/**
 * Switches the printer and the Pi off at the wall without pulling the power
 * from under a running Pi.
 *
 * A Pi that has halted can't say so. The nearest thing is silence: by the time
 * nothing answers at its address, all that is left of its shutdown is the last
 * unmounting, so the power is cut once the address has been silent for
 * [settleMillis]. Two things keep that honest. The Pi has to be seen answering
 * before it is told to go, or its silence would mean nothing. And silence only
 * counts while the plug still answers, which shows it isn't this display that
 * has dropped off the network.
 */
class PowerOff(
    private val rig: PowerRig,
    private val pollMillis: Long = 2_000,
    private val settleMillis: Long = 30_000,
    /** How long the Pi gets to go quiet before the whole thing is called off. */
    private val giveUpMillis: Long = 180_000,
    private val coolingGiveUpMillis: Long = 20 * 60_000,
) {

    /** Reports each step as it is reached and gives back the one it ended on. */
    suspend fun run(report: (PowerStep) -> Unit): PowerStep = outcome(report).also(report)

    private suspend fun outcome(report: (PowerStep) -> Unit): PowerStep {
        // Before anything at all is touched: a job that is running keeps its heat.
        if (rig.printing()) return leftOn("A print is running")

        // A hot end left without its fan lets heat creep up and jam the filament.
        report(rig.nozzle()?.takeIf { it >= COOL_BELOW }?.let { PowerStep.Cooling(it.roundToInt()) } ?: PowerStep.ShuttingDown)
        quietly { rig.heatersOff() }
        val coolingSince = now()
        while (true) {
            if (rig.printing()) return leftOn("A print started")
            val nozzle = rig.nozzle() ?: break
            if (nozzle < COOL_BELOW) break
            if (now() - coolingSince > coolingGiveUpMillis) return leftOn("The nozzle isn't cooling down")
            report(PowerStep.Cooling(nozzle.roundToInt()))
            delay(pollMillis)
        }

        report(PowerStep.ShuttingDown)
        if (!rig.plugAnswers()) return leftOn("The plug isn't answering")
        if (!rig.piAnswers()) return leftOn("The Pi couldn't be checked on")
        if (rig.printing()) return leftOn("A print started")

        try {
            rig.shutDownPi()
        } catch (e: CancellationException) {
            throw e
        } catch (e: OctoHttpException) {
            return leftOn(
                when (e.code) {
                    401, 403 -> "OctoPrint won't let this display shut the Pi down"
                    404 -> "OctoPrint has no shutdown command set up"
                    else -> "OctoPrint couldn't shut the Pi down (${e.code})"
                }
            )
        } catch (_: IOException) {
            // It can drop the connection on its way down. The silence below is what counts.
        }

        val asked = now()
        var silentSince: Long? = null
        var witnessed = 0
        while (true) {
            val pi = rig.piAnswers()
            val plug = rig.plugAnswers()
            when {
                pi -> {
                    // still going down, or it was a restart and it is back
                    silentSince = null
                    witnessed = 0
                    report(PowerStep.ShuttingDown)
                }
                plug -> {
                    val since = silentSince ?: now().also { silentSince = it }
                    witnessed++
                    val left = settleMillis - (now() - since)
                    if (left <= 0 && witnessed >= WITNESSES) break
                    report(PowerStep.Settling(ceil(left / 1000.0).toInt().coerceAtLeast(1)))
                }
                // Neither answers: it may be this display that is offline, which proves nothing.
            }
            val waited = now() - asked
            if (pi && waited > giveUpMillis) {
                return PowerStep.Failed("The Pi is still running, so the power was left on.")
            }
            if (!pi && !plug && waited > giveUpMillis + settleMillis) {
                return PowerStep.Failed("The plug stopped answering, so the power was left on.")
            }
            delay(pollMillis)
        }

        return try {
            rig.cutPower()
            PowerStep.Off
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            PowerStep.Failed("The Pi has shut down, but the plug didn't switch off. The power is still on.")
        }
    }

    private fun leftOn(why: String) = PowerStep.Failed("$why, so everything was left on.")

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // a printer that isn't connected has no heaters to switch off
        }
    }

    private fun now(): Long = System.nanoTime() / 1_000_000

    companion object {
        /** Below this the hot end is safe to leave without its fan. */
        const val COOL_BELOW = 50f
        /** Silent checks it takes, on top of the wait, before the Pi is believed to be gone. */
        const val WITNESSES = 3
    }
}

/**
 * Whether the machine at [address] is still running. A refused connection is
 * as good as an open one: something was there to refuse it. Only no reply at
 * all means the machine has gone.
 */
fun stillAnswers(address: InetAddress, port: Int, timeoutMillis: Int = 2_000): Boolean = try {
    Socket().use { it.connect(InetSocketAddress(address, port), timeoutMillis) }
    true
} catch (e: ConnectException) {
    e.message.orEmpty().contains("refused", ignoreCase = true)
} catch (_: IOException) {
    false
}

package com.drazan.echoshow.octoprint

import com.drazan.echoshow.octoprint.octo.OctoHttpException
import com.drazan.echoshow.octoprint.power.PowerOff
import com.drazan.echoshow.octoprint.power.PowerRig
import com.drazan.echoshow.octoprint.power.PowerStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Switching off at the wall. What matters is the order of things and, above
 * all, the cases where the power must be left alone.
 */
class PowerOffTest {

    /** A printer, a Pi and a plug that do as they are told unless a test says otherwise. */
    private class Rig : PowerRig {
        val did = ArrayList<String>()
        var nozzle: Float? = 24f
        var printing = false
        var plugThere = true
        var shutdownRefusedWith: Int? = null
        /** How many checks the Pi keeps answering for after being told to shut down. */
        var answersFor = 2
        /** Lets a test change the world part-way through; called on every check of the Pi. */
        var onCheck: (Int) -> Unit = {}
        private var checks = 0
        private var askedToShutDown = false

        override fun nozzle() = nozzle.also { n -> if (n != null) nozzle = n - 15f }
        override fun printing() = printing
        override suspend fun heatersOff() { did += "heaters off" }
        override suspend fun shutDownPi() {
            shutdownRefusedWith?.let { throw OctoHttpException(it, "no") }
            did += "shutdown"
            askedToShutDown = true
        }
        override suspend fun piAnswers(): Boolean {
            if (!askedToShutDown) return true
            onCheck(++checks)
            return checks <= answersFor
        }
        override suspend fun plugAnswers() = plugThere
        override suspend fun cutPower() { did += "power cut" }
    }

    private fun run(rig: Rig): List<PowerStep> = runBlocking {
        val steps = ArrayList<PowerStep>()
        PowerOff(rig, pollMillis = 5, settleMillis = 60, giveUpMillis = 250, coolingGiveUpMillis = 250)
            .run { if (steps.lastOrNull() != it) steps += it }
        steps
    }

    @Test
    fun thePiIsShutDownAndGivenTimeBeforeThePowerGoes() {
        val rig = Rig().apply { nozzle = 80f }
        val steps = run(rig)

        assertEquals(listOf("heaters off", "shutdown", "power cut"), rig.did)
        assertEquals(PowerStep.Off, steps.last())
        assertTrue("the nozzle should be waited for first", steps.first() is PowerStep.Cooling)
        val kinds = steps.map { it::class }
        assertTrue(
            "cooling, then shutting down, then the wait, then off",
            kinds.indexOf(PowerStep.Cooling::class) < kinds.indexOf(PowerStep.ShuttingDown::class) &&
                kinds.indexOf(PowerStep.ShuttingDown::class) < kinds.indexOf(PowerStep.Settling::class),
        )
    }

    @Test
    fun aPiThatKeepsAnsweringKeepsItsPower() {
        val rig = Rig().apply { answersFor = Int.MAX_VALUE }
        val steps = run(rig)

        assertTrue(steps.last() is PowerStep.Failed)
        assertFalse("power cut" in rig.did)
    }

    @Test
    fun silenceCountsForNothingWhenThePlugIsSilentToo() {
        // what it looks like from a display that has itself dropped off the network
        val rig = Rig()
        rig.onCheck = { rig.plugThere = false }
        val steps = run(rig)

        assertTrue(steps.last() is PowerStep.Failed)
        assertFalse("power cut" in rig.did)
        assertFalse("the wait must not even start", steps.any { it is PowerStep.Settling })
    }

    @Test
    fun aPiThatComesBackStartsTheWaitAgain() {
        // a restart rather than a shutdown: quiet for a while, then answering once more
        val rig = Rig().apply { answersFor = 0 }
        var cameBack = false
        rig.onCheck = { n ->
            if (n == 2) {
                rig.answersFor = 4
                cameBack = true
            }
        }
        val steps = run(rig)

        assertTrue(cameBack)
        val firstWait = steps.indexOfFirst { it is PowerStep.Settling }
        assertTrue("answering again should put it back to shutting down", steps.drop(firstWait).contains(PowerStep.ShuttingDown))
        assertEquals("and only the second silence earns the power cut", PowerStep.Off, steps.last())
    }

    @Test
    fun nothingIsShutDownWhenThePlugIsNotThere() {
        val rig = Rig().apply { plugThere = false }
        val steps = run(rig)

        assertTrue(steps.last() is PowerStep.Failed)
        assertEquals(listOf("heaters off"), rig.did)
    }

    @Test
    fun aRefusalFromOctoPrintLeavesEverythingOn() {
        val rig = Rig().apply { shutdownRefusedWith = 403 }
        val steps = run(rig)

        assertTrue((steps.last() as PowerStep.Failed).message.startsWith("OctoPrint won't let"))
        assertFalse("power cut" in rig.did)
    }

    @Test
    fun aRunningPrintIsNotTouchedAtAll() {
        val rig = Rig().apply {
            nozzle = 215f
            printing = true
        }
        val steps = run(rig)

        assertTrue(steps.last() is PowerStep.Failed)
        assertEquals("not even the heaters", emptyList<String>(), rig.did)
    }

    @Test
    fun aPrintThatStartsWhileCoolingCallsItOff() {
        val rig = Rig().apply { nozzle = 200f }
        var looks = 0
        val watched = object : PowerRig by rig {
            override fun printing() = ++looks > 2
        }
        val steps = runBlocking {
            val out = ArrayList<PowerStep>()
            PowerOff(watched, pollMillis = 5, settleMillis = 60, giveUpMillis = 250).run { out += it }
            out
        }

        assertTrue(steps.last() is PowerStep.Failed)
        assertFalse("shutdown" in rig.did)
        assertFalse("power cut" in rig.did)
    }

    @Test
    fun aConnectionThatDropsDuringShutdownIsNotAFailure() {
        // OctoPrint can go down before it has answered the request that took it down
        val rig = Rig()
        val abrupt = object : PowerRig by rig {
            override suspend fun shutDownPi() {
                rig.shutDownPi()
                throw IOException("connection reset")
            }
        }
        val steps = runBlocking {
            val out = ArrayList<PowerStep>()
            PowerOff(abrupt, pollMillis = 5, settleMillis = 60, giveUpMillis = 250).run { out += it }
            out
        }

        assertEquals(PowerStep.Off, steps.last())
        assertEquals(listOf("heaters off", "shutdown", "power cut"), rig.did)
    }
}

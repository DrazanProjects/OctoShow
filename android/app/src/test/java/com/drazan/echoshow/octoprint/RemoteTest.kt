package com.drazan.echoshow.octoprint

import com.drazan.echoshow.octoprint.power.PowerStep
import com.drazan.echoshow.octoprint.power.RemoteAsk
import com.drazan.echoshow.octoprint.power.forRemote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The words the display and the phone share through the OctoShow plugin (octoprint-plugin/README.md). */
class RemoteTest {

    @Test
    fun everyStepIsToldTheWayThePhoneReadsIt() {
        assertNull((null as PowerStep?).forRemote())
        assertEquals(mapOf("kind" to "confirm"), PowerStep.Confirm.forRemote())
        assertEquals(mapOf("kind" to "afterPrint", "seconds" to 30), PowerStep.AfterPrint(30).forRemote())
        assertEquals(mapOf("kind" to "cooling", "nozzle" to 112), PowerStep.Cooling(112).forRemote())
        assertEquals(mapOf("kind" to "shuttingDown"), PowerStep.ShuttingDown.forRemote())
        assertEquals(mapOf("kind" to "settling", "seconds" to 12), PowerStep.Settling(12).forRemote())
        assertEquals(mapOf("kind" to "off"), PowerStep.Off.forRemote())
        assertEquals(mapOf("kind" to "failed", "message" to "The plug isn't answering, so everything was left on."),
            PowerStep.Failed("The plug isn't answering, so everything was left on.").forRemote())
    }

    @Test
    fun onlyTheThreeRequestsAreHeard() {
        assertEquals(RemoteAsk.OFF, RemoteAsk.of("off"))
        assertEquals(RemoteAsk.OFF_WHEN_DONE, RemoteAsk.of("offWhenDone"))
        assertEquals(RemoteAsk.STAY, RemoteAsk.of("stay"))
        assertNull(RemoteAsk.of("Off"))
        assertNull(RemoteAsk.of("reboot"))
        assertNull(RemoteAsk.of(null))
    }
}

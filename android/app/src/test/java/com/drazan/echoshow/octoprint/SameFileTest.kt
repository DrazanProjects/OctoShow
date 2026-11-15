package com.drazan.echoshow.octoprint

import com.drazan.echoshow.octoprint.octo.JobFile
import com.drazan.echoshow.octoprint.octo.PrintFile
import com.drazan.echoshow.octoprint.octo.SD_CARD
import com.drazan.echoshow.octoprint.octo.sameFileAs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Finding OctoPrint's own copy of a file printed from the printer's SD card. */
class SameFileTest {

    private fun local(path: String, date: Long = 1_000L): PrintFile {
        val name = path.substringAfterLast('/')
        return PrintFile(name, name, path, "local", false, 841_348L, date, 596.0, null, null, null)
    }

    private fun sd(name: String, display: String = name) = JobFile(name, display, name, SD_CARD)

    private val library = listOf(
        local("Spool_Catch_Foot_Pin_PLA_7m45s.gcode", date = 9_000L),
        local("Spool_Holder_Bottom_Clip_PLA_51m19s.gcode", date = 8_000L),
        local("Gears/gear block v2_PLA_1h40m.gcode", date = 7_000L),
        local("calibration_cube_PLA_22m.gcode", date = 6_000L),
        local("Old/Spool_Catch_Foot_Pin_PLA_7m45s.gcode", date = 1_000L),
    )

    @Test
    fun theLongNameFindsIt() {
        val job = sd("SPOOL_~1.GCO", display = "Spool_Catch_Foot_Pin_PLA_7m45s.gcode")
        assertEquals("Spool_Catch_Foot_Pin_PLA_7m45s.gcode", sameFileAs(job, library)?.path)
    }

    @Test
    fun ofTwoWithTheSameNameTheNewestIsTaken() {
        assertEquals(9_000L, sameFileAs(sd("Spool_Catch_Foot_Pin_PLA_7m45s.gcode"), library)?.date)
    }

    @Test
    fun theCaseAndTheExtensionDontMatter() {
        assertEquals("Gears/gear block v2_PLA_1h40m.gcode", sameFileAs(sd("GEAR BLOCK V2_PLA_1H40M.GCO"), library)?.path)
    }

    @Test
    fun aShortNameWillDoWhenOnlyOneFileStartsThatWay() {
        assertEquals("calibration_cube_PLA_22m.gcode", sameFileAs(sd("CALIBR~1.GCO"), library)?.path)
        // the card drops the space from "gear block"
        assertEquals("Gears/gear block v2_PLA_1h40m.gcode", sameFileAs(sd("GEARBL~1.GCO"), library)?.path)
    }

    @Test
    fun aShortNameSeveralFilesCouldHaveFindsNothing() {
        // both spool files shorten to SPOOL_
        assertNull(sameFileAs(sd("SPOOL_~2.GCO"), library))
    }

    @Test
    fun aFileOctoPrintDoesntHaveFindsNothing() {
        assertNull(sameFileAs(sd("Benchy_PLA_1h2m.gcode"), library))
        assertNull(sameFileAs(sd("BENCHY~1.GCO"), library))
    }
}

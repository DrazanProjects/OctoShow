package com.drazan.echoshow.octoprint

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drazan.echoshow.octoprint.octo.CardSpace
import com.drazan.echoshow.octoprint.octo.parseCardReport
import com.drazan.echoshow.octoprint.octo.parseCardSpace
import com.drazan.echoshow.octoprint.ui.storageSize
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Card Transfer plugin's account of the printer's SD card: which files it has, and how full it is. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CardReportTest {

    @Test
    fun theFilesAndTheSpace() {
        val report = parseCardReport(JSONObject("""
            {"card_ready": true,
             "files": {"cone.gcode": "CONE~1.GCO", "Gears/gear block.gcode": "GEARBL~1.GCO"},
             "space": {"total": 7948206080, "free": 6120013824, "used": 1828192256, "cluster": 32768, "when": 1763600000}}
        """))
        assertEquals(mapOf("cone.gcode" to "CONE~1.GCO", "Gears/gear block.gcode" to "GEARBL~1.GCO"), report?.files)
        assertEquals(CardSpace(7_948_206_080L, 6_120_013_824L), report?.space)
        assertEquals(1_828_192_256L, report?.space?.used)
    }

    @Test
    fun whileTheCardCantBeReadItsFilesArentKnown() {
        val report = parseCardReport(JSONObject("""{"card_ready": false, "files": null, "space": null}"""))
        assertNull(report?.files)
        assertNull(report?.space)
        assertEquals(emptyMap<String, String>(), parseCardReport(JSONObject("""{"card_ready": true, "files": {}, "space": null}"""))?.files)
    }

    @Test
    fun aPluginFromBeforeTheListSaysNothing() {
        // 1.3.0 answers ?cards with its test commands' reply, which has no card_ready
        assertNull(parseCardReport(JSONObject("""{"commands": ["@transfer"]}""")))
    }

    @Test
    fun spaceThatDoesntAddUpIsLeftOut() {
        assertNull(parseCardSpace(JSONObject("""{"total": 0, "free": 0}""")))
        assertNull(parseCardSpace(JSONObject("""{"total": 100, "free": 200}""")))
        assertNull(parseCardSpace(JSONObject("""{"total": 100}""")))
    }

    @Test
    fun sizesAreGivenAsOctoPrintsPagesGiveThem() {
        assertEquals("7.4 GB", storageSize(7_948_206_080L))
        assertEquals("1.7 GB", storageSize(1_828_192_256L))
        assertEquals("477 MB", storageSize(500_000_000L))
        assertEquals("12 KB", storageSize(12_288L))
    }
}

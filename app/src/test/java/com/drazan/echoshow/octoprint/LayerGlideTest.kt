package com.drazan.echoshow.octoprint

import com.drazan.echoshow.octoprint.ui.components.FigurePace
import com.drazan.echoshow.octoprint.ui.components.glideMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How long the layer takes to move towards a new figure, given how long that figure took to come. */
class LayerGlideTest {

    @Test
    fun itRunsOnPastTheGapSoTheFillIsStillMovingWhenTheNextFigureComes() {
        // along straight lines ten moves can take seconds, which used to leave the fill standing
        for (gap in listOf(1000L, 2500L, 4000L, 6000L)) {
            assertTrue("after a gap of $gap", glideMillis(gap) > gap)
        }
    }

    @Test
    fun figuresComingQuicklyStillMoveVisibly() {
        assertEquals(900, glideMillis(50L))
    }

    /** One layer's figures going by: a figure, then how long until the next. Gives what was expected of each. */
    private fun FigurePace.layer(startingAt: Long, figures: List<Pair<Int, Long>>): List<Long> {
        var now = startingAt
        return figures.map { (figure, then) ->
            expect(figure, now).also { now += then }
        }
    }

    @Test
    fun aLongGapIsSeenComingFromTheLayerBefore() {
        // quick figures, then ten moves along straight lines that take five seconds, then quick again:
        // the shape of a real layer, where the fill used to stop at the same place every time
        val shape = listOf(10 to 900L, 15 to 900L, 20 to 900L, 25 to 5000L, 30 to 900L, 35 to 900L)
        val pace = FigurePace()
        pace.layer(1_000L, shape)
        val next = pace.layer(100_000L, shape)
        assertEquals("the long gap should be expected as it starts, from the layer before", 5000L, next[3])
        assertEquals("and the quick ones as quick", 900L, next[1])
    }

    @Test
    fun theFirstLayerGoesByTheLastGap() {
        val pace = FigurePace()
        assertEquals("nothing to go on at the very start", 0L, pace.expect(10, 5_000L))
        assertEquals(1_200L, pace.expect(20, 6_200L))
    }

    @Test
    fun aNewLayerStartingOverIsNotCountedAsAGap() {
        val pace = FigurePace()
        pace.expect(90, 1_000L)
        pace.expect(95, 2_000L)
        pace.expect(5, 50_000L)         // the new layer, far lower down
        assertEquals("what came before stands for 90 percent", 1_000L, pace.expect(90, 60_000L))
    }

    @Test
    fun aLongSilenceIsAStoppedPrintAndNotAPace() {
        // a pause of an hour shouldn't leave the next figure taking an hour to show
        assertEquals(8000, glideMillis(3_600_000L))
    }
}

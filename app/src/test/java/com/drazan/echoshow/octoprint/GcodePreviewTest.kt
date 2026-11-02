package com.drazan.echoshow.octoprint

import com.drazan.echoshow.octoprint.octo.GcodePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class GcodePreviewTest {

    private fun draw(gcode: String) = GcodePreview.draw({ gcode.byteInputStream() }, width = 96, height = 96)

    /** Share of the picture's pixels that show any of the part. */
    private fun GcodePreview.Picture.covered(): Float = alpha.count { it.toInt() != 0 }.toFloat() / alpha.size

    /** A solid 20 mm square slab, 2 mm tall, the way a slicer writes one layer after another. */
    private fun slab(label: String = ";TYPE:Outer wall"): String = buildString {
        var z = 0.2f
        while (z <= 2.01f) {
            appendLine("G1 Z%.2f F600".format(z))
            appendLine(label)
            var y = 100f
            while (y <= 120f) {
                appendLine("G1 X100 Y%.2f F6000".format(y))
                appendLine("G1 X120 Y%.2f E0.6".format(y))
                y += 0.4f
            }
            z += 0.2f
        }
    }

    private val start = """
        ;TYPE:Custom
        G28
        G92 E0
        G1 X-2 Y20 Z0.28 F5000
        G1 X-2 Y200 Z0.28 F1500 E15 ; purge line down the edge of the bed
        G90
        M83
    """.trimIndent() + "\n"

    @Test
    fun drawsThePartAndFillsThePictureWithIt() {
        val picture = draw(start + slab())
        assertNotNull(picture)
        // If the purge line counted, the part would be a speck in a picture framed around the whole bed.
        assertTrue("part should fill a good share of the picture, covered ${picture!!.covered()}", picture.covered() > 0.25f)
    }

    @Test
    fun leavesOutWhatIsPrintedAroundThePart() {
        val alone = draw(start + slab())!!.covered()
        val withSkirt = draw(
            start + ";TYPE:Skirt\nG1 Z0.2\nG1 X60 Y60 F6000\nG1 X160 Y60 E3\nG1 X160 Y160 E3\nG1 X60 Y160 E3\nG1 X60 Y60 E3\n" + slab()
        )!!.covered()
        assertEquals("a skirt far from the part must not change the framing", alone, withSkirt, 0.02f)
    }

    @Test
    fun fallsBackToEverythingWhenNothingIsLabelled() {
        val unlabelled = start.replace(";TYPE:Custom\n", "") + slab(label = "; no labels in this file")
        assertNotNull(draw(unlabelled))
    }

    @Test
    fun followsArcs() {
        // one full circle of radius 10, drawn as two half-circle arc moves
        val ring = "M83\nG90\nG1 Z0.2\n;TYPE:Outer wall\nG1 X110 Y100 F6000\nG2 X90 Y100 I-10 J0 E1\nG2 X110 Y100 I10 J0 E1\n"
        val picture = draw(ring)
        assertNotNull(picture)
        // a ring is mostly hole: some of the picture is covered, but nothing like a filled disc
        assertTrue(picture!!.covered() in 0.02f..0.35f)
    }

    @Test
    fun understandsAbsoluteExtrusionAndIgnoresRetractions() {
        val absolute = "M82\nG90\nG92 E0\nG1 Z0.2\n;TYPE:Outer wall\nG1 X100 Y100 F6000\nG1 X120 Y100 E1\nG1 X120 Y120 E0.5\nG1 X100 Y120 E0.5\n"
        // the second and third moves pull filament back or hold it still: only the first lays any
        assertNotNull(draw(absolute))
        assertNull("moves that lay no plastic are not a part", draw("M83\nG1 Z0.2\nG1 X100 Y100\nG1 X120 Y120\nG1 X90 Y90 E-1\n"))
    }

    @Test
    fun stopsWhenTold() {
        assertNull(GcodePreview.draw({ (start + slab()).byteInputStream() }, width = 96, height = 96, keepGoing = { false }))
    }

    @Test
    fun aPartHalfPrintedIsShorterWithItsTopLayerMarked() {
        val shape = GcodePreview.shape({ (start + slab()).byteInputStream() })!!
        val whole = GcodePreview.render(shape, width = 96, height = 96)!!
        val half = GcodePreview.render(shape, width = 96, height = 96, upTo = shape.top / 2)!!

        assertTrue("half a slab should cover less of the picture than a whole one", half.covered() < whole.covered())
        assertTrue("the layer being laid should be marked", half.fresh.any { it.toInt() != 0 })
        assertTrue("a finished part has no layer being laid", whole.fresh.all { it.toInt() == 0 })
    }

    @Test
    fun everyStageSitsInsideTheFinishedPicture() {
        // or the growing part would wander about inside its own ghost
        val shape = GcodePreview.shape({ (start + slab()).byteInputStream() })!!
        val whole = GcodePreview.render(shape, width = 96, height = 96)!!
        val covered = whole.alpha.count { it.toInt() != 0 }
        for (tenths in 1..9) {
            val stage = GcodePreview.render(shape, width = 96, height = 96, upTo = shape.top * tenths / 10)!!
            val outside = stage.alpha.indices.count { stage.alpha[it].toInt() != 0 && whole.alpha[it].toInt() == 0 }
            // a handful of pixels along an edge can fall either way; a part that had moved would be out by hundreds
            assertTrue("at $tenths tenths of the height, $outside pixels fell outside", outside <= covered / 100)
        }
    }

    @Test
    fun drawingTheShapeIsTheSameAsDrawingTheFile() {
        val gcode = start + slab()
        val direct = draw(gcode)!!
        val viaShape = GcodePreview.render(GcodePreview.shape({ gcode.byteInputStream() })!!, width = 96, height = 96)!!
        assertTrue(direct.light.contentEquals(viaShape.light) && direct.alpha.contentEquals(viaShape.alpha))
    }

    /* ---------------- the order a layer is laid in ---------------- */

    /**
     * The slab as OctoPrint stores it once the Dashboard plugin has marked its layers,
     * each one an outline and then lines filling it in from the front to the back.
     */
    private fun markedSlab(scarf: Boolean = false): String = buildString {
        var layer = 0
        var z = 0.2f
        while (z <= 2.01f) {
            layer++
            appendLine(";LAYER_CHANGE")
            appendLine("M117 DASHBOARD_LAYER_INDICATOR $layer")
            appendLine("G1 Z%.2f F600".format(z))
            appendLine(";TYPE:Outer wall")
            appendLine("G1 X100 Y100 F6000")
            if (scarf) {
                // OrcaSlicer's scarf seam: the wall sets off at the height of the layer below
                // and climbs to its own over the first stretch
                appendLine("G1 X104 Y100 Z%.2f E0.1".format(z - 0.19f))
                appendLine("G1 X108 Y100 Z%.2f E0.1".format(z))
                appendLine("G1 X120 Y100 E0.4")
            } else {
                appendLine("G1 X120 Y100 E0.6")
            }
            appendLine("G1 X120 Y120 E0.6")
            appendLine("G1 X100 Y120 E0.6")
            appendLine("G1 X100 Y100 E0.6")
            appendLine(";TYPE:Solid infill")
            var y = 100.4f
            while (y < 119.8f) {
                appendLine("G1 X100.4 Y%.2f F6000".format(y))
                appendLine("G1 X119.6 Y%.2f E0.6".format(y))
                y += 0.4f
            }
            z += 0.2f
        }
    }

    private fun read(gcode: String) = GcodePreview.read({ gcode.byteInputStream() })!!

    /** When in its layer the spot of bed at ([x], [y]) is laid: 1..255, or 0 for never. */
    private fun GcodePreview.Route.moment(layer: GcodePreview.Route.Layer, x: Float, y: Float): Int {
        val col = ((x - originX) / cell).toInt() - layer.left
        val row = ((y - originY) / cell).toInt() - layer.top
        if (col !in 0 until layer.width || row !in 0 until layer.height) return 0
        return layer.unpacked()[row * layer.width + col].toInt() and 0xFF
    }

    @Test
    fun aFileThePluginNeverMarkedHasNoRoute() {
        // without its marks there is no telling which layer its figures would be about
        assertTrue(read(start + slab()).route.layers.isEmpty())
    }

    @Test
    fun theRouteIsTheOrderTheNozzleGoesOverTheLayer() {
        val route = read(start + markedSlab()).route
        assertEquals("one for each layer the plugin marked", 10, route.layers.size)
        val layer = route.layers.first { it.number == 4 }
        assertEquals(0.8f, layer.z, 0.001f)

        val outline = route.moment(layer, 110f, 100f)
        val front = route.moment(layer, 110f, 104f)
        val middle = route.moment(layer, 110f, 110f)
        val back = route.moment(layer, 110f, 116f)
        assertTrue("the outline goes down first: $outline, then $front", outline in 1 until front)
        assertTrue("and the filling follows from front to back: $front, $middle, $back", front < middle && middle < back)
        assertTrue("half way back is about half way through, not $middle", middle in 108..148)
        assertEquals("nothing is laid outside the part", 0, route.moment(layer, 97f, 110f))
    }

    @Test
    fun aSeamThatClimbsIntoItsLayerDoesNotLowerTheLayer() {
        val reading = read(start + markedSlab(scarf = true))
        val layer = reading.route.layers.first { it.number == 4 }
        assertEquals("the layer is where most of it is laid, not where its seam starts", 0.8f, layer.z, 0.001f)
        val picture = GcodePreview.render(reading.shape, reading.route, layer, width = 96, height = 96)!!
        val laying = picture.fresh.count { it.toInt() != 0 }
        val timed = picture.fresh.indices.count { picture.fresh[it].toInt() != 0 && picture.reached!![it].toInt() != 0 }
        assertTrue("the whole layer should show as the one being laid: $timed of $laying", laying > 200 && timed >= laying * 95 / 100)
    }

    @Test
    fun aLayerIsMeasuredInMovesTheWayThePluginMeasuresIt() {
        // Eleven moves, of which the tenth lays the only plastic. The plugin would call the
        // layer 81% done as that line starts and 90% as it ends, though it is the whole part.
        val gcode = buildString {
            appendLine("M83")
            appendLine("G1 X5 Y5 F6000 ; before the first layer, so not counted in it")
            appendLine(";LAYER_CHANGE")
            appendLine("M117 DASHBOARD_LAYER_INDICATOR 1")
            appendLine("G1 Z0.2 F600")
            appendLine("G28 ; homing is not a move")
            appendLine("G92 E0")
            repeat(8) { appendLine("G0 X${50 + it} Y50 F6000") }
            appendLine(";TYPE:Outer wall")
            appendLine("G1 X120 Y50 E2")
            appendLine("G1 X10 Y10 F6000")
        }
        val route = read(gcode).route
        val layer = route.layers.single()
        val start = route.moment(layer, 58f, 50f)
        val end = route.moment(layer, 119f, 50f)
        assertTrue("the line starts 9 moves of 11 in, at about 209 of 255, not $start", start in 205..213)
        assertTrue("and ends 10 of 11 in, at about 232, not $end", end in 228..236)
    }

    @Test
    fun aPictureOfALayerSaysWhenEachBitOfItIsLaid() {
        val reading = read(start + markedSlab())
        val layer = reading.route.layers.first { it.number == 5 }
        val picture = GcodePreview.render(reading.shape, reading.route, layer, width = 96, height = 96)!!
        val reached = picture.reached!!
        val trail = picture.trail!!

        val laying = picture.fresh.indices.filter { picture.fresh[it].toInt() != 0 }
        assertTrue("the layer should show", laying.size > 200)
        val timed = laying.count { reached[it].toInt() != 0 }
        assertTrue("only $timed of ${laying.size} pixels of the layer have a time", timed >= laying.size * 95 / 100)
        // the filling runs from the front of the part to the back, which is up the picture
        val early = laying.filter { (reached[it].toInt() and 0xFF) in 40..90 }.map { it / 96 }.average()
        val late = laying.filter { (reached[it].toInt() and 0xFF) in 170..220 }.map { it / 96 }.average()
        assertTrue("what is laid later should be further up the picture: $early then $late", late < early - 4)

        // And the nozzle is where the picture says plastic is going down at that moment. Right
        // at the end of a line the spot under it belongs to the outline, laid long before, so
        // this looks a few pixels around.
        for (m in listOf(60, 127, 200)) {
            val x = (trail[2 * m] * 96).toInt()
            val y = (trail[2 * m + 1] * 96).toInt()
            val near = (-3..3).flatMap { dy -> (-3..3).map { dx -> (y + dy) * 96 + x + dx } }
                .filter { it in reached.indices }
                .map { reached[it].toInt() and 0xFF }
            assertTrue(
                "at moment ${m + 1} the nozzle is at $x, $y, where what is laid is laid at ${near.filter { it != 0 }.distinct().sorted()}",
                near.any { it != 0 && abs(it - (m + 1)) <= 8 },
            )
        }
    }

    @Test
    fun theLastLayerIsShownBeingLaidToo() {
        val reading = read(start + markedSlab())
        val picture = GcodePreview.render(reading.shape, reading.route, reading.route.layers.last(), width = 96, height = 96)!!
        assertTrue(picture.fresh.any { it.toInt() != 0 } && picture.reached!!.any { it.toInt() != 0 })
    }

    /* ---------------- what the part really is ---------------- */

    /** Layers of straight lines side by side, [wide] mm across and running between two points, from [from] to [to] mm up. */
    private fun block(x0: Float, y0: Float, x1: Float, y1: Float, wide: Float, from: Float, to: Float, label: String = ";TYPE:Outer wall") = buildString {
        val length = hypot(x1 - x0, y1 - y0)
        val acrossX = (y0 - y1) / length
        val acrossY = (x1 - x0) / length
        var z = from
        while (z <= to + 0.01f) {
            appendLine("G1 Z%.2f F600".format(z))
            appendLine(label)
            var off = -wide / 2
            while (off <= wide / 2) {
                appendLine("G1 X%.3f Y%.3f F6000".format(x0 + acrossX * off, y0 + acrossY * off))
                appendLine("G1 X%.3f Y%.3f E0.6".format(x1 + acrossX * off, y1 + acrossY * off))
                off += 0.4f
            }
            z += 0.2f
        }
    }

    /** How much of its width and height a picture's part reaches across. */
    private fun GcodePreview.Picture.spans(): Pair<Float, Float> {
        val xs = alpha.indices.filter { alpha[it].toInt() != 0 }.map { it % width }
        val ys = alpha.indices.filter { alpha[it].toInt() != 0 }.map { it / width }
        return (xs.max() - xs.min() + 1f) / width to (ys.max() - ys.min() + 1f) / height
    }

    private fun GcodePreview.Picture.meanLight(): Float {
        val shown = alpha.indices.filter { alpha[it].toInt() and 0xFF == 255 }
        return shown.map { light[it].toInt() and 0xFF }.average().toFloat()
    }

    @Test
    fun aLongPartLaidCornerToCornerStillFillsThePicture() {
        // a bar 280 mm long and 30 wide, the way a slicer fits it onto a 220 mm bed
        val picture = GcodePreview.draw({ ("M83\n" + block(10f, 10f, 208f, 208f, 30f, 0.2f, 4f)).byteInputStream() })!!
        assertTrue("it should be the picture's shape, not a square", picture.width > picture.height)
        val (wide, tall) = picture.spans()
        assertTrue("it should reach right across the picture, not ${wide * 100}%", wide > 0.9f)
        assertTrue("and be turned to lie across it, not stand ${tall * 100}% of the way up", tall < 0.6f)
        // which is all a bar that shape can cover of a picture this shape
        assertTrue("so there is a good deal of it, not ${picture.covered() * 100}%", picture.covered() > 0.08f)
    }

    @Test
    fun aPartIsMeasuredAlongItsOwnLength() {
        val shape = GcodePreview.shape({ ("M83\n" + block(10f, 10f, 208f, 208f, 30f, 0.2f, 4f)).byteInputStream() })!!
        val view = shape.view()
        assertEquals(280f, view.long, 3f)
        assertEquals(30f, view.broad, 3f)
        assertEquals(4f, view.high, 0.3f)
    }

    /** A roof 30 mm square, 8 to 10 mm up, held by a pillar in the middle of it. */
    private val mushroom = "M83\n" + block(100f, 110f, 104f, 110f, 4f, 0.2f, 10f) + block(87f, 110f, 117f, 110f, 30f, 8.2f, 10f)

    @Test
    fun theAirUnderAnOverhangIsAir() {
        val shape = GcodePreview.shape({ mushroom.byteInputStream() })!!
        val whole = GcodePreview.render(shape, width = 96, height = 96)!!
        val low = GcodePreview.render(shape, width = 96, height = 96, upTo = 5f)!!
        // with only one height for each spot of bed, the roof would have come up with the pillar
        assertTrue("at 5 mm only the pillar stands: ${low.covered()} of ${whole.covered()}", low.covered() < whole.covered() / 5)
        val stack = shape.stack
        val s = stack.of(4f)
        val beside = ((90f - shape.originX) / shape.cell).toInt()
        val row = ((110f - shape.originY) / shape.cell).toInt()
        assertFalse("nothing is laid under the roof", stack.solid(beside, row, s))
        assertTrue("the roof itself is there", stack.solid(beside, row, stack.of(9f)))
    }

    @Test
    fun whatOverhangsAWallFromFarAboveLeavesItTheSameAllAlong() {
        // a block, a shaft on it, and on that a roof reaching past the block's sides high above them,
        // like a gear on a block: the wall under the roof used to go dark in a band from top to bottom
        val gcode = "M83\n" + block(100f, 120f, 140f, 120f, 30f, 0.2f, 8f) +
            block(115f, 120f, 125f, 120f, 10f, 8.2f, 16f) + block(110f, 120f, 130f, 120f, 40f, 16.2f, 20f)
        val p = GcodePreview.draw({ gcode.byteInputStream() })!!
        // along the foot of the wall on the right of the picture, which stands square to the block
        val feet = (0 until p.width).map { x -> (p.height - 1 downTo 0).firstOrNull { y -> p.alpha[y * p.width + x].toInt() != 0 } }
        val covered = feet.indices.filter { feet[it] != null }
        val corner = covered.maxBy { feet[it]!! }
        val along = (corner + 6..covered.max() - 6).map { x ->
            p.light[(feet[x]!! - 4) * p.width + x].toInt() and 0xFF
        }
        assertTrue("the wall should be the same all along, not ${along.min()} to ${along.max()}", along.max() - along.min() <= 20)
    }

    @Test
    fun noLayerIsLeftEmpty() {
        val shape = GcodePreview.shape({ (start + markedSlab(scarf = true)).byteInputStream() })!!
        assertEquals("the climbing seams don't make the layers look thinner", 0.2f, shape.stack.slice, 0.001f)
        assertTrue(shape.stack.part.all { it != null })
    }

    @Test
    fun supportsAreDrawnDimmerThanThePart() {
        val part = block(100f, 110f, 120f, 110f, 20f, 0.2f, 4f)
        val support = block(130f, 110f, 150f, 110f, 20f, 0.2f, 4f, label = ";TYPE:Support")
        val helped = draw("M83\n$part$support")!!
        val shown = draw("M83\n$part" + support.replace(";TYPE:Support", ";TYPE:Outer wall"))!!
        assertEquals("they should show as much as the same thing in the part would", shown.covered(), helped.covered(), 0.02f)
        assertTrue("and be dimmer than the part: ${helped.meanLight()}, ${shown.meanLight()}", helped.meanLight() < shown.meanLight() - 15f)
    }

    @Test
    fun aBrimIsDrawnAndASkirtIsNot() {
        val part = block(100f, 110f, 120f, 110f, 20f, 0.2f, 4f)
        val around = "G1 X95 Y95 Z0.2 F6000\nG1 X125 Y95 E3\nG1 X125 Y125 E3\nG1 X95 Y125 E3\nG1 X95 Y95 E3\n"
        val shape = { label: String -> GcodePreview.shape({ "M83\n$label\n$around$part".byteInputStream() })!! }
        val brim = shape(";TYPE:Brim")
        assertTrue("a brim is printed with the part", brim.stack.extra[0] != null)
        for (skirt in listOf(";TYPE:Skirt", ";TYPE:Skirt/Brim")) assertTrue(shape(skirt).stack.extra.all { it == null })
    }

    @Test
    fun aPartCanBeLookedAtFromEverySide() {
        val lopsided = mushroom + block(120f, 110f, 140f, 110f, 4f, 0.2f, 3f)
        val shape = GcodePreview.shape({ lopsided.byteInputStream() })!!
        val front = GcodePreview.render(shape, width = 96, height = 96)!!
        val back = GcodePreview.render(shape, width = 96, height = 96, turn = 2)!!
        assertTrue("it should be seen differently", !front.light.contentEquals(back.light))
        for (turn in 1 until GcodePreview.TURNS) {
            val seen = GcodePreview.render(shape, width = 96, height = 96, turn = turn)!!
            assertTrue("from side $turn there should be as much to see", seen.covered() > front.covered() / 2)
        }
    }

    @Test
    fun theShadowIsNotCountedAsThePart() {
        val picture = draw(start + slab())!!
        assertTrue("the part stands in a shadow", picture.shadow.any { it.toInt() != 0 })
        val outside = picture.shadow.indices.first { picture.shadow[it].toInt() != 0 && picture.alpha[it].toInt() == 0 }
        val shown = picture.shown(outside)
        assertTrue("which shows as a bit of black", shown ushr 24 != 0 && shown and 0xFFFFFF == 0)
    }
}

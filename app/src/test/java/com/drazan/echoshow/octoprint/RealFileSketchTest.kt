package com.drazan.echoshow.octoprint

import com.drazan.echoshow.octoprint.octo.GcodePreview
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot

/**
 * A layer of a real file filling in, for seeing whether the order the display
 * works out looks like a printer at work. The file has to be one OctoPrint has
 * stored, since it is the Dashboard plugin's marks in it that say where layers
 * begin.
 *
 *   gradlew :app:testDebugUnitTest --tests "*RealFileSketchTest*" -Psketch=C:\path\to\file.gcode
 *
 * Pictures land in app/build/outputs/sketches as real-LLL-PPP.png, one for
 * every two hundredths of each layer looked at. No display and no mock: this
 * is only the reading and the drawing.
 */
class RealFileSketchTest {

    private val out = File("build/outputs/sketches").apply { mkdirs() }

    @Test
    fun aLayerOfARealFileFillingIn() {
        val file = File(System.getProperty("sketch").orEmpty())
        assumeTrue("ask for it with -Psketch=<a .gcode file from OctoPrint>", file.isFile && file.extension == "gcode")

        val began = System.currentTimeMillis()
        val reading = GcodePreview.read({ file.inputStream() })!!
        val route = reading.route
        println("read ${file.length() / 1024 / 1024} MB in ${System.currentTimeMillis() - began} ms")
        println("shape ${reading.shape.cols} x ${reading.shape.rows}, route ${route.cols} x ${route.rows}, ${route.layers.size} layers")
        println("route packs to ${route.layers.sumOf { it.packed.size } / 1024} KB, from ${route.layers.sumOf { it.width * it.height } / 1024} KB; " +
            "largest layer ${route.layers.maxOfOrNull { it.packed.size }} bytes")
        assumeTrue("the file has none of the plugin's layer marks", route.layers.isNotEmpty())

        // one layer from low in the part and one from half way up
        for (number in listOf(route.layers.size / 8, route.layers.size / 2)) {
            val layer = route.layers.firstOrNull { it.number == number } ?: continue
            val drawing = System.currentTimeMillis()
            val p = GcodePreview.render(reading.shape, route, layer, SIZE)!!
            println("layer $number at ${layer.z} mm: ${layer.width} x ${layer.height} squares, drawn in ${System.currentTimeMillis() - drawing} ms")
            val reached = p.reached!!
            val trail = p.trail!!
            for (done in 0..100 step 2) {
                val upTo = 1 + done * (GcodePreview.MOMENTS - 1) / 100
                val image = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB)
                val nx = trail[2 * (upTo - 1)] * SIZE
                val ny = trail[2 * (upTo - 1) + 1] * SIZE
                for (i in 0 until SIZE * SIZE) {
                    val cover = (p.alpha[i].toInt() and 0xFF) / 255f
                    val light = (p.light[i].toInt() and 0xFF) / 255f
                    var r = 0xD8 * light; var g = 0xD4 * light; var b = 0xCC * light
                    val fresh = (p.fresh[i].toInt() and 0xFF) / 255f
                    val moment = reached[i].toInt() and 0xFF
                    if (fresh > 0f && moment != 0 && moment <= upTo) {
                        // down already: ember, brighter the nearer the nozzle
                        val glow = (1f - hypot(i % SIZE - nx, i / SIZE - ny) / 26f).coerceIn(0f, 1f)
                        val share = fresh * (0.62f + 0.38f * glow)
                        r += (0xFF - r) * share; g += ((0x6B + 0x70 * glow) - g) * share; b += ((0x2C + 0x50 * glow) - b) * share
                    }
                    val rgb = ((0x12 + (r - 0x12) * cover).toInt() shl 16) or ((0x12 + (g - 0x12) * cover).toInt() shl 8) or (0x12 + (b - 0x12) * cover).toInt()
                    image.setRGB(i % SIZE, i / SIZE, rgb)
                }
                ImageIO.write(image, "png", File(out, "real-%03d-%03d.png".format(number, done)))
            }
        }
    }

    private companion object {
        const val SIZE = 320
    }
}

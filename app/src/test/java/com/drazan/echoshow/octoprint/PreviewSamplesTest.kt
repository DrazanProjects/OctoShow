package com.drazan.echoshow.octoprint

import com.drazan.echoshow.octoprint.octo.GcodePreview
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Draws previews for a folder of real G-code so the result can be looked at.
 *
 *   set GCODE_SAMPLES=C:\path\to\folder\of\gcode
 *   gradlew :app:testDebugUnitTest --tests "*PreviewSamplesTest*"
 *
 * Pictures land in app/build/outputs/previews, tinted the display's default
 * filament color on its panel color. Skipped when the folder isn't given.
 */
class PreviewSamplesTest {

    @Test
    fun drawEverySample() {
        val folder = System.getenv("GCODE_SAMPLES")?.let(::File)
        assumeTrue(folder != null && folder.isDirectory)
        val out = File("build/outputs/previews").apply { mkdirs() }

        for (file in folder!!.listFiles { f -> f.extension.lowercase() in setOf("gcode", "gco", "g") }.orEmpty().sortedBy { it.name }) {
            val started = System.currentTimeMillis()
            val picture = GcodePreview.draw({ file.inputStream() })
            println("%-60s %5.1f MB  %5d ms".format(file.name.take(60), file.length() / 1e6, System.currentTimeMillis() - started))
            assertNotNull("nothing drawn for ${file.name}", picture)
            ImageIO.write(onPanel(picture!!), "png", File(out, file.nameWithoutExtension + ".png"))
        }
    }

    /** The picture as the display shows it: filament color times brightness, over the panel color. */
    private fun onPanel(p: GcodePreview.Picture): BufferedImage {
        val filament = intArrayOf(0xE9, 0xE4, 0xD8)
        val panel = intArrayOf(0x12, 0x14, 0x16)
        val image = BufferedImage(p.size, p.size, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until p.size * p.size) {
            val light = (p.light[i].toInt() and 0xFF) / 255f
            val cover = (p.alpha[i].toInt() and 0xFF) / 255f
            var rgb = 0
            for (c in 0..2) {
                val v = (filament[c] * light * cover + panel[c] * (1 - cover)).toInt().coerceIn(0, 255)
                rgb = (rgb shl 8) or v
            }
            image.setRGB(i % p.size, i / p.size, rgb)
        }
        return image
    }
}

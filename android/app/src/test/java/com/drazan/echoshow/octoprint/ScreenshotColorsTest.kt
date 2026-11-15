package com.drazan.echoshow.octoprint

import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drazan.echoshow.octoprint.octo.Phase
import com.drazan.echoshow.octoprint.ui.theme.Accents
import com.drazan.echoshow.octoprint.ui.theme.Grounds
import com.drazan.echoshow.octoprint.ui.theme.Inks
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A walk through other colors, on the screens that show the most of them. See [ScreenWalk].
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w788dp-h394dp-land-195dpi")
class ScreenshotColorsTest : ScreenWalk(5003) {

    @Test
    fun otherColors() = walk("printing", "progress=0.3") { a, vm ->
        settleUntil(10_000, after = 1500) { vm.isIn(Phase.PRINTING) && vm.printing(CHESSBOARD) && vm.partDrawn() }
        val looks = listOf(
            Triple("Black", "Bone", "Teal"), Triple("Navy", "White", "Amber"),
            Triple("Charcoal", "Lemon", "Pink"), Triple("Forest", "Bone", "Lime"),
        )
        for ((ground, ink, accent) in looks) {
            vm.setGround(Grounds.first { it.label == ground }.color.toArgb())
            vm.setInk(Inks.first { it.label == ink }.color.toArgb())
            vm.setAccent(Accents.first { it.label == accent }.color.toArgb())
            val name = "colors-${ground.lowercase()}-${ink.lowercase()}-${accent.lowercase()}"
            settle(800)
            shot(a, "$name-1-status")
            tap(a, 38f, 104f)
            settle(1200)
            shot(a, "$name-2-files", load = 1500)
            tap(a, 38f, 164f)
            settle(800)
            shot(a, "$name-3-controls")
            tap(a, 38f, 436f)
            settle(800)
            tap(a, 180f, 112f)     // colors
            settle(600)
            shot(a, "$name-4-choosing")
            tap(a, 880f, 40f)      // away from the sheet closes it
            settle(400)
            tap(a, 38f, 44f)
            settle(800)
        }
    }
}

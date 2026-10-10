package com.drazan.echoshow.octoprint.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.toArgb
import com.drazan.echoshow.octoprint.PrinterViewModel
import com.drazan.echoshow.octoprint.octo.Previews
import com.drazan.echoshow.octoprint.octo.PrintFile
import com.drazan.echoshow.octoprint.ui.theme.Ink
import com.drazan.echoshow.octoprint.ui.theme.LocalDisplay
import kotlinx.coroutines.delay

/** A file's picture, or failing that a line saying why there isn't one yet. */
class Shown(val picture: Bitmap?, val note: String)

private val NothingYet = Shown(null, "")

/**
 * The picture for a file: the slicer's own if the G-code carries one,
 * otherwise the one this display draws from the G-code. Asking for a file
 * that has neither puts it at the front of the queue to be drawn, and the
 * picture appears here by itself when it is ready.
 */
@Composable
fun rememberPreview(vm: PrinterViewModel, file: PrintFile?): State<Shown> {
    val thumbs = vm.thumbs
    val tint = LocalDisplay.current.filament.color.toArgb()
    // read so this looks again whenever a drawing is finished or the printer frees up
    val drawn = vm.previewsDrawn
    val busy = vm.printer?.phase?.busy == true
    return key(file?.origin, file?.path) {
        produceState(NothingYet, thumbs, file?.thumbnail, file?.date, tint, drawn, busy) {
            if (thumbs == null || file == null || file.isFolder) {
                value = NothingYet
                return@produceState
            }
            val picture = thumbs.preview(file, tint)
            value = Shown(picture, if (picture == null) vm.previewNote(file) else "")
            if (picture == null) vm.wantPreview(file)
        }
    }
}

/**
 * The part as far as it has been printed: [file]'s drawing cut off at the layer
 * the job has reached, with that layer glowing. Null until there is one. A file
 * with a slicer's picture has none to begin with, as that picture can only ever
 * show the finished part; asking here has the display draw the file itself.
 *
 * A new one is drawn each time the job has moved on by a step, or by a [layer]
 * when OctoPrint says which it is on. The last stays up meanwhile, so the part
 * grows without ever blinking out.
 */
@Composable
fun rememberGrowth(vm: PrinterViewModel, file: PrintFile?, reveal: Float?, layer: Int?): State<Previews.Growth?> {
    val thumbs = vm.thumbs
    val tint = LocalDisplay.current.filament.color.toArgb()
    val hot = Ink.Ember.toArgb()
    // read so this looks again when the drawing it was waiting for is finished
    val drawn = vm.previewsDrawn
    val step = reveal?.let { (it.coerceIn(0f, 1f) * GROWTH_STEPS).toInt() }
    return key(file?.origin, file?.path) {
        produceState<Previews.Growth?>(null, thumbs, file?.date, tint, hot, step, layer, drawn) {
            if (thumbs == null || file == null || step == null || file.isFolder) {
                value = null
                return@produceState
            }
            val grown = thumbs.grown(file, step / GROWTH_STEPS.toFloat(), layer, tint, hot)
            if (grown != null) value = grown
            // No drawing to grow, or one from before all of it was kept: have it made again.
            // Asked for more than once, since the fetch can fail while the Pi is busy starting
            // a print. When it does come, the count of drawings changes and this starts over.
            while (grown == null || grown.dated) {
                vm.wantShape(file)
                delay(ASK_AGAIN_MS)
            }
        }
    }
}

/** Drawing the part costs the display a moment, and finer steps than this can't be told apart anyway. */
private const val GROWTH_STEPS = 120
private const val ASK_AGAIN_MS = 15_000L

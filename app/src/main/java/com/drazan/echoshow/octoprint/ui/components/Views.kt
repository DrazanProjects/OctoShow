package com.drazan.echoshow.octoprint.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.drazan.echoshow.octoprint.octo.GcodePreview
import com.drazan.echoshow.octoprint.octo.OctoApi
import com.drazan.echoshow.octoprint.octo.Previews
import com.drazan.echoshow.octoprint.octo.TempSample
import com.drazan.echoshow.octoprint.octo.Webcam
import com.drazan.echoshow.octoprint.octo.mjpegFrames
import com.drazan.echoshow.octoprint.octo.snapshotFrames
import com.drazan.echoshow.octoprint.ui.theme.Ink
import com.drazan.echoshow.octoprint.ui.theme.T
import com.drazan.echoshow.octoprint.ui.theme.Type
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/* ---------------- live camera ---------------- */

/**
 * The webcam, letterboxed inside whatever box it's given, with the picture
 * itself (not the box) getting the rounded corners. Honors OctoPrint's flip
 * and rotate settings. Runs only while the app is in the foreground and shows
 * [whenDown] if the camera stops answering.
 *
 * With [live] off it shows a fresh still every few seconds rather than the
 * stream, for a screen that is being glanced at, not watched.
 */
@Composable
fun CameraView(
    api: OctoApi,
    cam: Webcam,
    modifier: Modifier = Modifier,
    live: Boolean = true,
    fps: Int = 8,
    corner: Dp = 24.dp,
    onAspect: (Float) -> Unit = {},
    whenDown: @Composable () -> Unit,
) {
    var frame by remember(cam.streamUrl) { mutableStateOf<Bitmap?>(null) }
    var down by remember(cam.streamUrl) { mutableStateOf(false) }
    // Composition only cares whether there is a picture at all. Reading the frame itself here
    // would recompose this whole view ten times a second; the frame is read where it is drawn.
    val hasFrame by remember(cam.streamUrl) { derivedStateOf { frame != null } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reportAspect by rememberUpdatedState(onAspect)

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val shownWidth = constraints.maxWidth

        LaunchedEffect(api, cam, live, fps, shownWidth) {
            val still = cam.snapshotUrl?.takeIf { !live }?.let(api::resolve)
            val url = still ?: api.resolve(cam.streamUrl) ?: run {
                down = true
                return@LaunchedEffect
            }
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var wait = 2_000L
                while (true) {
                    try {
                        val frames = if (still != null) snapshotFrames(api.client, url, targetWidth = shownWidth)
                        else mjpegFrames(api.client, url, maxFps = if (live) fps else 1, targetWidth = shownWidth)
                        var first = true
                        frames.collect {
                            if (first && it.height > 0) {
                                first = false
                                val shape = it.width.toFloat() / it.height
                                reportAspect(if (cam.rotate90) 1f / shape else shape)
                            }
                            frame = it
                            down = false
                            wait = 2_000L
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // stalled or refused; fall through and try again
                    }
                    down = true
                    frame = null
                    // camera-streamer drops the first viewer after a long idle spell and is fine
                    // on the next try, so the first retries come quickly
                    delay(wait)
                    wait = (wait * 3 / 2).coerceAtMost(12_000L)
                }
            }
        }

        if (!hasFrame && down) {
            whenDown()
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val bmp = frame ?: return@Canvas
                val image = bmp.asImageBitmap()
                val turned = cam.rotate90
                val shownW = if (turned) image.height else image.width
                val shownH = if (turned) image.width else image.height
                val scale = min(size.width / shownW, size.height / shownH)
                val w = image.width * scale
                val h = image.height * scale
                val left = (size.width - w) / 2f
                val top = (size.height - h) / 2f
                withTransform({
                    if (turned) rotate(-90f)
                    scale(if (cam.flipH) -1f else 1f, if (cam.flipV) -1f else 1f)
                }) {
                    val round = Path().apply {
                        addRoundRect(RoundRect(Rect(left, top, left + w, top + h), CornerRadius(corner.toPx())))
                    }
                    clipPath(round) {
                        drawImage(
                            image,
                            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                }
            }
            if (!hasFrame) T("Starting camera", Type.Label, color = Ink.Faint)
        }
    }
}

/* ---------------- model preview ---------------- */

/**
 * The picture of the part, printing itself. With [reveal] null the whole part
 * is shown solid.
 *
 * Given [grown], a drawing of the part as it stands so far, that is shown
 * inside a ghost of the finished shape. Its top layer is colored in as far as
 * [layerDone] says the layer has been printed, in the order the nozzle goes
 * over it, with a hot spot where the nozzle is while plastic is actually
 * [laying].
 *
 * When that order isn't known for the layer, or OctoPrint gives no figure for
 * how far through it the print is, the whole layer is shown warm with the spot
 * wandering over it.
 *
 * Without a drawing, which is how a file with a slicer's picture starts out,
 * the picture is solid below the line the nozzle has reached and a ghost above
 * it. Once the drawing is there it takes the picture's place: the part can
 * only grow inside a picture taken from the same spot.
 */
@Composable
fun ModelPreview(
    thumb: Bitmap?,
    reveal: Float?,
    modifier: Modifier = Modifier,
    plate: Boolean = true,
    note: String = "",
    grown: Previews.Growth? = null,
    laying: Boolean = false,
    layerDone: Float? = null,
) {
    val picture = grown?.whole ?: thumb
    val bounds = remember(picture) { picture?.let(::opaqueBounds) }
    // The edge moves on smoothly as the figure comes in, but a new layer starts from nothing
    // at once: gliding backwards across the part would look like the print undoing itself.
    val down = remember { Animatable(layerDone ?: 0f) }
    LaunchedEffect(layerDone) {
        val target = (layerDone ?: 0f).coerceIn(0f, 1f)
        if (target < down.value) down.snapTo(target) else down.animateTo(target, tween(900, easing = LinearEasing))
    }
    // the same pace as the bead on the layer stack, so the two move as one
    val nozzle: State<Float>? = if (grown != null && laying && reveal != null) {
        rememberInfiniteTransition(label = "laying").animateFloat(
            initialValue = 0.04f,
            targetValue = 0.96f,
            animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "nozzle",
        )
    } else null
    Box(
        if (plate) modifier.clip(PanelShape).background(Ink.Panel) else modifier,
        contentAlignment = Alignment.Center,
    ) {
        if (plate) Canvas(Modifier.fillMaxSize()) { plateDots() }
        if (picture == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Cube, Ink.Line, size = 72.dp)
                Spacer(Modifier.height(10.dp))
                T(note, Type.Label, Modifier.padding(horizontal = 24.dp), color = Ink.Faint, align = TextAlign.Center)
            }
            return@Box
        }
        // its own layer: the moving spot then redraws the picture and nothing around it
        Canvas(Modifier.fillMaxSize().padding(if (plate) 18.dp else 0.dp).graphicsLayer()) {
            val image = picture.asImageBitmap()
            val scale = min(size.width / image.width, size.height / image.height)
            val w = image.width * scale
            val h = image.height * scale
            val left = (size.width - w) / 2f
            val top = (size.height - h) / 2f
            val at = IntOffset(left.roundToInt(), top.roundToInt())
            val to = IntSize(w.roundToInt(), h.roundToInt())

            if (reveal == null || bounds == null) {
                drawImage(image, dstOffset = at, dstSize = to, filterQuality = FilterQuality.High)
                return@Canvas
            }

            // map progress onto the rows the part actually occupies, not the empty margin around it
            val partTop = top + h * bounds.top
            val partBottom = top + h * bounds.bottom
            val line = partBottom - (partBottom - partTop) * reveal.coerceIn(0f, 1f)
            val overhang = 10.dp.toPx()
            val lineLeft = (left + w * bounds.left - overhang).coerceAtLeast(0f)
            val lineRight = (left + w * bounds.right + overhang).coerceAtMost(size.width)

            drawImage(
                image, dstOffset = at, dstSize = to, alpha = 0.22f,
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
                filterQuality = FilterQuality.High,
            )
            if (grown != null) {
                // drawn from the same spot as the finished picture, so it sits exactly inside the ghost
                drawImage(grown.part.asImageBitmap(), dstOffset = at, dstSize = to, filterQuality = FilterQuality.High)
                val fresh = grown.laying.asImageBitmap()
                val box = grown.box
                val along = nozzle?.value
                var spot: Offset? = null
                var reach = 0f
                val order = grown.order
                val trail = grown.trail
                if (layerDone != null && order != null && trail != null) {
                    // The layer is painted whole, then kept only where the nozzle has been.
                    val canvas = drawContext.canvas
                    canvas.saveLayer(Rect(left, top, left + w, top + h), Paint().apply { alpha = LAID })
                    drawImage(fresh, dstOffset = at, dstSize = to, filterQuality = FilterQuality.High)
                    drawImage(
                        order.asImageBitmap(), dstOffset = at, dstSize = to,
                        colorFilter = laidBy(down.value), blendMode = BlendMode.DstIn, filterQuality = FilterQuality.Low,
                    )
                    canvas.restore()
                    if (along != null) {
                        val moment = (down.value * (GcodePreview.MOMENTS - 1)).coerceIn(0f, GcodePreview.MOMENTS - 1.001f)
                        val m = moment.toInt()
                        val on = moment - m
                        spot = Offset(
                            left + w * (trail[2 * m] + (trail[2 * m + 2] - trail[2 * m]) * on),
                            top + h * (trail[2 * m + 1] + (trail[2 * m + 3] - trail[2 * m + 1]) * on),
                        )
                        // it has a place to be, so what keeps it alive is a slow breath
                        reach = NOZZLE_GLOW.toPx() * (0.85f + 0.3f * along)
                    }
                } else {
                    drawImage(fresh, dstOffset = at, dstSize = to, alpha = WARM, filterQuality = FilterQuality.High)
                    if (box != null && along != null) {
                        spot = Offset(left + w * (box.left + box.width() * along), top + h * box.centerY())
                        reach = max(w * box.width(), h * box.height()) * 0.34f
                    }
                }
                if (spot != null) {
                    // the spot is painted whole, then kept only where the top layer is
                    val canvas = drawContext.canvas
                    canvas.saveLayer(Rect(left, top, left + w, top + h), Paint())
                    drawCircle(
                        Brush.radialGradient(listOf(Ink.EmberHi, Ink.EmberHi.copy(alpha = 0f)), center = spot, radius = reach),
                        radius = reach, center = spot,
                    )
                    drawImage(fresh, dstOffset = at, dstSize = to, blendMode = BlendMode.DstIn, filterQuality = FilterQuality.High)
                    canvas.restore()
                }
                return@Canvas
            }
            clipRect(top = line) {
                drawImage(image, dstOffset = at, dstSize = to, filterQuality = FilterQuality.High)
            }
            if (reveal > 0f && reveal < 1f) {
                val glow = 14.dp.toPx()
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Ink.Ember.copy(alpha = 0.28f)),
                        startY = line - glow, endY = line,
                    ),
                    topLeft = Offset(lineLeft, line - glow), size = Size(lineRight - lineLeft, glow),
                )
                drawLine(
                    Ink.EmberHi, Offset(lineLeft, line), Offset(lineRight, line),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** How strongly the layer being laid shows as warm when all of it is, before the hot spot passes over it. */
private const val WARM = 0.5f
/** The same for the part of it known to be down, which can afford to be plainer about it. */
private const val LAID = 0.62f
/** How far the glow around the nozzle reaches, when where the nozzle is is known. */
private val NOZZLE_GLOW = 30.dp
/** How gradually plastic appears behind the nozzle, in steps of an order picture's opacity. */
private const val SOFT = 8f

/**
 * For an order picture, see [Previews.Growth.order]: keeps what the nozzle has
 * been over by the time the layer is [done] this far, 0..1, and none of the rest.
 */
private fun laidBy(done: Float): ColorFilter {
    val first = Previews.Growth.FIRST
    val last = Previews.Growth.LAST
    // anything at least this opaque is down; SOFT further and it is fully so
    val from = first - done * (first - last + SOFT)
    val steep = 255f / SOFT
    return ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, steep, -steep * from,
            )
        )
    )
}

/** A small square preview for lists. */
@Composable
fun ThumbTile(thumb: Bitmap?, size: Dp, modifier: Modifier = Modifier, folder: Boolean = false) {
    Box(
        modifier.size(size).clip(ChipShape).background(Ink.Panel),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) {
            Canvas(Modifier.fillMaxSize().padding(size * 0.08f)) {
                val image = thumb.asImageBitmap()
                val scale = min(this.size.width / image.width, this.size.height / image.height)
                val w = image.width * scale
                val h = image.height * scale
                drawImage(
                    image,
                    dstOffset = IntOffset(((this.size.width - w) / 2f).roundToInt(), ((this.size.height - h) / 2f).roundToInt()),
                    dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        } else {
            Icon(if (folder) Icons.Folder else Icons.Cube, Ink.Faint, size = size * 0.42f)
        }
    }
}

/** A dot grid, like the texture of a build plate. */
private fun DrawScope.plateDots() {
    val step = 22.dp.toPx()
    val r = 1.1.dp.toPx()
    var y = step / 2f + (size.height % step) / 2f
    while (y < size.height) {
        var x = step / 2f + (size.width % step) / 2f
        while (x < size.width) {
            drawCircle(Ink.Line, r, Offset(x, y))
            x += step
        }
        y += step
    }
}

/** The box, as fractions of the picture, that holds every visible pixel of the part. */
private fun opaqueBounds(b: Bitmap): Rect {
    val whole = Rect(0f, 0f, 1f, 1f)
    if (!b.hasAlpha() || b.config == Bitmap.Config.HARDWARE) return whole
    val w = b.width
    val h = b.height
    val px = IntArray(w * h)
    b.getPixels(px, 0, w, 0, 0, w, h)
    var minX = w
    var maxX = -1
    var minY = h
    var maxY = -1
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            if ((px[row + x] ushr 24) > 24) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }
    if (maxX < 0) return whole
    return Rect(minX.toFloat() / w, minY.toFloat() / h, (maxX + 1f) / w, (maxY + 1f) / h)
}

/* ---------------- temperature trace ---------------- */

/** The last few minutes of one heater, scaled to its own range. */
@Composable
fun HeatTrace(
    samples: List<TempSample>,
    pick: (TempSample) -> Float?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val values = samples.mapNotNull(pick)
        if (values.size < 2) return@Canvas
        // A steady temperature should read as a calm line through the middle, not hug an
        // edge, so the window is centred on the data and never tighter than a few degrees.
        val middle = (values.min() + values.max()) / 2f
        val span = max(values.max() - values.min(), 8f) * 1.5f
        val lo = middle - span / 2f
        val hi = middle + span / 2f
        val stroke = 2.dp.toPx()
        val usable = size.height - stroke * 2
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = stroke + usable * (1f - (v - lo) / (hi - lo))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.16f), Color.Transparent)))
        drawPath(
            path,
            Brush.horizontalGradient(listOf(color.copy(alpha = 0.25f), color)),
            style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

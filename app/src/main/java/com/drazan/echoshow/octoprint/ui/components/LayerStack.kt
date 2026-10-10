package com.drazan.echoshow.octoprint.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drazan.echoshow.octoprint.octo.LayerInfo
import com.drazan.echoshow.octoprint.ui.theme.Ink
import com.drazan.echoshow.octoprint.ui.theme.LocalDisplay
import com.drazan.echoshow.octoprint.ui.theme.T
import com.drazan.echoshow.octoprint.ui.theme.Type
import kotlin.math.floor

enum class StackMode { EMPTY, PRINTING, HELD, FULL }

/**
 * Progress drawn the way the printer makes it: a stack of layers that grows
 * from the bed up. Finished layers have cooled to the filament's color, the
 * few under the nozzle are still warm, and the one being laid down glows, with
 * a bead at the point the nozzle has reached along it. One bar is one layer
 * until there are too many to tell apart, then each bar stands for several.
 *
 * Without layer data from OctoPrint the stack fills by percentage and the bead
 * just paces back and forth to show the printer is working.
 */
@Composable
fun LayerStack(
    fraction: Float,
    layers: LayerInfo,
    mode: StackMode,
    modifier: Modifier = Modifier,
    barWidth: Dp = 46.dp,
    callout: (@Composable () -> Unit)? = null,
) {
    val filament = LocalDisplay.current.filament.color

    // Only animated while printing, so an idle display isn't redrawing sixty times a second.
    val head: State<Float>? = if (mode == StackMode.PRINTING) {
        rememberInfiniteTransition(label = "nozzle").animateFloat(
            initialValue = 0.04f,
            targetValue = 0.96f,
            animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "head",
        )
    } else null

    BoxWithConstraints(modifier) {
        val g = stackGeometry(maxHeight.value, layers, fraction)

        // its own layer: the moving bead then redraws this strip and nothing else
        Canvas(Modifier.width(barWidth).fillMaxHeight().graphicsLayer()) {
            val pitch = g.pitch.dp.toPx()
            val gap = g.gap.dp.toPx()
            val barH = pitch - gap
            val radius = CornerRadius(barH / 2f)
            val current = when (mode) {
                StackMode.EMPTY -> -1
                StackMode.FULL -> g.count
                else -> g.current
            }

            for (i in 0 until g.count) {
                val top = size.height - (i + 1) * pitch + gap / 2f
                val color = when {
                    i > current -> Ink.Line
                    i == current -> if (mode == StackMode.HELD) Ink.Warn else Ink.Ember.copy(alpha = 0.5f)
                    mode == StackMode.FULL -> filament
                    else -> {
                        // the layers just under the nozzle haven't cooled yet
                        val below = current - i
                        val warmth = (1f - (below - 1) / WARM_LAYERS).coerceIn(0f, 1f)
                        lerp(filament, Ink.Ember, warmth * 0.9f)
                    }
                }
                drawRoundRect(color, Offset(0f, top), Size(size.width, barH), radius)

                if (i == current && head != null) {
                    // with real progress the bead sits where the layer has got to and breathes;
                    // without it, it paces
                    val within = layers.layerProgress?.takeIf { layers.known }?.let { it / 100f }
                    val at = within ?: head.value
                    val cx = barH + (size.width - barH * 2f) * at
                    val cy = top + barH / 2f
                    if (within != null) {
                        drawRoundRect(Ink.Ember, Offset(0f, top), Size(cx, barH), radius)
                    }
                    val glow = barH * 2.6f
                    val strength = if (within != null) 0.25f + 0.45f * head.value else 0.55f
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Ink.EmberHi.copy(alpha = strength), Color.Transparent),
                            center = Offset(cx, cy), radius = glow,
                        ),
                        radius = glow, center = Offset(cx, cy),
                    )
                    drawRoundRect(
                        Ink.EmberHi,
                        Offset(cx - barH, top), Size(barH * 2f, barH), radius,
                    )
                }
            }
        }

        if (callout != null && mode != StackMode.EMPTY && mode != StackMode.FULL) {
            // rides up the side of the stack, level with the layer in progress
            val centre = maxHeight.value - (g.current + 0.5f) * g.pitch
            val y by animateDpAsState(
                (centre - CALLOUT_HALF).coerceIn(0f, (maxHeight.value - CALLOUT_HALF * 2).coerceAtLeast(0f)).dp,
                tween(600), label = "callout",
            )
            Box(Modifier.offset(x = barWidth, y = y).padding(start = 10.dp)) { callout() }
        }
    }
}

/** "12 of 33" beside the stack. */
@Composable
fun LayerCallout(layers: LayerInfo, zMillimetres: Double?) {
    Column {
        if (layers.known) {
            T("${minOf(layers.current ?: 0, layers.total ?: 0)}", Type.Reading, size = 20.sp, maxLines = 1)
            T("of ${layers.total}", Type.Meta, color = Ink.Faint, maxLines = 1)
        } else if (zMillimetres != null) {
            T(trimmed(zMillimetres), Type.Reading, size = 20.sp, maxLines = 1)
            T("mm up", Type.Meta, color = Ink.Faint, maxLines = 1)
        }
    }
}

private fun trimmed(v: Double): String =
    if (v >= 100) "%.0f".format(v) else "%.1f".format(v)

private class Geometry(val count: Int, val pitch: Float, val gap: Float, val current: Int)

/** All in dp. [current] is the index, from the bed, of the bar being printed. */
private fun stackGeometry(height: Float, layers: LayerInfo, fraction: Float): Geometry {
    val total = layers.total
    val count = if (layers.known && total != null) {
        total.coerceAtMost(floor(height / MIN_PITCH).toInt().coerceAtLeast(1))
    } else {
        floor(height / LOOSE_PITCH).toInt().coerceAtLeast(1)
    }
    val pitch = (height / count).coerceAtMost(MAX_PITCH)
    val gap = (pitch * 0.36f).coerceIn(1.6f, 4f)
    val current = if (layers.known && total != null) {
        (((layers.current ?: 1) - 1).coerceAtLeast(0).toLong() * count / total).toInt()
    } else {
        floor(fraction * count).toInt()
    }.coerceIn(0, count - 1)
    return Geometry(count, pitch, gap, current)
}

private const val MIN_PITCH = 5f
private const val LOOSE_PITCH = 9f
private const val MAX_PITCH = 16f
private const val WARM_LAYERS = 6f
private const val CALLOUT_HALF = 22f

package com.drazan.echoshow.octoprint.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A small hand-drawn icon set on a 24-unit grid, so the app carries no icon library. */
object Icons {
    val Layers = line("M12 3 21 8 12 13 3 8Z", "M3 12.5 12 17.5 21 12.5", "M3 17 12 22 21 17")
    val Folder = line("M3 7.5a2 2 0 0 1 2-2h4l2 2.5h8a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z")
    val Sliders = line(
        "M5 21v-7", "M5 10V3", "M12 21v-9", "M12 8V3", "M19 21v-5", "M19 12V3",
        "M2.5 14h5", "M9.5 8h5", "M16.5 16h5",
    )
    val Gear = gear()
    val Pause = line("M8.5 5.5v13", "M15.5 5.5v13", width = 3.2f)
    val Play = solid("M8.5 5.5v13l10.5-6.5Z")
    val Close = line("M6 6l12 12", "M18 6 6 18")
    val Home = line("M4 11l8-7 8 7", "M6 9.5V20h12V9.5")
    val Up = line("M6 15l6-6 6 6")
    val Down = line("M6 9l6 6 6-6")
    val Left = line("M15 6l-6 6 6 6")
    val Right = line("M9 6l6 6-6 6")
    val Camera = line(
        "M3 8a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z",
        "M16 10.5l5-3v9l-5-3",
    )
    val Cube = line("M12 3l8 4.5v9L12 21l-8-4.5v-9Z", "M4 7.5l8 4.5 8-4.5", "M12 12v9")
    val Refresh = line("M4 12a8 8 0 0 1 14-5.3L20 9", "M20 4v5h-5", "M20 12a8 8 0 0 1-14 5.3L4 15", "M4 20v-5h5")
    val Bulb = line(
        "M9 18h6", "M10 21h4",
        "M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3Z",
    )
    val Alert = line("M12 4l9 16H3Z", "M12 10v4", "M12 17v.5")
    val Nozzle = line("M7 4h10v7H7Z", "M9.5 11l1.5 4h2l1.5-4", "M12 18v2")
    val Bed = line(
        "M3 17h18", "M5 20.5h14",
        "M8 13c0-1.5 1.5-1.5 1.5-3S8 8.5 8 7", "M12 13c0-1.5 1.5-1.5 1.5-3S12 8.5 12 7",
        "M16 13c0-1.5 1.5-1.5 1.5-3S16 8.5 16 7",
    )
    val Plug = line("M9 3v5", "M15 3v5", "M6 8h12v3a6 6 0 0 1-12 0Z", "M12 17v4")
    val Power = line("M12 3v8.5", "M7.2 6.3a7.6 7.6 0 1 0 9.6 0")
    val Wifi = line("M3 9a13 13 0 0 1 18 0", "M6.5 12.5a8 8 0 0 1 11 0", "M10 16a3 3 0 0 1 4 0", "M12 19.5v.5")

    private fun line(vararg paths: String, width: Float = 1.9f): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                paths.forEach { d ->
                    addPath(
                        pathData = PathParser().parsePathString(d).toNodes(),
                        fill = null,
                        stroke = SolidColor(Color.White),
                        strokeLineWidth = width,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }.build()

    private fun solid(vararg paths: String): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                paths.forEach { d ->
                    addPath(
                        pathData = PathParser().parsePathString(d).toNodes(),
                        fill = SolidColor(Color.White),
                        // a stroke in the same color rounds the corners off
                        stroke = SolidColor(Color.White),
                        strokeLineWidth = 1.8f,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }.build()

    /** Eight flat-topped teeth around a hub. */
    private fun gear(): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                path(
                    fill = null,
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 1.9f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                ) {
                    val teeth = 8
                    val outer = 9.6f
                    val inner = 7.3f
                    val step = 2 * PI / teeth
                    for (i in 0 until teeth) {
                        val a = i * step
                        // each tooth: rise, flat top, fall, then the valley to the next tooth
                        val pts = listOf(
                            a - step * 0.30 to inner, a - step * 0.18 to outer,
                            a + step * 0.18 to outer, a + step * 0.30 to inner,
                        )
                        pts.forEachIndexed { j, (ang, r) ->
                            val x = 12f + (r * cos(ang)).toFloat()
                            val y = 12f + (r * sin(ang)).toFloat()
                            if (i == 0 && j == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }
                    close()
                }
                addPath(
                    pathData = PathParser().parsePathString("M12 8.9a3.1 3.1 0 1 0 0 6.2a3.1 3.1 0 1 0 0-6.2Z").toNodes(),
                    fill = null,
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 1.9f,
                )
            }.build()
}

@Composable
fun Icon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Image(
        painter = rememberVectorPainter(icon),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

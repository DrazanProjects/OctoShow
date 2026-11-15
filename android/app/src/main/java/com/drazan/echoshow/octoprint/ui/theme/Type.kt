package com.drazan.echoshow.octoprint.ui.theme

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.drazan.echoshow.octoprint.R

/*
 * One family, Archivo, bundled as a variable font so the look never depends on
 * a font provider being present on the device. The width axis does the work:
 * the screen is 2:1, so live values run wide to use the room they have, text
 * sits at normal width, and dense metadata narrows.
 */

private val Weights = listOf(400, 500, 600, 700, 800)

@OptIn(ExperimentalTextApi::class)
private fun archivo(width: Float) = FontFamily(
    Weights.map { w ->
        Font(
            resId = R.font.archivo,
            weight = FontWeight(w),
            variationSettings = FontVariation.Settings(
                FontVariation.weight(w),
                FontVariation.width(width),
            ),
        )
    }
)

val ArchivoWide = archivo(125f)
val Archivo = archivo(100f)
val ArchivoNarrow = archivo(84f)

/** The text styles in one theme's inks. Reached through [Type]. */
class Typography(ink: Palette) {
    /** The one number you read from across the room. */
    val Hero = TextStyle(
        fontFamily = ArchivoWide, fontWeight = FontWeight(700), fontSize = 62.sp,
        letterSpacing = (-0.02).em, color = ink.Bone, fontFeatureSettings = "tnum",
    )
    val HeroUnit = TextStyle(
        fontFamily = ArchivoWide, fontWeight = FontWeight(500), fontSize = 26.sp, color = ink.Soft,
    )
    /** Words standing where the hero number would be: Ready, Done, Offline. */
    val HeroWord = TextStyle(
        fontFamily = ArchivoWide, fontWeight = FontWeight(700), fontSize = 40.sp,
        letterSpacing = (-0.015).em, lineHeight = 44.sp, color = ink.Bone,
    )
    val Reading = TextStyle(
        fontFamily = ArchivoWide, fontWeight = FontWeight(600), fontSize = 28.sp,
        letterSpacing = (-0.01).em, color = ink.Bone, fontFeatureSettings = "tnum",
    )
    val Title = TextStyle(
        fontFamily = Archivo, fontWeight = FontWeight(600), fontSize = 19.sp,
        lineHeight = 24.sp, color = ink.Bone,
    )
    val Heading = TextStyle(
        fontFamily = Archivo, fontWeight = FontWeight(600), fontSize = 15.sp, color = ink.Bone,
    )
    val Body = TextStyle(
        fontFamily = Archivo, fontWeight = FontWeight(400), fontSize = 14.sp,
        lineHeight = 20.sp, color = ink.Soft,
    )
    val Label = TextStyle(
        fontFamily = Archivo, fontWeight = FontWeight(500), fontSize = 13.sp, color = ink.Soft,
    )
    val Meta = TextStyle(
        fontFamily = ArchivoNarrow, fontWeight = FontWeight(500), fontSize = 13.sp,
        color = ink.Soft, fontFeatureSettings = "tnum",
    )
    val Button = TextStyle(
        fontFamily = Archivo, fontWeight = FontWeight(600), fontSize = 16.sp, color = ink.Bone,
    )
}

/** The text styles for the theme in use. */
val Type: Typography get() = Ink.type

/** Text without Material: a style from [Type], optionally recolored or resized. */
@Composable
fun T(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    size: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
) {
    var s = style
    if (color != Color.Unspecified) s = s.copy(color = color)
    if (size != TextUnit.Unspecified) s = s.copy(fontSize = size)
    if (align != null) s = s.copy(textAlign = align)
    BasicText(
        text = text,
        modifier = modifier,
        style = s,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

package com.drazan.echoshow.octoprint.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * One color for each job on screen. The names are those of the look the display
 * was designed in, where the ground is true black and the ink is bone. [Black]
 * is always the ground everything sits on and [Bone] the ink that is read
 * first, whatever colors have been chosen for them.
 *
 * Color is reserved for heat and state. Everything else is ink on ground, and
 * the camera or the model preview supplies the only other color on screen.
 */
class Palette(
    val Black: Color,
    val Panel: Color,
    val PanelHi: Color,
    val Line: Color,
    val Bone: Color,
    val Soft: Color,
    /** The quietest text. Still close to 5:1 against the ground, since labels are set small. */
    val Faint: Color,
    /**
     * The accent: freshly extruded plastic, the nozzle, a job in progress, anything hot.
     * Orange unless another color has been chosen for it.
     */
    val Ember: Color,
    val EmberHi: Color,
    /** The heated bed, following OctoPrint's own graph colors. */
    val Bed: Color,
    val Ok: Color,
    val Warn: Color,
    val Bad: Color,
) {
    /** The text styles in these inks. */
    val type: Typography by lazy { Typography(this) }
}

/**
 * Bone filament on black, tuned by hand: the screen edge disappears into the
 * Echo Show's bezel. This is what the display looks like until someone chooses
 * otherwise, and what they get back by choosing these two colors again.
 */
private val BoneOnBlack = Palette(
    Black = Color(0xFF000000), Panel = Color(0xFF121416), PanelHi = Color(0xFF1C1F22), Line = Color(0xFF2A2E32),
    Bone = Color(0xFFF3F1EC), Soft = Color(0xFFA3A8AD), Faint = Color(0xFF7B8187),
    Ember = Color(0xFFFF6B2C), EmberHi = Color(0xFFFFC08A), Bed = Color(0xFF5AA9FF),
    Ok = Color(0xFF5BD6A0), Warn = Color(0xFFFFC857), Bad = Color(0xFFFF5A5F),
)

/** A color on offer, and what to call it. */
class Swatch(val label: String, val color: Color)

/**
 * Grounds to choose from. All of them dark: the layers and the model are drawn
 * in the filament's color, and a pale filament disappears on a light ground.
 */
val Grounds: List<Swatch> = listOf(
    Swatch("Black", BoneOnBlack.Black),
    Swatch("Charcoal", Color(0xFF17191C)),
    Swatch("Aluminium", Color(0xFF3A3F46)),
    Swatch("Navy", Color(0xFF0F2B4C)),
    Swatch("Forest", Color(0xFF0B3324)),
    Swatch("Plum", Color(0xFF2B1836)),
    Swatch("Wine", Color(0xFF3A121C)),
    Swatch("Espresso", Color(0xFF2B1B12)),
)

/** Inks to choose from, light enough to read on any of the grounds. */
val Inks: List<Swatch> = listOf(
    Swatch("Bone", BoneOnBlack.Bone),
    Swatch("White", Color(0xFFFFFFFF)),
    Swatch("Amber", Color(0xFFFFC56E)),
    Swatch("Lemon", Color(0xFFFFEE99)),
    Swatch("Mint", Color(0xFFA6F5C4)),
    Swatch("Sky", Color(0xFFAEDBFF)),
    Swatch("Lilac", Color(0xFFD6C6FF)),
    Swatch("Rose", Color(0xFFFFB8C6)),
)

/** Accents to choose from: the color of whatever is hot or under way. The first is the one it was designed with. */
val Accents: List<Swatch> = listOf(
    Swatch("Ember", BoneOnBlack.Ember),
    Swatch("Amber", Color(0xFFFFAE1F)),
    Swatch("Lime", Color(0xFF7ADB4B)),
    Swatch("Teal", Color(0xFF2FD5C0)),
    Swatch("Sky", Color(0xFF4AA8FF)),
    Swatch("Violet", Color(0xFF9D7DFF)),
    Swatch("Pink", Color(0xFFFF5FA2)),
    Swatch("Red", Color(0xFFFF4D4F)),
)

/**
 * Every color the display uses, worked out from the three that were chosen.
 * Surfaces are the ground with a little of the ink in it. Quieter text is the
 * ink with some of the ground in it. The accent is used as it is, with a paler
 * version for where it glows. The colors that mean a state are kept, and only
 * pushed far enough from this particular ground to stand out on it.
 */
fun paletteOf(ground: Color, ink: Color, accent: Color): Palette {
    if (ground == BoneOnBlack.Black && ink == BoneOnBlack.Bone && accent == BoneOnBlack.Ember) return BoneOnBlack
    val hot = accent.legibleOn(ground, 3.2f)
    val text = ink.legibleOn(ground, 7f)
    fun surface(share: Float) = mix(ground, text, share)
    fun quieter(share: Float, atLeast: Float) = mix(text, ground, share).legibleOn(ground, atLeast)
    fun signal(color: Long) = Color(color).legibleOn(ground, 3.2f)
    return Palette(
        Black = ground, Panel = surface(0.07f), PanelHi = surface(0.12f), Line = surface(0.19f),
        Bone = text, Soft = quieter(0.30f, 5.5f), Faint = quieter(0.48f, 4.5f),
        Ember = hot, EmberHi = mix(hot, Color.White, 0.55f), Bed = signal(0xFF7FD0FF),
        Ok = signal(0xFF6FE0A8), Warn = signal(0xFFFFD166), Bad = signal(0xFFFF6F6F),
    )
}

/** This color, or the nearest lighter (on a light ground, darker) one that reads against [ground]. */
private fun Color.legibleOn(ground: Color, atLeast: Float): Color {
    val away = if (ground.luminance() < 0.4f) Color.White else Color.Black
    var color = this
    repeat(20) {
        if (contrast(color, ground) >= atLeast) return color
        color = mix(color, away, 0.1f)
    }
    return color
}

/**
 * [share] of the way from [from] to [to], channel by channel. Not the blend that
 * is even to the eye: that one gives next to nothing for the first steps away
 * from black, and a panel has to show against a black ground. This is also the
 * blend the hand-tuned colors turn out to follow.
 */
private fun mix(from: Color, to: Color, share: Float): Color = Color(
    red = from.red + (to.red - from.red) * share,
    green = from.green + (to.green - from.green) * share,
    blue = from.blue + (to.blue - from.blue) * share,
)

/** How far apart two colors are in brightness, the way legibility is usually measured: 1 to 21. */
private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

private var current by mutableStateOf(BoneOnBlack)

/** The colors in use. Whatever was drawn with one is drawn again when they change. */
val Ink: Palette get() = current

fun usePalette(palette: Palette) {
    current = palette
}

/** The color printed layers settle to once they cool. Picked to match the loaded spool. */
data class Filament(val id: String, val label: String, val color: Color)

val Filaments: List<Filament> = listOf(
    Filament("bone", "Bone", Color(0xFFE9E4D8)),
    Filament("orange", "Orange", Color(0xFFFF8A3D)),
    Filament("yellow", "Yellow", Color(0xFFFFD25A)),
    Filament("mint", "Mint", Color(0xFF63D9A6)),
    Filament("sky", "Sky", Color(0xFF6CB8FF)),
    Filament("lilac", "Lilac", Color(0xFFB79CFF)),
    Filament("rose", "Rose", Color(0xFFFF8FA3)),
    Filament("grey", "Grey", Color(0xFF8C939A)),
)

fun filamentById(id: String): Filament = Filaments.firstOrNull { it.id == id } ?: Filaments[0]

data class DisplayTheme(
    val filament: Filament,
    val clock24: Boolean,
)

val LocalDisplay = compositionLocalOf { DisplayTheme(Filaments[0], clock24 = false) }

@Composable
fun DisplayThemeProvider(theme: DisplayTheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDisplay provides theme, content = content)
}

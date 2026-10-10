package com.drazan.echoshow.octoprint.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

private val TrailingDuration = Regex("[ _-]+(?:\\d+h)?(?:\\d+m)?(?:\\d+s)?$")

/**
 * "CHESSBOARD_top_front_left_PLA_8h8m.gcode" reads better as "CHESSBOARD top
 * front left PLA". Slicers like to append the print time to the file name; it
 * is shown properly elsewhere, so it is dropped here.
 */
fun prettyName(display: String): String {
    val stem = display.substringBeforeLast('.', display)
    val cut = TrailingDuration.find(stem)?.takeIf { it.value.any(Char::isDigit) }?.let { stem.removeRange(it.range) } ?: stem
    return cut.replace('_', ' ').trim().ifEmpty { display }
}

/** "6h 42m", "42m", "under a minute". */
fun duration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "under a minute"
    }
}

/** The same duration as (number, unit) pairs, for setting the numbers large. */
fun durationParts(seconds: Long): List<Pair<String, String>> {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> listOf("$h" to "h", "%02d".format(m) to "m")
        m > 0 -> listOf("$m" to "m")
        else -> listOf("<1" to "m")
    }
}

fun clockTime(t: LocalDateTime, clock24: Boolean): String =
    if (clock24) "%d:%02d".format(t.hour, t.minute)
    else "%d:%02d %s".format(if (t.hour % 12 == 0) 12 else t.hour % 12, t.minute, if (t.hour >= 12) "pm" else "am")

/** A moment relative to today: "6:31 am", "tomorrow 6:31 am", "Thu 6:31 am". */
fun whenIs(t: LocalDateTime, clock24: Boolean, today: LocalDate = LocalDate.now()): String {
    val time = clockTime(t, clock24)
    return when (t.toLocalDate()) {
        today -> time
        today.plusDays(1) -> "tomorrow $time"
        today.minusDays(1) -> "yesterday $time"
        else -> "${t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)} $time"
    }
}

fun fromEpochSeconds(s: Long): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochSecond(s), ZoneId.systemDefault())

fun fromEpochMillis(ms: Long): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())

/** When a file was uploaded: "today 10:22 pm", "yesterday", "Oct 3". */
fun uploadedAt(epochSeconds: Long, clock24: Boolean): String {
    if (epochSeconds <= 0) return ""
    val t = fromEpochSeconds(epochSeconds)
    val today = LocalDate.now()
    return when (t.toLocalDate()) {
        today -> "today ${clockTime(t, clock24)}"
        today.minusDays(1) -> "yesterday"
        else -> {
            val month = t.month.getDisplayName(TextStyle.SHORT, Locale.US)
            if (t.year == today.year) "$month ${t.dayOfMonth}" else "$month ${t.dayOfMonth}, ${t.year}"
        }
    }
}

/** Filament length in metres. */
fun filamentLength(mm: Double): String = "%.1f m".format(mm / 1000.0)

fun degrees(v: Float?): String = if (v == null) "–" else "${Math.round(v)}°"

@Composable
fun rememberNow(everyMillis: Long = 15_000L): State<LocalDateTime> =
    produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(everyMillis)
        }
    }

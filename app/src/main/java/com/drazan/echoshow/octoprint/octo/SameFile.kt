package com.drazan.echoshow.octoprint.octo

/** OctoPrint's name for the printer's own SD card, as a file's origin. */
const val SD_CARD = "sdcard"

/**
 * The file in OctoPrint's own storage that [job], printed from the printer's SD card, is
 * another copy of, going by its name. OctoPrint can't read files on the card, so this is the
 * one to draw the part and its layers from. [newestFirst] is OctoPrint's files, newest first,
 * so of several with the same name the most recent upload wins.
 *
 * A job started from the printer's own screen may only be known by the card's short name,
 * such as "SPOOL_~1.GCO". Then the first letters of the long name have to do, and only when
 * just one file starts with them: showing some other part would be worse than none.
 */
fun sameFileAs(job: JobFile, newestFirst: List<PrintFile>): PrintFile? {
    val names = listOf(job.display, job.name, job.path).map(::stem).filter { it.isNotEmpty() }.distinct()
    newestFirst.firstOrNull { stem(it.name) in names || stem(it.display) in names }?.let { return it }
    val start = names.firstNotNullOfOrNull { SHORT_NAME.matchEntire(it)?.groupValues?.get(1) } ?: return null
    return newestFirst.filter { shortNameStart(it.name) == start }.singleOrNull()
}

/** A file's name without its folders or its G-code extension, in lower case. */
private fun stem(name: String): String {
    val base = name.substringAfterLast('/').lowercase()
    val dot = base.lastIndexOf('.')
    return if (dot > 0 && base.substring(dot + 1) in GCODE_EXTENSIONS) base.substring(0, dot) else base
}

/** How the card shortens a long name: spaces and dots dropped, odd characters made "_", six kept. */
private fun shortNameStart(name: String): String =
    stem(name).filter { it != ' ' && it != '.' }
        .map { if (it in "+,;=[]" || it.code > 127) '_' else it }
        .take(6).joinToString("")

private val GCODE_EXTENSIONS = setOf("gcode", "gco", "gc", "g")

/** "spool_~1" once [stem] has been at it: up to six letters, a tilde and a number. */
private val SHORT_NAME = Regex("^([^~.]{1,6})~[0-9]{1,6}$")

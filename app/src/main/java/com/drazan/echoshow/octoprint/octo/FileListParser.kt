package com.drazan.echoshow.octoprint.octo

import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Stream-parses GET /api/files.
 *
 * Plugins such as PrintTimeGenius hang tens of kilobytes of analysis data off
 * every file, so a full listing can run to several megabytes. Building that as
 * a JSON tree would cost a 1 GB device dearly; reading it as a stream and
 * skipping everything we don't show keeps memory flat.
 */
internal object FileListParser {

    fun parse(input: InputStream): List<PrintFile> =
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            r.isLenient = true
            var files = emptyList<PrintFile>()
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "files") files = readEntries(r) else r.skipValue()
            }
            r.endObject()
            files
        }

    /** GET /api/files/{origin}/{path}: a single entry. */
    fun parseOne(input: InputStream): PrintFile? =
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            r.isLenient = true
            readEntry(r)
        }

    private fun readEntries(r: JsonReader): List<PrintFile> {
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue()
            return emptyList()
        }
        val out = ArrayList<PrintFile>()
        r.beginArray()
        while (r.hasNext()) readEntry(r)?.let(out::add)
        r.endArray()
        return out
    }

    private fun readEntry(r: JsonReader): PrintFile? {
        var name: String? = null
        var display: String? = null
        var path: String? = null
        var origin = "local"
        var type: String? = null
        var size = 0L
        var date = 0L
        var estimate: Double? = null
        var filament: Double? = null
        var thumbnail: String? = null
        var lastSuccess: Boolean? = null
        var children = emptyList<PrintFile>()

        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "name" -> name = r.string()
                "display" -> display = r.string()
                "path" -> path = r.string()
                "origin" -> origin = r.string() ?: origin
                "type" -> type = r.string()
                "size" -> size = r.number()?.toLong() ?: 0L
                "date" -> date = r.number()?.toLong() ?: 0L
                "thumbnail" -> thumbnail = r.string()
                "children" -> children = readEntries(r)
                "gcodeAnalysis" -> r.fields { key ->
                    when (key) {
                        "estimatedPrintTime" -> estimate = r.number()
                        "filament" -> filament = readFilament(r)
                        else -> r.skipValue()
                    }
                }
                "prints" -> r.fields { key ->
                    if (key == "last") r.fields { k ->
                        when (k) {
                            "success" -> lastSuccess = r.bool()
                            else -> r.skipValue()
                        }
                    } else r.skipValue()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()

        val n = name ?: return null
        val isFolder = type == "folder"
        if (!isFolder && type != "machinecode") return null
        return PrintFile(
            name = n,
            display = display ?: n,
            path = path ?: n,
            origin = origin,
            isFolder = isFolder,
            size = size,
            date = date,
            estimatedPrintTime = estimate,
            filamentMm = filament,
            thumbnail = thumbnail,
            lastPrintSuccess = lastSuccess,
            children = children,
        )
    }

    /** {"tool0": {"length": mm, "volume": cm3}, ...} summed over tools. */
    private fun readFilament(r: JsonReader): Double? {
        var total: Double? = null
        r.fields { _ ->
            r.fields { key ->
                if (key == "length") r.number()?.let { total = (total ?: 0.0) + it } else r.skipValue()
            }
        }
        return total
    }

    /** Walk an object's fields; a null or non-object value is skipped whole. */
    private inline fun JsonReader.fields(each: (String) -> Unit) {
        if (peek() != JsonToken.BEGIN_OBJECT) {
            skipValue()
            return
        }
        beginObject()
        while (hasNext()) each(nextName())
        endObject()
    }

    private fun JsonReader.string(): String? = when (peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> nextString()
        else -> { skipValue(); null }
    }

    private fun JsonReader.number(): Double? = when (peek()) {
        JsonToken.NUMBER -> nextDouble().takeIf { !it.isNaN() && !it.isInfinite() }
        JsonToken.STRING -> nextString().toDoubleOrNull()
        else -> { skipValue(); null }
    }

    private fun JsonReader.bool(): Boolean? = when (peek()) {
        JsonToken.BOOLEAN -> nextBoolean()
        else -> { skipValue(); null }
    }
}

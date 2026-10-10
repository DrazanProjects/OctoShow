package com.drazan.echoshow.octoprint.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.drazan.echoshow.octoprint.PrinterViewModel
import com.drazan.echoshow.octoprint.octo.Phase
import com.drazan.echoshow.octoprint.octo.PrintFile
import com.drazan.echoshow.octoprint.ui.components.Icon
import com.drazan.echoshow.octoprint.ui.components.IconButton
import com.drazan.echoshow.octoprint.ui.components.Icons
import com.drazan.echoshow.octoprint.ui.components.ModelPreview
import com.drazan.echoshow.octoprint.ui.components.QuietButton
import com.drazan.echoshow.octoprint.ui.components.SolidButton
import com.drazan.echoshow.octoprint.ui.components.ThumbTile
import com.drazan.echoshow.octoprint.ui.components.tap
import com.drazan.echoshow.octoprint.ui.duration
import com.drazan.echoshow.octoprint.ui.filamentLength
import com.drazan.echoshow.octoprint.ui.prettyName
import com.drazan.echoshow.octoprint.ui.rememberPreview
import com.drazan.echoshow.octoprint.ui.theme.Ink
import com.drazan.echoshow.octoprint.ui.theme.LocalDisplay
import com.drazan.echoshow.octoprint.ui.theme.T
import com.drazan.echoshow.octoprint.ui.theme.Type
import com.drazan.echoshow.octoprint.ui.uploadedAt

/** Everything on OctoPrint, newest first, with the chosen file's preview alongside. */
@Composable
fun FilesScreen(vm: PrinterViewModel, onAskPrint: (PrintFile) -> Unit) {
    DisposableEffect(Unit) {
        vm.watchFiles(true)
        onDispose { vm.watchFiles(false) }
    }

    val state = vm.files
    // folders opened so far, by path, so the place survives a refresh of the list
    var trail by remember { mutableStateOf(emptyList<String>()) }
    var chosenPath by remember { mutableStateOf<String?>(null) }

    var here = state.root
    val folders = ArrayList<PrintFile>()
    for (step in trail) {
        val next = here.firstOrNull { it.isFolder && it.path == step } ?: break
        folders += next
        here = next.children
    }
    val entries = remember(here) {
        here.filter { it.isFolder }.sortedBy { it.display.lowercase() } +
            here.filter { !it.isFolder }.sortedByDescending { it.date }
    }
    val chosen = entries.firstOrNull { !it.isFolder && it.path == chosenPath }
        ?: entries.firstOrNull { !it.isFolder }

    Row(Modifier.fillMaxSize().padding(start = 4.dp, top = 14.dp, end = 22.dp, bottom = 20.dp)) {
        Column(Modifier.weight(1.12f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                if (folders.isNotEmpty()) {
                    IconButton(Icons.Left, { trail = trail.dropLast(1) }, size = 44.dp)
                    Spacer(Modifier.width(10.dp))
                    T(folders.last().display, Type.Title, Modifier.weight(1f), maxLines = 1)
                } else {
                    Spacer(Modifier.width(8.dp))
                    T("Files", Type.Title, Modifier.weight(1f))
                }
                if (state.loading) {
                    T("Updating", Type.Meta, color = Ink.Faint)
                    Spacer(Modifier.width(10.dp))
                }
                IconButton(Icons.Refresh, vm::refreshFiles, size = 44.dp, filled = false, enabled = !state.loading)
            }
            Spacer(Modifier.height(4.dp))

            when {
                entries.isNotEmpty() -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(entries, key = { it.origin + ":" + it.path }) { f ->
                        FileRow(
                            vm = vm,
                            file = f,
                            selected = f == chosen,
                            onClick = { if (f.isFolder) trail = trail + f.path else chosenPath = f.path },
                        )
                    }
                }
                state.loading -> Hint("Reading the file list from OctoPrint. A big library takes a few seconds.")
                state.error != null -> Column(Modifier.padding(8.dp)) {
                    T(state.error, Type.Body)
                    Spacer(Modifier.height(14.dp))
                    QuietButton("Try again", vm::refreshFiles, icon = Icons.Refresh)
                }
                folders.isNotEmpty() -> Hint("This folder is empty.")
                else -> Hint("Nothing here yet. Send a file from your slicer and it will show up.")
            }
        }

        Spacer(Modifier.width(22.dp))

        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 6.dp)) {
            if (chosen != null) Detail(vm, chosen, onAskPrint)
        }
    }
}

@Composable
private fun Hint(text: String) {
    T(text, Type.Body, Modifier.padding(8.dp))
}

@Composable
private fun FileRow(vm: PrinterViewModel, file: PrintFile, selected: Boolean, onClick: () -> Unit) {
    val clock24 = LocalDisplay.current.clock24
    val thumb = rememberPreview(vm, file).value.picture
    Row(
        Modifier.fillMaxWidth().height(68.dp).tap(onClick = onClick).clip(RoundedCornerShape(16.dp))
            .background(if (selected) Ink.PanelHi else Color.Transparent)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbTile(thumb, 52.dp, folder = file.isFolder)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(if (file.isFolder) file.display else prettyName(file.display), Type.Heading, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            if (file.isFolder) {
                val n = file.children.size
                T(if (n == 1) "1 item" else "$n items", Type.Meta, color = Ink.Faint)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    file.estimatedPrintTime?.let { T(duration(it.toLong()), Type.Meta, maxLines = 1) }
                    T(uploadedAt(file.date, clock24), Type.Meta, color = Ink.Faint, maxLines = 1)
                }
            }
        }
        if (file.isFolder) {
            Icon(Icons.Right, Ink.Faint, size = 20.dp)
        } else if (file.lastPrintSuccess != null) {
            // how the last attempt at this file went
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (file.lastPrintSuccess) Ink.Ok else Ink.Bad))
        }
        Spacer(Modifier.width(6.dp))
    }
}

@Composable
private fun ColumnScope.Detail(vm: PrinterViewModel, file: PrintFile, onAskPrint: (PrintFile) -> Unit) {
    val clock24 = LocalDisplay.current.clock24
    val shown by rememberPreview(vm, file)
    val phase = vm.printer?.phase

    ModelPreview(shown.picture, reveal = null, modifier = Modifier.fillMaxWidth().weight(1f), note = shown.note)
    Spacer(Modifier.height(14.dp))
    T(prettyName(file.display), Type.Title, maxLines = 2)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        file.estimatedPrintTime?.let { Figure("Print time", duration(it.toLong())) }
        file.filamentMm?.takeIf { it > 0 }?.let { Figure("Filament", filamentLength(it)) }
        when (file.lastPrintSuccess) {
            true -> Figure("Last time", "Finished")
            false -> Figure("Last time", "Failed")
            null -> Figure("Uploaded", uploadedAt(file.date, clock24))
        }
    }
    Spacer(Modifier.height(14.dp))
    SolidButton(
        text = when {
            phase == Phase.READY -> "Print"
            phase?.busy == true -> "Printer is busy"
            else -> "Printer is offline"
        },
        onClick = { onAskPrint(file) },
        modifier = Modifier.fillMaxWidth(),
        icon = if (phase == Phase.READY) Icons.Play else null,
        enabled = phase == Phase.READY,
    )
}

@Composable
private fun Figure(label: String, value: String) {
    Column {
        T(label, Type.Meta, color = Ink.Faint)
        Spacer(Modifier.height(1.dp))
        T(value, Type.Heading, maxLines = 1)
    }
}

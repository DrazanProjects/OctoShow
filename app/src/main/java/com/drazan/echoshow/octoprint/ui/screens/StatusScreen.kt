package com.drazan.echoshow.octoprint.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.drazan.echoshow.octoprint.PrinterViewModel
import com.drazan.echoshow.octoprint.data.DisplayPrefs
import com.drazan.echoshow.octoprint.octo.Outcome
import com.drazan.echoshow.octoprint.octo.Phase
import com.drazan.echoshow.octoprint.octo.PrintFile
import com.drazan.echoshow.octoprint.octo.PrinterSnapshot
import com.drazan.echoshow.octoprint.octo.Temp
import com.drazan.echoshow.octoprint.octo.TempSample
import com.drazan.echoshow.octoprint.ui.components.ButtonShape
import com.drazan.echoshow.octoprint.ui.components.CameraView
import com.drazan.echoshow.octoprint.ui.components.HeatTrace
import com.drazan.echoshow.octoprint.ui.components.HoldButton
import com.drazan.echoshow.octoprint.ui.components.Icon
import com.drazan.echoshow.octoprint.ui.components.Icons
import com.drazan.echoshow.octoprint.ui.components.LayerCallout
import com.drazan.echoshow.octoprint.ui.components.LayerStack
import com.drazan.echoshow.octoprint.ui.components.ModelPreview
import com.drazan.echoshow.octoprint.ui.components.QuietButton
import com.drazan.echoshow.octoprint.ui.components.SolidButton
import com.drazan.echoshow.octoprint.ui.components.StackMode
import com.drazan.echoshow.octoprint.ui.components.ThumbTile
import com.drazan.echoshow.octoprint.ui.components.tap
import com.drazan.echoshow.octoprint.ui.degrees
import com.drazan.echoshow.octoprint.ui.duration
import com.drazan.echoshow.octoprint.ui.durationParts
import com.drazan.echoshow.octoprint.ui.filamentLength
import com.drazan.echoshow.octoprint.ui.fromEpochMillis
import com.drazan.echoshow.octoprint.ui.prettyName
import com.drazan.echoshow.octoprint.ui.rememberGrowth
import com.drazan.echoshow.octoprint.ui.rememberPreview
import com.drazan.echoshow.octoprint.ui.theme.Ink
import com.drazan.echoshow.octoprint.ui.theme.LocalDisplay
import com.drazan.echoshow.octoprint.ui.theme.T
import com.drazan.echoshow.octoprint.ui.theme.Type
import com.drazan.echoshow.octoprint.ui.whenIs
import java.time.LocalDateTime

/**
 * The screen the display lives on. Left to right: what the print looks like
 * (camera or model), how far up it has got (the layer stack), and when it
 * will be done, with the two things you might want to do about it.
 */
@Composable
fun StatusScreen(
    vm: PrinterViewModel,
    prefs: DisplayPrefs,
    onFiles: () -> Unit,
    onAskPrint: (PrintFile) -> Unit,
    onFullCamera: () -> Unit,
    /** Set when there is a plug to switch everything off at. */
    onPower: (() -> Unit)?,
) {
    val p = vm.printer ?: return
    val outcome = vm.outcome
    val active = p.phase.busy

    // What the picture and the "print it" offer are about: the running job, or the newest upload.
    val subject = if (active || outcome != null) vm.jobFile else (vm.files.newest ?: vm.jobFile)

    val layerFraction = vm.layers.let { l ->
        val total = l.total
        if (l.known && total != null) (((l.current ?: 1) - 1).coerceAtLeast(0).toFloat() / total).coerceIn(0f, 1f) else null
    }
    val stackMode = when {
        outcome?.kind == Outcome.Kind.DONE -> StackMode.FULL
        p.phase == Phase.PAUSED || p.phase == Phase.PAUSING || p.phase == Phase.CANCELLING -> StackMode.HELD
        active -> StackMode.PRINTING
        else -> StackMode.EMPTY
    }

    Row(Modifier.fillMaxSize().padding(start = 4.dp, top = 20.dp, end = 22.dp, bottom = 20.dp)) {
        Column(Modifier.width(416.dp).fillMaxHeight()) {
            ViewPanel(
                vm = vm,
                prefs = prefs,
                file = subject,
                reveal = if (active) layerFraction ?: p.fraction else null,
                layer = vm.layers.current?.takeIf { active && vm.layers.known },
                laying = stackMode == StackMode.PRINTING,
                layerDone = vm.layers.layerProgress?.takeIf { vm.layers.known }?.let { it / 100f },
                onFullCamera = onFullCamera,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                HeatReadout("Nozzle", Icons.Nozzle, p.tool, Ink.Ember, vm.temps, { it.tool }, Modifier.weight(1f).fillMaxHeight())
                if (p.hasBed) {
                    HeatReadout("Bed", Icons.Bed, p.bed, Ink.Bed, vm.temps, { it.bed }, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }

        Spacer(Modifier.width(22.dp))
        LayerStack(
            fraction = p.fraction,
            layers = vm.layers,
            mode = stackMode,
            modifier = Modifier.width(118.dp).fillMaxHeight(),
            callout = { LayerCallout(vm.layers, p.currentZ) },
        )
        Spacer(Modifier.width(6.dp))

        // Power lives in the one corner whatever is going on. With nothing printing it is
        // "switch off"; under a job it is "switch off once this is done", which needs OctoPrint
        // to be willing to shut the Pi down before it is worth offering.
        val power = onPower?.takeIf { !active || vm.info.canShutDown }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize()) {
                when {
                    // a fault needs reading before anything about the last job does
                    p.phase == Phase.ERROR -> NotConnected(vm, p)
                    active -> Printing(vm, p)
                    outcome != null -> Finished(vm, outcome, subject, onAskPrint)
                    p.phase == Phase.READY -> Ready(vm, subject, onFiles, onAskPrint)
                    else -> NotConnected(vm, p)
                }
            }
            if (power != null) {
                PowerButton(
                    set = active && vm.offWhenDone, onClick = power,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-8).dp),
                )
            }
        }
    }
}

/** Filled in once the job that is running is to end with everything switched off. */
@Composable
private fun PowerButton(set: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.size(44.dp).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(if (set) Ink.Bone else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Power, if (set) Ink.Black else Ink.Soft, size = if (set) 18.dp else 22.dp)
        }
    }
}

/* ---------------- the picture ---------------- */

@Composable
private fun ViewPanel(
    vm: PrinterViewModel,
    prefs: DisplayPrefs,
    file: PrintFile?,
    reveal: Float?,
    /** Which layer is being printed, when OctoPrint says. */
    layer: Int?,
    /** Plastic is going down right now, as opposed to a job that is paused or winding up. */
    laying: Boolean,
    /** How much of the layer being printed is down, 0..1, when OctoPrint says. */
    layerDone: Float?,
    onFullCamera: () -> Unit,
    modifier: Modifier,
) {
    val cam = vm.info.webcam
    val api = vm.api
    val shown by rememberPreview(vm, file)
    val thumb = shown.picture
    val wantCamera = cam != null && api != null && prefs.view == "camera"
    // with the camera up there is no drawing to keep growing, so none is made
    val grown by rememberGrowth(vm, file, reveal.takeIf { !wantCamera }, layer)
    // A drawing of the layer before, still up while this layer's is being made, is of a layer
    // that is finished: shown complete, not emptied to match how little of the new one is down.
    val drawnLayer = grown?.layer
    val done = if (drawnLayer != null && drawnLayer != layer) 1f else layerDone
    // The panel takes the camera's own shape so the picture fills it edge to edge.
    var camShape by remember { mutableFloatStateOf(16f / 9f) }
    val shape = if (wantCamera) camShape.coerceIn(4f / 3f, 16f / 9f) else 4f / 3f

    Box(modifier.aspectRatio(shape)) {
        if (wantCamera) {
            CameraView(
                api = api!!,
                cam = cam!!,
                modifier = Modifier.fillMaxSize().tap(onClick = onFullCamera),
                onAspect = { camShape = it },
            ) {
                ModelPreview(thumb, reveal, Modifier.fillMaxSize(), note = shown.note, grown = grown, laying = laying, layerDone = done)
                T(
                    "The camera isn't answering", Type.Meta, color = Ink.Soft,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                )
            }
        } else {
            ModelPreview(thumb, reveal, Modifier.fillMaxSize(), note = shown.note, grown = grown, laying = laying, layerDone = done)
        }

        if (cam != null) {
            Row(
                Modifier.align(Alignment.TopStart).padding(10.dp).clip(CircleShape)
                    .background(Ink.Black.copy(alpha = 0.6f)).padding(3.dp),
            ) {
                ViewChoice(Icons.Camera, prefs.view == "camera") { vm.setView("camera") }
                ViewChoice(Icons.Cube, prefs.view != "camera") { vm.setView("model") }
            }
        }

        // A camera in a dark room shows nothing, so the lamp is one touch away from the picture.
        val lamp = vm.info.lights.firstOrNull()
        if (lamp != null && wantCamera) {
            val lit = lamp.percent > 0
            Box(
                Modifier.align(Alignment.TopEnd).padding(10.dp).size(44.dp)
                    .tap { vm.setLight(lamp, if (lit) 0 else 100) }
                    .clip(CircleShape).background(if (lit) Ink.Bone else Ink.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Bulb, if (lit) Ink.Black else Ink.Soft, size = 20.dp)
            }
        }
    }
}

@Composable
private fun ViewChoice(icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 46.dp, height = 38.dp).tap(onClick = onClick).clip(CircleShape)
            .background(if (selected) Ink.Bone else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, if (selected) Ink.Black else Ink.Soft, size = 20.dp)
    }
}

/* ---------------- heat ---------------- */

@Composable
private fun HeatReadout(
    label: String,
    icon: ImageVector,
    temp: Temp?,
    tint: Color,
    samples: List<TempSample>,
    pick: (TempSample) -> Float?,
    modifier: Modifier,
) {
    val on = temp?.on == true
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, if (on) tint else Ink.Faint, size = 18.dp)
            Spacer(Modifier.width(7.dp))
            T(label, Type.Label)
        }
        Spacer(Modifier.height(2.dp))
        Row {
            T(degrees(temp?.actual), Type.Reading, Modifier.alignByBaseline(), color = if (temp == null) Ink.Faint else Ink.Bone)
            Spacer(Modifier.width(9.dp))
            T(
                text = when {
                    temp == null -> ""
                    !on -> "off"
                    temp.heating -> "heating to ${degrees(temp.target)}"
                    else -> "holding ${degrees(temp.target)}"
                },
                style = Type.Meta,
                modifier = Modifier.alignByBaseline(),
                color = if (temp?.heating == true) tint else Ink.Faint,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(6.dp))
        // with the printer disconnected there is no reading, and an old trace would suggest one
        HeatTrace(if (temp == null) emptyList() else samples, pick, if (on) tint else Ink.Faint, Modifier.fillMaxWidth().weight(1f))
    }
}


/* ---------------- right-hand column, one composable per situation ---------------- */

@Composable
private fun ColumnScope.Printing(vm: PrinterViewModel, p: PrinterSnapshot) {
    val clock24 = LocalDisplay.current.clock24
    val warmingUp = (p.phase == Phase.STARTING || p.phase == Phase.PRINTING) &&
        (p.completion ?: 0.0) < 0.5 && (p.tool?.heating == true || p.bed?.heating == true)

    val (word, color) = when (p.phase) {
        Phase.PAUSED -> "Paused" to Ink.Warn
        Phase.PAUSING -> "Pausing" to Ink.Warn
        Phase.RESUMING -> "Resuming" to Ink.Ember
        Phase.CANCELLING -> "Cancelling" to Ink.Bad
        Phase.FINISHING -> "Finishing" to Ink.Ok
        else -> if (warmingUp) "Heating up" to Ink.Ember else "Printing" to Ink.Ember
    }
    StateLine(word, color)
    Spacer(Modifier.height(10.dp))
    T(prettyName(p.file?.display.orEmpty()), Type.Title, maxLines = 2)

    Spacer(Modifier.weight(1f))

    val left = p.printTimeLeft ?: run {
        val whole = p.estimatedPrintTime
        val spent = p.printTime
        if (whole != null && spent != null) (whole - spent).toLong().coerceAtLeast(0) else null
    }
    if (left != null) {
        HeroTime(left)
        Spacer(Modifier.height(2.dp))
        T(
            if (p.phase == Phase.PAUSED) "left when it resumes"
            else "left, done around ${whenIs(LocalDateTime.now().plusSeconds(left), clock24)}",
            Type.Body, size = 17.sp,
        )
    } else {
        T(if (warmingUp) "Warming up" else "Working out the time", Type.HeroWord, size = 30.sp)
    }
    // said in words beside the time it will happen, not just by the button having changed
    if (vm.offWhenDone) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Power, Ink.Bone, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            T("Switches off when done", Type.Label, color = Ink.Bone, size = 16.sp)
        }
    }

    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        p.printTime?.let { Fact("Running for", duration(it)) }
        if (vm.layers.known) p.currentZ?.let { Fact("Height", "%.1f mm".format(it)) }
    }

    Spacer(Modifier.weight(1f))

    // How far along, said large in the one place with room for it. The bar takes the state's
    // color, so a paused job looks paused here too.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Ink.Line)) {
            Box(Modifier.fillMaxWidth(p.fraction).fillMaxHeight().clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(14.dp))
        T("${(p.fraction * 100).toInt().coerceIn(0, 99)}%", Type.Reading, size = 26.sp)
    }
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (p.phase == Phase.PAUSED || p.phase == Phase.RESUMING) {
            SolidButton("Resume", vm::resume, Modifier.weight(1f), Icons.Play, enabled = p.phase == Phase.PAUSED)
        } else {
            SolidButton("Pause", vm::pause, Modifier.weight(1f), Icons.Pause, enabled = p.phase.canPause)
        }
        HoldButton("Cancel", "Hold to cancel", vm::cancel, Modifier.weight(1f), enabled = p.phase.canCancel)
    }
}

@Composable
private fun ColumnScope.Ready(
    vm: PrinterViewModel,
    newest: PrintFile?,
    onFiles: () -> Unit,
    onAskPrint: (PrintFile) -> Unit,
) {
    StateLine("Ready", Ink.Ok)
    Spacer(Modifier.height(10.dp))
    T("Nothing printing", Type.HeroWord)

    Spacer(Modifier.weight(1f))

    if (newest != null) {
        val shown by rememberPreview(vm, newest)
        val thumb = shown.picture
        T("Newest upload", Type.Label, color = Ink.Faint)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().tap { onAskPrint(newest) }.clip(ButtonShape).background(Ink.Panel).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ThumbTile(thumb, 54.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                T(prettyName(newest.display), Type.Heading, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                FileFigures(newest)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SolidButton("Print it", { onAskPrint(newest) }, Modifier.weight(1f), Icons.Play)
            QuietButton("All files", onFiles, Modifier.weight(1f))
        }
    } else {
        T(
            when {
                vm.files.loading -> "Reading the file list from OctoPrint."
                vm.files.error != null -> "The file list didn't load. Open Files to try again."
                else -> "Send a file from your slicer and it will show up here."
            },
            Type.Body,
        )
        Spacer(Modifier.height(14.dp))
        QuietButton("All files", onFiles, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ColumnScope.Finished(
    vm: PrinterViewModel,
    outcome: Outcome,
    file: PrintFile?,
    onAskPrint: (PrintFile) -> Unit,
) {
    val clock24 = LocalDisplay.current.clock24
    val (state, color, headline) = when (outcome.kind) {
        Outcome.Kind.DONE -> Triple("Finished", Ink.Ok, "Done")
        Outcome.Kind.CANCELLED -> Triple("Cancelled", Ink.Warn, "Cancelled")
        Outcome.Kind.FAILED -> Triple("Failed", Ink.Bad, "Print failed")
    }
    StateLine(state, color)
    Spacer(Modifier.height(10.dp))
    T(prettyName(outcome.fileName), Type.Title, maxLines = 2)

    Spacer(Modifier.weight(1f))

    T(headline, Type.HeroWord, size = 46.sp)
    Spacer(Modifier.height(6.dp))
    val took = outcome.seconds?.let(::duration)
    val at = outcome.atMillis.takeIf { it > 0 }?.let { whenIs(fromEpochMillis(it), clock24) }
    T(
        when (outcome.kind) {
            Outcome.Kind.DONE -> listOfNotNull(took?.let { "Took $it." }, at?.let { "Finished $it." }).joinToString(" ")
                .ifEmpty { "The part is ready to come off the bed." }
            Outcome.Kind.CANCELLED -> listOfNotNull(took?.let { "Stopped after $it." }, "Clear the bed before the next one.").joinToString(" ")
            Outcome.Kind.FAILED -> listOfNotNull(took?.let { "Stopped after $it." }, "Check the printer before trying again.").joinToString(" ")
        },
        Type.Body,
    )

    Spacer(Modifier.weight(1f))

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SolidButton("Bed is clear", vm::dismissOutcome, Modifier.weight(1f))
        if (file != null) QuietButton("Print again", { onAskPrint(file) }, Modifier.weight(1f))
    }
}

@Composable
private fun ColumnScope.NotConnected(vm: PrinterViewModel, p: PrinterSnapshot) {
    when (p.phase) {
        Phase.ERROR -> {
            StateLine("Error", Ink.Bad)
            Spacer(Modifier.height(10.dp))
            T("Printer error", Type.HeroWord)
            Spacer(Modifier.height(10.dp))
            T(p.errorText ?: p.stateText, Type.Body, maxLines = 6)
            Spacer(Modifier.weight(1f))
            SolidButton("Reconnect", vm::connectPrinter, Modifier.fillMaxWidth(), Icons.Plug)
        }
        Phase.CONNECTING -> {
            StateLine("Connecting", Ink.Soft)
            Spacer(Modifier.height(10.dp))
            T("Waking the printer", Type.HeroWord)
            Spacer(Modifier.height(10.dp))
            T(p.stateText, Type.Body)
        }
        else -> {
            StateLine("Offline", Ink.Faint)
            Spacer(Modifier.height(10.dp))
            T("Printer offline", Type.HeroWord)
            Spacer(Modifier.height(10.dp))
            T(
                p.errorText
                    ?: "OctoPrint is running but isn't connected to the printer. Switch the printer on, then connect.",
                Type.Body, maxLines = 6,
            )
            Spacer(Modifier.weight(1f))
            SolidButton("Connect printer", vm::connectPrinter, Modifier.fillMaxWidth(), Icons.Plug)
        }
    }
}

/* ---------------- small parts ---------------- */

@Composable
private fun StateLine(text: String, color: Color) {
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(9.dp))
        T(text, Type.Heading, color = color)
    }
}

/** Time remaining, with the numbers large and the units small. */
@Composable
private fun HeroTime(seconds: Long) {
    // As big as the column takes. Ten hours and more is a digit wider, so it gives a little.
    val scale = if (seconds >= 10 * 3600) 68f / 62f else 76f / 62f
    Row {
        durationParts(seconds).forEachIndexed { i, (number, unit) ->
            if (i > 0) Spacer(Modifier.width(12.dp))
            T(number, Type.Hero, Modifier.alignByBaseline(), size = Type.Hero.fontSize * scale)
            Spacer(Modifier.width(3.dp))
            T(unit, Type.HeroUnit, Modifier.alignByBaseline(), size = Type.HeroUnit.fontSize * scale)
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        T(label, Type.Meta, color = Ink.Faint, size = 15.sp)
        Spacer(Modifier.height(1.dp))
        T(value, Type.Title)
    }
}

/** Print time and filament for a file, side by side. */
@Composable
fun FileFigures(file: PrintFile, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        file.estimatedPrintTime?.let { T(duration(it.toLong()), Type.Meta, maxLines = 1) }
        file.filamentMm?.takeIf { it > 0 }?.let { T(filamentLength(it), Type.Meta, color = Ink.Faint, maxLines = 1) }
    }
}

package com.drazan.echoshow.octoprint.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.drazan.echoshow.octoprint.PrinterViewModel
import com.drazan.echoshow.octoprint.data.DisplayPrefs
import com.drazan.echoshow.octoprint.octo.Link
import com.drazan.echoshow.octoprint.octo.Phase
import com.drazan.echoshow.octoprint.octo.PrintFile
import com.drazan.echoshow.octoprint.octo.Prompt
import com.drazan.echoshow.octoprint.power.PowerOff
import com.drazan.echoshow.octoprint.power.PowerStep
import com.drazan.echoshow.octoprint.ui.components.ButtonShape
import com.drazan.echoshow.octoprint.ui.components.CameraView
import com.drazan.echoshow.octoprint.ui.components.Field
import com.drazan.echoshow.octoprint.ui.components.HoldButton
import com.drazan.echoshow.octoprint.ui.components.Icon
import com.drazan.echoshow.octoprint.ui.components.IconButton
import com.drazan.echoshow.octoprint.ui.components.Icons
import com.drazan.echoshow.octoprint.ui.components.ModelPreview
import com.drazan.echoshow.octoprint.ui.components.PanelShape
import com.drazan.echoshow.octoprint.ui.components.QuietButton
import com.drazan.echoshow.octoprint.ui.components.SolidButton
import com.drazan.echoshow.octoprint.ui.components.tap
import com.drazan.echoshow.octoprint.ui.screens.ControlsScreen
import com.drazan.echoshow.octoprint.ui.screens.FilesScreen
import com.drazan.echoshow.octoprint.ui.screens.SettingsScreen
import com.drazan.echoshow.octoprint.ui.screens.SetupScreen
import com.drazan.echoshow.octoprint.ui.screens.StatusScreen
import com.drazan.echoshow.octoprint.ui.theme.DisplayTheme
import com.drazan.echoshow.octoprint.ui.theme.Accents
import com.drazan.echoshow.octoprint.ui.theme.DisplayThemeProvider
import com.drazan.echoshow.octoprint.ui.theme.Grounds
import com.drazan.echoshow.octoprint.ui.theme.Ink
import com.drazan.echoshow.octoprint.ui.theme.Inks
import com.drazan.echoshow.octoprint.ui.theme.LocalDisplay
import com.drazan.echoshow.octoprint.ui.theme.Swatch
import com.drazan.echoshow.octoprint.ui.theme.T
import com.drazan.echoshow.octoprint.ui.theme.Type
import com.drazan.echoshow.octoprint.ui.theme.filamentById
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import kotlin.math.min

enum class Screen(val icon: ImageVector) {
    STATUS(Icons.Layers), FILES(Icons.Folder), CONTROLS(Icons.Sliders), SETTINGS(Icons.Gear)
}

@Composable
fun App(vm: PrinterViewModel, screenOff: Boolean) {
    val prefs by vm.display.collectAsState()
    DisplayThemeProvider(DisplayTheme(filamentById(prefs.filamentId), prefs.clock24)) {
        DesignCanvas {
            // Off means nothing is composed at all, so nothing animates or asks the camera for
            // frames behind the black. Touched awake, it starts again from the status screen.
            AnimatedVisibility(visible = !screenOff, enter = EnterTransition.None, exit = fadeOut(tween(600))) {
                Box(Modifier.fillMaxSize().background(Ink.Black)) {
                    when {
                        !vm.ready -> Unit
                        vm.server == null -> SetupScreen(vm)
                        else -> Shell(vm, prefs)
                    }
                }
            }
        }
    }
}

/**
 * Every measurement in this interface is in units of a 960 x 480 canvas, the
 * Echo Show 5's panel at one unit per pixel. The device doesn't report itself
 * that way: it claims 195 dpi, which would leave an ordinary Android layout
 * only 788 x 394 to work with and squeeze everything. So instead of trusting
 * the reported density, the canvas is fitted to whatever screen it is given.
 * The system font size is ignored for the same reason: there is one right size
 * for this text on this screen.
 */
@Composable
private fun DesignCanvas(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink.Black)) {
        val fit = min(constraints.maxWidth / DESIGN_WIDTH, constraints.maxHeight / DESIGN_HEIGHT)
        val density = if (constraints.hasBoundedWidth && constraints.hasBoundedHeight && fit > 0f) {
            Density(density = fit, fontScale = 1f)
        } else {
            LocalDensity.current
        }
        CompositionLocalProvider(LocalDensity provides density, content = content)
    }
}

private const val DESIGN_WIDTH = 960f
private const val DESIGN_HEIGHT = 480f

/** The rail on the left, the current screen beside it, and whatever is layered over both. */
@Composable
private fun BoxScope.Shell(vm: PrinterViewModel, prefs: DisplayPrefs) {
    var screen by rememberSaveable { mutableStateOf(Screen.STATUS) }
    var asking by remember { mutableStateOf<PrintFile?>(null) }
    var fullCamera by remember { mutableStateOf(false) }
    var editingPlug by remember { mutableStateOf(false) }
    var choosingColors by remember { mutableStateOf(false) }
    var askingWhenDone by remember { mutableStateOf(false) }

    // A dropped connection usually comes straight back, so keep showing the last
    // known state for a moment before admitting OctoPrint has gone.
    val link = vm.link
    var gone by remember { mutableStateOf(false) }
    LaunchedEffect(link is Link.Unreachable) {
        if (link is Link.Unreachable) {
            delay(12_000)
            gone = true
        } else {
            gone = false
        }
    }

    Row(Modifier.fillMaxSize()) {
        Rail(screen) { screen = it }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when {
                screen == Screen.SETTINGS -> SettingsScreen(
                    vm, prefs,
                    onColors = { choosingColors = true },
                    onPlug = { editingPlug = true },
                )
                link == Link.Unauthorized -> ServerProblem(
                    title = "This display was signed out",
                    body = "OctoPrint no longer accepts its access key. Set the display up again to carry on.",
                    action = "Set up again",
                    onAction = { vm.forgetServer() },
                )
                link == Link.Untrusted -> ServerProblem(
                    title = "This isn’t the OctoPrint it was",
                    body = "The server is showing a different security certificate from the one this display was set up with. " +
                        "If you reinstalled the Pi, set the display up again. If you didn’t, something else is answering at its address.",
                    action = "Set up again",
                    onAction = { vm.forgetServer() },
                )
                // With a plug set up, a printer that was switched off needn't look like one that has gone missing.
                link != Link.Online && vm.plugOn == false -> ServerProblem(
                    title = "Switched off",
                    body = "The plug has cut the power to the printer and the Pi.",
                    action = "Switch on",
                    onAction = { vm.switchOn() },
                )
                link != Link.Online && vm.startingUp -> ServerProblem(
                    title = "Starting up",
                    body = "The power is back on. OctoPrint takes a minute or two to wake.",
                    action = null,
                    onAction = {},
                )
                gone || (vm.printer == null && link is Link.Unreachable) -> ServerProblem(
                    title = "Can’t reach OctoPrint",
                    body = "Trying ${vm.server?.url.orEmpty().substringAfter("://").trimEnd('/')} again every few seconds. " +
                        "Check that the Pi is switched on and on the network.",
                    action = null,
                    onAction = {},
                )
                vm.printer == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    T("Connecting to OctoPrint", Type.Body)
                }
                else -> when (screen) {
                    Screen.FILES -> FilesScreen(vm) { asking = it }
                    Screen.CONTROLS -> ControlsScreen(vm)
                    else -> StatusScreen(
                        vm = vm,
                        prefs = prefs,
                        onFiles = { screen = Screen.FILES },
                        onAskPrint = { asking = it },
                        onFullCamera = { fullCamera = true },
                        onPower = if (vm.plug == null) null else ({
                            if (vm.printer?.phase?.busy == true) askingWhenDone = true else vm.askSwitchOff()
                        }),
                    )
                }
            }
        }
    }

    if (fullCamera) FullCamera(vm) { fullCamera = false }

    asking?.let { file ->
        PrintSheet(vm, file, onClose = { asking = null }, onStarted = {
            asking = null
            screen = Screen.STATUS
        })
    }

    if (editingPlug) PlugSheet(vm) { editingPlug = false }
    if (choosingColors) ColorsSheet(vm, prefs) { choosingColors = false }
    if (askingWhenDone) WhenDoneSheet(vm) { askingWhenDone = false }
    vm.power?.let { PowerSheet(vm, it) }

    // The printer wants an answer (filament ran out, say): this goes over everything.
    vm.prompt?.let { PromptSheet(it, vm::answerPrompt) }

    AnimatedVisibility(
        visible = vm.notice != null,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        // keep the last text while fading out
        var last by remember { mutableStateOf("") }
        vm.notice?.let { last = it }
        Row(
            Modifier.clip(CircleShape).background(Ink.PanelHi).border(1.dp, Ink.Line, CircleShape)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Alert, Ink.Warn, size = 18.dp)
            Spacer(Modifier.width(10.dp))
            T(last, Type.Label, color = Ink.Bone)
        }
    }
}

/* ---------------- rail ---------------- */

@Composable
private fun Rail(current: Screen, onSelect: (Screen) -> Unit) {
    val clock24 = LocalDisplay.current.clock24
    val now by rememberNow()
    Column(
        Modifier.width(76.dp).fillMaxHeight().padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        listOf(Screen.STATUS, Screen.FILES, Screen.CONTROLS).forEach { s ->
            RailItem(s, s == current) { onSelect(s) }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.weight(1f))
        Clock(now, clock24)
        Spacer(Modifier.height(12.dp))
        RailItem(Screen.SETTINGS, current == Screen.SETTINGS) { onSelect(Screen.SETTINGS) }
    }
}

@Composable
private fun RailItem(screen: Screen, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).tap(onClick = onClick).clip(RoundedCornerShape(17.dp))
            .background(if (selected) Ink.PanelHi else Ink.Black),
        contentAlignment = Alignment.Center,
    ) {
        Icon(screen.icon, if (selected) Ink.Bone else Ink.Faint, size = 24.dp)
    }
}

@Composable
private fun Clock(now: LocalDateTime, clock24: Boolean) {
    val text = clockTime(now, clock24)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        T(text.substringBefore(' '), Type.Meta, color = Ink.Soft, size = Type.Heading.fontSize)
        if (!clock24) T(text.substringAfter(' '), Type.Meta, color = Ink.Faint)
    }
}

/* ---------------- when OctoPrint itself is the problem ---------------- */

@Composable
private fun ServerProblem(title: String, body: String, action: String?, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(start = 12.dp, end = 60.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        T(title, Type.HeroWord)
        Spacer(Modifier.height(12.dp))
        T(body, Type.Body, Modifier.widthIn(max = 520.dp))
        if (action != null) {
            Spacer(Modifier.height(22.dp))
            SolidButton(action, onAction)
        }
    }
}

/* ---------------- overlays ---------------- */

/** Dims the screen behind a sheet. A tap outside closes it; taps on the sheet stay on the sheet. */
@Composable
private fun Scrim(onDismiss: (() -> Unit)?, content: @Composable () -> Unit) {
    val quiet = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(Ink.Black.copy(alpha = 0.82f))
            .clickable(interactionSource = quiet, indication = null) { onDismiss?.invoke() }
            // a sheet with something to type in stays clear of the keyboard
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.clickable(interactionSource = quiet, indication = null) {}) { content() }
    }
}

/** One last look before the head starts moving. */
@Composable
private fun PrintSheet(vm: PrinterViewModel, file: PrintFile, onClose: () -> Unit, onStarted: () -> Unit) {
    val clock24 = LocalDisplay.current.clock24
    val shown by rememberPreview(vm, file)
    val canPrint = vm.printer?.phase == Phase.READY

    Scrim(onClose) {
        Row(Modifier.width(640.dp).clip(PanelShape).background(Ink.Panel).padding(20.dp)) {
            Box(Modifier.size(196.dp).clip(ButtonShape).background(Ink.Black)) {
                ModelPreview(shown.picture, reveal = null, modifier = Modifier.fillMaxSize().padding(10.dp), plate = false, note = shown.note)
            }
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f).height(196.dp)) {
                T(prettyName(file.display), Type.Title, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                val seconds = file.estimatedPrintTime?.toLong()
                T(
                    if (seconds != null)
                        "About ${duration(seconds)}, so done around ${whenIs(LocalDateTime.now().plusSeconds(seconds), clock24)}."
                    else "OctoPrint hasn’t worked out how long this one takes yet.",
                    Type.Body,
                )
                Spacer(Modifier.height(4.dp))
                T(
                    if (canPrint) "Check the bed is clear before you start." else "The printer isn’t ready to start a job.",
                    Type.Body, color = if (canPrint) Ink.Bone else Ink.Warn,
                )
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SolidButton(
                        "Start print",
                        {
                            vm.print(file)
                            onStarted()
                        },
                        Modifier.weight(1f), Icons.Play, enabled = canPrint,
                    )
                    QuietButton("Not now", onClose, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PromptSheet(prompt: Prompt, onAnswer: (Int) -> Unit) {
    Scrim(onDismiss = null) {
        Column(Modifier.width(560.dp).clip(PanelShape).background(Ink.Panel).padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Alert, Ink.Warn, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                T("The printer needs you", Type.Label, color = Ink.Warn)
            }
            Spacer(Modifier.height(12.dp))
            T(prompt.text, Type.Title, maxLines = 5)
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                prompt.choices.forEachIndexed { i, choice ->
                    if (i == 0) SolidButton(choice, { onAnswer(i) }, Modifier.weight(1f))
                    else QuietButton(choice, { onAnswer(i) }, Modifier.weight(1f))
                }
            }
        }
    }
}

/** Everything off at the wall: the question first, then where it has got to. */
@Composable
private fun PowerSheet(vm: PrinterViewModel, step: PowerStep) {
    val failed = step is PowerStep.Failed
    Scrim(onDismiss = if (step == PowerStep.Confirm || failed) vm::dismissPower else null) {
        Column(Modifier.width(560.dp).clip(PanelShape).background(Ink.Panel).padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Power, if (failed) Ink.Warn else Ink.Soft, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                T("Power", Type.Label, color = if (failed) Ink.Warn else Ink.Soft)
            }
            Spacer(Modifier.height(12.dp))
            val (title, body) = when (step) {
                PowerStep.Confirm -> "Switch everything off?" to when {
                    !vm.info.canShutDown ->
                        "OctoPrint has no shutdown command this display may use, so the Pi can’t be shut down safely from here. " +
                            "One can be set up under Server in OctoPrint’s settings."
                    vm.displayCanGoOff ->
                        "The nozzle is left to cool and the Pi is shut down properly. Once the Pi has stopped answering, " +
                            "the plug cuts the power to it and the printer, and this display switches itself off."
                    else ->
                        "The nozzle is left to cool and the Pi is shut down properly. Once the Pi has stopped answering, " +
                            "the plug cuts the power to it and the printer. This display stays on."
                }
                is PowerStep.AfterPrint -> "The print is done" to
                    "Everything switches off in ${if (step.secondsLeft == 1) "1 second" else "${step.secondsLeft} seconds"}."
                is PowerStep.Cooling -> "Letting the nozzle cool" to
                    "It is at ${step.nozzle}°. The power stays on until it is under ${PowerOff.COOL_BELOW.toInt()}°, " +
                    "so the fan can keep the hot end from clogging."
                PowerStep.ShuttingDown -> "Shutting the Pi down" to "The power stays on until the Pi has stopped answering."
                is PowerStep.Settling -> "The Pi has gone quiet" to
                    "Giving it ${if (step.secondsLeft == 1) "1 more second" else "${step.secondsLeft} more seconds"} " +
                    "to finish before the power goes."
                is PowerStep.Failed -> "Still switched on" to step.message
                PowerStep.Off -> "Switched off" to "The printer and the Pi have no power. This display is switching itself off too."
            }
            T(title, Type.Title)
            Spacer(Modifier.height(8.dp))
            T(body, Type.Body)
            Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (step) {
                    PowerStep.Confirm -> {
                        HoldButton(
                            "Switch off", "Hold to switch off", vm::switchOff,
                            Modifier.weight(1f), enabled = vm.info.canShutDown,
                        )
                        QuietButton("Not now", vm::dismissPower, Modifier.weight(1f))
                    }
                    is PowerStep.AfterPrint -> QuietButton("Leave it on", vm::dismissPower, Modifier.weight(1f))
                    is PowerStep.Cooling -> QuietButton("Stop", vm::dismissPower, Modifier.weight(1f))
                    is PowerStep.Failed -> QuietButton("OK", vm::dismissPower, Modifier.weight(1f))
                    // past the point of stopping: the Pi has been told to go
                    else -> Unit
                }
            }
        }
    }
}

/** Whether the job that is running should end with everything switched off. */
@Composable
private fun WhenDoneSheet(vm: PrinterViewModel, onClose: () -> Unit) {
    val busy = vm.printer?.phase?.busy == true
    // the job this was about is over
    LaunchedEffect(busy) { if (!busy) onClose() }
    val set = vm.offWhenDone

    Scrim(onClose) {
        Column(Modifier.width(560.dp).clip(PanelShape).background(Ink.Panel).padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Power, Ink.Soft, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                T("Power", Type.Label)
            }
            Spacer(Modifier.height(12.dp))
            T(if (set) "Switching off when this print is done" else "Switch off when this print is done?", Type.Title)
            Spacer(Modifier.height(8.dp))
            T(
                "Once it has finished, the nozzle is left to cool, the Pi is shut down and the plug cuts the power" +
                    (if (vm.displayCanGoOff) ", and this display switches itself off. " else ". This display stays on. ") +
                    "A print that is cancelled or fails leaves everything on.",
                Type.Body,
            )
            Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (set) {
                    SolidButton("Keep it that way", onClose, Modifier.weight(1f))
                    QuietButton(
                        "Leave everything on",
                        {
                            vm.switchOffWhenDone(false)
                            onClose()
                        },
                        Modifier.weight(1f),
                    )
                } else {
                    SolidButton(
                        "Switch off when done",
                        {
                            vm.switchOffWhenDone(true)
                            onClose()
                        },
                        Modifier.weight(1f), Icons.Power,
                    )
                    QuietButton("Not now", onClose, Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * The three colors the rest are worked out from: what everything sits on, what
 * is written on it, and what is hot or under way. A choice takes at once, the
 * sheet itself included, so it can be judged before closing.
 */
@Composable
private fun ColorsSheet(vm: PrinterViewModel, prefs: DisplayPrefs, onClose: () -> Unit) {
    Scrim(onClose) {
        Column(Modifier.width(600.dp).clip(PanelShape).background(Ink.Panel).padding(24.dp)) {
            T("Colors", Type.Title)
            Spacer(Modifier.height(14.dp))
            Swatches("Background", Grounds, prefs.ground) { vm.setGround(it) }
            Spacer(Modifier.height(16.dp))
            Swatches("Text", Inks, prefs.ink) { vm.setInk(it) }
            Spacer(Modifier.height(16.dp))
            Swatches("Accent", Accents, prefs.accent) { vm.setAccent(it) }
            Spacer(Modifier.height(22.dp))
            QuietButton("Done", onClose, Modifier.fillMaxWidth(), height = 52.dp)
        }
    }
}

@Composable
private fun Swatches(title: String, choices: List<Swatch>, chosen: Int, onChoose: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        T(title, Type.Heading)
        choices.firstOrNull { it.color.toArgb() == chosen }?.let {
            Spacer(Modifier.width(10.dp))
            T(it.label, Type.Label, color = Ink.Faint)
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { swatch ->
            val on = swatch.color.toArgb() == chosen
            Box(
                Modifier.size(44.dp).tap { onChoose(swatch.color.toArgb()) }
                    .border(2.dp, if (on) Ink.Bone else Ink.Panel, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // an edge of its own, or a ground close to the sheet's would have none
                Box(Modifier.size(30.dp).clip(CircleShape).background(swatch.color).border(1.dp, Ink.Line, CircleShape))
            }
        }
    }
}

/** Where the smart plug lives. The display only keeps an address that something answers at. */
@Composable
private fun PlugSheet(vm: PrinterViewModel, onClose: () -> Unit) {
    var address by remember { mutableStateOf(vm.plug?.address.orEmpty()) }
    DisposableEffect(Unit) { onDispose { vm.clearPlugProblem() } }
    fun save() = vm.usePlug(address, onSaved = onClose)

    Scrim(onClose) {
        Column(Modifier.width(600.dp).clip(PanelShape).background(Ink.Panel).padding(24.dp)) {
            T("Power plug", Type.Title)
            Spacer(Modifier.height(8.dp))
            val problem = vm.plugProblem
            T(
                problem ?: "A Tasmota smart plug that the printer and the Pi are powered through. " +
                    "Given its address, this display can switch them off properly and back on.",
                Type.Body, color = if (problem != null) Ink.Warn else Ink.Soft,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(
                    address, { address = it }, "192.168.1.60", last = true, onDone = ::save,
                    modifier = Modifier.weight(1f), fill = Ink.PanelHi,
                )
                SolidButton("Save", ::save, enabled = address.isNotBlank(), height = 52.dp)
                if (vm.plug != null) {
                    QuietButton(
                        "Remove",
                        {
                            vm.forgetPlug()
                            onClose()
                        },
                        height = 52.dp,
                    )
                }
            }
        }
    }
}

/** The camera on the whole screen, with just enough on top to know how the job stands. */
@Composable
private fun FullCamera(vm: PrinterViewModel, onClose: () -> Unit) {
    val api = vm.api
    val cam = vm.info.webcam
    if (api == null || cam == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val p = vm.printer
    Box(Modifier.fillMaxSize().background(Ink.Black).tap(onClick = onClose)) {
        CameraView(api, cam, Modifier.fillMaxSize(), fps = 10, corner = 0.dp) {
            T("The camera isn’t answering", Type.Body, align = TextAlign.Center)
        }
        IconButton(Icons.Close, onClose, Modifier.align(Alignment.TopEnd).padding(14.dp))
        if (p != null && p.phase.busy) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(14.dp).clip(CircleShape)
                    .background(Ink.Black.copy(alpha = 0.66f)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                T("${(p.fraction * 100).toInt().coerceIn(0, 99)}%", Type.Heading)
                p.printTimeLeft?.let {
                    Spacer(Modifier.width(12.dp))
                    T("${duration(it)} left", Type.Label)
                }
            }
        }
    }
}

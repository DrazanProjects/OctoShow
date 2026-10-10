package com.drazan.echoshow.octoprint.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.drazan.echoshow.octoprint.PrinterViewModel
import com.drazan.echoshow.octoprint.octo.Light
import com.drazan.echoshow.octoprint.octo.Phase
import com.drazan.echoshow.octoprint.octo.Temp
import com.drazan.echoshow.octoprint.ui.components.Chip
import com.drazan.echoshow.octoprint.ui.components.Choice
import com.drazan.echoshow.octoprint.ui.components.Icon
import com.drazan.echoshow.octoprint.ui.components.IconButton
import com.drazan.echoshow.octoprint.ui.components.Icons
import com.drazan.echoshow.octoprint.ui.components.QuietButton
import com.drazan.echoshow.octoprint.ui.components.SetpointStepper
import com.drazan.echoshow.octoprint.ui.components.Toggle
import com.drazan.echoshow.octoprint.ui.degrees
import com.drazan.echoshow.octoprint.ui.theme.Ink
import com.drazan.echoshow.octoprint.ui.theme.T
import com.drazan.echoshow.octoprint.ui.theme.Type

/** The lowest nozzle temperature at which pushing filament is safe for the extruder. */
private const val MIN_EXTRUDE_TEMP = 170f

/**
 * Hands-on controls: heat, moving the head, filament. While a job runs the
 * movement column turns into speed and flow, since the head is not yours to move.
 */
@Composable
fun ControlsScreen(vm: PrinterViewModel) {
    val p = vm.printer ?: return
    val connected = p.phase != Phase.OFFLINE && p.phase != Phase.CONNECTING && p.phase != Phase.ERROR
    val busy = p.phase.busy

    Row(Modifier.fillMaxSize().padding(start = 12.dp, top = 22.dp, end = 22.dp, bottom = 20.dp)) {
        Heat(vm, p.tool, p.bed, p.hasBed, connected, Modifier.weight(1.08f).fillMaxHeight())
        Divider()
        if (busy) {
            Tune(vm, Modifier.weight(0.92f).fillMaxHeight())
        } else {
            Move(vm, connected, Modifier.weight(0.92f).fillMaxHeight())
        }
        Divider()
        Extras(vm, p.tool, connected, busy, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun Divider() {
    Spacer(Modifier.width(20.dp))
    Box(Modifier.width(1.dp).fillMaxHeight().background(Ink.Line))
    Spacer(Modifier.width(20.dp))
}

/* ---------------- heat ---------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Heat(vm: PrinterViewModel, tool: Temp?, bed: Temp?, hasBed: Boolean, connected: Boolean, modifier: Modifier) {
    Column(modifier) {
        T("Heat", Type.Title)
        Spacer(Modifier.height(14.dp))
        HeatRow("Nozzle", Icons.Nozzle, tool, Ink.Ember, connected, 0..280, 200, vm::setTool)
        if (hasBed) {
            Spacer(Modifier.height(14.dp))
            HeatRow("Bed", Icons.Bed, bed, Ink.Bed, connected, 0..100, 60, vm::setBed)
        }

        Spacer(Modifier.weight(1f))

        // OctoPrint can have no presets at all, and then there is nothing to introduce
        if (vm.info.presets.isNotEmpty()) {
            T("Preheat for", Type.Label, color = Ink.Faint)
            Spacer(Modifier.height(8.dp))
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            vm.info.presets.take(4).forEach { preset ->
                val on = tool?.target == preset.tool.toFloat() && (!hasBed || bed?.target == preset.bed.toFloat())
                Chip(preset.name, on, { vm.preheat(preset) }, enabled = connected, height = 44.dp)
            }
            Chip("Cool down", false, vm::coolDown, enabled = connected && (tool?.on == true || bed?.on == true), height = 44.dp)
        }
    }
}

@Composable
private fun HeatRow(
    label: String,
    icon: ImageVector,
    temp: Temp?,
    tint: Color,
    enabled: Boolean,
    range: IntRange,
    usual: Int,
    onCommit: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, if (temp?.on == true) tint else Ink.Faint, size = 18.dp)
                Spacer(Modifier.width(7.dp))
                T(label, Type.Label)
            }
            Spacer(Modifier.height(2.dp))
            T(degrees(temp?.actual), Type.Reading, color = if (temp == null) Ink.Faint else Ink.Bone)
        }
        SetpointStepper(
            value = temp?.target?.toInt() ?: 0,
            onCommit = onCommit,
            label = { if (it <= 0) "Off" else "$it°" },
            range = range,
            enabled = enabled,
            floorWhenOff = usual,
        )
    }
}

/* ---------------- move ---------------- */

@Composable
private fun Move(vm: PrinterViewModel, connected: Boolean, modifier: Modifier) {
    var step by remember { mutableDoubleStateOf(10.0) }
    Column(modifier) {
        T("Move", Type.Title)
        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // X and Y, with home in the middle
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Gap()
                    Jog(Icons.Up, connected) { vm.jog(y = step) }
                    Gap()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Jog(Icons.Left, connected) { vm.jog(x = -step) }
                    Jog(Icons.Home, connected) { vm.home("x", "y") }
                    Jog(Icons.Right, connected) { vm.jog(x = step) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Gap()
                    Jog(Icons.Down, connected) { vm.jog(y = -step) }
                    Gap()
                }
            }
            // Z on its own, so up and down can't be confused with back and forward
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Jog(Icons.Up, connected) { vm.jog(z = step.coerceAtMost(10.0)) }
                Jog(Icons.Home, connected) { vm.home("z") }
                Jog(Icons.Down, connected) { vm.jog(z = -step.coerceAtMost(10.0)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T("Left, right, back, forward", Type.Meta, color = Ink.Faint)
            T("Up, down", Type.Meta, color = Ink.Faint)
        }

        Spacer(Modifier.weight(1f))

        T("Distance per tap, mm", Type.Label, color = Ink.Faint)
        Spacer(Modifier.height(8.dp))
        Choice(listOf(0.1 to "0.1", 1.0 to "1", 10.0 to "10", 50.0 to "50"), step, { step = it }, enabled = connected)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton("Home all", { vm.home("x", "y", "z") }, Modifier.weight(1f), enabled = connected, height = 48.dp)
            QuietButton("Motors off", vm::motorsOff, Modifier.weight(1f), enabled = connected, height = 48.dp)
        }
    }
}

@Composable
private fun Jog(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    IconButton(icon, onClick, enabled = enabled, size = 54.dp)
}

@Composable
private fun Gap() {
    Spacer(Modifier.size(54.dp))
}

/* ---------------- tune (while printing) ---------------- */

@Composable
private fun Tune(vm: PrinterViewModel, modifier: Modifier) {
    Column(modifier) {
        T("Tune", Type.Title)
        Spacer(Modifier.height(14.dp))
        TuneRow("Speed", vm.feedRate, 50..150, vm::changeFeedRate)
        Spacer(Modifier.height(10.dp))
        TuneRow("Flow", vm.flowRate, 75..125, vm::changeFlowRate)
        Spacer(Modifier.weight(1f))
        T(
            "Moving the head by hand is locked while a job is running.",
            Type.Body, color = Ink.Faint,
        )
    }
}

@Composable
private fun TuneRow(label: String, value: Int, range: IntRange, onCommit: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        T(label, Type.Heading, Modifier.weight(1f))
        SetpointStepper(value = value, onCommit = onCommit, label = { "$it%" }, range = range)
    }
}

/* ---------------- filament, fan, lights ---------------- */

@Composable
private fun Extras(vm: PrinterViewModel, tool: Temp?, connected: Boolean, busy: Boolean, modifier: Modifier) {
    var amount by remember { mutableIntStateOf(10) }
    val hot = (tool?.actual ?: 0f) >= MIN_EXTRUDE_TEMP
    val canFeed = connected && !busy && hot

    Column(modifier.verticalScroll(rememberScrollState())) {
        T("Filament", Type.Title)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuietButton("Retract", { vm.extrude(-amount) }, Modifier.weight(1f), Icons.Up, enabled = canFeed, height = 52.dp)
            QuietButton("Extrude", { vm.extrude(amount) }, Modifier.weight(1f), Icons.Down, enabled = canFeed, height = 52.dp)
        }
        Spacer(Modifier.height(8.dp))
        if (canFeed) {
            Choice(listOf(5 to "5 mm", 10 to "10 mm", 50 to "50 mm"), amount, { amount = it })
        } else {
            T(
                when {
                    !connected -> "Connect the printer first."
                    busy -> "Locked while a job is running."
                    else -> "Heat the nozzle to ${MIN_EXTRUDE_TEMP.toInt()}° or more first."
                },
                Type.Body, color = Ink.Faint,
            )
        }

        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Part fan", Type.Heading, Modifier.weight(1f))
            vm.layers.fanPercent?.let { T("$it%", Type.Meta, color = Ink.Faint) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val fan = vm.layers.fanPercent
            Chip("Off", fan == 0, { vm.setFan(0) }, enabled = connected)
            Chip("Half", fan != null && fan in 40..60, { vm.setFan(50) }, enabled = connected)
            Chip("Full", fan != null && fan >= 99, { vm.setFan(100) }, enabled = connected)
        }

        vm.info.lights.forEach { light ->
            Spacer(Modifier.height(22.dp))
            LightRow(light) { vm.setLight(light, it) }
        }
    }
}

@Composable
private fun LightRow(light: Light, onSet: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Bulb, if (light.percent > 0) Ink.Warn else Ink.Faint, size = 20.dp)
        Spacer(Modifier.width(8.dp))
        T(light.name, Type.Heading, Modifier.weight(1f), maxLines = 1)
        Toggle(light.percent > 0, { onSet(if (it) 100 else 0) })
    }
    if (light.dimmable && light.percent > 0) {
        Spacer(Modifier.height(8.dp))
        Choice(listOf(25 to "Low", 60 to "Medium", 100 to "Full"), nearestLevel(light.percent), onSet)
    }
}

private fun nearestLevel(percent: Int): Int = when {
    percent <= 40 -> 25
    percent <= 80 -> 60
    else -> 100
}

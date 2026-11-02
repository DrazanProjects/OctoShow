package com.drazan.echoshow.octoprint.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.drazan.echoshow.octoprint.BuildConfig
import com.drazan.echoshow.octoprint.PrinterViewModel
import com.drazan.echoshow.octoprint.data.DisplayPrefs
import com.drazan.echoshow.octoprint.ui.components.ChipShape
import com.drazan.echoshow.octoprint.ui.components.Choice
import com.drazan.echoshow.octoprint.ui.components.DropdownField
import com.drazan.echoshow.octoprint.ui.components.DropdownList
import com.drazan.echoshow.octoprint.ui.components.HoldButton
import com.drazan.echoshow.octoprint.ui.components.Icon
import com.drazan.echoshow.octoprint.ui.components.Icons
import com.drazan.echoshow.octoprint.ui.components.tap
import com.drazan.echoshow.octoprint.ui.theme.Filaments
import com.drazan.echoshow.octoprint.ui.theme.Ink
import com.drazan.echoshow.octoprint.ui.theme.T
import com.drazan.echoshow.octoprint.ui.theme.Type

@Composable
fun SettingsScreen(vm: PrinterViewModel, prefs: DisplayPrefs, onColors: () -> Unit, onPlug: () -> Unit) {
    // where the screen-off field is, while its list of times is open
    var offList by remember { mutableStateOf<Rect?>(null) }
    Box(Modifier.fillMaxSize()) {
        Settings(vm, prefs, onColors, onPlug, offListOpen = offList != null, onOffList = { offList = it })
        offList?.let { anchor ->
            DropdownList(
                anchor = anchor,
                options = offAfterChoices(prefs.offAfterMin),
                selected = prefs.offAfterMin,
                onSelect = {
                    vm.setOffAfter(it)
                    offList = null
                },
                onDismiss = { offList = null },
            )
        }
    }
}

@Composable
private fun Settings(
    vm: PrinterViewModel,
    prefs: DisplayPrefs,
    onColors: () -> Unit,
    onPlug: () -> Unit,
    offListOpen: Boolean,
    onOffList: (Rect) -> Unit,
) {
    Row(Modifier.fillMaxSize().padding(start = 12.dp, top = 22.dp, end = 22.dp, bottom = 20.dp)) {
        Column(Modifier.weight(1.15f).fillMaxHeight()) {
            T("Display", Type.Title)
            Spacer(Modifier.height(16.dp))

            Setting("Colors", null) {
                // the three that are chosen, as they are now; the choosing happens on a sheet with room for it
                Row(
                    Modifier.height(40.dp).tap(onClick = onColors).clip(ChipShape).background(Ink.PanelHi).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Ink.Black).border(1.dp, Ink.Faint, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Ink.Bone))
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Ink.Ember))
                    Spacer(Modifier.width(12.dp))
                    T("Background, text and accent", Type.Label, color = Ink.Bone)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Right, Ink.Soft, size = 16.dp)
                }
            }
            Spacer(Modifier.height(20.dp))
            Setting("Filament color", "Tints the printed layers. Match it to your spool.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Filaments.forEach { f ->
                        val on = f.id == prefs.filamentId
                        Box(
                            Modifier.size(40.dp).tap { vm.setFilament(f.id) }
                                .border(2.dp, if (on) Ink.Bone else Ink.Black, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(f.color))
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Setting("Turn the screen off", "After this long without a touch. Touching it anywhere brings it back.") {
                DropdownField(offAfterName(prefs.offAfterMin), offListOpen, onOffList)
            }
            Spacer(Modifier.height(20.dp))
            Setting("Clock", null) {
                Choice(listOf(false to "12-hour", true to "24-hour"), prefs.clock24, { vm.setClock24(it) })
            }
        }

        Spacer(Modifier.width(20.dp))
        Box(Modifier.width(1.dp).fillMaxHeight().background(Ink.Line))
        Spacer(Modifier.width(20.dp))

        Column(Modifier.weight(1f).fillMaxHeight()) {
            T("OctoPrint", Type.Title)
            Spacer(Modifier.height(16.dp))
            Detail("Name", vm.info.name.ifEmpty { "OctoPrint" })
            Detail("Address", vm.server?.url.orEmpty().substringAfter("://").trimEnd('/'))
            if (vm.server?.url.orEmpty().startsWith("https")) {
                Detail("Security", if (vm.server?.cert != null) "Encrypted, certificate remembered" else "Encrypted")
            }
            if (vm.info.version.isNotEmpty()) Detail("Version", vm.info.version)
            // each of these says where the thing comes from, so that what is missing can be added
            Detail("Camera", if (vm.info.webcam != null) "Set up in OctoPrint" else "None set up in OctoPrint")
            vm.previewProgress?.let { (drawn, total) ->
                Detail("Previews", if (drawn >= total) "Drawn for all $total files" else "Drawn for $drawn of $total files")
            }
            Detail(
                "Layer count",
                if (vm.layers.total != null) "From the Dashboard plugin" else "Needs the Dashboard plugin",
            )
            Detail("Power plug", vm.plug?.address ?: "None. Touch to add one", onClick = onPlug)
            if (vm.plug != null) {
                Detail("This display", if (vm.displayCanGoOff) "Goes off with the printer" else "Stays on when the printer is off")
            }

            Spacer(Modifier.weight(1f))

            HoldButton(
                "Disconnect this display", "Hold to disconnect",
                { vm.forgetServer() },
                Modifier.fillMaxWidth(), height = 52.dp,
            )
            Spacer(Modifier.height(10.dp))
            T("OctoShow ${BuildConfig.VERSION_NAME}", Type.Meta, color = Ink.Faint)
        }
    }
}

/** Minutes without a touch before the screen goes off, shortest first; 0, never, comes last. */
private val OffAfterMinutes = listOf(1, 2, 5, 10, 15, 30, 60, 120, 240, 0)

/** The times on offer, with whatever a display already has set kept among them. */
private fun offAfterChoices(current: Int): List<Pair<Int, String>> {
    val times = if (current in OffAfterMinutes) OffAfterMinutes
    else (OffAfterMinutes - 0 + current).sorted() + 0
    return times.map { it to offAfterName(it) }
}

private fun offAfterName(min: Int): String = when {
    min <= 0 -> "Never"
    min % 60 == 0 -> if (min == 60) "1 hour" else "${min / 60} hours"
    min == 1 -> "1 minute"
    else -> "$min minutes"
}

@Composable
private fun Setting(title: String, about: String?, control: @Composable () -> Unit) {
    T(title, Type.Heading)
    if (about != null) {
        Spacer(Modifier.height(2.dp))
        T(about, Type.Body, color = Ink.Faint)
    }
    Spacer(Modifier.height(10.dp))
    control()
}

@Composable
private fun Detail(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        (if (onClick != null) Modifier.tap(onClick = onClick) else Modifier).fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(label, Type.Label, Modifier.width(110.dp), color = Ink.Faint)
        T(value, Type.Label, Modifier.weight(1f), color = Ink.Bone, maxLines = 1)
        // something to change, not just to read
        if (onClick != null) Icon(Icons.Right, Ink.Soft, size = 16.dp)
    }
}

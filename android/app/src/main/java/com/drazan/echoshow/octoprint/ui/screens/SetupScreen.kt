package com.drazan.echoshow.octoprint.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.drazan.echoshow.octoprint.PrinterViewModel
import com.drazan.echoshow.octoprint.SetupState
import com.drazan.echoshow.octoprint.octo.FoundServer
import com.drazan.echoshow.octoprint.octo.LayerInfo
import com.drazan.echoshow.octoprint.ui.components.ButtonShape
import com.drazan.echoshow.octoprint.ui.components.Field
import com.drazan.echoshow.octoprint.ui.components.Icon
import com.drazan.echoshow.octoprint.ui.components.Icons
import com.drazan.echoshow.octoprint.ui.components.LayerStack
import com.drazan.echoshow.octoprint.ui.components.QuietButton
import com.drazan.echoshow.octoprint.ui.components.SolidButton
import com.drazan.echoshow.octoprint.ui.components.StackMode
import com.drazan.echoshow.octoprint.ui.components.tap
import com.drazan.echoshow.octoprint.ui.theme.Ink
import com.drazan.echoshow.octoprint.ui.theme.T
import com.drazan.echoshow.octoprint.ui.theme.Type

/**
 * First run. The display finds OctoPrint on the network and asks it for an
 * access key; approving that request in OctoPrint's own web page is the only
 * step that needs another device, and it only happens once.
 */
@Composable
fun SetupScreen(vm: PrinterViewModel) {
    DisposableEffect(Unit) {
        vm.startDiscovery()
        onDispose { vm.stopDiscovery() }
    }

    val setup = vm.setup
    val waiting = setup is SetupState.Waiting || setup is SetupState.Checking

    Row(Modifier.fillMaxSize().imePadding().padding(horizontal = 40.dp, vertical = 30.dp)) {
        Column(Modifier.weight(0.92f).fillMaxHeight()) {
            // a small stack of layers, standing in for a logo
            LayerStack(
                fraction = if (waiting) 0.62f else 1f,
                layers = LayerInfo(),
                mode = if (waiting) StackMode.PRINTING else StackMode.FULL,
                modifier = Modifier.size(width = 46.dp, height = 54.dp),
            )
            Spacer(Modifier.weight(1f))
            when (setup) {
                is SetupState.Waiting -> {
                    T("Say yes in OctoPrint", Type.HeroWord)
                    Spacer(Modifier.height(12.dp))
                    T(
                        "Open OctoPrint in a browser on your computer or phone and sign in. " +
                            "It will ask whether “OctoShow” may connect. Choose Allow.",
                        Type.Body,
                    )
                }
                is SetupState.Checking -> {
                    T("Asking OctoPrint", Type.HeroWord)
                    Spacer(Modifier.height(12.dp))
                    T(setup.url.substringAfter("://").trimEnd('/'), Type.Body)
                }
                else -> {
                    T("Find your printer", Type.HeroWord)
                    Spacer(Modifier.height(12.dp))
                    T(
                        "This display works through OctoPrint. Pick yours from the list, or type its address.",
                        Type.Body,
                    )
                }
            }
        }

        Spacer(Modifier.width(44.dp))

        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (waiting) {
                Spacer(Modifier.weight(1f))
                T(
                    if (setup is SetupState.Waiting) "Waiting for your answer. This screen moves on by itself."
                    else "Checking that address.",
                    Type.Body,
                )
                Spacer(Modifier.height(16.dp))
                QuietButton("Cancel", vm::cancelSetup, Modifier.fillMaxWidth())
            } else {
                Choose(vm, (setup as? SetupState.Failed)?.message)
            }
        }
    }
}

@Composable
private fun Choose(vm: PrinterViewModel, problem: String?) {
    var address by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var byKey by remember { mutableStateOf(false) }

    fun go() {
        if (byKey) vm.useKey(address, key) else vm.requestAccess(address)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (problem != null) {
            Row(
                Modifier.fillMaxWidth().clip(ButtonShape).background(Ink.Panel).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Alert, Ink.Warn, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                T(problem, Type.Body, Modifier.weight(1f), color = Ink.Bone)
            }
            Spacer(Modifier.height(16.dp))
        }

        T("On this network", Type.Label, color = Ink.Faint)
        Spacer(Modifier.height(8.dp))
        if (vm.found.isEmpty()) {
            T("Looking for OctoPrint…", Type.Body)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.found.forEach { f -> FoundRow(f) { vm.requestAccess(f.url, f.name, announced = true) } }
            }
        }

        Spacer(Modifier.height(22.dp))
        T("Or by address", Type.Label, color = Ink.Faint)
        Spacer(Modifier.height(8.dp))
        Field(address, { address = it }, "octopi.local or 192.168.1.50", last = !byKey, onDone = ::go)
        if (byKey) {
            Spacer(Modifier.height(8.dp))
            Field(key, { key = it }, "Access key from OctoPrint’s settings", last = true, onDone = ::go)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SolidButton(
                if (byKey) "Connect" else "Ask to connect", ::go, Modifier.weight(1f),
                enabled = address.isNotBlank() && (!byKey || key.isNotBlank()), height = 52.dp,
            )
            QuietButton(if (byKey) "Ask instead" else "I have a key", { byKey = !byKey }, Modifier.weight(1f), height = 52.dp)
        }
    }
}

@Composable
private fun FoundRow(found: FoundServer, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).tap(onClick = onClick).clip(ButtonShape).background(Ink.PanelHi)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Wifi, Ink.Bone, size = 22.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            T(found.name.ifEmpty { "OctoPrint" }, Type.Heading, maxLines = 1)
            T(found.url.substringAfter("://").trimEnd('/'), Type.Meta, color = Ink.Faint, maxLines = 1)
        }
        Icon(Icons.Right, Ink.Soft, size = 20.dp)
    }
}

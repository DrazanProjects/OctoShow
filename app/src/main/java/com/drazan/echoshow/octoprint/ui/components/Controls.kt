package com.drazan.echoshow.octoprint.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.drazan.echoshow.octoprint.ui.theme.Ink
import com.drazan.echoshow.octoprint.ui.theme.T
import com.drazan.echoshow.octoprint.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shapes step down with importance: picture, action, small control. */
val PanelShape = RoundedCornerShape(24.dp)
val ButtonShape = RoundedCornerShape(18.dp)
val ChipShape = RoundedCornerShape(12.dp)

/** A tap with a quick dip in brightness instead of a ripple. Put it before any background. */
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val alpha by animateFloatAsState(if (pressed) 0.55f else 1f, tween(90), label = "press")
    graphicsLayer { this.alpha = alpha }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

/** The one thing to do next. */
@Composable
fun SolidButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 56.dp,
) {
    ButtonBody(
        modifier.height(height).tap(enabled, onClick).clip(ButtonShape)
            .background(if (enabled) Ink.Bone else Ink.PanelHi),
    ) {
        val ink = if (enabled) Ink.Black else Ink.Faint
        if (icon != null) {
            Icon(icon, ink, size = 20.dp)
            Spacer(Modifier.width(9.dp))
        }
        T(text, Type.Button, color = ink, maxLines = 1)
    }
}

/** Everything else you can press. */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 56.dp,
) {
    ButtonBody(modifier.height(height).tap(enabled, onClick).clip(ButtonShape).background(Ink.PanelHi)) {
        val ink = if (enabled) Ink.Bone else Ink.Faint
        if (icon != null) {
            Icon(icon, ink, size = 20.dp)
            Spacer(Modifier.width(9.dp))
        }
        T(text, Type.Button, color = ink, maxLines = 1)
    }
}

@Composable
private fun ButtonBody(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
fun IconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 48.dp,
    filled: Boolean = true,
    tint: Color = Ink.Bone,
) {
    Box(
        modifier.size(size).tap(enabled, onClick).clip(RoundedCornerShape(size * 0.32f))
            .background(if (filled) Ink.PanelHi else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, if (enabled) tint else Ink.Faint, size = 22.dp)
    }
}

/**
 * For the action you can't take back. A stray touch does nothing: the button
 * has to be held while it fills, and letting go early backs out.
 */
@Composable
fun HoldButton(
    text: String,
    hint: String,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    holdMillis: Int = 1400,
) {
    val progress = remember { Animatable(0f) }
    val holding by remember { derivedStateOf { progress.value > 0.02f } }
    var showHint by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val confirm by rememberUpdatedState(onConfirmed)

    LaunchedEffect(showHint) {
        if (showHint) {
            delay(2200)
            showHint = false
        }
    }

    Box(
        modifier
            .height(height)
            .clip(ButtonShape)
            .background(Ink.PanelHi)
            .drawBehind { drawRect(Ink.Bad, size = Size(size.width * progress.value, size.height)) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var fired = false
                    val fill = scope.launch {
                        progress.animateTo(1f, tween(holdMillis, easing = LinearEasing))
                        fired = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        confirm()
                    }
                    waitForUpOrCancellation()
                    fill.cancel()
                    if (!fired) showHint = true
                    scope.launch { progress.animateTo(0f, tween(180)) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        T(
            text = if (holding || showHint) hint else text,
            style = Type.Button,
            color = if (enabled) Ink.Bone else Ink.Faint,
            maxLines = 1,
        )
    }
}

/** One line of typing: an address, a key. */
@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    last: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    /** On a sheet, which is already panel-colored, the field needs the lighter one to show. */
    fill: Color = Ink.Panel,
) {
    Box(
        modifier.fillMaxWidth().height(52.dp).clip(ButtonShape).background(fill).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) T(placeholder, Type.Heading, color = Ink.Faint, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = Type.Heading,
            cursorBrush = SolidColor(Ink.Ember),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = if (last) ImeAction.Go else ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onGo = { onDone() }),
        )
    }
}

/** A choice among a few: filled when it's the current one. */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 40.dp,
) {
    Box(
        modifier.height(height).tap(enabled, onClick).clip(ChipShape)
            .background(if (selected) Ink.Bone else Ink.PanelHi)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(
            text, Type.Label,
            color = when {
                !enabled -> Ink.Faint
                selected -> Ink.Black
                else -> Ink.Bone
            },
            maxLines = 1,
        )
    }
}

@Composable
fun <V> Choice(
    options: List<Pair<V, String>>,
    selected: V,
    onSelect: (V) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            Chip(label, value == selected, { onSelect(value) }, enabled = enabled)
        }
    }
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val x by animateDpAsState(if (on) 25.dp else 3.dp, tween(140), label = "knob")
    Box(
        modifier.size(54.dp, 32.dp).tap { onChange(!on) }.clip(CircleShape)
            .background(if (on) Ink.Bone else Ink.Line),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = x).size(26.dp).clip(CircleShape).background(if (on) Ink.Black else Ink.Soft))
    }
}

/**
 * Minus / value / plus for a setpoint. Taps pile up locally and are sent once
 * the tapping stops, so stepping from 200 to 215 is one command, not three.
 */
@Composable
fun SetpointStepper(
    value: Int,
    onCommit: (Int) -> Unit,
    label: (Int) -> String,
    modifier: Modifier = Modifier,
    step: Int = 5,
    range: IntRange = 0..300,
    enabled: Boolean = true,
    floorWhenOff: Int = 0,
) {
    var draft by remember { mutableStateOf<Int?>(null) }
    val commit by rememberUpdatedState(onCommit)

    LaunchedEffect(draft) {
        val d = draft ?: return@LaunchedEffect
        delay(650)
        commit(d)
        // hold the draft until the printer echoes the new target back, then let go
        delay(2500)
        draft = null
    }
    LaunchedEffect(value) { if (draft == value) draft = null }

    val shown = draft ?: value
    fun nudge(by: Int) {
        // stepping up from "off" jumps straight to a useful temperature
        val from = if (shown <= 0 && by > 0 && floorWhenOff > 0) floorWhenOff - by else shown
        draft = (from + by).coerceIn(range)
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(Icons.Down, { nudge(-step) }, enabled = enabled && shown > range.first, size = 44.dp)
        Box(Modifier.width(74.dp), contentAlignment = Alignment.Center) {
            T(label(shown), Type.Heading, color = if (draft != null) Ink.EmberHi else Ink.Bone, maxLines = 1)
        }
        IconButton(Icons.Up, { nudge(step) }, enabled = enabled && shown < range.last, size = 44.dp)
    }
}

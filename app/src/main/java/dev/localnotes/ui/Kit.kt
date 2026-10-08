package dev.localnotes.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable fun Hairline(modifier: Modifier = Modifier, color: Color = P.line) = Box(modifier.fillMaxWidth().height(1.dp).background(color))
/** A heavier rule, used like a newspaper column rule above each day. */
@Composable fun Rule(modifier: Modifier = Modifier, color: Color = P.ink) = Box(modifier.fillMaxWidth().height(2.dp).background(color))

/** Small mono caps label above a section or beside a number. */
@Composable fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = P.muted) =
    Text(text.uppercase(), modifier, style = Type.label, color = color)

@Composable fun Muted(text: String, modifier: Modifier = Modifier, style: TextStyle = Type.small, align: TextAlign? = null) =
    Text(text, modifier, style = style, color = P.muted, textAlign = align)

/** A quiet text action with a full-size touch target. */
@Composable
fun TextAction(text: String, modifier: Modifier = Modifier, color: Color = P.ink, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(4.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 8.dp).alpha(if (enabled) 1f else 0.4f), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp), tint = color); Spacer(Modifier.width(7.dp)) }
        Text(text, style = Type.smallStrong, color = color)
    }
}

/** Primary action: a solid ink block with square-ish corners. */
@Composable
fun InkButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(P.ink).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 52.dp).padding(horizontal = 22.dp).alpha(if (enabled) 1f else 0.4f), contentAlignment = Alignment.Center) {
        Text(text, style = Type.bodyStrong, color = P.paper)
    }
}

/**
 * Bottom dock: two destinations as plain words, with the record knob between them.
 * It replaces a tab bar so the one thing you do most is always under your thumb.
 */
@Composable
fun Dock(selected: Int, labels: List<String>, onSelect: (Int) -> Unit, onRecord: () -> Unit, recordEnabled: Boolean) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().height(88.dp)) {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp).background(P.paper)) { Hairline(); }
        Row(Modifier.fillMaxWidth().padding(top = 24.dp).height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { i, label ->
                if (i == 1) Spacer(Modifier.width(104.dp))
                Column(Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab) { onSelect(i) }.semantics { this.selected = i == selected },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(label, style = Type.smallStrong, color = if (i == selected) P.ink else P.muted)
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.size(5.dp).clip(CircleShape).background(if (i == selected) P.rec else Color.Transparent))
                }
            }
        }
        RecordKnob(Modifier.align(Alignment.TopCenter), 68.dp, recordEnabled, onRecord)
    }
}

/** Record knob: an ink ring, a gap, and a red dot that sinks in when pressed. */
@Composable
fun RecordKnob(modifier: Modifier = Modifier, size: Dp = 68.dp, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dot by animateDpAsState(if (pressed) size * 0.34f else size * 0.5f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "knob")
    Box(modifier.size(size).clip(CircleShape).background(P.paper).border(2.dp, if (enabled) P.ink else P.line, CircleShape)
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "Start recording" }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(dot).clip(CircleShape).background(if (enabled) P.rec else P.line))
    }
}

/** Reading tabs: plain words with a short red underline under the one you are on. */
@Composable
fun TypeTabs(items: List<String>, selected: Int, modifier: Modifier = Modifier, ink: Color = P.ink, muted: Color = P.muted,
    line: Color = P.line, onSelect: (Int) -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            items.forEachIndexed { i, label ->
                val active = i == selected
                // Intrinsic width keeps the underline as wide as the word instead of filling the whole row.
                Column(Modifier.width(IntrinsicSize.Max).clickable(role = Role.Tab) { onSelect(i) }.semantics { this.selected = active }) {
                    Text(label, Modifier.padding(top = 14.dp, bottom = 9.dp), style = if (active) Type.bodyStrong else Type.body, color = if (active) ink else muted)
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) P.rec else Color.Transparent))
                }
            }
        }
        Hairline(color = line)
    }
}

/** Round control for the recording screen: Mark, Pause and Stop. */
@Composable
fun DeckControl(label: String, size: Dp, filled: Boolean, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).clip(CircleShape).then(if (filled) Modifier.background(P.rec) else Modifier.border(1.5.dp, P.deckLine, CircleShape))
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center, content = content)
        Spacer(Modifier.height(9.dp))
        Text(label.uppercase(), style = Type.label, color = P.deckMuted)
    }
}

/** Thin progress line; indeterminate when [progress] is null. */
@Composable
fun ThinProgress(progress: Float?, modifier: Modifier = Modifier, color: Color = P.rec, track: Color = P.line) {
    val moving = rememberInfiniteTransition(label = "p").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        val y = size.height / 2
        drawLine(track, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Butt)
        if (progress != null) drawLine(color, Offset(0f, y), Offset(size.width * progress.coerceIn(0f, 1f), y), size.height, StrokeCap.Butt)
        else {
            val x = moving.value * size.width * 1.3f - size.width * 0.3f
            drawLine(color, Offset(x.coerceAtLeast(0f), y), Offset((x + size.width * 0.3f).coerceAtMost(size.width), y), size.height, StrokeCap.Butt)
        }
    }
}

/** A softly pulsing dot for anything live. */
@Composable
fun PulseDot(color: Color = P.rec, size: Dp = 8.dp) {
    val a by rememberInfiniteTransition(label = "pulse").animateFloat(1f, 0.25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = a)))
}

/** Text input drawn as a line on paper, not a boxed field. */
@Composable
fun InkField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, label: String? = null, hint: String = "",
    singleLine: Boolean = true, minLines: Int = 1, maxLines: Int = if (singleLine) 1 else 8, onDeck: Boolean = false, testTag: String? = null) {
    val ink = if (onDeck) P.deckInk else P.ink
    val faded = if (onDeck) P.deckMuted else P.muted
    val rule = if (onDeck) P.deckLine else P.line
    Column(modifier) {
        if (label != null) { Eyebrow(label, color = faded); Spacer(Modifier.height(4.dp)) }
        Box(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            if (value.isEmpty()) Text(hint, style = Type.body, color = faded)
            BasicTextField(value, onChange, singleLine = singleLine, minLines = minLines, maxLines = maxLines,
                textStyle = Type.body.copy(color = ink), cursorBrush = SolidColor(P.rec),
                modifier = Modifier.fillMaxWidth().then(if (testTag != null) Modifier.testTag(testTag) else Modifier))
        }
        Hairline(color = if (onDeck) P.deckInk.copy(alpha = 0.5f) else P.ink.copy(alpha = 0.55f))
    }
}

/** A dialog on paper: serif title, content, and two text actions. */
@Composable
fun InkDialog(title: String, onDismiss: () -> Unit, confirm: String, onConfirm: () -> Unit, confirmEnabled: Boolean = true,
    confirmColor: Color = P.rec, cancel: String = "Cancel", onDeck: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        val surface = if (onDeck) P.deck else P.paper
        val ink = if (onDeck) P.deckInk else P.ink
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(surface)
            .border(1.dp, if (onDeck) P.deckLine else P.line, RoundedCornerShape(6.dp)).padding(horizontal = 22.dp, vertical = 20.dp)) {
            Text(title, style = Type.heading, color = ink)
            Spacer(Modifier.height(12.dp))
            content()
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextAction(cancel, color = if (onDeck) P.deckMuted else P.muted, onClick = onDismiss)
                Spacer(Modifier.width(6.dp))
                TextAction(confirm, color = confirmColor, enabled = confirmEnabled, onClick = onConfirm)
            }
        }
    }
}


package dev.localnotes.ui

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable fun Hairline(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(P.line))

/** Small uppercase label above a section. */
@Composable fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), modifier, style = Type.label, color = P.muted)

/** A text-only action in the accent colour, with a comfortable touch target. */
@Composable
fun TextAction(text: String, modifier: Modifier = Modifier, color: Color = P.accent, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 44.dp).padding(horizontal = 6.dp).alpha(if (enabled) 1f else 0.4f), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp), tint = color); Spacer(Modifier.width(6.dp)) }
        Text(text, style = Type.body, color = color)
    }
}

/** Primary studio action. */
@Composable
fun SolidAction(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(P.accent).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 20.dp).alpha(if (enabled) 1f else 0.4f), contentAlignment = Alignment.Center) {
        Text(text, style = Type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = P.onAccent)
    }
}

/** Floating navigation dock. */
@Composable
fun WordTabs(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(P.paper).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(P.raised).border(1.dp, P.line, RoundedCornerShape(22.dp))
            .padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEachIndexed { i, label ->
                val active = i == selected
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (active) P.accentSoft else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .semantics { contentDescription = label + if (active) ", selected" else "" }
                    .padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = Type.small.copy(fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal),
                        color = if (active) P.accent else P.muted)
                }
            }
        }
    }
}

/** Rounded view switcher, legible while recording. */
@Composable
fun TextTabs(items: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Column(modifier) {
        Row(Modifier.clip(RoundedCornerShape(16.dp)).background(P.raised).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            items.forEachIndexed { i, label ->
                val active = i == selected
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(if (active) P.accentSoft else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) }.padding(horizontal = 15.dp, vertical = 10.dp)) {
                    Text(label, style = Type.small.copy(fontWeight = if (active) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal),
                        color = if (active) P.accent else P.muted)
                }
            }
        }
    }
}

/** A luminous recording control, with two offset rings like an audio signal. */
@Composable
fun RecordRing(size: Dp = 112.dp, enabled: Boolean = true, color: Color = P.accent, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val inner by animateDpAsState(if (pressed) size * 0.46f else size * 0.56f, spring(dampingRatio = 0.5f), label = "dot")
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.10f))
        .border(1.5.dp, if (enabled) color.copy(alpha = 0.55f) else P.line, CircleShape)
        .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "Start recording" }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 0.8f).border(1.dp, color.copy(alpha = 0.45f), CircleShape))
        Box(Modifier.size(inner).clip(CircleShape).background(if (enabled)
            Brush.linearGradient(listOf(color, color.copy(alpha = 0.7f))) else Brush.linearGradient(listOf(P.line, P.line))))
    }
}

/** Round control used on the recording screen. */
@Composable
fun RoundControl(label: String, size: Dp, filled: Boolean, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).clip(CircleShape).then(if (filled) Modifier.background(P.accent) else Modifier.background(P.raised).border(1.5.dp, P.line, CircleShape))
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center, content = content)
        Spacer(Modifier.height(8.dp))
        Text(label, style = Type.small, color = P.muted)
    }
}

/** Thin progress line; indeterminate when [progress] is null. */
@Composable
fun ThinProgress(progress: Float?, modifier: Modifier = Modifier) {
    val color = P.accent; val track = P.line
    val moving = rememberInfiniteTransition(label = "p").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        drawLine(track, androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
        if (progress != null) drawLine(color, androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width * progress.coerceIn(0f, 1f), size.height / 2), size.height, StrokeCap.Round)
        else { val x = moving.value * size.width * 1.3f - size.width * 0.3f
            drawLine(color, androidx.compose.ui.geometry.Offset(x.coerceAtLeast(0f), size.height / 2), androidx.compose.ui.geometry.Offset((x + size.width * 0.3f).coerceAtMost(size.width), size.height / 2), size.height, StrokeCap.Round) }
    }
}

/** A softly pulsing dot for "working" states. */
@Composable
fun PulseDot(color: Color = P.accent, size: Dp = 8.dp) {
    val a by rememberInfiniteTransition(label = "pulse").animateFloat(1f, 0.25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = a)))
}

@Composable fun Muted(text: String, modifier: Modifier = Modifier, style: TextStyle = Type.small, align: TextAlign? = null) =
    Text(text, modifier, style = style, color = P.muted, textAlign = align)

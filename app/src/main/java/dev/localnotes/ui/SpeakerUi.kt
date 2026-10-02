package dev.localnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.localnotes.SpeakerNames
import dev.localnotes.Live
import java.io.File

private val brightVoices = listOf(Color(0xFFBEF06B), Color(0xFF6CDDF4), Color(0xFFFFA377), Color(0xFFC5A4FF), Color(0xFFFF89BD))
private val darkVoices = listOf(Color(0xFF56860B), Color(0xFF007F9F), Color(0xFFB85822), Color(0xFF6B4DC1), Color(0xFFB92E77))
@Composable fun voiceColor(id: Int): Color {
    val colors = if (isSystemInDarkTheme()) brightVoices else darkVoices
    return colors[(id - 1).coerceAtLeast(0) % colors.size]
}

@Composable
fun SpeakerChip(id: Int, session: File, onRename: (Int) -> Unit) {
    val color = voiceColor(id)
    val version by Live.transcriptVersion.collectAsState()
    val label = remember(id, session, version) { SpeakerNames(session).name(id) }
    Row(Modifier.testTag("speaker-$id").background(color.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
        .clickable { onRename(id) }.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("●", color = color, style = Type.small)
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, style = Type.small)
        Spacer(Modifier.width(3.dp))
        Text("✎", color = color.copy(alpha = 0.7f), style = Type.small)
    }
}

@Composable
fun RenameSpeakerDialog(id: Int, session: File, onClose: () -> Unit) {
    var name by remember(id, session) { mutableStateOf(SpeakerNames(session).name(id)) }
    AlertDialog(onDismissRequest = onClose, containerColor = P.raised,
        title = { Text("Name Speaker $id", style = Type.heading, color = P.ink) },
        text = { OutlinedTextField(name, { name = it.take(60) }, singleLine = true,
            label = { Text("Speaker name") }, supportingText = { Text("Tap a speaker label to change it anytime.") }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            SpeakerNames(session).rename(id, name); onClose()
        }) { Text("Save", color = P.accent) } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = P.muted) } })
}

package dev.localnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.localnotes.Live
import dev.localnotes.SpeakerNames
import java.io.File

// Six inks that stay distinguishable on paper and on the dark deck.
private val paperVoices = listOf(Color(0xFF2B59C3), Color(0xFF2E7D5A), Color(0xFFB7791F), Color(0xFF8E4FA8), Color(0xFF137C8B), Color(0xFFC23B6A))
private val deckVoices = listOf(Color(0xFF7FA2FF), Color(0xFF5FCB9A), Color(0xFFE7B04F), Color(0xFFC79BE0), Color(0xFF4FC3D1), Color(0xFFF28BAA))
fun voiceColor(id: Int, onDark: Boolean): Color = (if (onDark) deckVoices else paperVoices)[(id - 1).coerceAtLeast(0) % paperVoices.size]
@Composable fun voiceColor(id: Int): Color = voiceColor(id, isSystemInDarkTheme())

/** A speaker's name with a square of their colour. Tap to rename; the transcript keeps the stable number. */
@Composable
fun SpeakerTag(id: Int, session: File, onRename: (Int) -> Unit, onDeck: Boolean = false) {
    val color = voiceColor(id, onDeck || isSystemInDarkTheme())
    val version by Live.transcriptVersion.collectAsState()
    val label = remember(id, session, version) { SpeakerNames(session).name(id) }
    Row(Modifier.testTag("speaker-$id").clickable(role = Role.Button, onClickLabel = "Rename $label", onClick = { onRename(id) })
        .heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(color))
        Spacer(Modifier.width(7.dp))
        Text(label, style = Type.smallStrong, color = if (onDeck) P.deckInk else P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun RenameSpeakerDialog(id: Int, session: File, onDeck: Boolean = false, onClose: () -> Unit) {
    var name by remember(id, session) { mutableStateOf(SpeakerNames(session).name(id)) }
    InkDialog("Name speaker $id", onDismiss = onClose, confirm = "Save", confirmEnabled = name.isNotBlank(), onDeck = onDeck,
        onConfirm = { SpeakerNames(session).rename(id, name); onClose() }) {
        InkField(name, { name = it.take(60) }, label = "Speaker name", onDeck = onDeck)
        Text("Tap a name in the transcript to change it anytime.", Modifier.padding(top = 8.dp), style = Type.small,
            color = if (onDeck) P.deckMuted else P.muted)
    }
}

package dev.localnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.localnotes.*
import java.io.File
import kotlinx.coroutines.delay

// ---------------- Recordings ----------------

@Composable
fun LibraryScreen(open: (String) -> Unit) {
    val sessions = rememberSessions()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val matches = sessions.filter {
        "${it.title} ${it.date()} ${it.day()}".contains(query.trim(), ignoreCase = true) &&
            (filter == 0 || if (filter == 1) it.pending || it.error != null else it.durationMs >= 30 * 60_000L)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp)) {
        item {
            Text("Recordings", style = Type.title, color = P.ink)
            Spacer(Modifier.height(5.dp))
            Muted("${sessions.size} saved on this device")
            Spacer(Modifier.height(20.dp))
        }
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(P.raised).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Search, null, Modifier.size(18.dp), tint = P.muted); Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Muted("Search by name or date", style = Type.body)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = Type.body.copy(color = P.ink), cursorBrush = SolidColor(P.accent), modifier = Modifier.fillMaxWidth())
                }
                if (query.isNotEmpty()) TextAction("Clear", onClick = { query = "" })
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Needs attention", "30+ minutes").forEachIndexed { index, label ->
                    FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(label) })
                }
            }
        }
        if (matches.isEmpty()) item {
            Muted(when {
                sessions.isEmpty() -> "No recordings yet."
                query.isBlank() && filter == 1 -> "All caught up. No recordings need attention."
                query.isBlank() && filter == 2 -> "No recordings of 30 minutes or longer."
                else -> "No recordings match this search and filter."
            }, Modifier.padding(vertical = 24.dp), style = Type.body)
        }
        var lastDay = ""
        matches.forEach { session ->
            val day = session.day()
            if (day != lastDay) { lastDay = day; item(key = "day-$day") { SectionLabel(day, Modifier.padding(top = 22.dp, bottom = 2.dp)) } }
            item(key = session.id) { SessionRow(session, showDate = false) { open(session.id) } }
        }
    }
}

// ---------------- One recording ----------------

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun DetailScreen(id: String, actions: Actions, back: () -> Unit) {
    val context = LocalContext.current
    val sessions = rememberSessions()
    val session = sessions.firstOrNull { it.id == id } ?: run { LaunchedEffect(Unit) { back() }; return }
    val player = remember(id) { RecordingPlayer(context, File(session.dir, "audio.pcm")) }
    DisposableEffect(player) { onDispose { player.close() } }
    val working by Live.working.collectAsState()
    val recording by Live.recording.collectAsState()
    val activeId by Live.session.collectAsState()
    val summaryStatus by Live.summaryStatus.collectAsState()
    val draft by Live.summaryDraft.collectAsState()
    val busyHere = working && activeId == id
    val transcriptionPending = File(session.dir, ProcessingService.TRANSCRIBE_PENDING).exists()
    // Show already-written detailed notes while a long summary is still running.
    var page by rememberSaveable(id) { mutableIntStateOf(when {
        session.summary != null -> 0
        session.notes != null -> 1
        else -> 2
    }) }
    var renaming by remember { mutableStateOf(false) }
    var speakerToRename by remember { mutableStateOf<Int?>(null) }
    var segmentToEdit by remember { mutableStateOf<Segment?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var transcriptQuery by rememberSaveable(id) { mutableStateOf("") }
    var showTools by rememberSaveable(id) { mutableStateOf(false) }
    val nameVersion by Live.transcriptVersion.collectAsState()
    val speakerNames = remember(session.dir, nameVersion) { SpeakerNames(session.dir) }
    val segments = session.transcript.visible()
    val term = transcriptQuery.trim()
    val shownSegments = if (term.isBlank()) segments else segments.filter { segment ->
        segment.text.contains(term, ignoreCase = true) ||
            segment.speakerId?.let { speakerNames.name(it).contains(term, ignoreCase = true) } == true
    }
    val transcriptText = if (segments.isNotEmpty()) session.transcript.text() else session.legacyTranscript.orEmpty()
    val body = when (page) { 0 -> session.summary; 1 -> session.notes; else -> transcriptText.ifBlank { null } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction("‹  Recordings", color = P.muted, onClick = back)
            Spacer(Modifier.weight(1f))
            TextAction("Rename", color = P.muted) { renaming = true }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 24.dp)) {
          item {
            Column {
            Spacer(Modifier.height(6.dp))
            Text(session.title, style = Type.title, color = P.ink)
            Spacer(Modifier.height(8.dp))
            val end = RecordingInfo.endEpoch(session.dir)?.let { " – " + RecordingInfo.format(it, "HH:mm:ss") } ?: ""
            Muted("${RecordingInfo.format(session.startMs, "EEEE d MMMM yyyy")}\n${RecordingInfo.format(session.startMs, "HH:mm:ss")}$end · ${duration(session.durationMs)}",
                style = Type.small.merge(Tabular))
            Spacer(Modifier.height(14.dp))

            when {
                busyHere -> Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(); Spacer(Modifier.width(8.dp))
                    Text(summaryStatus.ifBlank { "Writing the summary" }, Modifier.weight(1f), style = Type.small, color = P.accent)
                    TextAction("Stop", color = P.muted, onClick = actions::stopProcessing)
                }
                session.pending -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Muted(when {
                        File(session.dir, ProcessingService.PROCESSING_ERROR).exists() -> "Processing stopped after an error. Tap Continue to retry."
                        recording || working -> "${if (transcriptionPending) "Transcription" else "Summary"} queued: it continues after the current task."
                        else -> "${if (transcriptionPending) "Transcription" else "Summary"} paused."
                    }, Modifier.weight(1f))
                    if (!recording && !working) TextAction("Continue") { actions.process(id, transcribe = transcriptionPending, resummarize = false) }
                }
                else -> Column {
                  TextAction(if (showTools) "Hide processing tools" else "Processing tools", icon = AppIcons.Tune) { showTools = !showTools }
                  if (showTools) Column {
                    val hasText = segments.isNotEmpty()
                    when {
                        !hasText -> TextAction("Transcribe") { actions.process(id, transcribe = true, resummarize = false) }
                        session.summary == null -> TextAction("Write summary") { actions.process(id, false, false) }
                        else -> TextAction("Summarize again") { actions.process(id, false, true) }
                    }
                    if (hasText) TextAction("Transcribe again", color = P.muted) { actions.process(id, true, false) }
                  }
                }
            }
            Spacer(Modifier.height(14.dp))
            PlayerCard(player)
            if (File(session.dir, "transcript-edited").exists() && session.summary != null) {
                Muted("Transcript corrected. Summarize again to update the notes and summary.",
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.accentSoft).padding(12.dp))
            }
            Spacer(Modifier.height(6.dp))
            TextTabs(listOf("Summary", "Notes", "Transcript"), page) { page = it }
            Spacer(Modifier.height(18.dp))
            }
          }
            if (busyHere && page < 2 && draft.isNotBlank()) item {
                MarkdownText(Writer.clean(draft), dim = true)
                Spacer(Modifier.height(14.dp))
            }
            if (page == 2 && segments.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.raised)
                    .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Search, null, Modifier.size(18.dp), tint = P.muted)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) {
                        if (transcriptQuery.isEmpty()) Muted("Find a word or speaker", style = Type.body)
                        BasicTextField(transcriptQuery, { transcriptQuery = it.take(100) }, singleLine = true,
                            textStyle = Type.body.copy(color = P.ink), cursorBrush = SolidColor(P.accent),
                            modifier = Modifier.fillMaxWidth().testTag("transcript-search"))
                    }
                    if (transcriptQuery.isNotEmpty()) TextAction("Clear", color = P.muted) { transcriptQuery = "" }
                }
                if (transcriptQuery.isNotBlank()) Muted("${shownSegments.size} matching passages · tap one to play it",
                    Modifier.padding(top = 8.dp, bottom = 10.dp))
            }
            if (body != null && page == 2 && segments.isNotEmpty()) {
                if (shownSegments.isEmpty()) item { Muted("No matching passages. Try another word or speaker.",
                    Modifier.padding(vertical = 16.dp), style = Type.body) }
                itemsIndexed(shownSegments, key = { index, segment -> "${segment.startMs}-$index" }) { _, segment ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { player.playFrom(segment.startMs) }
                        .padding(horizontal = 8.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            segment.speakerId?.let { SpeakerChip(it, session.dir) { selected -> speakerToRename = selected } }
                            Spacer(Modifier.width(8.dp))
                            Text(RecordingInfo.wallClockAt(session.dir, segment.startMs)?.let { RecordingInfo.format(it, "HH:mm:ss") } ?: "",
                                style = Type.label.merge(Tabular), color = P.faint)
                            if (!busyHere) {
                                Spacer(Modifier.weight(1f))
                                TextAction("Edit", color = P.muted) { segmentToEdit = segment }
                            }
                        }
                        Text(segment.text, style = Type.reading, color = P.ink)
                    }
                }
            } else if (body != null && page == 1) {
                itemsIndexed(body.lines()) { _, line -> MarkdownLine(line) }
            } else if (body != null) item {
                SelectionContainer { MarkdownText(body) }
            } else item {
                Muted(when (page) {
                    0, 1 -> if (Models.active(context, Role.SUMMARY) == null) "Download a summary model in Setup, then open Processing tools to write a summary." else "No summary yet. Open Processing tools to write one."
                    else -> "No transcript yet. Open Processing tools to transcribe this recording."
                }, style = Type.body)
            }
          item {
            Column {
            Spacer(Modifier.height(20.dp))
            Hairline()
            FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (body != null) TextAction("Copy", icon = AppIcons.Copy) { actions.copy(body) }
                TextAction("Share", icon = AppIcons.Share) {
                    actions.share(session.title + "\n\n" + listOfNotNull(session.summary, session.notes, transcriptText.ifBlank { null }).joinToString("\n\n"))
                }
                TextAction("Export", icon = AppIcons.Download) { actions.export(id) }
                TextAction("Delete", color = P.muted) { deleting = true }
            }
            session.error?.let { Text(it, Modifier.padding(top = 8.dp), style = Type.small, color = P.danger) }
            Muted("Written on this phone by speech recognition and a language model. Check important details against the audio.",
                Modifier.padding(vertical = 20.dp), style = Type.small)
            }
          }
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(session.title) }
        AlertDialog(onDismissRequest = { renaming = false }, containerColor = P.paper, title = { Text("Rename", style = Type.heading, color = P.ink) },
            text = { OutlinedTextField(name, { name = it.take(100) }, singleLine = true) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { Store.write(File(session.dir, "title.txt"), name.trim()); Live.notesVersion.value++; renaming = false }) { Text("Save", color = P.accent) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel", color = P.muted) } })
    }
    speakerToRename?.let { RenameSpeakerDialog(it, session.dir) { speakerToRename = null } }
    segmentToEdit?.let { segment ->
        var correction by remember(segment.startMs) { mutableStateOf(segment.text) }
        AlertDialog(onDismissRequest = { segmentToEdit = null }, containerColor = P.raised,
            title = { Text("Correct transcript", style = Type.heading, color = P.ink) },
            text = { OutlinedTextField(correction, { correction = it.take(2000) }, minLines = 3, maxLines = 10,
                label = { Text("What was said") }) },
            confirmButton = { TextButton(enabled = correction.isNotBlank(), onClick = {
                session.transcript.editText(segment.startMs, correction)
                Live.notesVersion.value++
                segmentToEdit = null
            }) { Text("Save", color = P.accent) } },
            dismissButton = { TextButton(onClick = { segmentToEdit = null }) { Text("Cancel", color = P.muted) } })
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, containerColor = P.paper, title = { Text("Delete this recording?", style = Type.heading, color = P.ink) },
        text = { Text("The audio, transcript and notes are removed from this phone. This can't be undone.", style = Type.body, color = P.muted) },
        confirmButton = { TextButton(onClick = { deleting = false; player.close(); session.dir.deleteRecursively(); Live.notesVersion.value++; back() }) { Text("Delete", color = P.danger) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel", color = P.muted) } })
}

/** Compact transport for a saved recording. Transcript rows seek to their exact audio offset. */
@Composable
private fun PlayerCard(player: RecordingPlayer) {
    val playing by player.playing.collectAsState()
    val position by player.positionMs.collectAsState()
    val error by player.error.collectAsState()
    var dragging by remember(player) { mutableStateOf<Float?>(null) }
    LaunchedEffect(player, playing) { while (playing) { player.updatePosition(); delay(200) } }
    val total = player.durationMs
    val fraction = dragging ?: if (total > 0) (position.toFloat() / total).coerceIn(0f, 1f) else 0f
    val signal = P.spark
    val white = P.heroInk
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(P.hero)
        .padding(horizontal = 16.dp, vertical = 14.dp)) {
        Column {
            Text("RECORDING", style = Type.label, color = signal)
            Spacer(Modifier.height(4.dp))
            Text("Tap a transcript line to play it", style = Type.small, color = white.copy(alpha = 0.7f))
        }
        Canvas(Modifier.fillMaxWidth().height(34.dp)
            .semantics {
                contentDescription = "Playback position"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { value -> player.seekTo((value.coerceIn(0f, 1f) * total).toLong()); true }
            }
            .pointerInput(total) {
                if (total <= 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val margin = 9.dp.toPx()
                    val width = (size.width - margin * 2).coerceAtLeast(1f)
                    fun fractionAt(x: Float) = ((x - margin) / width).coerceIn(0f, 1f)
                    dragging = fractionAt(down.position.x)
                    drag(down.id) { change -> dragging = fractionAt(change.position.x); change.consume() }
                    player.seekTo(((dragging ?: 0f) * total).toLong())
                    dragging = null
                }
            }) {
            val margin = 9.dp.toPx()
            val width = (size.width - margin * 2).coerceAtLeast(0f)
            val trackHeight = 6.dp.toPx()
            val top = (size.height - trackHeight) / 2
            drawRoundRect(white.copy(alpha = 0.28f), Offset(margin, top), Size(width, trackHeight), CornerRadius(trackHeight / 2))
            if (fraction > 0f) drawRoundRect(signal, Offset(margin, top), Size(width * fraction, trackHeight), CornerRadius(trackHeight / 2))
            drawCircle(signal, radius = margin, center = Offset(margin + width * fraction, size.height / 2))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(duration(if (dragging != null) (fraction * total).toLong() else position),
                style = Type.small.merge(Tabular), color = white)
            Text(" / ${duration(total)}", style = Type.small.merge(Tabular), color = white.copy(alpha = 0.6f))
            Spacer(Modifier.weight(1f))
            Text("−10", Modifier.clickable { player.seekTo(position - 10_000) }.padding(8.dp), style = Type.small, color = white)
            Spacer(Modifier.width(7.dp))
            Box(Modifier.size(45.dp).clip(RoundedCornerShape(15.dp)).background(signal).clickable { player.toggle() },
                contentAlignment = Alignment.Center) {
                Icon(if (playing) AppIcons.Pause else AppIcons.Play, if (playing) "Pause audio" else "Play audio",
                    Modifier.size(22.dp), tint = P.hero)
            }
            Spacer(Modifier.width(7.dp))
            Text("+10", Modifier.clickable { player.seekTo(position + 10_000) }.padding(8.dp), style = Type.small, color = white)
        }
        error?.let { Text(it, Modifier.padding(top = 7.dp), style = Type.small, color = Color(0xFFFFB6C2)) }
    }
}

/** Minimal Markdown: serif headings, bullets and bold. */
@Composable
fun MarkdownText(text: String, dim: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        text.lines().forEach { MarkdownLine(it, dim) }
    }
}

@Composable
private fun MarkdownLine(raw: String, dim: Boolean = false) {
    val color = if (dim) P.muted else P.ink
    val line = raw.trimEnd()
    val bullet = line.trimStart().let { it.startsWith("- ") || it.startsWith("* ") || it.startsWith("• ") }
    when {
        line.isBlank() -> Spacer(Modifier.height(4.dp))
        line.startsWith("#") -> Text(line.trimStart('#', ' '), Modifier.padding(top = 12.dp, bottom = 2.dp), style = Type.heading, color = color)
        bullet -> Row {
            Text("–", Modifier.width(18.dp), style = Type.reading, color = P.accent)
            Text(bold(line.trimStart().drop(2).trimStart()), style = Type.reading, color = color)
        }
        else -> Text(bold(line), style = Type.reading, color = color)
    }
}
private fun bold(line: String) = buildAnnotatedString {
    line.split("**").forEachIndexed { i, part -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) } else append(part) }
}

// ---------------- Setup ----------------

@Composable
fun SetupScreen(actions: Actions) {
    val context = LocalContext.current
    val downloads by Live.downloads.collectAsState()
    val status by Live.status.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(downloads.keys) { refresh++ }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
        Text("Setup", style = Type.title, color = P.ink)
        Spacer(Modifier.height(6.dp))
        Muted("Download once. After that, recording, transcription and summaries run on this phone without internet.", style = Type.body)
        if (status.isNotBlank()) Text(status, Modifier.padding(top = 10.dp), style = Type.small, color = P.accent)
        key(refresh) {
            val missing = Models.all.filter { it.recommended && !Models.installed(context, it) }
            if (missing.isNotEmpty() && downloads.isEmpty()) {
                Spacer(Modifier.height(20.dp))
                SolidAction("Download everything  ·  ${"%.1f".format(missing.sumOf { it.bytes } / 1e9)} GB", Modifier.fillMaxWidth()) { missing.forEach(actions::download) }
            }
            listOf(Triple(Role.LIVE, "Live transcript", "Shows words as you speak."),
                Triple(Role.ACCURATE, "Accuracy pass", "Re-checks each paragraph behind live text. Saved audio can be reprocessed if the live pass falls behind."),
                Triple(Role.SPEAKER, "Voice ID", "Labels different voices as Speaker 1, Speaker 2 and so on. Tap a label in the transcript to give it a name."),
                Triple(Role.SUMMARY, "Summary", "Writes notes during recording, then finishes pending notes and the concise overview in the background. Long sessions can take several minutes.")).forEach { (role, title, explain) ->
                Spacer(Modifier.height(30.dp))
                SectionLabel(title)
                Spacer(Modifier.height(4.dp))
                Muted(explain)
                Spacer(Modifier.height(6.dp))
                val active = Models.active(context, role)
                Models.all.filter { it.role == role }.forEach { spec ->
                    ModelRow(spec, Models.installed(context, spec), active?.id == spec.id, downloads[spec.id], actions,
                        choose = { Models.choose(context, spec); refresh++ }, delete = { Models.delete(context, spec); refresh++ })
                }
            }
        }
        if (downloads.isNotEmpty()) TextAction("Pause downloads", Modifier.padding(top = 12.dp), color = P.muted, onClick = actions::cancelDownloads)
        Spacer(Modifier.height(30.dp))
        Muted("Speech: sherpa-onnx with Kroko and NVIDIA Parakeet. Summary: llama.cpp. Recordings never leave this phone; the internet is only used to download models.")
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        Muted("Local Notes $version", Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun ModelRow(spec: ModelSpec, installed: Boolean, active: Boolean, progress: Float?, actions: Actions, choose: () -> Unit, delete: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(spec.name, style = Type.item, color = P.ink)
                Muted(spec.detail)
            }
            when {
                progress != null -> Text("${(progress * 100).toInt()}%", style = Type.small.merge(Tabular), color = P.accent)
                active -> Row(verticalAlignment = Alignment.CenterVertically) { Icon(AppIcons.Check, null, Modifier.size(16.dp), tint = P.accent); Spacer(Modifier.width(4.dp)); Text("In use", style = Type.small, color = P.accent) }
                installed -> TextAction("Use", onClick = choose)
                else -> TextAction("Download") { actions.download(spec) }
            }
        }
        if (progress != null) { ThinProgress(progress); Spacer(Modifier.height(10.dp)) }
        if (installed && !active) TextAction("Remove", color = P.muted, onClick = delete)
        Hairline()
    }
}

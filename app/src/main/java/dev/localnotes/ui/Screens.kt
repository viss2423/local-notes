package dev.localnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localnotes.*
import java.io.File
import kotlinx.coroutines.delay

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
    val positionState = player.positionMs.collectAsState()
    // Index of the line being heard; only the rows that change redraw while audio plays.
    val playingStart by remember(segments) { derivedStateOf {
        val position = positionState.value
        segments.lastOrNull { it.startMs <= position && position <= it.endMs + 400 }?.startMs
    } }
    val playing by player.playing.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction("Notes", icon = AppIcons.Back, color = P.ink, onClick = back)
            Spacer(Modifier.weight(1f))
            TextAction("Rename", color = P.muted) { renaming = true }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 24.dp)) {
            item {
                Column {
                    Spacer(Modifier.height(6.dp))
                    Text(session.title, style = Type.title, color = P.ink)
                    Spacer(Modifier.height(10.dp))
                    val end = RecordingInfo.endEpoch(session.dir)?.let { " – " + RecordingInfo.format(it, "HH:mm:ss") } ?: ""
                    Text(RecordingInfo.format(session.startMs, "EEE d MMM yyyy").uppercase(), style = Type.label, color = P.muted)
                    Text("${RecordingInfo.format(session.startMs, "HH:mm:ss")}$end · ${duration(session.durationMs)}", style = Type.mono, color = P.muted)
                    Spacer(Modifier.height(18.dp))
                    PlayerBlock(player, session, segments)
                    if (session.bookmarks.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                            Eyebrow("Flags", Modifier.padding(end = 12.dp))
                            session.bookmarks.forEach { FlagLink(it) { player.playFrom(it) } }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    when {
                        busyHere -> Row(verticalAlignment = Alignment.CenterVertically) {
                            PulseDot(); Spacer(Modifier.width(8.dp))
                            Text(summaryStatus.ifBlank { "Writing the summary" }, Modifier.weight(1f), style = Type.small, color = P.rec)
                            TextAction("Stop", color = P.muted, onClick = actions::stopProcessing)
                        }
                        session.pending -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Muted(when {
                                File(session.dir, ProcessingService.PROCESSING_ERROR).exists() -> "Processing stopped after an error. Continue to retry."
                                recording || working -> "${if (transcriptionPending) "Transcription" else "Summary"} queued: it continues after the current task."
                                else -> "${if (transcriptionPending) "Transcription" else "Summary"} paused."
                            }, Modifier.weight(1f))
                            if (!recording && !working) TextAction("Continue", color = P.rec) { actions.process(id, transcribe = transcriptionPending, resummarize = false) }
                        }
                        else -> Column {
                            TextAction(if (showTools) "Hide processing tools" else "Processing tools", icon = AppIcons.Tune, color = P.muted) { showTools = !showTools }
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
                    if (File(session.dir, "transcript-edited").exists() && session.summary != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Transcript corrected. Summarize again to update the notes and summary.", style = Type.small, color = P.ink,
                            modifier = Modifier.fillMaxWidth().background(P.markSoft, RoundedCornerShape(4.dp)).padding(12.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    TypeTabs(listOf("Summary", "Notes", "Transcript"), page) { page = it }
                    Spacer(Modifier.height(18.dp))
                }
            }
            if (busyHere && page < 2 && draft.isNotBlank()) item {
                MarkdownText(Writer.clean(draft), dim = true)
                Spacer(Modifier.height(14.dp))
            }
            if (page == 2 && segments.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Search, null, Modifier.size(19.dp), tint = P.ink)
                    Spacer(Modifier.width(10.dp))
                    InkField(transcriptQuery, { transcriptQuery = it.take(100) }, Modifier.weight(1f), hint = "Find a word or a speaker", testTag = "transcript-search")
                    if (transcriptQuery.isNotEmpty()) TextAction("Clear", color = P.muted) { transcriptQuery = "" }
                }
                if (transcriptQuery.isNotBlank()) Muted("${shownSegments.size} matching passages · tap one to play it",
                    Modifier.padding(top = 8.dp, bottom = 10.dp))
                else Spacer(Modifier.height(8.dp))
            }
            if (body != null && page == 2 && segments.isNotEmpty()) {
                if (shownSegments.isEmpty()) item { Muted("No matching passages. Try another word or speaker.", Modifier.padding(vertical = 16.dp), style = Type.body) }
                itemsIndexed(shownSegments, key = { index, segment -> "${segment.startMs}-$index" }) { _, segment ->
                    val current = playing && segment.startMs == playingStart
                    Row(Modifier.fillMaxWidth().background(if (current) P.markSoft else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { player.playFrom(segment.startMs) }.padding(horizontal = 8.dp, vertical = 11.dp)) {
                        Column(Modifier.width(96.dp).padding(end = 10.dp)) {
                            segment.speakerId?.let { SpeakerTag(it, session.dir, onRename = { selected -> speakerToRename = selected }) }
                            Text(RecordingInfo.wallClockAt(session.dir, segment.startMs)?.let { RecordingInfo.format(it, "HH:mm:ss") } ?: "",
                                style = Type.mono.copy(fontSize = 11.sp), color = P.muted)
                            if (!busyHere) Text("Edit", Modifier.clickable { segmentToEdit = segment }.padding(vertical = 8.dp),
                                style = Type.label.copy(textDecoration = TextDecoration.Underline), color = P.muted)
                        }
                        Text(segment.text, Modifier.weight(1f), style = Type.reading, color = P.ink)
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
                    Spacer(Modifier.height(22.dp))
                    Rule()
                    FlowRow(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (body != null) TextAction("Copy", icon = AppIcons.Copy) { actions.copy(body) }
                        TextAction("Share", icon = AppIcons.Share) {
                            actions.share(session.title + "\n\n" + listOfNotNull(session.summary, session.notes, transcriptText.ifBlank { null }).joinToString("\n\n"))
                        }
                        TextAction("Export", icon = AppIcons.Download) { actions.export(id) }
                        TextAction("Delete", color = P.danger) { deleting = true }
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
        InkDialog("Rename", onDismiss = { renaming = false }, confirm = "Save", confirmEnabled = name.isNotBlank(), onConfirm = {
            Store.write(File(session.dir, "title.txt"), name.trim()); Live.notesVersion.value++; renaming = false
        }) { InkField(name, { name = it.take(100) }, label = "Title") }
    }
    speakerToRename?.let { RenameSpeakerDialog(it, session.dir) { speakerToRename = null } }
    segmentToEdit?.let { segment ->
        var correction by remember(segment.startMs) { mutableStateOf(segment.text) }
        InkDialog("Correct the transcript", onDismiss = { segmentToEdit = null }, confirm = "Save", confirmEnabled = correction.isNotBlank(), onConfirm = {
            session.transcript.editText(segment.startMs, correction)
            Live.notesVersion.value++
            segmentToEdit = null
        }) { InkField(correction, { correction = it.take(2000) }, label = "What was said", singleLine = false, minLines = 3, maxLines = 10) }
    }
    if (deleting) InkDialog("Delete this recording?", onDismiss = { deleting = false }, confirm = "Delete", confirmColor = P.danger, onConfirm = {
        deleting = false; player.close(); session.dir.deleteRecursively(); Live.notesVersion.value++; back()
    }) { Text("The audio, transcript and notes are removed from this phone. This can't be undone.", style = Type.body, color = P.muted) }
}

/** The page's player: the recording as a waveform with speakers and flags, and plain transport controls. */
@Composable
private fun PlayerBlock(player: RecordingPlayer, session: SessionView, segments: List<Segment>) {
    val playing by player.playing.collectAsState()
    val position by player.positionMs.collectAsState()
    val error by player.error.collectAsState()
    LaunchedEffect(player, playing) { while (playing) { player.updatePosition(); delay(200) } }
    val total = player.durationMs
    val envelope = rememberEnvelope(session.dir)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(P.raised).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Scrubber(envelope, position, total, segments, session.bookmarks, onSeek = { player.seekTo(it) })
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(duration(position), style = Type.mono, color = P.ink)
            Text(" / ${duration(total)}", style = Type.mono, color = P.muted)
            Spacer(Modifier.weight(1f))
            Text("−10", Modifier.clickable { player.seekTo(position - 10_000) }.padding(horizontal = 10.dp, vertical = 12.dp), style = Type.mono, color = P.ink)
            Box(Modifier.size(52.dp).clip(CircleShape).background(P.ink).clickable { player.toggle() }, contentAlignment = Alignment.Center) {
                Icon(if (playing) AppIcons.Pause else AppIcons.Play, if (playing) "Pause audio" else "Play audio", Modifier.size(24.dp), tint = P.paper)
            }
            Text("+10", Modifier.clickable { player.seekTo(position + 10_000) }.padding(horizontal = 10.dp, vertical = 12.dp), style = Type.mono, color = P.ink)
        }
        Text("Tap a line in the transcript to play from there", Modifier.padding(top = 4.dp), style = Type.small, color = P.muted)
        error?.let { Text(it, Modifier.padding(top = 7.dp), style = Type.small, color = P.danger) }
    }
}

/** Minimal Markdown: serif headings, dashes and bold. */
@Composable
fun MarkdownText(text: String, dim: Boolean = false, onDeck: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        text.lines().forEach { MarkdownLine(it, dim, onDeck) }
    }
}

@Composable
private fun MarkdownLine(raw: String, dim: Boolean = false, onDeck: Boolean = false) {
    val color = when { onDeck && dim -> P.deckMuted; onDeck -> P.deckInk; dim -> P.muted; else -> P.ink }
    val line = raw.trimEnd()
    val bullet = line.trimStart().let { it.startsWith("- ") || it.startsWith("* ") || it.startsWith("• ") }
    when {
        line.isBlank() -> Spacer(Modifier.height(4.dp))
        line.startsWith("#") -> Text(line.trimStart('#', ' '), Modifier.padding(top = 14.dp, bottom = 2.dp), style = Type.heading.copy(fontStyle = FontStyle.Italic), color = color)
        bullet -> Row {
            Text("—", Modifier.width(24.dp), style = Type.reading, color = P.rec)
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 26.dp)) {
        Eyebrow("Models")
        Spacer(Modifier.height(2.dp))
        Text("Setup", style = Type.display, color = P.ink)
        Spacer(Modifier.height(10.dp))
        Muted("Download once. After that, recording, transcription and summaries run on this phone without internet.", style = Type.body)
        if (status.isNotBlank()) Text(status, Modifier.padding(top = 10.dp), style = Type.small, color = P.rec)
        key(refresh) {
            val missing = Models.all.filter { it.recommended && !Models.installed(context, it) }
            if (missing.isNotEmpty() && downloads.isEmpty()) {
                Spacer(Modifier.height(22.dp))
                InkButton("Download everything · ${"%.1f".format(missing.sumOf { it.bytes } / 1e9)} GB", Modifier.fillMaxWidth()) { missing.forEach(actions::download) }
            }
            listOf(Triple(Role.LIVE, "Live transcript", "Shows words as you speak."),
                Triple(Role.ACCURATE, "Accuracy pass", "Re-checks each paragraph behind live text. Saved audio can be reprocessed if the live pass falls behind."),
                Triple(Role.SPEAKER, "Voice labels", "Labels different voices as Speaker 1, Speaker 2 and so on. Tap a label in the transcript to give it a name."),
                Triple(Role.SUMMARY, "Summary", "Writes notes during recording, then finishes pending notes and the concise overview in the background. Long sessions can take several minutes.")).forEach { (role, title, explain) ->
                Spacer(Modifier.height(32.dp))
                Rule()
                Spacer(Modifier.height(10.dp))
                Text(title, style = Type.heading, color = P.ink)
                Spacer(Modifier.height(4.dp))
                Muted(explain)
                Spacer(Modifier.height(4.dp))
                val active = Models.active(context, role)
                Models.all.filter { it.role == role }.forEach { spec ->
                    ModelRow(spec, Models.installed(context, spec), active?.id == spec.id, downloads[spec.id], actions,
                        choose = { Models.choose(context, spec); refresh++ }, delete = { Models.delete(context, spec); refresh++ })
                }
            }
        }
        if (downloads.isNotEmpty()) TextAction("Pause downloads", Modifier.padding(top = 12.dp), color = P.muted, onClick = actions::cancelDownloads)
        Spacer(Modifier.height(32.dp))
        Muted("Speech: sherpa-onnx with Kroko and NVIDIA Parakeet. Summary: llama.cpp. Recordings never leave this phone; the internet is only used to download models.")
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        Text("LOCAL NOTES $version", Modifier.padding(top = 12.dp), style = Type.label, color = P.faint)
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
                progress != null -> Text("${(progress * 100).toInt()}%", style = Type.mono, color = P.rec)
                active -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(P.rec)); Spacer(Modifier.width(7.dp)); Eyebrow("In use", color = P.ink)
                }
                installed -> TextAction("Use", color = P.ink, onClick = choose)
                else -> TextAction("Download", color = P.rec) { actions.download(spec) }
            }
        }
        if (progress != null) { ThinProgress(progress); Spacer(Modifier.height(10.dp)) }
        if (installed && !active) TextAction("Remove", color = P.muted, onClick = delete)
        Hairline()
    }
}

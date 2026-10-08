package dev.localnotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localnotes.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface Actions {
    fun record()
    fun pause()
    fun stop()
    fun download(spec: ModelSpec)
    fun cancelDownloads()
    fun process(id: String, transcribe: Boolean, resummarize: Boolean)
    fun stopProcessing()
    fun export(id: String)
    fun share(text: String)
    fun copy(text: String)
}

enum class Tab(val label: String) { Notes("Notes"), Setup("Setup") }

@Composable
fun App(actions: Actions) {
    val recording by Live.recording.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Notes) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = !recording && (selected != null || tab != Tab.Notes)) { if (selected != null) selected = null else tab = Tab.Notes }

    Box(Modifier.fillMaxSize().background(P.paper)) {
        if (recording) RecordingScreen(actions)
        else Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.weight(1f)) {
                val id = selected
                when {
                    id != null -> DetailScreen(id, actions) { selected = null }
                    tab == Tab.Setup -> SetupScreen(actions)
                    else -> NotebookScreen(actions, openSetup = { tab = Tab.Setup }, open = { selected = it })
                }
            }
            WorkStrip(actions)
            if (selected == null) Dock(tab.ordinal, Tab.entries.map { it.label }, { tab = Tab.entries[it] }, actions::record, recordEnabled = true)
            else Spacer(Modifier.navigationBarsPadding())
        }
    }
}

/** One quiet line above the dock while a summary is being written in the background. */
@Composable
private fun WorkStrip(actions: Actions) {
    val working by Live.working.collectAsState()
    val recording by Live.recording.collectAsState()
    val status by Live.summaryStatus.collectAsState()
    AnimatedVisibility(working && !recording, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(Modifier.background(P.raised)) {
            ThinProgress(null)
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                PulseDot(size = 7.dp); Spacer(Modifier.width(10.dp))
                Text(status.ifBlank { "Writing the summary in the background" }, Modifier.weight(1f), style = Type.small, color = P.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextAction("Stop", color = P.muted, onClick = actions::stopProcessing)
            }
        }
    }
}

/** Re-reads lightweight recording metadata when the services save something. */
@Composable
fun rememberSessions(): List<SessionView> {
    val context = LocalContext.current
    val version by Live.transcriptVersion.collectAsState()
    val notes by Live.notesVersion.collectAsState()
    val session by Live.session.collectAsState()
    val recording by Live.recording.collectAsState()
    val working by Live.working.collectAsState()
    return remember(version, notes, session, recording, working) { Store.sessions(context).map(::SessionView) }
}

// ---------------- Notebook: every recording, newest first ----------------

@Composable
fun NotebookScreen(actions: Actions, openSetup: () -> Unit, open: (String) -> Unit) {
    val context = LocalContext.current
    val sessions = rememberSessions()
    val status by Live.status.collectAsState()
    val downloads by Live.downloads.collectAsState()
    val recording by Live.recording.collectAsState()
    val activeId by Live.session.collectAsState()
    val liveReady = remember(downloads) { Models.active(context, Role.LIVE) != null }
    val voicesReady = remember(downloads) { Models.active(context, Role.SPEAKER) != null }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val matches = sessions.filter {
        "${it.title} ${it.date()} ${it.day()}".contains(query.trim(), ignoreCase = true) &&
            (filter == 0 || if (filter == 1) it.pending || it.error != null else it.durationMs >= 30 * 60_000L)
    }
    val totalMs = sessions.sumOf { it.durationMs }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 24.dp)) {
        item {
            Column {
                Eyebrow(RecordingInfo.format(System.currentTimeMillis(), "EEEE d MMMM"))
                Spacer(Modifier.height(2.dp))
                Text("Notebook", style = Type.display, color = P.ink)
                Spacer(Modifier.height(6.dp))
                Muted(if (sessions.isEmpty()) "Nothing recorded yet" else "${sessions.size} recordings · ${spokenDuration(totalMs)} of audio", style = Type.mono)
                Spacer(Modifier.height(20.dp))
            }
        }
        if (!liveReady) item {
          Column {
            Column(Modifier.fillMaxWidth().border(1.dp, P.ink, RoundedCornerShape(4.dp)).padding(18.dp)) {
                Eyebrow("One-time setup", color = P.rec)
                Spacer(Modifier.height(6.dp))
                Text("Live words need a download", style = Type.heading, color = P.ink)
                Spacer(Modifier.height(4.dp))
                val downloadGb = Models.all.filter { it.recommended }.sumOf { it.bytes } / 1e9
                Muted("About ${"%.1f".format(downloadGb)} GB, once. After that everything runs offline on this phone.", style = Type.body)
                Spacer(Modifier.height(8.dp))
                TextAction("Open setup", icon = AppIcons.Download, color = P.rec, onClick = openSetup)
            }
            Spacer(Modifier.height(22.dp))
          }
        } else if (!voicesReady) item {
          Column {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(P.raised).clickable(onClick = openSetup).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Add voice labels to tell speakers apart", Modifier.weight(1f), style = Type.small, color = P.ink)
                Icon(AppIcons.Back, null, Modifier.size(16.dp).rotate(180f), tint = P.ink)
            }
            Spacer(Modifier.height(22.dp))
          }
        }
        if (status.isNotBlank()) item { Muted(status, Modifier.padding(bottom = 16.dp)) }
        if (sessions.isNotEmpty()) item {
          Column {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Search, null, Modifier.size(19.dp), tint = P.ink)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (query.isEmpty()) Text("Search by name or date", style = Type.body, color = P.muted)
                    BasicTextField(query, { query = it.take(80) }, singleLine = true, textStyle = Type.body.copy(color = P.ink),
                        cursorBrush = SolidColor(P.rec), modifier = Modifier.fillMaxWidth())
                }
                if (query.isNotEmpty()) TextAction("Clear", color = P.muted) { query = "" }
            }
            Hairline(color = P.ink.copy(alpha = 0.55f))
            TypeTabs(listOf("All", "Needs attention", "30+ min"), filter) { filter = it }
          }
        }
        if (matches.isEmpty()) item {
            Column(Modifier.padding(vertical = 40.dp)) {
                Text(when {
                    sessions.isEmpty() -> "A blank page."
                    query.isBlank() && filter == 1 -> "All caught up."
                    query.isBlank() && filter == 2 -> "Nothing that long yet."
                    else -> "No match."
                }, style = Type.heading.copy(fontStyle = FontStyle.Italic), color = P.ink)
                Spacer(Modifier.height(6.dp))
                Muted(when {
                    sessions.isEmpty() -> "Press the red button to record. Words appear as you speak, and notes are written as you go."
                    query.isBlank() && filter == 1 -> "No recording is waiting for a summary or needs a retry."
                    query.isBlank() && filter == 2 -> "Recordings of 30 minutes or more will show up here."
                    else -> "Try a different word, or clear the filter."
                }, style = Type.body)
            }
        }
        var lastDay = ""
        matches.forEach { session ->
            val day = session.day()
            if (day != lastDay) {
                lastDay = day
                val count = matches.count { it.day() == day }
                item(key = "day-$day") {
                    Column(Modifier.padding(top = 28.dp)) {
                        Rule()
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                            Text(day, Modifier.weight(1f), style = Type.heading.copy(fontStyle = FontStyle.Italic), color = P.ink)
                            Eyebrow(if (count == 1) "1 entry" else "$count entries")
                        }
                    }
                }
            }
            item(key = session.id) { Entry(session, live = recording && activeId == session.id) { open(session.id) } }
        }
    }
}

/** One recording in the notebook: start time and length on the left, title and its waveform on the right. */
@Composable
fun Entry(session: SessionView, live: Boolean = false, onClick: () -> Unit) {
    val envelope = rememberEnvelope(session.dir, live)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Column(Modifier.width(60.dp)) {
                Text(RecordingInfo.format(session.startMs, "HH:mm"), style = Type.mono.copy(fontSize = 15.sp), color = P.ink)
                Spacer(Modifier.height(2.dp))
                Text(duration(session.durationMs), style = Type.mono.copy(fontSize = 12.sp), color = P.muted)
            }
            Column(Modifier.weight(1f)) {
                Text(session.title, style = Type.item, color = P.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(9.dp))
                MiniWave(envelope)
                val status = session.status()
                if (status.isNotEmpty()) {
                    Spacer(Modifier.height(7.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val active = status == "Recording" || status == "Writing summary"
                        if (active) { PulseDot(size = 6.dp); Spacer(Modifier.width(6.dp)) }
                        Eyebrow(status, color = if (status == "Needs attention") P.danger else if (active) P.rec else P.muted)
                    }
                }
            }
        }
        Hairline()
    }
}

// ---------------- Live recording: always on the dark deck ----------------

@Composable
fun RecordingScreen(actions: Actions) {
    val context = LocalContext.current
    val id by Live.session.collectAsState()
    val paused by Live.paused.collectAsState()
    val elapsed by Live.elapsedMs.collectAsState()
    val partial by Live.partial.collectAsState()
    val version by Live.transcriptVersion.collectAsState()
    val notesVersion by Live.notesVersion.collectAsState()
    val draft by Live.summaryDraft.collectAsState()
    val summaryStatus by Live.summaryStatus.collectAsState()
    val status by Live.status.collectAsState()
    val dir = remember(id) { id?.let { s -> Store.sessions(context).firstOrNull { it.name == s } } }
    val segments = remember(version, dir) { dir?.let { Transcript(it).visible() } ?: emptyList() }
    val summary = remember(notesVersion, dir) { dir?.let { java.io.File(it, "summary.md").takeIf { f -> f.exists() }?.readText() } }
    val flags = remember(notesVersion, dir) { dir?.let { Bookmarks.list(it) } ?: emptyList() }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var speakerToRename by remember { mutableStateOf<Int?>(null) }
    var markedAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(markedAt) { if (markedAt != null) { delay(2600); markedAt = null } }

    Column(Modifier.fillMaxSize().background(P.deck).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (paused) Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(P.deckMuted)) else PulseDot(size = 9.dp)
            Spacer(Modifier.width(10.dp))
            Text(if (paused) "PAUSED" else "REC", style = Type.label.copy(fontSize = 13.sp), color = if (paused) P.deckMuted else P.rec)
            Spacer(Modifier.weight(1f))
            dir?.let { Text("SINCE " + RecordingInfo.format(RecordingInfo.startEpoch(it), "HH:mm"), style = Type.label, color = P.deckMuted) }
        }
        Text(clock(elapsed), Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = Type.timer, color = P.deckInk)
        Spacer(Modifier.height(10.dp))
        Oscilloscope(paused, Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp))
        if (segments.any { it.speakerId != null }) {
            Spacer(Modifier.height(12.dp))
            Column(Modifier.padding(horizontal = 24.dp)) {
                SpeakerLane(segments, elapsed, onDark = true, height = 5.dp)
            }
        }
        Spacer(Modifier.height(6.dp))
        TypeTabs(listOf("Transcript", "Notes"), page, Modifier.padding(horizontal = 24.dp), ink = P.deckInk, muted = P.deckMuted, line = P.deckLine) { page = it }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (page == 0) LiveTranscript(segments, partial, dir, Models.active(context, Role.LIVE) != null,
                Models.active(context, Role.SPEAKER) != null, { speakerToRename = it })
            else LiveNotes(summary, draft, summaryStatus, Models.active(context, Role.SUMMARY) != null)
        }
        val note = markedAt?.let { "FLAG DROPPED AT ${clock(it)}" } ?: status
        if (note.isNotBlank()) Text(note, Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = Type.label,
            color = if (markedAt != null) P.mark else P.deckMuted)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
            DeckControl(if (flags.isEmpty()) "Flag" else "Flag · ${flags.size}", 58.dp, filled = false, onClick = {
                dir?.let { Bookmarks.add(it, elapsed); markedAt = elapsed }
            }) { Icon(AppIcons.Flag, null, Modifier.size(24.dp), tint = P.deckInk) }
            DeckControl("Stop & save", 78.dp, filled = true, onClick = actions::stop) {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(P.onRec))
            }
            DeckControl(if (paused) "Resume" else "Pause", 58.dp, filled = false, onClick = actions::pause) {
                Icon(if (paused) AppIcons.Play else AppIcons.Pause, null, Modifier.size(24.dp), tint = P.deckInk)
            }
        }
    }
    speakerToRename?.let { selected -> dir?.let { RenameSpeakerDialog(selected, it, onDeck = true) { speakerToRename = null } } }
}

/** The live level as a scrolling trace that fades in from the left, like an oscilloscope. */
@Composable
private fun Oscilloscope(paused: Boolean, modifier: Modifier) {
    val levels = remember { mutableStateListOf<Float>().apply { repeat(64) { add(0f) } } }
    LaunchedEffect(Unit) { while (true) { levels.removeAt(0); levels.add(Live.level.value); delay(100) } }
    val color = if (paused) P.deckMuted else P.rec
    val rule = P.deckLine
    Canvas(modifier) {
        val mid = size.height / 2
        drawLine(rule, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        val step = size.width / levels.size
        levels.forEachIndexed { i, level ->
            val h = maxOf(2.dp.toPx(), level * size.height)
            val x = i * step + step / 2
            val fade = (i.toFloat() / levels.size).let { it * it }.coerceIn(0.08f, 1f)
            drawLine(color.copy(alpha = fade), Offset(x, mid - h / 2), Offset(x, mid + h / 2), 2.5.dp.toPx(), StrokeCap.Round)
        }
    }
}

/**
 * Live transcript list. It follows new text only while you are at the bottom; scroll up to read and it
 * stays put, with a "Latest" button to jump back.
 */
@Composable
private fun LiveTranscript(segments: List<Segment>, partial: String, dir: java.io.File?, ready: Boolean,
    voicesReady: Boolean, rename: (Int) -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by remember { mutableStateOf(true) }
    val atBottom by remember { derivedStateOf {
        val info = list.layoutInfo; val last = info.visibleItemsInfo.lastOrNull()
        last == null || (last.index >= info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 8)
    } }
    LaunchedEffect(list.isScrollInProgress) { if (list.isScrollInProgress) follow = atBottom else if (atBottom) follow = true }
    val count = segments.size + 1
    LaunchedEffect(count, partial.length / 12) { if (follow) list.scrollToItem(count - 1, Int.MAX_VALUE / 2) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            itemsIndexed(segments, key = { i, s -> "${s.startMs}-$i" }) { _, segment ->
                Row {
                    Column(Modifier.width(92.dp).padding(end = 10.dp)) {
                        if (dir != null) segment.speakerId?.let { SpeakerTag(it, dir, rename, onDeck = true) }
                        val clock = dir?.let { RecordingInfo.wallClockAt(it, segment.startMs) }?.let { RecordingInfo.format(it, "HH:mm:ss") } ?: ""
                        Text(clock, style = Type.mono.copy(fontSize = 11.sp), color = P.deckMuted)
                    }
                    Text(segment.text, Modifier.weight(1f), style = Type.reading, color = if (segment.refined) P.deckInk else P.deckInk.copy(alpha = 0.72f))
                }
            }
            item(key = "partial") {
                Row {
                    Column(Modifier.width(92.dp).padding(end = 10.dp)) {
                        if (voicesReady && partial.isNotBlank()) Text("LISTENING", style = Type.label.copy(fontSize = 10.sp), color = P.deckMuted)
                    }
                    Text(when { partial.isNotBlank() -> partial; segments.isEmpty() && ready -> "Start talking. Words appear here as you speak."
                        !ready -> "Live text is off. Download the speech models in Setup; your audio is still being saved."; else -> "" },
                        Modifier.weight(1f), style = Type.reading.copy(fontStyle = if (partial.isBlank()) FontStyle.Italic else FontStyle.Normal), color = P.rec)
                }
            }
        }
        AnimatedVisibility(!follow, Modifier.align(Alignment.BottomCenter).padding(10.dp), enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.clip(RoundedCornerShape(4.dp)).background(P.rec).clickable { follow = true; scope.launch { list.animateScrollToItem(count - 1, Int.MAX_VALUE / 2) } }
                .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Down, null, Modifier.size(16.dp), tint = P.onRec); Spacer(Modifier.width(6.dp)); Text("Latest", style = Type.smallStrong, color = P.onRec)
            }
        }
    }
}

@Composable
private fun LiveNotes(summary: String?, draft: String, status: String, ready: Boolean) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
        when {
            !ready -> Text("Download a summary model in Setup to get notes while you record.", style = Type.body, color = P.deckMuted)
            summary == null && draft.isBlank() && status.isBlank() ->
                Text("Notes start after a few minutes of talking and are finished when you stop.", style = Type.body, color = P.deckMuted)
        }
        if (status.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) { PulseDot(); Spacer(Modifier.width(8.dp)); Text(status, style = Type.small, color = P.rec) }
            Spacer(Modifier.height(10.dp))
        }
        if (draft.isNotBlank()) { MarkdownText(Writer.clean(draft), dim = true, onDeck = true); Spacer(Modifier.height(16.dp)) }
        summary?.let { MarkdownText(it, onDeck = true) }
    }
}

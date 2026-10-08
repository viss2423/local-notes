package dev.localnotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

enum class Tab(val label: String) { Record("Home"), Library("Recordings"), Setup("Setup") }

@Composable
fun App(actions: Actions) {
    val recording by Live.recording.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Record) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = !recording && (selected != null || tab != Tab.Record)) { if (selected != null) selected = null else tab = Tab.Record }

    Box(Modifier.fillMaxSize().background(P.paper)) {
            if (recording) RecordingScreen(actions)
            else Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Box(Modifier.weight(1f)) {
                    val id = selected
                    when {
                        id != null -> DetailScreen(id, actions) { selected = null }
                        tab == Tab.Library -> LibraryScreen { selected = it }
                        tab == Tab.Setup -> SetupScreen(actions)
                        else -> HomeScreen(actions, openSetup = { tab = Tab.Setup }, open = { selected = it }, openAll = { tab = Tab.Library })
                    }
                }
                WorkStrip(actions)
                if (selected == null) WordTabs(Tab.entries.map { it.label }, tab.ordinal) { tab = Tab.entries[it] }
                else Spacer(Modifier.navigationBarsPadding())
            }
    }
}

/** One quiet line at the bottom while a summary is being written in the background. */
@Composable
private fun WorkStrip(actions: Actions) {
    val working by Live.working.collectAsState()
    val recording by Live.recording.collectAsState()
    val status by Live.summaryStatus.collectAsState()
    AnimatedVisibility(working && !recording, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column {
            ThinProgress(null)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(status.ifBlank { "Writing the summary in the background" }, Modifier.weight(1f), style = Type.small, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Composable
fun HomeScreen(actions: Actions, openSetup: () -> Unit, open: (String) -> Unit, openAll: () -> Unit) {
    val context = LocalContext.current
    val sessions = rememberSessions()
    val status by Live.status.collectAsState()
    val downloads by Live.downloads.collectAsState()
    val liveReady = remember(downloads) { Models.active(context, Role.LIVE) != null }
    val voicesReady = remember(downloads) { Models.active(context, Role.SPEAKER) != null }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalMark()
            Spacer(Modifier.width(10.dp))
            Text("Local Notes", style = Type.heading, color = P.ink)
            Spacer(Modifier.weight(1f))
            TextAction("Setup", icon = AppIcons.Tune, onClick = openSetup)
        }
        Spacer(Modifier.height(26.dp))
        Muted(RecordingInfo.format(System.currentTimeMillis(), "EEEE, d MMMM"), style = Type.small)
        Spacer(Modifier.height(5.dp))
        Text("Your workspace", style = Type.display, color = P.ink)
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.hero).padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Mic, null, Modifier.size(22.dp), tint = P.spark)
                Spacer(Modifier.width(9.dp))
                Text("NEW RECORDING", style = Type.label, color = P.heroInk)
            }
            Spacer(Modifier.height(16.dp))
            Text("Record a conversation", style = Type.heading, color = P.heroInk)
            Spacer(Modifier.height(6.dp))
            Text(if (liveReady) "Record audio and follow the transcript live." else "Record audio now. Set up live text when ready.",
                style = Type.small, color = P.heroInk.copy(alpha = 0.8f))
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(P.spark)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = actions::record)
                .padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(AppIcons.Mic, null, Modifier.size(20.dp), tint = P.hero)
                Spacer(Modifier.width(10.dp))
                Text("Start recording", style = Type.item, color = P.hero)
            }
            Spacer(Modifier.height(14.dp))
            Text("Saved on this device · Works offline after setup", style = Type.small, color = P.heroInk.copy(alpha = 0.8f))
        }
        Spacer(Modifier.height(18.dp))
        if (!liveReady) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.accentSoft).padding(18.dp)) {
                Text("Live transcripts need a one-time download", style = Type.heading, color = P.ink)
                Spacer(Modifier.height(4.dp))
                val downloadGb = Models.all.filter { it.recommended }.sumOf { it.bytes } / 1e9
                Muted("About ${"%.1f".format(downloadGb)} GB for the recommended models. After that everything runs offline on this phone.")
                Spacer(Modifier.height(10.dp))
                TextAction("Set up →", onClick = openSetup)
            }
            Spacer(Modifier.height(14.dp))
        } else if (!voicesReady) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.raised)
                .clickable(onClick = openSetup).padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Tune, null, Modifier.size(18.dp), tint = P.accent)
                Spacer(Modifier.width(10.dp))
                Text("Enable speaker labels", Modifier.weight(1f), style = Type.small, color = P.ink)
                Text("→", style = Type.body, color = P.accent)
            }
            Spacer(Modifier.height(18.dp))
        }
        if (status.isNotBlank()) { Muted(status); Spacer(Modifier.height(20.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Recent recordings", Modifier.weight(1f), style = Type.heading, color = P.ink)
            if (sessions.isNotEmpty()) TextAction("View all", onClick = openAll)
        }
        Spacer(Modifier.height(6.dp))
        if (sessions.isEmpty()) Muted("Your recordings will appear here.", Modifier.padding(vertical = 12.dp))
        sessions.take(4).forEach { SessionRow(it, showDate = true) { open(it.id) } }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SignalMark() {
    val color = P.accent
    Canvas(Modifier.size(26.dp)) {
        val heights = listOf(.32f, .72f, 1f, .55f, .85f)
        heights.forEachIndexed { i, h ->
            val x = size.width * (i + 1) / 6f
            drawLine(color, Offset(x, size.height * (1 - h) / 2), Offset(x, size.height * (1 + h) / 2),
                3.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
fun SessionRow(session: SessionView, showDate: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(12.dp))
        .background(P.raised).clickable(onClick = onClick).padding(horizontal = 15.dp)) {
        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(P.accentSoft), contentAlignment = Alignment.Center) {
                Icon(AppIcons.List, null, Modifier.size(22.dp), tint = P.accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(session.title, style = Type.item, color = P.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                val time = session.range().replace(Regex(":\\d\\d(?= |$)"), "")
                Muted((if (showDate) session.date() + " · " else "") + time, style = Type.small.merge(Tabular))
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(spokenDuration(session.durationMs), style = Type.small.merge(Tabular), color = P.ink)
                val status = session.status()
                if (status.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    if (status == "Recording" || status == "Writing summary") { PulseDot(size = 6.dp); Spacer(Modifier.width(5.dp)) }
                    Text(status, style = Type.small, color = if (status == "Recording") P.accent else P.faint)
                }
            }
        }
    }
}

// ---------------- Live recording ----------------

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
    var page by rememberSaveable { mutableIntStateOf(0) }
    var speakerToRename by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize().background(P.paper).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(P.accentSoft).padding(horizontal = 11.dp, vertical = 7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (paused) Box(Modifier.size(8.dp).clip(CircleShape).background(P.faint)) else PulseDot()
            Spacer(Modifier.width(10.dp))
                    Text(if (paused) "Paused" else "Recording", style = Type.small, color = P.accent)
                }
            }
            Spacer(Modifier.weight(1f))
            dir?.let { Muted("STARTED " + RecordingInfo.format(RecordingInfo.startEpoch(it), "HH:mm"), style = Type.label.merge(Tabular)) }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(18.dp))
            .background(P.hero).padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (paused) "ON HOLD" else "LIVE CAPTURE", style = Type.label, color = P.spark)
            Spacer(Modifier.height(5.dp))
            Text(duration(elapsed), style = Type.timer, color = P.heroInk)
            Spacer(Modifier.height(8.dp))
            Waveform(paused, Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 27.dp))
            Spacer(Modifier.height(10.dp))

        }
        Spacer(Modifier.height(15.dp))
        TextTabs(listOf("Transcript", "Notes"), page, Modifier.padding(horizontal = 24.dp)) { page = it }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (page == 0) LiveTranscript(segments, partial, dir, Models.active(context, Role.LIVE) != null,
                Models.active(context, Role.SPEAKER) != null, { speakerToRename = it })
            else LiveNotes(summary, draft, summaryStatus, Models.active(context, Role.SUMMARY) != null)
        }
        if (status.isNotBlank()) Muted(status, Modifier.padding(horizontal = 24.dp))
        Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 22.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
            RoundControl(if (paused) "Resume" else "Pause", 64.dp, filled = false, onClick = actions::pause) {
                Icon(if (paused) AppIcons.Play else AppIcons.Pause, null, Modifier.size(26.dp), tint = P.ink)
            }
            RoundControl("Stop & save", 64.dp, filled = true, onClick = actions::stop) {
                Box(Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(P.onAccent))
            }
        }
    }
    speakerToRename?.let { selected -> dir?.let { RenameSpeakerDialog(selected, it) { speakerToRename = null } } }
}

@Composable
private fun Waveform(paused: Boolean, modifier: Modifier) {
    val levels = remember { mutableStateListOf<Float>().apply { repeat(56) { add(0f) } } }
    LaunchedEffect(Unit) { while (true) { levels.removeAt(0); levels.add(Live.level.value); delay(100) } }
    val color = if (paused) P.heroInk.copy(alpha = 0.4f) else P.spark
    Canvas(modifier) {
        val step = size.width / levels.size
        levels.forEachIndexed { i, level ->
            val h = maxOf(2.dp.toPx(), level * size.height)
            val x = i * step + step / 2
            drawLine(color, Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

/**
 * Live transcript list. It follows new text only while you are at the bottom; scroll up to read and it
 * stays put, with a "Latest" link to jump back.
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
        LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(segments, key = { i, s -> "${s.startMs}-$i" }) { _, segment ->
                Column {
                    val clock = dir?.let { RecordingInfo.wallClockAt(it, segment.startMs) }?.let { RecordingInfo.format(it, "HH:mm") } ?: ""
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (dir != null) segment.speakerId?.let { SpeakerChip(it, dir, rename) }
                        Spacer(Modifier.width(8.dp))
                        Text(clock, style = Type.label.merge(Tabular), color = P.faint)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(segment.text, style = Type.reading, color = if (segment.refined) P.ink else P.muted)
                }
            }
            item(key = "partial") {
                Column {
                if (voicesReady && partial.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot(size = 6.dp)
                        Spacer(Modifier.width(7.dp))
                        Text("Identifying speaker", style = Type.small, color = P.muted)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(when { partial.isNotBlank() -> partial; segments.isEmpty() && ready -> "Start talking. Words appear here as you speak."
                    !ready -> "Live text is off. Download the speech models in Setup; your audio is still being saved."; else -> "" },
                    style = Type.reading, color = P.accent)
                }
            }
        }
        AnimatedVisibility(!follow, Modifier.align(Alignment.BottomCenter).padding(10.dp), enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.clip(RoundedCornerShape(20.dp)).background(P.ink).clickable { follow = true; scope.launch { list.animateScrollToItem(count - 1, Int.MAX_VALUE / 2) } }
                .padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Down, null, Modifier.size(16.dp), tint = P.paper); Spacer(Modifier.width(6.dp)); Text("Latest", style = Type.small, color = P.paper)
            }
        }
    }
}

@Composable
private fun LiveNotes(summary: String?, draft: String, status: String, ready: Boolean) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
        when {
            !ready -> Muted("Download a summary model in Setup to get notes while you record.", style = Type.body)
            summary == null && draft.isBlank() && status.isBlank() ->
                Muted("Notes start after a few minutes of talking and are finished when you stop.", style = Type.body)
        }
        if (status.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) { PulseDot(); Spacer(Modifier.width(8.dp)); Text(status, style = Type.small, color = P.accent) }
            Spacer(Modifier.height(10.dp))
        }
        if (draft.isNotBlank()) { MarkdownText(Writer.clean(draft), dim = true); Spacer(Modifier.height(16.dp)) }
        summary?.let { MarkdownText(it) }
    }
}

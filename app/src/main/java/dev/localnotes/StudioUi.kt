package dev.localnotes

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StudioUi(private val activity: Activity, private val actions: Actions) {
    interface Actions {
        fun record()
        fun pauseRecording()
        fun stopWork()
        fun importModelFile(speech: Boolean)
        fun openDownload(speech: Boolean)
        fun processSession(session: File, transcriptOnly: Boolean = false)
        fun exportSession(session: File)
    }
    private val s = StudioStyle(activity)
    private val root = s.column()
    private val content = s.column()
    private val navigation = s.row()
    private val scroll = ScrollView(activity).apply { isFillViewport = true; clipToPadding = false; addView(content) }
    private val livePane = s.column()
    private var destination = "record"
    private var selected: String? = null
    private var document = 0
    private var search = ""
    private var signature = ""
    private var libraryList: LinearLayout? = null
    private var timer: TextView? = null
    private var banner: TextView? = null
    private var detailStatus: TextView? = null
    private var liveText: TextView? = null
    private var liveScroll: ScrollView? = null
    private var liveState: TextView? = null
    private var liveShown = ""
    init {
        root.isFocusableInTouchMode = true
        root.setBackgroundColor(s.paper)
        val stack = FrameLayout(activity)
        stack.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        stack.addView(livePane, FrameLayout.LayoutParams(-1, -1))
        root.addView(stack, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(navigation, LinearLayout.LayoutParams(-1, -2))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            root.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom))
            insets
        }
        activity.setContentView(root)
        root.requestFocus()
        activity.window.statusBarColor = s.paper
        activity.window.navigationBarColor = s.surface
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        activity.window.insetsController?.setSystemBarsAppearance(if (s.dark) 0 else light, light)
        render()
    }
    fun save(out: android.os.Bundle) {
        out.putString("screen", destination); out.putString("selected", selected)
        out.putInt("document", document); out.putString("search", search)
    }
    fun restore(state: android.os.Bundle?) {
        if (state == null) return
        destination = state.getString("screen", "record")
        selected = state.getString("selected"); document = state.getInt("document", 0).coerceIn(0, 2)
        search = state.getString("search", ""); render()
    }
    fun back(): Boolean {
        if (selected != null) { selected = null; render(); return true }
        if (destination != "record") { destination = "record"; render(); return true }
        return false
    }
    private fun hasSpeech() = File(Store.models(activity), "speech.bin").exists()
    private fun hasSummary() = File(Store.models(activity), "summary.gguf").exists()
    private fun guarded(action: () -> Unit) {
        runCatching(action).onFailure {
            AlertDialog.Builder(activity).setTitle("Can't do that yet").setMessage(it.message ?: "Please try again.").setPositiveButton("OK", null).show()
        }
    }
    private fun go(screen: String) { destination = screen; selected = null; root.requestFocus(); scroll.scrollTo(0, 0); render() }
    fun refresh() { render() }
    fun tick() {
        val sessions = Store.sessions(activity)
        val current = sessions.firstOrNull { it.name == selected }
        val outputs = current?.let { runFolder(it).listFiles()?.filter { file -> file.name in listOf("concise.md", "detailed.md", "transcript.txt") }?.joinToString { file -> "${file.name}:${file.lastModified()}" } } ?: ""
        val next = "${Store.busy.get()}:${Store.recording}:${Store.paused}:${hasSpeech()}:${hasSummary()}:${sessions.size}:$outputs"
        if (next != signature) { signature = next; render() }
        val active = sessions.firstOrNull { it.name == Store.activeSession }
        timer?.text = Chunking.timestamp((active?.let { File(it, "audio.pcm").length() } ?: 0) / 32)
        banner?.text = friendlyStatus()
        banner?.visibility = if (showBanner()) View.VISIBLE else View.GONE
        detailStatus?.text = friendlyStatus()
        if (active != null) updateLive(active)
    }
    private fun friendlyStatus(): String = when {
        Store.status.startsWith("Model imported") -> "Model installed."
        Store.status.startsWith("Transcribing section") -> Store.status.replace("Transcribing section", "Transcribing · section")
        Store.status.startsWith("Loading") -> "Loading model…"
        else -> Store.status
    }
    private fun showBanner() = !Store.recording && (Store.busy.get() || Store.status !in listOf("Ready", "Recording saved"))
    private fun recordingView() = Store.recording && destination == "record" && selected == null

    private fun render() {
        val y = scroll.scrollY
        content.removeAllViews(); navigation.removeAllViews(); livePane.removeAllViews()
        timer = null; banner = null; detailStatus = null; libraryList = null; liveText = null; liveScroll = null; liveState = null; liveShown = ""
        val session = Store.sessions(activity).firstOrNull { it.name == selected }
        if (selected != null && session == null) selected = null
        if (recordingView()) {
            scroll.visibility = View.GONE; livePane.visibility = View.VISIBLE; navigation.visibility = View.GONE
            Store.sessions(activity).firstOrNull { it.name == Store.activeSession }?.let { recording(it) }
            return
        }
        scroll.visibility = View.VISIBLE; livePane.visibility = View.GONE; navigation.visibility = View.VISIBLE
        content.setPadding(s.dp(20), s.dp(12), s.dp(20), s.dp(28))
        if (session != null) {
            val header = s.row()
            header.addView(s.iconButton("back", "Back to recordings") { back() }, LinearLayout.LayoutParams(s.dp(48), s.dp(48)))
            header.addView(s.text("Recordings", 15f, s.muted), LinearLayout.LayoutParams(0, -2, 1f))
            s.add(content, header, bottom = 4)
        }
        banner = s.text(friendlyStatus(), 14f, s.ink).apply {
            background = s.shape(s.surface, 12, s.line); setPadding(s.dp(14), s.dp(12), s.dp(14), s.dp(12))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = if (showBanner()) View.VISIBLE else View.GONE
        }
        when {
            session != null -> { if (session.name == Store.activeSession) banner!!.visibility = View.GONE else s.add(content, banner!!, bottom = 16); detail(session) }
            destination == "library" -> { title("Recordings"); s.add(content, banner!!, bottom = 16); library() }
            destination == "setup" -> { title("Setup"); s.add(content, banner!!, bottom = 16); setup() }
            else -> { title("Record"); s.add(content, banner!!, bottom = 16); recorder() }
        }
        navigation.setPadding(s.dp(8), s.dp(6), s.dp(8), s.dp(6))
        navigation.background = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.ColorDrawable(s.line), android.graphics.drawable.ColorDrawable(s.surface))).apply { setLayerInset(1, 0, s.dp(1), 0, 0) }
        for ((key, label, icon) in listOf(Triple("record", "Record", "mic"), Triple("library", "Recordings", "library"), Triple("setup", "Setup", "setup"))) {
            val active = if (selected != null) key == "library" else destination == key
            val tab = s.column().apply {
                gravity = Gravity.CENTER; minimumHeight = s.dp(56)
                background = s.ripple(Color.TRANSPARENT, 12)
                contentDescription = "$label tab${if (active) ", selected" else ""}"
                isFocusable = true; isSelected = active
                addView(StudioIcon(activity, icon, if (active) s.ink else s.muted), LinearLayout.LayoutParams(s.dp(22), s.dp(22)))
                addView(s.text(label, 12f, if (active) s.ink else s.muted, active).apply { gravity = Gravity.CENTER })
                setOnClickListener { go(key) }
            }
            navigation.addView(tab, LinearLayout.LayoutParams(0, -2, 1f))
        }
        scroll.post { scroll.scrollTo(0, y) }
    }
    private fun title(text: String) = s.add(content, s.text(text, 28f, s.ink, true), top = 16, bottom = 20)

    // ---------- Record ----------
    private fun recorder() {
        val today = s.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        today.addView(s.text(RecordingInfo.format(System.currentTimeMillis(), "EEEE d MMMM yyyy"), 14f, s.muted))
        today.addView(s.numbers("00:00:00", 54f).apply { setTextColor(s.muted); gravity = Gravity.CENTER })
        s.gap(today, 20)
        val idle = !Store.busy.get()
        today.addView(s.transport("record", if (idle) "Record" else "Busy", 88, s.surface, s.line, s.red) { guarded { actions.record() } }.apply {
            isEnabled = idle; alpha = if (idle) 1f else .4f; getChildAt(0).isEnabled = idle
            getChildAt(0).contentDescription = "Start recording"
        })
        s.gap(today, 18)
        val free = Store.root(activity).usableSpace
        today.addView(s.text("16 kHz mono · about 115 MB per hour · ${free / 115_200_000L} h of space free", 12f, s.muted).apply { gravity = Gravity.CENTER })
        s.add(content, today, bottom = 24)
        if (!Store.recording && Store.busy.get() && Store.activeSession != null)
            s.add(content, s.button("Stop processing", false) { guarded { actions.stopWork() } }, bottom = 16)
        if (!hasSpeech()) notice("Live text is off", "Install the speech model (60 MB) in Setup to see words appear while you record.", "Open Setup") { go("setup") }
        val sessions = Store.sessions(activity)
        s.add(content, s.label("Recent"), top = 8, bottom = 4)
        if (sessions.isEmpty()) s.add(content, s.text("No recordings yet.", 15f, s.muted), top = 8)
        else {
            sessions.take(3).forEach { sessionRow(content, it, showDate = true) }
            if (sessions.size > 3) s.add(content, s.button("All ${sessions.size} recordings", false) { go("library") }, top = 12)
        }
    }
    private fun notice(heading: String, body: String, button: String, action: () -> Unit) {
        val box = s.column().apply { background = s.shape(s.surface, 12, s.line); setPadding(s.dp(16), s.dp(14), s.dp(16), s.dp(14)) }
        box.addView(s.text(heading, 15f, s.ink, true))
        s.add(box, s.text(body, 14f, s.muted), top = 4)
        s.add(box, s.button(button, false, action = action), top = 12)
        s.add(content, box, bottom = 20)
    }

    // ---------- Recording in progress ----------
    private fun recording(session: File) {
        livePane.setPadding(s.dp(20), s.dp(16), s.dp(20), s.dp(16))
        val top = s.row()
        top.addView(View(activity).apply { background = s.circle(if (Store.paused) s.muted else s.red) }, LinearLayout.LayoutParams(s.dp(10), s.dp(10)).apply { marginEnd = s.dp(8) })
        top.addView(s.text(if (Store.paused) "Paused" else "Recording", 15f, s.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(s.text("Started ${RecordingInfo.format(RecordingInfo.startEpoch(session), "HH:mm:ss")}", 13f, s.muted))
        s.add(livePane, top)
        timer = s.numbers("00:00:00", 54f).apply { gravity = Gravity.CENTER }
        s.add(livePane, timer!!, top = 12)
        s.add(livePane, s.text(sessionTitle(session), 13f, s.muted).apply { gravity = Gravity.CENTER }, bottom = 16)
        val head = s.row()
        head.addView(s.label("Live transcript"), LinearLayout.LayoutParams(0, -2, 1f))
        liveState = s.text(Store.liveStatus, 12f, s.muted).also { head.addView(it) }
        s.add(livePane, head, bottom = 8)
        liveText = s.text("", 17f, s.ink).apply { setTextIsSelectable(true); setLineSpacing(s.dp(6).toFloat(), 1f) }
        liveScroll = ScrollView(activity).apply {
            background = s.shape(s.surface, 12, s.line); setPadding(s.dp(16), s.dp(14), s.dp(16), s.dp(14)); clipToPadding = false
            addView(liveText)
        }
        livePane.addView(liveScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = s.row().apply { gravity = Gravity.CENTER }
        controls.addView(s.transport(if (Store.paused) "play" else "pause", if (Store.paused) "Resume" else "Pause", 64, s.surface, s.line, s.ink) { guarded { actions.pauseRecording() } },
            LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(s.transport("stop", "Stop & save", 80, s.surface, s.red, s.red) { guarded { actions.stopWork() } },
            LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        s.add(livePane, controls, top = 16)
        updateLive(session)
        timer?.text = Chunking.timestamp(File(session, "audio.pcm").length() / 32)
    }
    private fun updateLive(session: File) {
        val view = liveText ?: return
        liveState?.text = Store.liveStatus
        val committed = File(session, "live-transcript.txt").takeIf { it.exists() }?.readText().orEmpty()
        val draft = Store.liveDraft
        val key = "${committed.length}:$draft"
        if (key == liveShown) return
        liveShown = key
        val text = transcriptSpans(session, committed.takeLast(20000), clock = true)
        if (draft.isNotBlank()) {
            if (text.isNotEmpty()) text.append('\n')
            val start = text.length
            text.append(draft)
            text.setSpan(ForegroundColorSpan(s.muted), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (text.isEmpty()) text.append(if (hasSpeech()) "Words appear here a few seconds after they're spoken." else "Live text needs the speech model. Audio is still being saved.")
            .also { it.setSpan(ForegroundColorSpan(s.muted), 0, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val follow = liveScroll?.let { it.scrollY + it.height >= view.height - s.dp(48) } ?: true
        view.text = text
        if (follow) liveScroll?.post { liveScroll?.fullScroll(View.FOCUS_DOWN) }
    }
    /** Renders "[00:00:12–00:00:20] text" lines with a muted time of day (or audio offset) prefix. */
    private fun transcriptSpans(session: File, raw: String, clock: Boolean): SpannableStringBuilder {
        val out = SpannableStringBuilder()
        val pattern = Regex("""^\[(\d+):(\d\d):(\d\d)[–-][^\]]*]\s*(.*)$""")
        raw.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val match = pattern.find(line)
            if (out.isNotEmpty()) out.append('\n')
            if (match == null) { out.append(line); return@forEach }
            val (h, m, sec, words) = match.destructured
            val offset = (h.toLong() * 3600 + m.toLong() * 60 + sec.toLong()) * 1000
            val label = if (clock) RecordingInfo.wallClockAt(session, offset)?.let { RecordingInfo.format(it, "HH:mm:ss") } ?: Chunking.timestamp(offset)
                else Chunking.timestamp(offset)
            val start = out.length
            out.append(label).append("  ")
            out.setSpan(ForegroundColorSpan(s.muted), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(AbsoluteSizeSpan(13, true), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append(words)
        }
        return out
    }

    // ---------- Recordings ----------
    private fun sessionTitle(session: File): String = File(session, "title.txt").takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
        ?: RecordingInfo.defaultTitle(RecordingInfo.startEpoch(session))
    private fun runFolder(session: File): File {
        val name = File(session, "current-run.txt").takeIf { it.exists() }?.readText() ?: "not-processed"
        return File(session, if (name.matches(Regex("run-[a-f0-9]{20}"))) name else "not-processed")
    }
    private fun state(session: File): String {
        if (session.name == Store.activeSession) return if (Store.recording) "Recording" else "Processing"
        if (File(runFolder(session), "concise.md").exists()) return "Notes"
        if (File(runFolder(session), "transcript.complete").exists()) return "Transcript"
        if (File(runFolder(session), "transcript.txt").exists()) return "Partly transcribed"
        if (File(session, "live-transcript.txt").exists()) return "Live text"
        return "Audio only"
    }
    private fun sessionRow(parent: LinearLayout, session: File, showDate: Boolean) {
        val row = s.row().apply {
            background = s.ripple(Color.TRANSPARENT, 8); isFocusable = true
            setPadding(0, s.dp(14), 0, s.dp(14)); minimumHeight = s.dp(64)
        }
        val labels = s.column()
        labels.addView(s.text(sessionTitle(session), 16f, s.ink, true).apply { maxLines = 2 })
        val time = RecordingInfo.displayRange(session).replace(Regex(":\\d\\d(?= |$)"), "")
        labels.addView(s.text("${if (showDate) RecordingInfo.displayDate(session) + " · " else ""}$time · ${RecordingInfo.capturedDuration(session)}", 13f, s.muted).apply { fontFeatureSettings = "tnum" })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val status = state(session)
        row.addView(s.text(status, 12f, if (status == "Recording") s.red else s.muted), LinearLayout.LayoutParams(-2, -2).apply { marginStart = s.dp(12); marginEnd = s.dp(4) })
        row.addView(StudioIcon(activity, "arrow", s.muted), LinearLayout.LayoutParams(s.dp(16), s.dp(16)))
        row.setOnClickListener {
            selected = session.name
            document = if (File(runFolder(session), "concise.md").exists()) 0 else 2
            scroll.scrollTo(0, 0); render()
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        s.divider(parent)
    }
    private fun library() {
        val field = EditText(activity).apply {
            hint = "Search by name or date"; textSize = 15f; setTextColor(s.ink); setHintTextColor(s.muted)
            background = s.shape(s.surface, 12, s.line); setPadding(s.dp(16), s.dp(12), s.dp(16), s.dp(12))
            minHeight = s.dp(50); setSingleLine(true); setText(search); contentDescription = "Search recordings"
        }
        s.add(content, field, bottom = 8)
        libraryList = s.column().also { s.add(content, it) }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) { search = text.toString(); fillLibrary() }
            override fun afterTextChanged(text: Editable?) {}
        })
        fillLibrary()
    }
    private fun day(session: File): String {
        val date = Instant.ofEpochMilli(RecordingInfo.startEpoch(session)).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        return when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> RecordingInfo.format(RecordingInfo.startEpoch(session), "EEE d MMM yyyy")
        }
    }
    private fun fillLibrary() {
        val list = libraryList ?: return
        list.removeAllViews()
        val all = Store.sessions(activity)
        val query = search.trim()
        val matches = all.filter { "${sessionTitle(it)} ${RecordingInfo.displayDate(it)} ${RecordingInfo.displayStart(it)} ${day(it)}".contains(query, ignoreCase = true) }
        if (matches.isEmpty()) {
            s.add(list, s.text(if (all.isEmpty()) "No recordings yet" else "No matching recordings", 16f, s.ink, true), top = 24)
            s.add(list, s.text(if (all.isEmpty()) "Recordings you make appear here, newest first." else "Try another name or a date like “1 Oct”.", 14f, s.muted), top = 4)
            return
        }
        var last = ""
        matches.forEach { session ->
            val heading = day(session)
            if (heading != last) { s.add(list, s.label(heading), top = 20, bottom = 2); last = heading }
            sessionRow(list, session, showDate = false)
        }
    }

    // ---------- Setup ----------
    private fun setup() {
        val ready = listOf(hasSpeech(), hasSummary()).count { it }
        s.add(content, s.label("Models · $ready of 2 installed"), bottom = 8)
        modelCard(true, "Speech recognition", "Whisper base.en, quantized · 60 MB", "Used for live text and the final transcript.", "ggml-base.en-q5_1.bin")
        modelCard(false, "Summaries", "Qwen 2.5 1.5B Instruct · 1.12 GB", "Optional. Writes the summary and detailed notes after recording.", "qwen2.5-1.5b-instruct-q4_k_m.gguf")
        s.add(content, s.label("How to install"), top = 12, bottom = 8)
        s.add(content, s.text("1.  Tap Download. The file opens in your browser.\n2.  When it finishes, come back and tap Import file.\n3.  Pick it from Downloads. You can delete the downloaded copy afterwards.", 14f, s.ink).apply { setLineSpacing(s.dp(6).toFloat(), 1f) })
        s.add(content, s.label("Privacy"), top = 24, bottom = 8)
        s.add(content, s.text("Recording, transcription and summaries run on this phone. The app has no internet permission and no account. Transcripts and summaries can contain mistakes, so keep the audio.", 14f, s.muted))
        val version = runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull()
        version?.let { s.add(content, s.text("Local Notes $it", 12f, s.muted), top = 24) }
    }
    private fun modelCard(speech: Boolean, name: String, model: String, purpose: String, filename: String) {
        val file = File(Store.models(activity), if (speech) "speech.bin" else "summary.gguf")
        val exists = file.exists()
        val box = s.column().apply { background = s.shape(s.surface, 12, s.line); setPadding(s.dp(16), s.dp(16), s.dp(16), s.dp(16)) }
        val head = s.row()
        head.addView(s.text(name, 17f, s.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (exists) head.addView(StudioIcon(activity, "check", s.ink), LinearLayout.LayoutParams(s.dp(16), s.dp(16)).apply { marginEnd = s.dp(6) })
        head.addView(s.text(if (exists) "Installed" else "Not installed", 13f, if (exists) s.ink else s.muted))
        box.addView(head)
        s.add(box, s.text(model, 14f, s.ink), top = 6)
        s.add(box, s.text(purpose, 13f, s.muted), top = 2)
        s.add(box, s.text(filename, 12f, s.muted).apply { typeface = Typeface.MONOSPACE }, top = 6)
        if (speech && exists && file.length() > 300_000_000L)
            s.add(box, s.text("A larger speech model is installed. It is slower and too slow for live text on most phones. Download the 60 MB model and use Replace for faster results.", 13f, s.ink), top = 10)
        val buttons = s.row()
        buttons.addView(s.button("Download", false) { guarded { actions.openDownload(speech) } }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = s.dp(8) })
        buttons.addView(s.button(if (exists) "Replace" else "Import file", primary = !exists, enabled = !Store.busy.get()) { guarded { actions.importModelFile(speech) } },
            LinearLayout.LayoutParams(0, -2, 1f))
        s.add(box, buttons, top = 14)
        s.add(content, box, bottom = 12)
    }

    // ---------- Recording detail ----------
    private fun detail(session: File) {
        val titleRow = s.row()
        titleRow.addView(s.text(sessionTitle(session), 24f, s.ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(s.iconButton("edit", "Rename recording") { rename(session) }, LinearLayout.LayoutParams(s.dp(48), s.dp(48)))
        s.add(content, titleRow)
        val live = session.name == Store.activeSession && Store.recording
        val facts = s.column().apply { setPadding(0, s.dp(8), 0, s.dp(8)) }
        fun fact(name: String, value: String) {
            val row = s.row().apply { setPadding(0, s.dp(4), 0, s.dp(4)) }
            row.addView(s.text(name, 14f, s.muted), LinearLayout.LayoutParams(s.dp(84), -2))
            row.addView(s.text(value, 14f, s.ink).apply { fontFeatureSettings = "tnum" }, LinearLayout.LayoutParams(0, -2, 1f))
            facts.addView(row)
        }
        fact("Started", RecordingInfo.displayStart(session))
        fact("Ended", if (live) "Recording now" else RecordingInfo.displayEnd(session))
        fact("Length", RecordingInfo.capturedDuration(session) + (RecordingInfo.read(session)?.optJSONArray("pauses")?.length()?.takeIf { it > 0 }?.let { " of audio · paused $it×" } ?: ""))
        s.add(content, facts, bottom = 8)
        if (Store.activeSession == session.name && !Store.recording) {
            detailStatus = s.text(friendlyStatus(), 14f, s.ink).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
            s.add(content, detailStatus!!, bottom = 12)
        }
        val output = runFolder(session)
        val complete = File(output, "concise.md").exists()
        val transcribed = File(output, "transcript.complete").exists()
        val idle = !Store.busy.get()
        if (!complete && !live) {
            when {
                !hasSpeech() -> s.add(content, s.button("Install speech model to transcribe") { go("setup") }, bottom = 10)
                !transcribed -> {
                    s.add(content, s.button("Transcribe", enabled = idle) { guarded { actions.processSession(session, true); document = 2 } }, bottom = 8)
                    if (hasSummary()) s.add(content, s.button("Transcribe and summarize", false, idle) { guarded { actions.processSession(session, false) } }, bottom = 10)
                }
                hasSummary() -> s.add(content, s.button("Create summary and notes", enabled = idle) { guarded { actions.processSession(session, false) } }, bottom = 10)
                else -> s.add(content, s.button("Install summary model", false) { go("setup") }, bottom = 10)
            }
        }
        if (Store.activeSession == session.name && !Store.recording) s.add(content, s.button("Stop processing", false) { guarded { actions.stopWork() } }, bottom = 10)
        val tabs = s.row()
        for ((i, label) in listOf("Summary", "Notes", "Transcript").withIndex()) {
            val active = i == document
            val tab = s.column().apply {
                gravity = Gravity.CENTER_HORIZONTAL; minimumHeight = s.dp(48); isFocusable = true; isSelected = active
                background = s.ripple(Color.TRANSPARENT, 8); contentDescription = "$label${if (active) ", selected" else ""}"
                addView(s.text(label, 15f, if (active) s.ink else s.muted, active).apply { gravity = Gravity.CENTER; setPadding(0, s.dp(12), 0, s.dp(10)) }, LinearLayout.LayoutParams(-1, -2))
                addView(View(activity).apply { setBackgroundColor(if (active) s.ink else Color.TRANSPARENT) }, LinearLayout.LayoutParams(-1, s.dp(2)))
                setOnClickListener { document = i; render() }
            }
            tabs.addView(tab, LinearLayout.LayoutParams(0, -2, 1f))
        }
        s.add(content, tabs, top = 8)
        s.divider(content)
        val filename = listOf("concise.md", "detailed.md", "transcript.txt")[document]
        val finalFile = File(output, filename)
        val file = if (document == 2 && !finalFile.exists()) File(session, "live-transcript.txt") else finalFile
        if (file.exists()) {
            if (document == 2 && !transcribed)
                s.add(content, s.text(if (finalFile.exists()) "Partial transcript. Transcribing again continues where it stopped." else "Live text from the recording. Tap Transcribe for the more accurate final version.", 13f, s.muted), top = 14)
            val text = file.readText()
            val preview = if (text.length > 100000) text.take(100000) + "\n\nPreview ends here. Export for the complete text." else text
            val body = if (document == 2) transcriptSpans(session, preview, clock = true) else markdown(preview)
            s.add(content, s.text("", 16f).apply { this.text = body; setTextIsSelectable(true); setLineSpacing(s.dp(6).toFloat(), 1f) }, top = 16, bottom = 20)
            val buttons = s.row()
            buttons.addView(s.button("Copy ${listOf("summary", "notes", "transcript")[document]}", false) {
                if (text.toByteArray().size > 250000) {
                    AlertDialog.Builder(activity).setMessage("This is too long for the clipboard. Use Export to save it in full.").setPositiveButton("OK", null).show()
                } else {
                    activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(filename, text))
                    Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = s.dp(8) })
            buttons.addView(s.button("Export", false, idle) { guarded { actions.exportSession(session) } }, LinearLayout.LayoutParams(0, -2, 1f))
            s.add(content, buttons, bottom = 12)
        } else {
            s.add(content, s.text(when (document) { 0 -> "No summary yet"; 1 -> "No notes yet"; else -> "No transcript yet" }, 16f, s.ink, true), top = 20)
            s.add(content, s.text(if (Store.activeSession == session.name) "Processing. You can leave this screen; finished sections are saved as they complete."
                else if (document == 2) "Tap Transcribe above. The audio is saved either way." else "Summaries are written after the transcript, using the summary model.", 14f, s.muted), top = 4, bottom = 20)
            s.add(content, s.button("Export audio", false, idle) { guarded { actions.exportSession(session) } }, bottom = 12)
        }
        if (complete && document != 2) s.add(content, s.text("Generated on this phone. Check important details against the recording.", 12f, s.muted))
        val error = File(session, "state.txt").takeIf { it.exists() }?.readText().orEmpty()
        if (!live && (error.contains("stopped:", true) || error.contains("interrupted", true))) s.add(content, s.text(error, 13f, s.red), top = 12)
    }
    private fun rename(session: File) {
        val field = EditText(activity).apply { setSingleLine(true); setText(sessionTitle(session)); selectAll(); hint = "Name"; filters = arrayOf(android.text.InputFilter.LengthFilter(100)) }
        val wrapper = s.column().apply { setPadding(s.dp(24), s.dp(8), s.dp(24), 0); addView(field) }
        AlertDialog.Builder(activity).setTitle("Rename").setView(wrapper).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> guarded {
                val title = field.text.toString().trim()
                check(title.isNotEmpty()) { "Enter a name." }
                Store.write(File(session, "title.txt"), title); render()
            } }.show()
    }
    private fun markdown(text: String): CharSequence {
        val result = SpannableStringBuilder()
        text.lineSequence().forEach { line ->
            val heading = line.startsWith("#")
            val display = if (heading) line.trimStart('#', ' ') else if (line.startsWith("- ")) "•  " + line.drop(2) else line
            val start = result.length
            result.append(display.replace("**", "")).append('\n')
            if (heading) {
                result.setSpan(StyleSpan(Typeface.BOLD), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                result.setSpan(AbsoluteSizeSpan(18, true), start, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return result
    }
}

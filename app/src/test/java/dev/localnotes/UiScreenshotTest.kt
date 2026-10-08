package dev.localnotes

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.test.performTextInput
import dev.localnotes.ui.*
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders each screen with realistic data and saves PNGs to build/ui-previews for review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi", shadows = [HostAtomicFileShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = RuntimeEnvironment.getApplication()
    private val actions = object : Actions {
        override fun record() {}; override fun pause() {}; override fun stop() {}
        override fun download(spec: ModelSpec) {}; override fun cancelDownloads() {}
        override fun process(id: String, transcribe: Boolean, resummarize: Boolean) {}
        override fun stopProcessing() {}; override fun export(id: String) {}; override fun share(text: String) {}; override fun copy(text: String) {}
    }
    @Before @After fun reset() {
        Live.recording.value = false; Live.session.value = null; Live.working.value = false; Live.status.value = ""
        Live.partial.value = ""; Live.summaryDraft.value = ""; Live.summaryStatus.value = ""; Live.downloads.value = emptyMap()
    }
    /** The record knob and waveform animate forever, so the test drives the clock instead of waiting for idle. */
    @Before fun manualClock() { compose.mainClock.autoAdvance = false }
    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(1200)
        // Draw the window directly (captureToImage waits for a frame Robolectric never schedules).
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File("build/ui-previews").apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun install(vararg ids: String) = ids.forEach { id ->
        val spec = Models.all.first { it.id == id }
        Models.folder(context, spec).mkdirs(); File(Models.folder(context, spec), ".complete").writeText("x")
        spec.files.forEach { File(Models.folder(context, spec), it).writeText("preview") }
    }

    /** A 46-minute, two-voice meeting with a believable loudness shape, started [daysAgo] days and [hoursAgo] hours ago. */
    private fun meeting(daysAgo: Int = 0, title: String = "Clinic app weekly planning", hoursAgo: Int = 0): File {
        val session = Store.create(context)
        val start = System.currentTimeMillis() - 47 * 60_000L - daysAgo * 86_400_000L - hoursAgo * 3_600_000L
        RecordingInfo.start(session, start, "audio-clock")
        RecordingInfo.finish(session, start + 46 * 60_000L + 12_000, 46L * 60 * RATE, false)
        val bytes = (46 * 60 + 12) * 32000L
        java.io.RandomAccessFile(File(session, "audio.pcm"), "rw").use { it.setLength(bytes) }
        // The loudness cache the app would compute from the audio: phrases of speech separated by pauses.
        val random = java.util.Random(7L + daysAgo + hoursAgo)
        val shape = (0 until Waveforms.BUCKETS).map { i ->
            val phrase = Math.abs(Math.sin(i * 0.23 + daysAgo)) * 0.6 + Math.abs(Math.sin(i * 0.071)) * 0.3
            (0.12 + phrase * (0.55 + 0.45 * random.nextDouble())).coerceIn(0.05, 1.0)
        }
        Store.write(File(session, "waveform.json"), org.json.JSONObject().put("bytes", bytes).put("v", org.json.JSONArray(shape)).toString())
        Store.write(File(session, "title.txt"), title)
        val t = Transcript(session)
        listOf("Okay, let's get started. This is the weekly planning call for the Riverside clinic app.",
            "The launch date is moving from October 7th to October 14th because the App Store review is taking longer.",
            "Just to be clear, the internal beta still starts on October 1st. That hasn't changed.",
            "We have 12,500 euros left this quarter, and the agency quote came in at 4,200.",
            "I'd rather we don't sign the agency until finance approves it. Priya, can you check with finance?")
            .forEachIndexed { i, text -> t.add(Segment(i * 9000L + i * i * 60_000L, i * 9000L + 8000 + i * i * 60_000L, text, refined = true,
                speakerId = if (i < 2) 1 else if (i < 4) 2 else 1)) }
        Bookmarks.add(session, 7 * 60_000L); Bookmarks.add(session, 21 * 60_000L + 30_000L)
        Store.write(File(session, "summary.md"), "## Summary\nWeekly planning for the Riverside clinic app. The public launch moves to **October 14**; the beta still starts October 1.\n\n" +
            "## Decisions\n- Launch moves from October 7 to October 14.\n- Spanish is phase two.\n\n## Action items\n- Priya: ask finance about the agency (Friday)\n- Tom: compare Stripe and Adyen fees (Wednesday)\n- Alex: fix the date picker for screen readers (this week)\n\n" +
            "## Open questions\n- Payment provider: Stripe or Adyen.\n- Free trial: 14 or 30 days.")
        return session
    }

    @Test fun notebookFirstRun() {
        compose.setContent { AppTheme { NotebookScreen(actions, {}, {}) } }
        compose.onNodeWithText("Live words need a download").assertExists()
        compose.onNodeWithText("Notebook").assertExists()
        shot("01-notebook-first-run")
    }

    @Test fun notebookWithRecordings() {
        install("kroko-en", "parakeet-unified", "qwen35-2b")
        meeting(); meeting(0, "Interview: Dr. Rao", 3); meeting(1, "Budget review", 1); meeting(5, "Lecture 4: signals")
        compose.setContent { AppTheme { NotebookScreen(actions, {}, {}) } }
        compose.onNodeWithText("Clinic app weekly planning").assertExists()
        shot("02-notebook")
    }

    @Test fun recordingLive() {
        install("kroko-en", "parakeet-unified", "qwen35-2b")
        val session = meeting()
        File(session, "summary.md").delete()
        Live.session.value = session.name; Live.recording.value = true; Live.elapsedMs.value = 754_000
        Live.partial.value = "so moving on, the booking flow had a lot of drop off on the"
        compose.setContent { AppTheme { RecordingScreen(actions) } }
        compose.onNodeWithText("STOP & SAVE").assertExists()
        shot("03-recording-transcript")
        Live.summaryStatus.value = "Writing notes for 10:41–10:44"
        Live.summaryDraft.value = "- The public launch moves from October 7 to October 14.\n- The internal beta still starts on October 1.\n- 12,500 euros remain"
        compose.onNodeWithText("Notes").performClick()
        compose.mainClock.advanceTimeBy(600)
        shot("04-recording-notes")
    }

    @Test fun recordingFlagIsSavedAtTheCurrentTime() {
        val session = meeting()
        File(session, "bookmarks.json").delete()
        Live.session.value = session.name; Live.recording.value = true; Live.elapsedMs.value = 125_000
        compose.setContent { AppTheme { RecordingScreen(actions) } }
        compose.onNodeWithContentDescription("Flag").performClick()
        assertEquals(listOf(125_000L), Bookmarks.list(session))
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("FLAG DROPPED AT 00:02:05").assertExists()
        shot("17-recording-flag")
    }

    @Test fun recordingDetail() {
        install("kroko-en", "parakeet-unified", "qwen35-2b")
        val session = meeting()
        compose.setContent { AppTheme { DetailScreen(session.name, actions) {} } }
        compose.onNodeWithText("Action items").assertExists()
        compose.onNodeWithText("Tap a line in the transcript to play from there").assertExists()
        shot("05-detail-summary")
        compose.onNodeWithText("Transcript").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        shot("06-detail-transcript")
        compose.onAllNodesWithTag("speaker-1").onFirst().assertExists()
        compose.onNodeWithTag("transcript-search").performTextInput("beta")
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("1 matching passages · tap one to play it").assertExists()
        shot("11-detail-search")
    }

    @Test fun pendingLongRecordingOpensSavedNotes() {
        val session = meeting()
        File(session, "summary.md").delete()
        val folder = File(session, "notes").apply { mkdirs() }
        File(folder, "section-001.md").writeText("### 10:00–10:03\n- Beta starts on October 1.")
        File(folder, "sections.json").writeText("[{\"from\":0,\"to\":1,\"file\":\"section-001.md\"}]")
        File(session, RecorderService.SUMMARY_PENDING).writeText("")
        compose.setContent { AppTheme { DetailScreen(session.name, actions) {} } }
        compose.onNodeWithText("Beta starts on October 1.", substring = true).assertExists()
        shot("10-detail-notes-pending")
    }

    @Test fun setupScreen() {
        install("kroko-en")
        Live.downloads.value = mapOf("parakeet-unified" to 0.42f)
        compose.setContent { AppTheme { SetupScreen(actions) } }
        compose.onNodeWithText("Live transcript").assertExists()
        shot("07-setup")
    }

    private fun filterFixture() {
        val long = meeting()
        Store.write(File(long, "title.txt"), "Long planning meeting")
        val pending = Store.create(context)
        Store.write(File(pending, "title.txt"), "Pending interview")
        File(pending, RecorderService.SUMMARY_PENDING).writeText("")
        compose.setContent { AppTheme { NotebookScreen(actions, {}, {}) } }
    }

    private fun tapTab(label: String) {
        compose.onNodeWithText(label).performTouchInput { down(center); up() }
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeBy(300)
    }

    @Test fun notebookFilterShowsOnlyRecordingsThatNeedAttention() {
        filterFixture()
        tapTab("Needs attention")
        compose.onNodeWithText("Pending interview").assertExists()
        compose.onNodeWithText("Long planning meeting").assertDoesNotExist()
        shot("12-notebook-filter")
    }

    @Test fun notebookFilterShowsOnlyLongRecordings() {
        filterFixture()
        tapTab("30+ min")
        compose.onNodeWithText("Long planning meeting").assertExists()
        compose.onNodeWithText("Pending interview").assertDoesNotExist()
    }

    @Test fun workspaceNavigation() {
        // This screen has no continuous recording animation; let input/recomposition settle normally.
        compose.mainClock.autoAdvance = true
        compose.setContent { AppTheme { App(actions) } }
        compose.onNodeWithText("Notes").assertIsSelected()
        compose.onNodeWithText("Setup").performClick()
        shot("13-navigation")
        compose.onNodeWithText("Live transcript").assertExists()
        compose.onNodeWithText("Notes").performClick()
        compose.onNodeWithText("Notebook").assertExists()
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xxhdpi")
    fun compactRecordKnob() {
        var starts = 0
        val recordActions = object : Actions by actions { override fun record() { starts++ } }
        compose.setContent { AppTheme { App(recordActions) } }
        compose.onNodeWithContentDescription("Start recording").assertIsDisplayed().performClick()
        assertEquals(1, starts)
        shot("14-notebook-compact")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun notebookDark() {
        install("kroko-en")
        meeting(); meeting(1, "Budget review", 1)
        compose.setContent { AppTheme { App(actions) } }
        shot("15-notebook-dark")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun detailDark() {
        val session = meeting()
        compose.setContent { AppTheme { DetailScreen(session.name, actions) {} } }
        shot("18-detail-dark")
    }

    @Test fun largeTextDetail() {
        val session = meeting()
        compose.setContent { AppTheme {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.5f)
            ) { DetailScreen(session.name, actions) {} }
        } }
        // At 150% text the page header and player are tall; the tabs sit below the first screenful but are reachable by scrolling.
        compose.onNodeWithText("Transcript").assertExists()
        compose.onNodeWithContentDescription("Play audio").assertIsDisplayed()
        shot("16-detail-large-text")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xxhdpi")
    fun recordingDark() {
        install("kroko-en", "parakeet-unified", "qwen35-2b")
        val session = meeting()
        Live.session.value = session.name; Live.recording.value = true; Live.elapsedMs.value = 2_754_000
        Live.partial.value = "and the next meeting is Tuesday at"
        compose.setContent { AppTheme { RecordingScreen(actions) } }
        shot("09-recording-dark")
    }
}

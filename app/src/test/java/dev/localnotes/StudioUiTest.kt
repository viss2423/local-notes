package dev.localnotes

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File
import java.io.RandomAccessFile
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi", shadows = [HostAtomicFileShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudioUiTest {
    private var controller: ActivityController<MainActivity>? = null
    @Before fun reset() {
        Store.busy.set(false); Store.recording = false; Store.paused = false
        Store.status = "Ready"; Store.activeSession = null; Store.liveDraft = ""; Store.liveStatus = ""
    }
    @After fun stop() { controller?.pause()?.stop()?.destroy(); reset() }
    private fun launch(): MainActivity {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        shadowOf(Looper.getMainLooper()).idle()
        return controller!!.get()
    }
    private fun all(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(all(view.getChildAt(i)))
    }
    private fun views(activity: MainActivity) = all(activity.window.decorView)
    private fun clickText(activity: MainActivity, label: String) {
        var view: View = views(activity).filterIsInstance<TextView>().first { it.text.toString() == label }
        while (!view.isClickable) view = view.parent as View
        assertTrue(view.performClick()); shadowOf(Looper.getMainLooper()).idle()
    }
    private fun has(activity: MainActivity, label: String) = views(activity).filterIsInstance<TextView>().any { it.text.toString() == label }
    private fun screenshot(activity: MainActivity, name: String, widthDp: Int = 411, heightDp: Int = 891) {
        val view = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val density = activity.resources.displayMetrics.density
        val width = (widthDp * density).toInt(); val height = (heightDp * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        // Capture the top of each screen independently of pending focus/scroll restoration.
        (view as ViewGroup).getChildAt(0).scrollTo(0, 0)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val folder = File("build/ui-previews").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
    @Test fun firstRunHasClearRecordingAndSetupActions() {
        val activity = launch()
        assertTrue(has(activity, "Record"))
        assertTrue(views(activity).any { it.contentDescription == "Start recording" })
        assertTrue(has(activity, "No recordings yet."))
        assertTrue(has(activity, "Live text is off"))
        screenshot(activity, "01-record")
        clickText(activity, "Setup")
        assertTrue(has(activity, "Setup"))
        assertTrue(has(activity, "MODELS · 0 OF 2 INSTALLED"))
        screenshot(activity, "02-setup")
        clickText(activity, "Import file")
        val request = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
        assertEquals(20, request.requestCode)
    }
    @Test fun librarySearchRenameAndReaderPreserveSavedAudio() {
        val context = RuntimeEnvironment.getApplication()
        val session = Store.create(context)
        val audio = File(session, "audio.pcm").apply { writeBytes(ByteArray(32000) { (it % 100).toByte() }) }
        val original = audio.readBytes()
        Store.write(File(session, "title.txt"), "Product planning")
        val folder = File(session, "run-0123456789abcdefabcd").apply { mkdirs() }
        Store.write(File(session, "current-run.txt"), folder.name)
        Store.write(File(folder, "concise.md"), "# Product planning\n\nThe team agreed to test the new onboarding flow with five users before Friday.\n\n## Next steps\n- Maya will prepare the prototype.\n- Alex will schedule the interviews.\n\n## Open question\nShould reminders be enabled by default?")
        Store.write(File(folder, "detailed.md"), "# Detailed notes\n\n[00:00:05] Five user interviews are planned.\n\n## Decision\nTest onboarding before Friday.")
        Store.write(File(folder, "transcript.txt"), "[00:00:00–00:00:08] Let's test onboarding with five users before Friday.")
        val activity = launch()
        screenshot(activity, "01b-record-with-history")
        clickText(activity, "Recordings")
        assertTrue(has(activity, "TODAY"))
        screenshot(activity, "03-library")
        val search = views(activity).filterIsInstance<EditText>().first()
        search.setText("no match")
        assertTrue(has(activity, "No matching recordings"))
        search.setText("Product")
        clickText(activity, "Product planning")
        assertTrue(has(activity, "Copy summary"))
        screenshot(activity, "04-summary")
        clickText(activity, "Transcript")
        assertTrue(has(activity, "Copy transcript"))
        views(activity).first { it.contentDescription == "Rename recording" }.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        all(dialog.window!!.decorView).filterIsInstance<EditText>().first().setText("Weekly planning")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Weekly planning", File(session, "title.txt").readText())
        assertArrayEquals(original, audio.readBytes())
        activity.onBackPressed()
        assertTrue(has(activity, "Recordings"))
        assertTrue("Search is kept, so the renamed recording no longer matches", has(activity, "No matching recordings"))
        views(activity).filterIsInstance<EditText>().first().setText("")
        assertTrue(has(activity, "Weekly planning"))
    }
    @Test fun activeRecordingShowsTimerAndPauseState() {
        val session = Store.create(RuntimeEnvironment.getApplication())
        RandomAccessFile(File(session, "audio.pcm"), "rw").use { it.setLength(65 * 32000) }
        Store.write(File(session, "live-transcript.txt"), "[00:00:00–00:00:12] The meeting has started.")
        Store.liveDraft = "and the next item is"; Store.liveStatus = "Listening"
        Store.busy.set(true); Store.recording = true; Store.activeSession = session.name; Store.status = "Recording"
        val activity = launch()
        assertTrue(has(activity, "00:01:05"))
        assertTrue(has(activity, "Stop & save"))
        assertTrue(has(activity, "LIVE TRANSCRIPT"))
        assertTrue(views(activity).filterIsInstance<TextView>().any { it.text.toString().endsWith("The meeting has started.\nand the next item is") })
        screenshot(activity, "05-recording")
        Store.paused = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(has(activity, "Resume"))
        assertTrue(has(activity, "Paused"))
    }
    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun darkModeRecordingScreenRenders() {
        val session = Store.create(RuntimeEnvironment.getApplication())
        RecordingInfo.start(session, System.currentTimeMillis() - 754_000, "audio-clock")
        RandomAccessFile(File(session, "audio.pcm"), "rw").use { it.setLength(754L * 32000) }
        Store.write(File(session, "live-transcript.txt"), "[00:00:00–00:00:09] Okay, let's start with the budget review.\n[00:00:09–00:00:18] Spending is about four percent under plan this quarter.")
        Store.liveDraft = "mostly because the travel line came in lower"; Store.liveStatus = "Listening"
        Store.busy.set(true); Store.recording = true; Store.activeSession = session.name; Store.status = "Recording"
        val activity = launch()
        assertTrue(has(activity, "Stop & save"))
        screenshot(activity, "07-recording-dark")
    }
    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun compactScreenKeepsNavigationAndSetupAccessible() {
        val activity = launch()
        clickText(activity, "Setup")
        screenshot(activity, "06-setup-compact", 360, 800)
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val navigation = (root as ViewGroup).getChildAt(root.childCount - 1) as ViewGroup
        assertTrue(navigation.bottom <= root.height)
        for (i in 0 until navigation.childCount) {
            assertTrue(navigation.getChildAt(i).height >= (48 * activity.resources.displayMetrics.density).toInt())
        }
    }
}

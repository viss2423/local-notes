package dev.localnotes

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dev.localnotes.ui.App
import dev.localnotes.ui.AppTheme
import dev.localnotes.ui.Actions
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity(), Actions {
    private var exportTarget: String? = null
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) record() else toast("Microphone access is needed to record. Allow it in Settings → Apps → Local Notes.")
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = exportTarget ?: return@registerForActivityResult
        if (uri != null) Thread {
            runCatching { export(uri, Store.sessions(this).first { it.name == id }) }
                .onSuccess { runOnUiThread { toast("Exported") } }
                .onFailure { runOnUiThread { toast("Export failed: ${it.message}") } }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        exportTarget = savedInstanceState?.getString("exportTarget")
        setContent { AppTheme { App(this) } }
        simulateFrom(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); simulateFrom(intent) }
    /** Debug builds: `am start -n dev.localnotes/.MainActivity --es simulate <wav>` runs the full pipeline on a test file. */
    private fun simulateFrom(intent: Intent?) {
        val wav = intent?.getStringExtra("simulate") ?: return
        // The test WAV is a one-shot command, not a property of the task. Activity recreation
        // must never start another synthetic recording from the same launch intent.
        intent.removeExtra("simulate")
        if (!BuildConfig.DEBUG) return
        startRecording(Intent(this, RecorderService::class.java).putExtra("simulate", wav))
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("exportTarget", exportTarget); super.onSaveInstanceState(outState) }

    override fun record() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { microphone.launch(Manifest.permission.RECORD_AUDIO); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        startRecording(Intent(this, RecorderService::class.java))
    }
    /** Starts recording; if the last recording is still being saved (a few seconds), starts right after it. */
    private fun startRecording(intent: Intent) {
        // A summary still being written for an earlier recording pauses and resumes after this one.
        if (ProcessingService.isRunning()) startService(Intent(this, ProcessingService::class.java).setAction(ProcessingService.PAUSE))
        if (!Store.busy.get()) { startForegroundService(intent); return }
        if (Live.recording.value) return
        toast("Saving the last recording. Recording starts in a moment.")
        Thread {
            val until = System.currentTimeMillis() + 30_000
            while (Store.busy.get() && System.currentTimeMillis() < until) Thread.sleep(200)
            runOnUiThread {
                // Android only lets the microphone start while the app is on screen.
                if (Store.busy.get() || isDestroyed) return@runOnUiThread
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) startForegroundService(intent)
                else toast("Recording did not start because the app was in the background. Tap Record again.")
            }
        }.start()
    }
    override fun pause() { startService(Intent(this, RecorderService::class.java).setAction("pause")) }
    override fun stop() { startService(Intent(this, RecorderService::class.java).setAction("stop")) }
    override fun download(spec: ModelSpec) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        startForegroundService(Intent(this, DownloadService::class.java).putExtra("model", spec.id))
    }
    override fun cancelDownloads() { startService(Intent(this, DownloadService::class.java).setAction("stop")) }
    override fun process(id: String, transcribe: Boolean, resummarize: Boolean) {
        if (transcribe && Models.active(this, Role.ACCURATE) == null) { toast("Download the speech models in Setup first."); return }
        if (!transcribe && Models.active(this, Role.SUMMARY) == null) { toast("Download a summary model in Setup first."); return }
        val session = Store.sessions(this).firstOrNull { it.name == id } ?: return
        ProcessingService.enqueue(this, session, transcribe, resummarize)
        if (Live.recording.value) toast("Queued. It runs after the current recording.")
    }
    override fun stopProcessing() {
        Language.requestStop()
        startService(Intent(this, ProcessingService::class.java).setAction(ProcessingService.STOP))
    }
    override fun export(id: String) {
        exportTarget = id
        val session = Store.sessions(this).first { it.name == id }
        exporter.launch("${exportStem(session)}.zip")
    }
    override fun share(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))
    }
    override fun copy(text: String) {
        if (text.toByteArray().size > 500_000) { toast("Too long for the clipboard. Use Export instead."); return }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Local Notes", text))
        if (Build.VERSION.SDK_INT < 33) toast("Copied")
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    /** "2026-10-01 1041 Weekly planning": sorts by date in any file manager. */
    private fun exportStem(session: File): String {
        val start = RecordingInfo.startEpoch(session)
        val stamp = RecordingInfo.format(start, "yyyy-MM-dd HHmm")
        val title = File(session, "title.txt").takeIf { it.exists() }?.readText().orEmpty()
        if (title.isBlank() || title == RecordingInfo.defaultTitle(start)) return "$stamp Recording"
        return "$stamp ${title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), " ").trim().take(60)}"
    }

    private fun export(uri: Uri, session: File) {
        val audio = File(session, "audio.pcm")
        val length = audio.length() / 2 * 2
        check(length <= 0xffffffffL - 36) { "Audio exceeds the WAV size limit" }
        requireNotNull(contentResolver.openOutputStream(uri, "wt")).use { output ->
            ZipOutputStream(output).use { zip ->
                fun entry(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
                zip.putNextEntry(ZipEntry("${exportStem(session)}.wav"))
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray()).putInt((length + 36).toInt()).put("WAVEfmt ".toByteArray())
                    .putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
                    .put("data".toByteArray()).putInt(length.toInt())
                zip.write(header.array())
                audio.inputStream().use { input ->
                    val buffer = ByteArray(1 shl 16); var remaining = length
                    while (remaining > 0) {
                        val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        check(n > 0) { "Unexpected end of recording" }
                        zip.write(buffer, 0, n); remaining -= n
                    }
                }
                zip.closeEntry()
                val transcript = Transcript(session)
                if (transcript.size() > 0) entry("transcript.txt", transcript.text().toByteArray())
                File(session, "summary.md").takeIf { it.exists() }?.let { entry("summary.md", it.readBytes()) }
                NotesBuilder(session, transcript).detailed().takeIf { it.isNotBlank() }?.let { entry("notes.md", it.toByteArray()) }
                for (name in listOf("recording-info.json", "segments.json")) File(session, name).takeIf { it.exists() }?.let { entry(name, it.readBytes()) }
                entry("README.txt", ("Audio: 16 kHz mono WAV. recording-info.json holds the exact start/end times (epoch ms, from the audio clock " +
                    "when available), time zone and pauses. Transcript times are time of day. The transcript and notes were made on the phone " +
                    "by speech recognition and a language model and can contain mistakes; the audio is the source of truth.").toByteArray())
            }
        }
    }
}

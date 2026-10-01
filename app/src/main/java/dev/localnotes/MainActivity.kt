package dev.localnotes

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity(), StudioUi.Actions {
    private lateinit var ui: StudioUi
    private val handler = Handler(Looper.getMainLooper())
    private var exportTarget: String? = null
    private val ticker = object : Runnable {
        override fun run() { ui.tick(); handler.postDelayed(this, 1000) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportTarget = savedInstanceState?.getString("exportSession")
        ui = StudioUi(this, this)
        ui.restore(savedInstanceState)
    }
    override fun onResume() { super.onResume(); handler.post(ticker); ui.refresh() }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exportSession", exportTarget); ui.save(outState)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Platform back navigation")
    override fun onBackPressed() { if (!ui.back()) super.onBackPressed() }
    override fun record() {
        if (Store.busy.get()) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10); return
        }
        startForegroundService(Intent(this, RecorderService::class.java))
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
    }
    override fun pauseRecording() {
        if (Store.recording) startService(Intent(this, RecorderService::class.java).setAction("pause"))
    }
    override fun stopWork() {
        if (Store.busy.get() && Store.activeSession != null)
            startService(Intent(this, if (Store.recording) RecorderService::class.java else ProcessingService::class.java).setAction("stop"))
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 10) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) record()
            else alert("Microphone access is needed to record. You can enable it in your phone's Settings → Apps → Local Notes → Permissions.")
        }
    }
    override fun importModelFile(speech: Boolean) {
        check(!Store.busy.get()) { "Finish the current recording or task before importing a model." }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
        }, if (speech) 20 else 21)
    }
    override fun openDownload(speech: Boolean) {
        val url = if (speech) "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en-q5_1.bin?download=true"
            else "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf?download=true"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
    override fun processSession(session: File, transcriptOnly: Boolean) {
        check(!Store.busy.get()) { "Finish the current recording or task first." }
        val models = Store.models(this)
        check(File(models, "speech.bin").exists()) { "Add an English speech model in Setup first." }
        check(transcriptOnly || File(models, "summary.gguf").exists()) { "Add the summary model in Setup first." }
        startForegroundService(Intent(this, ProcessingService::class.java).putExtra("session", session.name).putExtra("transcriptOnly", transcriptOnly))
    }
    override fun exportSession(session: File) {
        check(!Store.busy.get()) { "Finish the current recording or task before exporting." }
        exportTarget = session.name
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/zip"; addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, "${exportStem(session)}.zip")
        }, 30)
    }
    /** "2026-10-01 1041 Weekly planning": sorts by date in any file manager. */
    private fun exportStem(session: File): String {
        val start = RecordingInfo.startEpoch(session)
        val stamp = RecordingInfo.format(start, "yyyy-MM-dd HHmm")
        val title = File(session, "title.txt").takeIf { it.exists() }?.readText().orEmpty()
        if (title.isBlank() || title == RecordingInfo.defaultTitle(start)) return "$stamp Recording"
        return "$stamp ${title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), " ").trim().take(60)}"
    }
    private fun currentRun(session: File): File {
        val name = File(session, "current-run.txt").takeIf { it.exists() }?.readText() ?: "not-processed"
        check(name.matches(Regex("run-[a-f0-9]{20}|not-processed")))
        return File(session, name)
    }
    @Deprecated("Uses platform document picker for minimal dependencies")
    override fun onActivityResult(code: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(code, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (!Store.busy.compareAndSet(false, true)) { alert("Finish the current operation first"); return }
        val sessionId = exportTarget
        Thread {
            try {
                when (code) {
                    20, 21 -> importModel(uri, code == 20)
                    30 -> export(uri, Store.sessions(this).first { it.name == sessionId })
                }
            } catch (error: Exception) {
                Store.status = "Operation failed: ${error.message}"
            } finally {
                Store.busy.set(false)
                runOnUiThread { if (!isDestroyed) ui.refresh() }
            }
        }.start()
    }
    private fun importModel(uri: Uri, speech: Boolean) {
        val destination = File(Store.models(this), if (speech) "speech.bin" else "summary.gguf")
        val pending = File(destination.parentFile, destination.name + ".importing")
        try {
            Store.status = "Importing model…"
            requireNotNull(contentResolver.openInputStream(uri)).use { input ->
                FileOutputStream(pending).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var count = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        check(pending.parentFile!!.usableSpace > n + 20L * 1024 * 1024) { "Not enough storage for model" }
                        output.write(buffer, 0, n); count += n
                        Store.status = "Importing model • ${count / (1024 * 1024)} MB"
                    }
                    output.fd.sync()
                }
            }
            val header = ByteArray(4)
            DataInputStream(pending.inputStream()).use { it.readFully(header) }
            val expected = if (speech) byteArrayOf(0x6c, 0x6d, 0x67, 0x67) else "GGUF".toByteArray()
            check(header.contentEquals(expected) && pending.length() > 1000000) { "Wrong model format; existing model retained" }
            // POSIX rename on Android atomically replaces an existing file on the same filesystem.
            check(pending.renameTo(destination)) { "Could not save imported model" }
            Store.status = "Model imported. Load compatibility will be checked when processing."
        } finally { pending.delete() }
    }
    private fun export(uri: Uri, session: File) {
        Store.status = "Exporting audio and notes…"
        val audio = File(session, "audio.pcm")
        val length = audio.length() / 2 * 2
        check(length <= 0xffffffffL - 36) { "Audio exceeds WAV size limit" }
        requireNotNull(contentResolver.openOutputStream(uri, "wt")).use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("${exportStem(session)}.wav"))
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray()).putInt((length + 36).toInt()).put("WAVEfmt ".toByteArray())
                    .putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
                    .put("data".toByteArray()).putInt(length.toInt())
                zip.write(header.array())
                audio.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    var remaining = length
                    while (remaining > 0) {
                        val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        check(n > 0) { "Unexpected end of recording" }
                        zip.write(buffer, 0, n); remaining -= n
                    }
                }
                zip.closeEntry()
                for (name in listOf("recording-info.json", "live-transcript.txt")) {
                    val file = File(session, name)
                    if (file.exists()) {
                        zip.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
                }
                for (name in listOf("transcript.txt", "detailed.md", "concise.md", "performance.json", "transcript-progress.json")) {
                    val file = File(currentRun(session), name)
                    if (file.exists()) {
                        zip.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                    }
                }
                zip.putNextEntry(ZipEntry("README.txt"))
                zip.write("Original audio: 16 kHz mono PCM WAV. recording-info.json records exact wall-clock start/end instants available to Android, zone IDs and sample count. Audio timestamps exclude paused time. Live text is a preview; the final transcript may differ. AI transcripts and summaries may contain errors or omissions. Keep the audio as the source of truth.".toByteArray())
                zip.closeEntry()
            }
        }
        Store.status = "Export saved"
    }
    private fun alert(message: String) { AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show() }
}

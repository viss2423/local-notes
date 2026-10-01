package dev.localnotes

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.util.concurrent.CancellationException

class ProcessingService : Service() {
    @Volatile private var stopping = false
    private var worker: Thread? = null
    private var transcriptOnly = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopping = true
            if (worker == null) stopSelf()
            Store.status = "Stopping processing…"
            runCatching { Speech.requestStop() }
            runCatching { Language.requestStop() }
            return START_NOT_STICKY
        }
        if (worker != null) return START_NOT_STICKY
        if (!Store.busy.compareAndSet(false, true)) { stopSelf(); return START_NOT_STICKY }
        try {
            val id = requireNotNull(intent?.getStringExtra("session"))
            transcriptOnly = intent?.getBooleanExtra("transcriptOnly", false) ?: false
            val session = Store.sessions(this).firstOrNull { it.name == id } ?: error("Session not found")
            Store.activeSession = id
            Notifications.show(this, "Preparing offline AI", 2, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            worker = Thread { process(session) }.also { it.start() }
        } catch (error: Exception) {
            Store.status = "Cannot process: ${error.message}"
            Store.activeSession = null; Store.busy.set(false); stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun update(text: String) {
        if (stopping) throw CancellationException("Paused; tap Process to resume")
        Store.status = text
        Notifications.show(this, text, 2, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }
    private fun process(session: File) {
        val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalNotes:process")
        try {
            wake.acquire()
            val speech = File(Store.models(this), "speech.bin")
            val language = File(Store.models(this), "summary.gguf")
            check(speech.exists()) { "Import an English speech model first" }
            check(transcriptOnly || language.exists()) { "Import the summary model, or choose Transcribe only" }
            Speech.resetStop()
            Language.resetStop()
            val audio = File(session, "audio.pcm")
            check(audio.length() >= 3200) { "Recording is empty or too short" }
            val asrKey = TranscriptionStage.cacheKey(audio, speech)
            val key = TranscriptionStage.key("notes-v2:$asrKey:${language.length()}:${language.lastModified()}")
            val cache = File(session, "run-$key").apply { mkdirs() }
            val speechCache = File(session, "asr-$asrKey")
            Store.write(File(session, "current-run.txt"), cache.name)
            Store.write(File(session, "state.txt"), "Processing interrupted; tap Continue to resume")
            var speechHandle = 0L
            try {
                TranscriptionStage.run(audio, speechCache, cache, transcribe = { samples, progress ->
                    if (speechHandle == 0L) {
                        update("Loading transcription model")
                        speechHandle = Speech.open(speech.absolutePath)
                    }
                    Speech.transcribe(speechHandle, samples, SpeechProgress { percent -> progress(percent) }, 4, 0).toString(Charsets.UTF_8)
                }, update = { update(it) }, progress = { Store.status = it })
            } finally { if (speechHandle != 0L) Speech.close(speechHandle) }
            val transcript = File(cache, "transcript.txt").readText()
            check(transcript.isNotBlank()) { "No speech detected. Audio is retained." }
            if (transcriptOnly) {
                Store.write(File(session, "state.txt"), "Transcript complete; summaries can be created separately")
                Store.status = "Transcript saved. Open your recording to read it or create summaries."
                return
            }
            check(transcript.isNotBlank()) { "No speech detected. Audio is retained." }
            update("Loading summary model")
            val model = Language.open(language.absolutePath)
            try {
                val parts = Chunking.textParts(transcript)
                val notes = parts.mapIndexed { i, part ->
                    val target = File(cache, "notes-%05d.txt".format(i))
                    if (!target.exists()) {
                        update("Writing detailed notes ${i + 1}/${parts.size}")
                        val prompt = SummaryPrompts.detailed + "\n\nSOURCE TRANSCRIPT:\n$part"
                        Store.write(target, Language.generate(model, prompt.toByteArray()).toString(Charsets.UTF_8))
                    }
                    "## Section ${i + 1}\n${target.readText()}"
                }
                Store.write(File(cache, "detailed.md"), "# Detailed notes\n\nAI-generated: verify facts against the timestamped transcript and original audio.\n\n" + notes.joinToString("\n\n"))
                // Every source section participates; the full detailed notes are never replaced by reductions.
                var current = notes.joinToString("\n\n")
                var level = 0
                while (true) {
                    val batches = Chunking.textParts(current)
                    val reduced = batches.mapIndexed { i, part ->
                        val target = File(cache, "brief-$level-%05d.txt".format(i))
                        if (!target.exists()) {
                            update("Writing concise summary • pass ${level + 1}, ${i + 1}/${batches.size}")
                            val prompt = SummaryPrompts.concise + "\n\nSOURCE NOTES:\n$part"
                            Store.write(target, Language.generate(model, prompt.toByteArray()).toString(Charsets.UTF_8))
                        }
                        target.readText()
                    }.joinToString("\n\n")
                    if (batches.size == 1) {
                        Store.write(File(cache, "concise.md"), "# Concise summary\n\nAI-generated overview; consult detailed notes and transcript for full context.\n\n$reduced")
                        break
                    }
                    check(reduced.length < current.length && level < 10) { "Summary reduction did not converge. Detailed notes and transcript are available." }
                    current = reduced; level++
                }
            } finally { Language.close(model) }
            Store.write(File(session, "state.txt"), "Complete • review AI output for accuracy")
            Store.status = "Transcript and both summaries saved"
        } catch (error: Throwable) {
            Store.status = if (stopping) "Stopped; completed sections are saved. Tap Continue to resume." else if (error is CancellationException) error.message!! else "Processing stopped: ${error.message ?: error.javaClass.simpleName}"
            runCatching { Store.write(File(session, "state.txt"), Store.status) }
        } finally {
            if (wake.isHeld) wake.release()
            Store.activeSession = null; Store.busy.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    override fun onDestroy() { stopping = true; runCatching { Speech.requestStop() }; runCatching { Language.requestStop() }; super.onDestroy() }
}

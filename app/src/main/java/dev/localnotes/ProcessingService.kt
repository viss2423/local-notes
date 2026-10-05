package dev.localnotes

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background job queue for saved recordings: finishes summaries after recording, transcribes audio that
 * has no transcript (older recordings) and re-creates summaries on request. Work is tracked with marker
 * files in each recording's folder, so it survives being paused (by a new recording), stopped or killed,
 * and resumes later. It never blocks the microphone.
 */
class ProcessingService : Service() {
    companion object {
        const val DRAIN = "drain"
        const val PAUSE = "pause"
        const val STOP = "stop"
        const val TRANSCRIBE_PENDING = "transcribe-pending"
        const val PROCESSING_ERROR = "processing-error.txt"
        private val running = AtomicBoolean(false)

        /** Queue work for a recording and start the job. */
        fun enqueue(context: Context, session: File, transcribe: Boolean, resummarize: Boolean) {
            File(session, PROCESSING_ERROR).delete()
            if (transcribe) Store.write(File(session, TRANSCRIBE_PENDING), "")
            if (resummarize && !transcribe) NotesBuilder(session, Transcript(session)).reset()
            Store.write(File(session, RecorderService.SUMMARY_PENDING), "")
            context.startForegroundService(Intent(context, ProcessingService::class.java).setAction(DRAIN))
        }
        fun isRunning() = running.get()
        fun pending(session: File) = File(session, RecorderService.SUMMARY_PENDING).exists() || File(session, TRANSCRIBE_PENDING).exists()

        /** Recover audio left by an abrupt app/process exit; the PCM file remains the source of truth. */
        fun recoverInterrupted(context: Context) {
            if (Store.busy.get() || Live.recording.value) return
            if (Models.active(context, Role.ACCURATE) == null || Models.vadPath(context) == null) return
            Store.sessions(context).forEach { session ->
                val state = File(session, "state.txt")
                val audio = File(session, "audio.pcm")
                val message = runCatching { state.readText() }.getOrNull().orEmpty()
                if ((!message.startsWith("Recording was interrupted.") && !message.startsWith("Audio saved. Processing is incomplete."))
                    || audio.length() == 0L || pending(session)) return@forEach
                val samples = audio.length() / 2
                if (RecordingInfo.endEpoch(session) == null) {
                    val end = RecordingInfo.wallClockAt(session, samples * 1000 / RATE)
                        ?: audio.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
                    RecordingInfo.finish(session, end, samples, interrupted = true)
                }
                Store.write(File(session, TRANSCRIBE_PENDING), "")
                if (Models.active(context, Role.SUMMARY) != null) Store.write(File(session, RecorderService.SUMMARY_PENDING), "")
                Store.write(state, "done")
            }
        }
    }
    @Volatile private var cancelled = false
    @Volatile private var again = false
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PAUSE, STOP -> {
                // PAUSE: a recording is starting; leave the work queued. STOP: the user stopped it; also clear the queue.
                cancelled = true; Language.requestStop()
                if (intent.action == STOP) Store.sessions(this).forEach { File(it, RecorderService.SUMMARY_PENDING).delete(); File(it, TRANSCRIBE_PENDING).delete() }
                if (!running.get()) stopSelf()
                return START_NOT_STICKY
            }
        }
        Notifications.show(this, "Writing the summary", 2, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!running.compareAndSet(false, true)) { again = true; return START_NOT_STICKY }
        cancelled = false
        Thread { drain() }.also { it.name = "LocalNotes-process" }.start()
        return START_NOT_STICKY
    }

    private fun drain() {
        val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalNotes:process")
        wake.acquire(6 * 60 * 60 * 1000L)
        try {
            do {
                again = false
                // Oldest first; never touch a recording that is still being recorded.
                for (session in Store.sessions(this).reversed()) {
                    if (cancelled || Live.recording.value) break
                    if (!pending(session) || File(session, PROCESSING_ERROR).exists() || session.name == Live.session.value) continue
                    process(session)
                }
            } while (again && !cancelled && !Live.recording.value)
        } finally {
            if (wake.isHeld) wake.release()
            Live.working.value = false; Live.summaryStatus.value = ""; Live.summaryDraft.value = ""
            if (Live.session.value != null && !Live.recording.value) Live.session.value = null
            running.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }

    private fun process(session: File) {
        Live.working.value = true
        if (!Live.recording.value) Live.session.value = session.name
        val transcript = Transcript(session)
        try {
            Language.resetStop()
            if (File(session, TRANSCRIBE_PENDING).exists() || transcript.size() == 0) {
                transcribe(session, transcript)
                File(session, TRANSCRIBE_PENDING).delete()
            }
            if (cancelled) return
            summarize(session, transcript)
            if (!cancelled) {
                File(session, RecorderService.SUMMARY_PENDING).delete()
                File(session, PROCESSING_ERROR).delete()
            }
        } catch (error: InterruptedException) {
            // Paused; the markers stay so the work resumes later.
        } catch (error: Throwable) {
            if (!cancelled) {
                Live.status.value = "Could not finish ${File(session, "title.txt").takeIf { it.exists() }?.readText() ?: "a recording"}: ${error.message}"
                // Keep the work markers. The user can retry after fixing a model or storage problem;
                // this marker prevents the same failure from looping automatically on every launch.
                runCatching { Store.write(File(session, PROCESSING_ERROR), error.message ?: error.javaClass.simpleName) }
            }
        } finally {
            if (Live.session.value == session.name && !Live.recording.value) Live.session.value = null
        }
    }

    private fun threads() = if (Thermal.hot(this)) Tuning.summaryThreads else Tuning.finishThreads

    private fun transcribe(session: File, transcript: Transcript) {
        val spec = Models.active(this, Role.ACCURATE) ?: error("Download the speech models in Setup to transcribe saved audio.")
        val vad = Models.vadPath(this) ?: error("The voice detector is missing. Download the speech models again in Setup.")
        val segments = mutableListOf<Segment>()
        var speaker = Models.active(this, Role.SPEAKER)?.let { runCatching { SpeakerEngine(this, it) }.getOrNull() }
        try {
            Refiner(this, spec, threads()).use { refiner ->
                SpeechSplitter(vad).use { splitter ->
                    splitter.split(File(session, "audio.pcm"), { cancelled }, { Live.summaryStatus.value = "Transcribing · ${(it * 100).toInt()}%" }) { startMs, samples ->
                        val text = refiner.transcribe(samples)
                        if (text.isNotBlank()) {
                            val voice = runCatching { speaker?.identify(samples) }.onFailure {
                                Log.w(RecorderService.PERF, "Voice ID stopped during saved transcription", it)
                                runCatching { speaker?.close() }; speaker = null
                            }.getOrNull()
                            segments += Segment(startMs, startMs + samples.size * 1000L / RATE, text, true, voice)
                        }
                        if (segments.size % 10 == 0) transcript.replaceAll(segments)
                    }
                }
            }
        } finally {
            runCatching { speaker?.close() }
        }
        transcript.replaceAll(segments)
        NotesBuilder(session, transcript).reset()
    }

    private fun summarize(session: File, transcript: Transcript) {
        if (transcript.words() == 0) return
        val spec = Models.active(this, Role.SUMMARY) ?: return
        val notes = NotesBuilder(session, transcript)
        val t = System.currentTimeMillis()
        Writer(this, spec, threads()).use { writer ->
            while (!cancelled && notes.step(writer, final = true, needRefined = false)) Unit
            if (!cancelled && (notes.overviewDue() || !File(session, "summary.md").exists())) notes.overview(writer)
        }
        if (!cancelled && File(session, "summary.md").exists()) File(session, "transcript-edited").delete()
        Log.i(RecorderService.PERF, "finish_ms=${System.currentTimeMillis() - t} session=${session.name}")
    }
}

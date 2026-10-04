package dev.localnotes

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Records audio and, at the same time, transcribes and summarizes it:
 * capture → live text (streaming model) → accuracy pass (offline model, per sentence) → section notes.
 * Stop ends capture; the remaining work (seconds, not minutes) finishes before the service exits.
 */
class RecorderService : Service() {
    @Volatile private var stopping = false
    @Volatile private var captureDone = false
    @Volatile private var lastAudioAt = 0L
    @Volatile private var stoppedAt = 0L
    private var notificationStartMs = 0L
    private var worker: Thread? = null
    private val audioQueue = CaptureBacklog(600) // At most 60 s of 100 ms chunks, even on a multi-hour recording.
    private val refineQueue = LinkedBlockingQueue<Int>()
    @Volatile private var liveIncompleteAlerted = false
    @Volatile private var liveFailed = false
    @Volatile private var liveDone = false
    @Volatile private var refineDone = false
    /** Debug builds only: a 16 kHz mono 16-bit WAV played in real time instead of the microphone, for on-device tests. */
    private var simulate: File? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "stop" -> { stoppedAt = System.currentTimeMillis(); stopping = true; if (worker?.isAlive != true) stopSelf(startId); return START_NOT_STICKY }
            "pause" -> {
                Live.paused.value = !Live.paused.value
                Notifications.show(this, if (Live.paused.value) "Paused" else "Recording", 1,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, notificationStartMs)
                return START_NOT_STICKY
            }
        }
        // Started with startForegroundService: always enter the foreground first, even if this start is ignored.
        notificationStartMs = System.currentTimeMillis()
        Notifications.show(this, "Recording", 1, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, notificationStartMs)
        // This instance may still be finishing the previous recording; only one recording at a time.
        if (worker?.isAlive == true) return START_NOT_STICKY
        if (!Store.busy.compareAndSet(false, true)) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        // Fresh state for this recording (Android may reuse the service instance right after the last one).
        stopping = false; captureDone = false; liveDone = false; refineDone = false; lastAudioAt = 0L; stoppedAt = 0L
        audioQueue.reset(); refineQueue.clear(); liveIncompleteAlerted = false; liveFailed = false
        simulate = if (BuildConfig.DEBUG) intent?.getStringExtra("simulate")?.let(::File)?.takeIf { it.exists() } else null
        try {
            val session = Store.create(this)
            Live.session.value = session.name; Live.recording.value = true; Live.paused.value = false
            Live.elapsedMs.value = 0; Live.partial.value = ""; Live.summaryDraft.value = ""; Live.summaryStatus.value = ""; Live.status.value = ""
            worker = Thread { run(session) }.also { it.name = "LocalNotes-record"; it.start() }
        } catch (error: Exception) {
            Live.status.value = "Cannot start recording: ${error.message}"
            Live.recording.value = false; Store.busy.set(false); stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun run(session: File) {
        val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalNotes:record")
        wake.acquire(12 * 60 * 60 * 1000L)
        val transcript = Transcript(session)
        val helpers = listOf(
            Thread({ liveLoop(session, transcript) }, "LocalNotes-live"),
            Thread({ refineLoop(session, transcript) }, "LocalNotes-refine"),
            Thread({ summaryLoop(session, transcript) }, "LocalNotes-summary"),
        )
        try {
            helpers.forEach { it.start() }
            simulate?.let { simulateCapture(session, it) } ?: capture(session)
        } finally {
            captureDone = true
            try {
                // Interrupt a section that is being written right now; it is redone by the summary job.
                Language.requestStop()
                Live.recording.value = false; Live.paused.value = false; Live.working.value = true
                Notifications.show(this, "Finishing the transcript", 1, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                val finishing = System.currentTimeMillis()
                helpers.forEach { runCatching { it.join() } }
                Log.i(PERF, "transcript_final_ms=${System.currentTimeMillis() - finishing} segments=${transcript.size()} words=${transcript.words()}")
                // A full, preserved PCM file is the source of truth if live inference could not keep up.
                val recover = (audioQueue.dropped || liveFailed) && Models.active(this, Role.ACCURATE) != null && Models.vadPath(this) != null
                if (recover) Store.write(File(session, ProcessingService.TRANSCRIBE_PENDING), "")
                val summarize = Models.active(this, Role.SUMMARY) != null && (transcript.words() > 0 || recover)
                if (summarize) Store.write(File(session, SUMMARY_PENDING), "")
                Store.write(File(session, "state.txt"), "done")
                Live.working.value = false; Live.summaryStatus.value = ""; Live.session.value = null
                // Clear the active-session guard before the job starts, or it may skip its own pending session.
                if (recover || summarize) runCatching {
                    startForegroundService(Intent(this, ProcessingService::class.java).setAction(ProcessingService.DRAIN))
                }.onFailure { Live.status.value = "Recording saved. Open the app to finish processing: ${it.message}" }
            } catch (error: Exception) {
                Log.e(PERF, "Could not finish recording", error)
                Live.status.value = "Audio saved, but finishing failed: ${error.message}"
                runCatching { Store.write(File(session, "state.txt"), "Audio saved. Processing is incomplete.") }
            } finally {
                if (wake.isHeld) wake.release()
                Live.recording.value = false; Live.working.value = false; Live.session.value = null
                // Always free the microphone and allow another recording after a storage or processing error.
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                Store.busy.set(false)
            }
        }
    }

    private fun capture(session: File) {
        var recorder: AudioRecord? = null
        var started = false
        var captured = 0L
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            Store.write(File(session, "state.txt"), "Recording was interrupted. The saved audio can still be transcribed.")
            val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Microphone does not support 16 kHz recording" }
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission is required" }
            recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 4, 64000))
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            recorder.startRecording()
            val audioRecord = recorder
            val stamp = AudioTimestamp()
            // Wall time of a frame from the audio clock; falls back to read-return time minus buffered audio.
            fun wallAt(frame: Long, fallback: Long): Long {
                if (audioRecord.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) return fallback
                val frameNanos = stamp.nanoTime + (frame - stamp.framePosition) * 1_000_000_000L / RATE
                val wall = System.currentTimeMillis() - (System.nanoTime() - frameNanos) / 1_000_000
                return if (abs(wall - fallback) < 2000) wall else fallback
            }
            val buffer = ByteArray(3200) // 100 ms: short enough for word-by-word live text.
            var framesRead = 0L
            var wasPaused = false
            var lastSync = System.currentTimeMillis()
            FileOutputStream(File(session, "audio.pcm")).use { output ->
                while (!stopping) {
                    val count = recorder.read(buffer, 0, buffer.size)
                    val returnedAt = System.currentTimeMillis()
                    check(count >= 0) { "Microphone read error $count" }
                    if (count == 0) continue
                    val firstFrame = framesRead
                    framesRead += count / 2
                    if (!started) {
                        val fallback = returnedAt - count / 32
                        val startAt = wallAt(0, fallback)
                        RecordingInfo.start(session, startAt, if (startAt == fallback) "read-time" else "audio-clock")
                        Store.write(File(session, "title.txt"), RecordingInfo.defaultTitle(startAt))
                        started = true
                    }
                    if (recorder.activeRecordingConfiguration?.isClientSilenced == true)
                        error("Another app took the microphone. The audio so far is saved.")
                    if (Live.paused.value) {
                        if (!wasPaused) { wasPaused = true; RecordingInfo.pause(session, captured / 2, wallAt(firstFrame, returnedAt - count / 32)) }
                        Live.level.value = 0f
                        continue
                    }
                    if (wasPaused) { wasPaused = false; RecordingInfo.resume(session, wallAt(firstFrame, returnedAt - count / 32)) }
                    check(session.usableSpace > 20 * 1024 * 1024) { "Storage is nearly full; recording stopped safely" }
                    output.write(buffer, 0, count)
                    captured += count
                    lastAudioAt = wallAt(framesRead, returnedAt)
                    val samples = FloatArray(count / 2) { i -> ((buffer[i * 2].toInt() and 255) or (buffer[i * 2 + 1].toInt() shl 8)).toShort() / 32768f }
                    queueLive(samples)
                    var sum = 0.0; for (v in samples) sum += v * v
                    Live.level.value = (sqrt(sum / samples.size).toFloat() * 6f).coerceIn(0f, 1f)
                    Live.elapsedMs.value = captured / 32
                    if (returnedAt - lastSync >= 5000) { output.fd.sync(); lastSync = returnedAt }
                }
                output.fd.sync()
            }
            if (wasPaused) lastAudioAt = 0L // Paused at Stop: the session ends when Stop was pressed.
        } catch (error: Exception) {
            Live.status.value = "Recording stopped: ${error.message}"
            runCatching { Store.write(File(session, "error.txt"), Live.status.value) }
        } finally {
            if (started) runCatching { RecordingInfo.finish(session,
                lastAudioAt.takeIf { it > 0 } ?: stoppedAt.takeIf { it > 0 } ?: System.currentTimeMillis(), captured / 2, !stopping) }
            runCatching { recorder?.stop() }; runCatching { recorder?.release() }
            Live.level.value = 0f
        }
    }

    private fun queueLive(samples: FloatArray) {
        if (!audioQueue.offer(samples) && !liveIncompleteAlerted) {
            liveIncompleteAlerted = true
            Live.partial.value = ""
            Live.status.value = "Live text fell behind. Full audio is safe and will be transcribed after Stop."
            Log.w(PERF, "Live audio backlog exceeded 60 seconds; saved audio will be reprocessed")
        }
    }

    private fun liveLoop(session: File, transcript: Transcript) {
        val spec = Models.active(this, Role.LIVE)
        if (spec == null) { liveFailed = true; Live.status.value = "Recording without live text. Download a speech model in Setup."; liveDone = true; drainAudio(); return }
        var speaker = Models.active(this, Role.SPEAKER)?.let { voiceModel ->
            runCatching { SpeakerEngine(this, voiceModel) }.onFailure {
                Log.w(PERF, "Voice ID unavailable", it)
                Live.status.value = "Voice ID unavailable; audio and transcript are still being saved."
            }.getOrNull()
        }
        val audio = File(session, "audio.pcm")
        fun label(segment: Segment): Segment {
            val engine = speaker ?: return segment
            val id = runCatching {
                engine.identify(readAudio(audio,
                    maxOf(segment.speechStartMs ?: segment.startMs, segment.endMs - 8000), segment.endMs, padMs = 0))
            }.onFailure {
                Log.w(PERF, "Voice ID stopped", it)
                Live.status.value = "Voice ID stopped; recording continues."
                runCatching { speaker?.close() }; speaker = null
            }.getOrNull()
            return segment.copy(speakerId = id)
        }
        try {
            LiveRecognizer(this, spec, Tuning.liveThreads).use { live ->
                while (true) {
                    val chunk = audioQueue.poll(200, TimeUnit.MILLISECONDS)
                    if (chunk == null) { if (captureDone || audioQueue.dropped) break else continue }
                    live.accept(chunk)?.let { raw ->
                        val segment = label(raw)
                        Log.i(PERF, "live_segment end_ms=${segment.endMs} audio_ms=${Live.elapsedMs.value} lag_ms=${Live.elapsedMs.value - segment.endMs} queue=${audioQueue.size}")
                        refineQueue.put(transcript.add(segment))
                    }
                    Live.partial.value = live.partial
                }
                live.flush()?.let { refineQueue.put(transcript.add(label(it))) }
            }
        } catch (error: Throwable) {
            liveFailed = true
            Live.status.value = "Live text stopped: ${error.message}. Audio is still saved."
            drainAudio()
        } finally { runCatching { speaker?.close() }; Live.partial.value = ""; liveDone = true }
    }
    private fun drainAudio() { while (!captureDone) audioQueue.poll(500, TimeUnit.MILLISECONDS); audioQueue.clear() }

    private fun refineLoop(session: File, transcript: Transcript) {
        val spec = Models.active(this, Role.ACCURATE)
        if (spec == null) { refineDone = true; return }
        val audio = File(session, "audio.pcm")
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            Refiner(this, spec, Tuning.refineThreads).use { refiner ->
                // Re-check whole paragraphs (~20 s of speech): the accurate model is much better with context.
                val pending = mutableListOf<Int>()
                fun flush() {
                    if (pending.isEmpty()) return
                    val all = transcript.all()
                    val first = pending.first(); val last = pending.last()
                    val t = System.currentTimeMillis()
                    val text = refiner.transcribe(readAudio(audio, all[first].startMs, all[last].endMs))
                    transcript.refineParagraph(first, last, text)
                    Log.i(PERF, "refine idx=$first..$last dur_ms=${all[last].endMs - all[first].startMs} took_ms=${System.currentTimeMillis() - t} behind_ms=${Live.elapsedMs.value - all[last].endMs} pending=${refineQueue.size}")
                    pending.clear()
                }
                while (true) {
                    val index = refineQueue.poll(300, TimeUnit.MILLISECONDS)
                    if (index == null) {
                        if (liveDone) { flush(); break }
                        // A long pause ends the paragraph so text doesn't wait for the next speaker.
                        val last = pending.lastOrNull()?.let { transcript.all().getOrNull(it) }
                        if (last != null && Live.elapsedMs.value - last.endMs > PARAGRAPH_PAUSE_MS) flush()
                        continue
                    }
                    val all = transcript.all()
                    if (pending.isNotEmpty() && all[pending.last()].speakerId != all[index].speakerId) flush()
                    pending += index
                    // When the phone runs hot, let paragraphs grow (fewer, later re-checks) until it cools down.
                    val limit = if (Thermal.hot(this)) PARAGRAPH_MS * 3 else PARAGRAPH_MS
                    if (all[pending.last()].endMs - all[pending.first()].startMs >= limit) flush()
                }
            }
        } catch (error: Throwable) {
            Live.status.value = "Accuracy pass stopped: ${error.message}"
        } finally { refineDone = true }
    }

    private fun summaryLoop(session: File, transcript: Transcript) {
        val spec = Models.active(this, Role.SUMMARY) ?: return
        val needRefined = Models.active(this, Role.ACCURATE) != null
        val notes = NotesBuilder(session, transcript)
        var writer: Writer? = null
        var lastOverview = System.currentTimeMillis()
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            // While recording: notes for each finished section, and a refreshed overview every few minutes.
            // Everything left at Stop is done by the summary job (ProcessingService), which can be paused.
            while (!captureDone && !audioQueue.dropped) {
                if (Thermal.hot(this)) { Live.summaryStatus.value = "Phone is warm: notes will be written after recording"; Thread.sleep(5000); continue }
                if (writer == null && transcript.words() >= NotesBuilder.SECTION_WORDS) {
                    // A paused summary job must have exited before the shared stop flag is cleared.
                    while (ProcessingService.isRunning() && !captureDone) Thread.sleep(500)
                    if (captureDone) break
                    Language.resetStop()
                    writer = Writer(this, spec, Tuning.summaryThreads)
                }
                val w = writer
                when {
                    w != null && run { val t = System.currentTimeMillis(); notes.step(w, final = false, needRefined = needRefined)
                        .also { if (it) Log.i(PERF, "notes section=${notes.sectionCount()} took_ms=${System.currentTimeMillis() - t} final=false") } } -> {}
                    w != null && notes.overviewDue() && System.currentTimeMillis() - lastOverview > OVERVIEW_EVERY_MS -> {
                        val t = System.currentTimeMillis(); notes.overview(w); lastOverview = System.currentTimeMillis()
                        Log.i(PERF, "overview took_ms=${lastOverview - t} final=false")
                    }
                    else -> { Live.summaryStatus.value = ""; Thread.sleep(2000) }
                }
            }
        } catch (error: Throwable) {
            if (!captureDone) Live.status.value = "Notes paused: ${error.message}. They will be finished after recording."
        } finally { runCatching { writer?.close() }; Live.summaryDraft.value = ""; Live.summaryStatus.value = "" }
    }

    /** Plays a WAV file through the pipeline at real-time pace (debug test hook). */
    private fun simulateCapture(session: File, wav: File) {
        val start = System.currentTimeMillis()
        RecordingInfo.start(session, start, "simulated")
        Store.write(File(session, "title.txt"), "Test · ${wav.nameWithoutExtension}")
        var captured = 0L
        FileOutputStream(File(session, "audio.pcm")).use { output ->
            wav.inputStream().buffered().use { input ->
                input.skip(44)
                val buffer = ByteArray(3200)
                while (!stopping) {
                    val n = input.readNBytes(buffer, 0, buffer.size); if (n <= 0) break
                    output.write(buffer, 0, n); captured += n
                    val samples = FloatArray(n / 2) { i -> ((buffer[i * 2].toInt() and 255) or (buffer[i * 2 + 1].toInt() shl 8)).toShort() / 32768f }
                    queueLive(samples)
                    var sum = 0.0; for (v in samples) sum += v * v
                    Live.level.value = (sqrt(sum / samples.size).toFloat() * 6f).coerceIn(0f, 1f)
                    Live.elapsedMs.value = captured / 32
                    val due = start + captured / 32 - System.currentTimeMillis()
                    if (due > 0) Thread.sleep(due)
                }
            }
        }
        RecordingInfo.finish(session, start + captured / 32, captured / 2, false)
        Log.i(PERF, "capture_done audio_ms=${captured / 32}")
    }

    override fun onDestroy() { stopping = true; super.onDestroy() }
    companion object {
        const val PERF = "LocalNotesPerf"
        const val PARAGRAPH_MS = 24_000L
        const val PARAGRAPH_PAUSE_MS = 4_000L
        const val OVERVIEW_EVERY_MS = 5 * 60_000L
        const val SUMMARY_PENDING = "summary-pending"
    }
}

/** Never let a stalled recognizer consume unbounded memory or block microphone capture. */
class CaptureBacklog(capacity: Int) {
    private val queue = LinkedBlockingQueue<FloatArray>(capacity)
    @Volatile var dropped = false
        private set
    val size: Int get() = queue.size
    fun offer(samples: FloatArray): Boolean {
        if (dropped) return false
        if (queue.offer(samples)) return true
        dropped = true
        queue.clear()
        return false
    }
    fun poll(timeout: Long, unit: TimeUnit): FloatArray? = queue.poll(timeout, unit)
    fun clear() = queue.clear()
    fun reset() { queue.clear(); dropped = false }
}

/**
 * Thread budget for the Snapdragon 8 Gen 3 (1 prime + 5 performance + 2 efficiency cores), chosen to keep
 * the phone cool during long recordings: on-phone tests showed live text at ~10% and re-checking at ~16% of
 * real time on a single core, so more threads only add heat.
 */
object Tuning {
    var liveThreads = 1
    var refineThreads = 1
    /** Notes written during recording run at background priority on two cores. */
    var summaryThreads = 2
    /** After recording, the remaining summary uses four cores so it is ready quickly. */
    var finishThreads = 4
}

/** Android's thermal status: background AI pauses when the phone is warm. */
object Thermal {
    fun hot(context: android.content.Context): Boolean =
        context.getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
}

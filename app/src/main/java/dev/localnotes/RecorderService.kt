package dev.localnotes

import android.app.Service
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.io.FileOutputStream

class RecorderService : Service() {
    @Volatile private var stopping = false
    @Volatile private var stoppedAt = 0L
    @Volatile private var liveCancel = false
    @Volatile private var liveFinal = false
    @Volatile private var lastAudioAt = 0L
    private var worker: Thread? = null
    private var liveWorker: Thread? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "stop" -> { stoppedAt = System.currentTimeMillis(); stopping = true; if (worker == null) stopSelf(); return START_NOT_STICKY }
            "pause" -> { Store.paused = !Store.paused; return START_NOT_STICKY }
        }
        if (worker != null) return START_NOT_STICKY
        if (!Store.busy.compareAndSet(false, true)) { stopSelf(); return START_NOT_STICKY }
        try {
            Notifications.show(this, "Recording on this phone", 1, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            val session = Store.create(this)
            Store.activeSession = session.name
            Store.recording = true
            Store.paused = false
            Store.liveStatus = ""
            Store.liveDraft = ""
            lastAudioAt = 0L
            worker = Thread { capture(session) }.also { it.start() }
        } catch (error: Exception) {
            Store.status = "Cannot start recording: ${error.message}"
            Store.recording = false; Store.busy.set(false); stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun capture(session: File) {
        val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalNotes:record")
        var recorder: AudioRecord? = null
        var started = false
        var capturedBytes = 0L
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            wake.acquire()
            Store.write(File(session, "state.txt"), "Recording was interrupted. Saved audio can be processed.")
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "Microphone does not support 16 kHz mono recording" }
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission is required" }
            recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 32000))
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            recorder.startRecording()
            val audioRecord = recorder
            val stamp = AudioTimestamp()
            // Wall time of a frame from the audio clock; falls back to read-return time minus buffered audio.
            fun wallAt(frame: Long, fallback: Long): Long {
                if (audioRecord.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) return fallback
                val frameNanos = stamp.nanoTime + (frame - stamp.framePosition) * 1_000_000_000L / 16000
                val wall = System.currentTimeMillis() - (System.nanoTime() - frameNanos) / 1_000_000
                return if (kotlin.math.abs(wall - fallback) < 2000) wall else fallback
            }
            val buffer = ByteArray(8000)
            var framesRead = 0L
            var total = 0L
            var wasPaused = false
            var lastSync = System.currentTimeMillis()
            FileOutputStream(File(session, "audio.pcm")).use { output ->
                while (!stopping) {
                    val count = recorder.read(buffer, 0, buffer.size)
                    val returnedAt = System.currentTimeMillis()
                    check(count >= 0) { "Microphone read error $count" }
                    val firstFrame = framesRead
                    framesRead += count / 2
                    if (!started) {
                        val startAt = wallAt(0, returnedAt - count / 32)
                        RecordingInfo.start(session, startAt, if (startAt == returnedAt - count / 32) "read-time" else "audio-clock")
                        Store.write(File(session, "title.txt"), RecordingInfo.defaultTitle(startAt))
                        started = true
                        startLiveTranscription(session)
                    }
                    if (recorder.activeRecordingConfiguration?.isClientSilenced == true)
                        error("Microphone was interrupted by another app. Saved audio is retained.")
                    if (Store.paused) {
                        if (!wasPaused) { wasPaused = true; RecordingInfo.pause(session, total / 2, wallAt(firstFrame, returnedAt - count / 32)) }
                        Store.status = "Paused • ${Chunking.timestamp(total / 32)} recorded"
                        continue
                    }
                    if (wasPaused) { wasPaused = false; RecordingInfo.resume(session, wallAt(firstFrame, returnedAt - count / 32)) }
                    check(session.usableSpace > 20 * 1024 * 1024) { "Storage is nearly full; recording stopped safely" }
                    output.write(buffer, 0, count)
                    total += count
                    capturedBytes = total
                    lastAudioAt = wallAt(framesRead, returnedAt)
                    Store.status = "Recording • ${Chunking.timestamp(total / 32)}"
                    if (returnedAt - lastSync >= 5000) {
                        output.fd.sync(); lastSync = returnedAt
                    }
                }
                output.fd.sync()
            }
            if (wasPaused) lastAudioAt = 0L // Paused at Finish: the session ends when Finish was pressed.
            Store.write(File(session, "state.txt"), "Recorded • ready to process")
            Store.status = "Recording saved"
        } catch (error: Exception) {
            Store.status = "Recording stopped: ${error.message}"
            runCatching { Store.write(File(session, "state.txt"), Store.status) }
        } finally {
            liveFinal = true // Let live text catch up on the last seconds, then stop it firmly.
            runCatching { liveWorker?.join(8000) }
            liveCancel = true
            liveWorker?.interrupt()
            runCatching { Speech.requestStop() }
            runCatching { liveWorker?.join(3000) }
            if (started) runCatching { RecordingInfo.finish(session,
                lastAudioAt.takeIf { it > 0 } ?: stoppedAt.takeIf { it > 0 } ?: System.currentTimeMillis(), capturedBytes / 2, !stopping) }
            runCatching { recorder?.stop() }; runCatching { recorder?.release() }
            if (wake.isHeld) wake.release()
            Store.recording = false; Store.paused = false
            Store.activeSession = null; Store.busy.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    private fun startLiveTranscription(session: File) {
        val speechModel = File(Store.models(this), "speech.bin")
        if (!speechModel.exists()) { Store.liveStatus = "Add a speech model in Setup to see live text"; return }
        liveWorker = Thread {
            var handle = 0L
            try {
                Store.liveStatus = "Loading speech model"
                Speech.resetStop()
                handle = Speech.open(speechModel.absolutePath)
                val stage = LiveTranscriptionStage(session)
                val engine: (FloatArray) -> String = { samples ->
                    Speech.transcribe(handle, samples, SpeechProgress { }, 4, LiveTranscriptionStage.audioContext(samples.size)).toString(Charsets.UTF_8)
                }
                Store.liveStatus = "Listening"
                while (!liveCancel) {
                    if (liveFinal) { stage.processAvailable(true, engine); break }
                    if (stage.processAvailable(false, engine) > 0) Store.liveDraft = ""
                    if (liveCancel) break
                    val draft = if (Store.paused) null else stage.draft(2.0, engine)
                    if (draft != null) Store.liveDraft = draft
                    else Thread.sleep(250)
                }
            } catch (error: InterruptedException) {
            } catch (error: Throwable) {
                if (!liveCancel) Store.liveStatus = "Live text stopped: ${error.message ?: "model error"}. Audio is still recording."
            } finally {
                Store.liveDraft = ""
                if (handle != 0L) Speech.close(handle)
            }
        }.also { it.name = "LocalNotes-live-transcript"; it.priority = Thread.NORM_PRIORITY - 1; it.start() }
    }
    override fun onDestroy() { stopping = true; liveCancel = true; liveWorker?.interrupt(); runCatching { Speech.requestStop() }; super.onDestroy() }
}

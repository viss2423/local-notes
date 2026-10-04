package dev.localnotes

import android.content.Context
import com.k2fsa.sherpa.onnx.*
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt

const val RATE = 16000

/** Boosts quiet model input only. Saved PCM remains the microphone's untouched recording. */
fun boostQuiet(samples: FloatArray): FloatArray {
    if (samples.isEmpty()) return samples
    val rms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size).toFloat()
    if (rms < 0.0001f || rms >= 0.015f) return samples
    val peak = samples.maxOf { kotlin.math.abs(it) }
    val gain = minOf(16f, 0.045f / rms, 0.98f / peak).coerceAtLeast(1f)
    return if (gain < 1.1f) samples else FloatArray(samples.size) { samples[it] * gain }
}

/** Smoothed live gain avoids sudden jumps between 100 ms microphone reads. */
class LiveInputGain(private val maxGain: Float = 16f) {
    private var gain = 1f
    fun apply(samples: FloatArray, rms: Float): FloatArray {
        if (rms >= 0.006f) gain = 1f
        else if (rms >= 0.00015f) gain += ((0.045f / rms).coerceAtMost(maxGain) - gain) * 0.5f
        if (gain < 1.1f) return samples
        val peak = samples.maxOf { kotlin.math.abs(it) }
        val safe = minOf(gain, if (peak > 0f) 0.98f / peak else gain)
        return FloatArray(samples.size) { samples[it] * safe }
    }
}

private fun transducerOnline(context: Context, spec: ModelSpec) = OnlineTransducerModelConfig().apply {
    val f = { name: String -> Models.file(context, spec, spec.files.first { it.startsWith(name) }) }
    encoder = f("encoder"); decoder = f("decoder"); joiner = f("joiner")
}

/**
 * Streaming recognizer fed straight from the microphone. Words appear as [partial] within
 * a fraction of a second; at each pause the sentence is finished and returned with its audio times.
 */
class LiveRecognizer(context: Context, spec: ModelSpec, threads: Int = 2) : Closeable {
    private val recognizer = OnlineRecognizer(null, OnlineRecognizerConfig().apply {
        featConfig = FeatureConfig(RATE, 80, 0f)
        modelConfig = OnlineModelConfig().apply {
            transducer = transducerOnline(context, spec)
            tokens = Models.file(context, spec, "tokens.txt"); numThreads = threads; provider = "cpu"
        }
        enableEndpoint = true
        // A shorter pause lets a new voice receive its own segment promptly. Keep the 20 s
        // fallback for continuous speech, and let the accuracy pass join same-speaker phrases.
        endpointConfig = EndpointConfig(EndpointRule(false, 2.4f, 0f), EndpointRule(true, 0.45f, 0f), EndpointRule(false, 0f, 20f))
        decodingMethod = "greedy_search"
    })
    private val stream = recognizer.createStream("")
    /** Audio offset (samples) where the current sentence's stream began. */
    private var streamStart = 0L
    private var fed = 0L
    private var quietSamples = 0
    private var heardSignal = false
    // A 4x ceiling beat the former 16x ceiling on a 48-clip quiet-accent sample;
    // VAD keeps its stronger gain for detecting very faint speech.
    private val inputGain = LiveInputGain(4f)
    var partial = ""; private set

    /** Feeds [samples]; returns a finished sentence when a pause ends one. */
    fun accept(samples: FloatArray): Segment? {
        if (samples.isEmpty()) return null
        val rms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size)
        quietSamples = if (rms < 0.0002) quietSamples + samples.size else 0
        if (rms >= 0.00015) heardSignal = true
        stream.acceptWaveform(inputGain.apply(samples, rms.toFloat()), RATE)
        fed += samples.size
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val result = recognizer.getResult(stream)
        partial = result.text.trim()
        // Streaming ASR can carry text across a short pause into the next person's voice.
        // A clear acoustic gap closes that turn even when the model's endpoint lags behind.
        if (!recognizer.isEndpoint(stream) && !(quietSamples >= RATE * 35 / 100 && partial.isNotBlank())) return null
        val segment = finish(result)
        recognizer.reset(stream); streamStart = fed; partial = ""; quietSamples = 0; heardSignal = false
        return segment
    }
    /** Ends the last sentence at the end of recording. */
    fun flush(): Segment? {
        stream.acceptWaveform(FloatArray(RATE), RATE) // Trailing silence lets the model emit the last words.
        stream.inputFinished()
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val segment = finish(recognizer.getResult(stream)); partial = ""
        return segment
    }
    private fun finish(result: OnlineRecognizerResult): Segment? {
        val text = result.text.trim()
        // The transducer can echo the previous turn while consuming only silence after reset.
        if (text.isEmpty() || !heardSignal) return null
        // The segment covers all audio since the previous sentence ended, not just the recognized words:
        // the accuracy pass then also hears words the live model dropped at the start of a sentence.
        val firstWord = streamStart * 1000 / RATE + ((result.timestamps.firstOrNull() ?: 0f) * 1000).toLong()
        val startMs = maxOf(streamStart * 1000 / RATE, firstWord - 2500)
        return Segment(startMs, maxOf(fed * 1000 / RATE, startMs + 300), text, false,
            speechStartMs = maxOf(streamStart * 1000 / RATE, firstWord - 250))
    }
    override fun close() { stream.release(); recognizer.release() }
}

/** Accurate non-streaming model that re-transcribes each finished sentence a few seconds behind live text. */
class Refiner(context: Context, spec: ModelSpec, threads: Int = 2) : Closeable {
    private val recognizer = OfflineRecognizer(null, OfflineRecognizerConfig().apply {
        featConfig = FeatureConfig(RATE, 80, 0f)
        modelConfig = OfflineModelConfig().apply {
            transducer = OfflineTransducerModelConfig().apply {
                val f = { name: String -> Models.file(context, spec, spec.files.first { it.startsWith(name) }) }
                encoder = f("encoder"); decoder = f("decoder"); joiner = f("joiner")
            }
            tokens = Models.file(context, spec, "tokens.txt"); numThreads = threads; provider = "cpu"; modelType = "nemo_transducer"
        }
        decodingMethod = "greedy_search"
    })
    fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(boostQuiet(samples), RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally { stream.release() }
    }
    override fun close() { recognizer.release() }
}

/** Reads [fromMs, toMs) of a session's 16-bit PCM as floats, padded a little on both sides. */
fun readAudio(audio: File, fromMs: Long, toMs: Long, padMs: Long = 200): FloatArray {
    val total = audio.length() / 2
    val from = maxOf(0L, (fromMs - padMs) * RATE / 1000)
    val to = minOf(total, (toMs + padMs) * RATE / 1000)
    if (to <= from) return FloatArray(0)
    val bytes = ByteArray(((to - from) * 2).toInt())
    RandomAccessFile(audio, "r").use { it.seek(from * 2); it.readFully(bytes) }
    return FloatArray(bytes.size / 2) { i -> ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort() / 32768f }
}

/** Splits saved audio into spoken sentences (for recordings made before live transcription existed). */
class SpeechSplitter(vadModel: String) : Closeable {
    private val vad = Vad(null, VadModelConfig().apply {
        sileroVadModelConfig = SileroVadModelConfig().apply {
            model = vadModel; threshold = 0.5f; minSilenceDuration = 0.4f; minSpeechDuration = 0.25f; windowSize = 512; maxSpeechDuration = 20f
        }
        sampleRate = RATE; numThreads = 1; provider = "cpu"
    })
    private val inputGain = LiveInputGain()
    /** Calls [onSpeech] with (startMs, samples) for each detected sentence of [audio]. */
    fun split(audio: File, cancelled: () -> Boolean, progress: (Float) -> Unit, onSpeech: (Long, FloatArray) -> Unit) {
        val total = audio.length() / 2
        val window = 512
        var position = 0L
        audio.inputStream().buffered(1 shl 16).use { input ->
            val bytes = ByteArray(window * 2)
            while (true) {
                if (cancelled()) throw InterruptedException("Stopped")
                val n = input.readNBytes(bytes, 0, bytes.size)
                if (n <= 0) break
                val samples = FloatArray(n / 2) { i -> ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort() / 32768f }
                val rms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size).toFloat()
                vad.acceptWaveform(inputGain.apply(samples, rms)); position += samples.size
                drain(onSpeech)
                if (position % (RATE * 30) < window) progress(position.toFloat() / total)
            }
        }
        vad.flush(); drain(onSpeech)
    }
    private fun drain(onSpeech: (Long, FloatArray) -> Unit) {
        while (!vad.empty()) { val s = vad.front(); onSpeech(s.start.toLong() * 1000 / RATE, s.samples); vad.pop() }
    }
    override fun close() { vad.release() }
}

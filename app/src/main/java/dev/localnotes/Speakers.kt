package dev.localnotes

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.Closeable
import java.io.File
import kotlin.math.sqrt
import org.json.JSONObject

/** User-chosen display names are separate from stable, first-appearance speaker numbers. */
class SpeakerNames(private val session: File) {
    private val file = File(session, "speakers.json")
    private var names = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    fun name(id: Int): String = names.optString(id.toString()).takeIf(String::isNotBlank) ?: "Speaker $id"

    @Synchronized fun rename(id: Int, value: String) {
        require(id > 0)
        names = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        val clean = value.trim().take(60)
        if (clean.isBlank()) names.remove(id.toString()) else names.put(id.toString(), clean)
        Store.write(file, names.toString())
        Live.transcriptVersion.value++
    }
}

/** Small, bounded online clustering. IDs follow the first reliable voice, not accent or word choice. */
class SpeakerClusters(private val threshold: Float = 0.55f) {
    private val centroids = mutableListOf<FloatArray>()
    private val counts = mutableListOf<Int>()
    val size get() = centroids.size

    fun identify(raw: FloatArray, newVoiceThreshold: Float = threshold): Int? {
        if (raw.isEmpty() || raw.any { !it.isFinite() }) return null
        val norm = sqrt(raw.sumOf { (it * it).toDouble() }).toFloat()
        if (norm < 1e-6f) return null
        val vector = FloatArray(raw.size) { raw[it] / norm }
        val scores = centroids.map { center ->
            if (center.size != vector.size) return null
            center.indices.sumOf { (center[it] * vector[it]).toDouble() }.toFloat()
        }
        val best = scores.indices.maxByOrNull { scores[it] }
        if (best == null || scores[best] < newVoiceThreshold) {
            centroids += vector; counts += 1
            return centroids.size
        }
        val count = counts[best]
        val merged = FloatArray(vector.size) { (centroids[best][it] * count + vector[it]) / (count + 1) }
        val mergedNorm = sqrt(merged.sumOf { (it * it).toDouble() }).toFloat()
        centroids[best] = FloatArray(vector.size) { merged[it] / mergedNorm }
        counts[best] = count + 1
        return best + 1
    }
}

/** 26 MB English voice model. Called once per finished utterance; never blocks audio capture. */
class SpeakerEngine(context: Context, spec: ModelSpec) : Closeable {
    private val extractor = SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(
        model = Models.file(context, spec, spec.files.single()), numThreads = 1))
    private val clusters = SpeakerClusters()

    fun identify(samples: FloatArray): Int? {
        if (samples.size < RATE || samples.size > RATE * 25) return null
        val rms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size)
        if (rms < 0.0002) return null
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(boostQuiet(samples), RATE)
            stream.inputFinished()
            if (!extractor.isReady(stream)) return null
        // Short utterances have noisier embeddings: a 1 s VCTK crop over-split at 0.55,
        // while 0.35 kept the four known voices separate in the local fixture.
        return clusters.identify(extractor.compute(stream),
            if (samples.size < RATE * 5 / 2) 0.35f else 0.55f)
        } finally { stream.release() }
    }

    override fun close() = extractor.release()
}

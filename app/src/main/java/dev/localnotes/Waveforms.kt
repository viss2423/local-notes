package dev.localnotes

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/** Loudness shape of a finished recording, drawn as the small waveform on each entry and as the scrubber. */
object Waveforms {
    const val BUCKETS = 160
    private const val CACHE = "waveform.json"
    private const val WINDOW = 6400 // 0.2 s of 16 kHz mono 16-bit audio sampled at each point

    /**
     * Values from 0 to 1, one per slice of the recording, or null when there is no audio yet.
     * Computed from short samples (so a multi-hour recording takes milliseconds) and cached beside the audio;
     * pass [cache] = false for a recording that is still growing.
     */
    fun envelope(session: File, cache: Boolean = true): FloatArray? {
        val audio = File(session, "audio.pcm")
        val length = audio.length() and -2L
        if (length < WINDOW) return null
        val file = File(session, CACHE)
        if (cache) runCatching {
            val saved = JSONObject(file.readText())
            if (saved.getLong("bytes") == length) {
                val values = saved.getJSONArray("v")
                if (values.length() == BUCKETS) return FloatArray(BUCKETS) { values.getDouble(it).toFloat() }
            }
        }
        val values = compute(audio, length)
        if (cache) runCatching {
            Store.write(file, JSONObject().put("bytes", length).put("v", JSONArray(values.map { it.toDouble() })).toString())
        }
        return values
    }

    fun compute(audio: File, length: Long = audio.length() and -2L): FloatArray {
        val loudness = FloatArray(BUCKETS)
        val bytes = ByteArray(WINDOW)
        RandomAccessFile(audio, "r").use { source ->
            for (i in 0 until BUCKETS) {
                val centre = ((i + 0.5) / BUCKETS * length).toLong()
                val start = (centre - WINDOW / 2).coerceIn(0L, length - WINDOW) and -2L
                source.seek(start)
                source.readFully(bytes)
                var sum = 0.0
                for (k in 0 until WINDOW / 2) {
                    val sample = ((bytes[k * 2].toInt() and 255) or (bytes[k * 2 + 1].toInt() shl 8)).toShort() / 32768.0
                    sum += sample * sample
                }
                loudness[i] = sqrt(sum / (WINDOW / 2)).toFloat()
            }
        }
        // Scale to the louder passages (95th percentile) so a quiet recording still draws a readable shape.
        val ceiling = loudness.sorted()[(BUCKETS * 0.95).toInt()].coerceAtLeast(0.002f)
        return FloatArray(BUCKETS) { sqrt((loudness[it] / ceiling).coerceIn(0f, 1f)).coerceAtLeast(0.05f) }
    }

    /** Fewer, taller-of-the-group bars for small displays. */
    fun shrink(values: FloatArray, count: Int): FloatArray {
        if (values.size <= count) return values
        return FloatArray(count) { i ->
            val from = i * values.size / count
            val to = ((i + 1) * values.size / count).coerceAtLeast(from + 1)
            (from until to).maxOf { values[it] }
        }
    }
}

/** Flags dropped during a recording, stored as audio offsets in milliseconds. */
object Bookmarks {
    private fun file(session: File) = File(session, "bookmarks.json")

    fun list(session: File): List<Long> = runCatching {
        val saved = JSONArray(file(session).readText())
        List(saved.length()) { saved.getLong(it) }.sorted()
    }.getOrDefault(emptyList())

    @Synchronized fun add(session: File, atMs: Long): Int {
        val all = (list(session) + atMs.coerceAtLeast(0)).distinct().sorted()
        Store.write(file(session), JSONArray(all).toString())
        Live.notesVersion.value++
        return all.size
    }
}

package dev.localnotes

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.json.JSONObject

/** Durable, incrementally published transcription independent of the summary model. */
object TranscriptionStage {
    fun key(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
    fun cacheKey(audio: File, speech: File) = key("asr-v2:${audio.length()}:${audio.lastModified()}:${speech.length()}:${speech.lastModified()}")
    fun run(audio: File, cache: File, output: File,
            transcribe: (FloatArray, (Int) -> Unit) -> String,
            update: (String) -> Unit, progress: (String) -> Unit) {
        cache.mkdirs(); output.mkdirs()
        val windows = Chunking.windows(audio.length() / 2)
        val wholeStart = System.nanoTime()
        val transcript = StringBuilder()
        var computed = 0
        RandomAccessFile(audio, "r").use { source ->
            windows.forEachIndexed { index, window ->
                update("Transcribing section ${index + 1}/${windows.size}")
                val target = File(cache, "transcript-%05d.txt".format(java.util.Locale.ROOT, index))
                if (!target.exists()) {
                    val raw = ByteArray(((window.endSample - window.startSample) * 2).toInt())
                    source.seek(window.startSample * 2); source.readFully(raw)
                    val samples = FloatArray(maxOf(16000, raw.size / 2)) { i ->
                        if (i * 2 + 1 >= raw.size) 0f else
                            ((raw[i * 2].toInt() and 255) or (raw[i * 2 + 1].toInt() shl 8)).toShort() / 32768f
                    }
                    val start = System.nanoTime()
                    val result = transcribe(samples) { percent ->
                        val overall = ((index + percent.coerceIn(0, 100) / 100.0) / windows.size * 100).toInt()
                        progress("Transcribing · $overall% · section ${index + 1}/${windows.size}")
                    }
                    val text = result.lineSequence().mapNotNull { line ->
                        val fields = line.split('\t', limit = 3)
                        if (fields.size != 3) null else {
                            val from = fields[0].toLong() + window.startSample / 16
                            val to = minOf(fields[1].toLong() + window.startSample / 16, audio.length() / 32)
                            if (to <= window.coreStartSample / 16 || from >= to) null
                            else "[${Chunking.timestamp(from)}–${Chunking.timestamp(to)}] ${fields[2].trim()}"
                        }
                    }.joinToString("\n")
                    Store.write(target, text)
                    Store.write(File(cache, "timing-%05d.json".format(java.util.Locale.ROOT, index)), JSONObject()
                        .put("audioSeconds", (window.endSample - window.startSample) / 16000.0)
                        .put("computeSeconds", (System.nanoTime() - start) / 1e9).toString())
                    computed++
                }
                if (transcript.isNotEmpty()) transcript.append('\n')
                transcript.append(target.readText())
                // Make the completed portion readable before a multi-hour job finishes.
                Store.write(File(output, "transcript.txt"), transcript.toString())
                Store.write(File(output, "transcript-progress.json"), JSONObject()
                    .put("completedSections", index + 1).put("totalSections", windows.size).toString())
            }
        }
        Store.write(File(output, "transcript.complete"), "complete")
        val timings = cache.listFiles()?.filter { it.name.startsWith("timing-") }?.map { JSONObject(it.readText()) } ?: emptyList()
        val totalCompute = timings.sumOf { it.optDouble("computeSeconds", 0.0) }
        Store.write(File(output, "performance.json"), JSONObject()
            .put("audioSeconds", audio.length() / 32000.0)
            .put("transcriptionSeconds", totalCompute)
            .put("thisRunSeconds", (System.nanoTime() - wholeStart) / 1e9)
            .put("computedSections", computed).put("reusedSections", windows.size - computed)
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("pipeline", "asr-v2-60s").toString(2))
    }
}

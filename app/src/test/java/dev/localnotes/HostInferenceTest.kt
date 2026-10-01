package dev.localnotes

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Real CPU inference through the app's chunk/cache/publication pipeline.
 * Uses the version-matched Windows CLI because Android ARM64 JNI cannot execute
 * on this host. This is not a phone speed or Android native-library test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [HostAtomicFileShadow::class])
class HostInferenceTest {
    @Test fun realSpeechProducesReadableTimestampedTextAndReusesItsCache() {
        val root = File("..").canonicalFile
        val engine = File(root, ".tools/bench/whisper/Release/whisper-cli.exe")
        val model = File(root, ".tools/bench/ggml-base.en-q5_1.bin")
        val fixture = File(root, "validation/asr/clean.wav")
        assumeTrue("Optional host inference assets are installed by benchmark_assets.py", engine.exists() && model.exists() && fixture.exists())
        val session = Store.create(RuntimeEnvironment.getApplication())
        val audio = File(session, "audio.pcm").apply { writeBytes(fixture.readBytes().copyOfRange(44, fixture.length().toInt())) }
        val cache = File(session, "asr")
        val output = File(session, "out")
        var calls = 0
        TranscriptionStage.run(audio, cache, output, { samples, progress ->
            calls++
            val wav = File(session, "input-$calls.wav")
            val payload = samples.size * 2
            val buffer = ByteBuffer.allocate(44 + payload).order(ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray()).putInt(36 + payload).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(payload)
            samples.forEach { buffer.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
            wav.writeBytes(buffer.array())
            val prefix = File(session, "result-$calls")
            val process = ProcessBuilder(engine.absolutePath, "-m", model.absolutePath, "-f", wav.absolutePath,
                "-t", "4", "-ng", "-l", "en", "-bs", "1", "-bo", "5", "-mc", "0", "-oj", "-of", prefix.absolutePath)
                .redirectErrorStream(true).redirectOutput(File(session, "engine-$calls.log")).start()
            if (!process.waitFor(120, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Inference exceeded timeout") }
            assertEquals(0, process.exitValue())
            progress(100)
            val segments = JSONObject(File(prefix.path + ".json").readText()).getJSONArray("transcription")
            (0 until segments.length()).joinToString("\n") { i ->
                val segment = segments.getJSONObject(i)
                val offsets = segment.getJSONObject("offsets")
                "${offsets.getLong("from")}\t${offsets.getLong("to")}\t${segment.getString("text").trim()}"
            }
        }, {}, {})
        assertEquals(3, calls)
        val transcript = File(output, "transcript.txt").readText()
        assertTrue(transcript.contains("middle classes", ignoreCase = true))
        assertTrue(transcript.contains("Christmas", ignoreCase = true))
        assertTrue(transcript.contains("[00:02:"))
        assertTrue(File(output, "transcript.complete").exists())
        TranscriptionStage.run(audio, cache, output, { _, _ -> error("A complete transcript must be reused") }, {}, {})
        assertEquals(transcript, File(output, "transcript.txt").readText())
        val report = File(root, "validation/app-pipeline").apply { mkdirs() }
        File(report, "transcript.txt").writeText(transcript)
        File(output, "performance.json").copyTo(File(report, "resume-performance.json"), overwrite = true)
    }
}

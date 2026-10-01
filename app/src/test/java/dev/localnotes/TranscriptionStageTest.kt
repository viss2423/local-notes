package dev.localnotes

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [HostAtomicFileShadow::class])
class TranscriptionStageTest {
    @Test fun interruptedProcessingPublishesPartialTextAndResumesWithoutRepeatingWork() {
        val session = Store.create(RuntimeEnvironment.getApplication())
        val audio = File(session, "audio.pcm")
        RandomAccessFile(audio, "rw").use { it.setLength(125L * 32000) }
        val cache = File(session, "asr-test")
        val output = File(session, "run-test")
        var calls = 0
        try {
            TranscriptionStage.run(audio, cache, output, { _, progress ->
                calls++
                if (calls == 2) throw CancellationException("test interruption")
                progress(50); "0\t1000\tFirst section"
            }, {}, {})
            fail("Expected interruption")
        } catch (_: CancellationException) { }
        assertTrue(File(output, "transcript.txt").readText().contains("First section"))
        assertFalse(File(output, "transcript.complete").exists())
        var resumed = 0
        TranscriptionStage.run(audio, cache, output, { _, progress ->
            resumed++; progress(100); "1000\t2000\tResumed section $resumed"
        }, {}, {})
        assertEquals(2, resumed)
        assertTrue(File(output, "transcript.complete").exists())
        val full = File(output, "transcript.txt").readText()
        assertTrue(full.contains("[00:01:00–00:01:01] Resumed section 1"))
        assertTrue(full.contains("[00:02:00–00:02:01] Resumed section 2"))
        val secondOutput = File(session, "new-summary-model")
        TranscriptionStage.run(audio, cache, secondOutput, { _, _ -> error("Cached speech must not run again") }, {}, {})
        assertEquals(full, File(secondOutput, "transcript.txt").readText())
        assertEquals(125L * 32000, audio.length())
    }
    @Test fun subsecondAudioIsPaddedAndTimestampsStayInsideTheRecording() {
        val session = Store.create(RuntimeEnvironment.getApplication())
        val audio = File(session, "audio.pcm").apply { writeBytes(ByteArray(8000)) }
        val output = File(session, "out")
        TranscriptionStage.run(audio, File(session, "cache"), output, { samples, _ ->
            assertEquals(16000, samples.size)
            "0\t1000\tHello"
        }, {}, {})
        assertTrue(File(output, "transcript.txt").readText().contains("Hello"))
        assertTrue(File(output, "transcript.complete").exists())
    }
}

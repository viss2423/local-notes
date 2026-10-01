package dev.localnotes

import java.io.File
import java.io.FileOutputStream
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [HostAtomicFileShadow::class])
class LiveRecordingTest {
    /** Appends [seconds] of tone (or digital silence) as 16-bit PCM. */
    private fun append(audio: File, seconds: Double, loud: Boolean = true) = FileOutputStream(audio, true).use { out ->
        val count = (seconds * 16000).toInt()
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = if (loud) (sin(i * 2 * Math.PI * 220 / 16000) * 8000).toInt() else 0
            bytes[i * 2] = v.toByte(); bytes[i * 2 + 1] = (v shr 8).toByte()
        }
        out.write(bytes)
    }
    /** Fake engine: one segment spanning the whole clip, numbered per call; records clip lengths. */
    private class Engine : (FloatArray) -> String {
        val lengths = mutableListOf<Int>()
        override fun invoke(samples: FloatArray): String { lengths += samples.size; return "0\t${samples.size / 16}\tSection ${lengths.size}" }
    }

    @Test fun sectionsCommitOnlyWhenCompleteAndFinalFlushesTail() {
        val session = Store.create(RuntimeEnvironment.getApplication())
        val audio = File(session, "audio.pcm")
        val stage = LiveTranscriptionStage(session)
        val engine = Engine()
        append(audio, 9.0)
        assertEquals(0, stage.processAvailable(false, engine))
        assertFalse(File(session, "live-transcript.txt").exists())
        append(audio, 1.0) // 10 s of steady sound: the section runs to its full length.
        assertEquals(1, stage.processAvailable(false, engine))
        assertTrue(File(session, "live-transcript.txt").readText().startsWith("[00:00:00–00:00:10] Section 1"))
        append(audio, 3.0)
        assertEquals(0, stage.processAvailable(false, engine))
        assertEquals(1, stage.processAvailable(true, engine))
        val text = File(session, "live-transcript.txt").readText()
        assertTrue(text, text.contains("[00:00:10–00:00:13] Section 2"))
        // Second clip carries 0.5 s of earlier context but its text is not repeated.
        assertEquals((3.5 * 16000).toInt(), engine.lengths[1])
        assertEquals(0, stage.processAvailable(true, engine))
    }

    @Test fun sectionsEndInAPauseAndSilenceIsNotSentToWhisper() {
        val session = Store.create(RuntimeEnvironment.getApplication())
        val audio = File(session, "audio.pcm")
        append(audio, 7.0); append(audio, 0.5, loud = false); append(audio, 4.0)
        val stage = LiveTranscriptionStage(session)
        val engine = Engine()
        assertEquals(1, stage.processAvailable(false, engine))
        val first = File(session, "live-transcript.txt").readText()
        assertTrue(first, first.startsWith("[00:00:00–00:00:07] Section 1")) // Cut inside the 7.0–7.5 s gap.
        append(audio, 12.0, loud = false)
        val calls = engine.lengths.size
        stage.processAvailable(true, engine)
        assertEquals("Silent sections are skipped", calls + 1, engine.lengths.size)
    }

    @Test fun draftCoversUnfinishedTailAndIsRateLimited() {
        val session = Store.create(RuntimeEnvironment.getApplication())
        val audio = File(session, "audio.pcm")
        val stage = LiveTranscriptionStage(session)
        val engine = Engine()
        append(audio, 0.5)
        assertNull(stage.draft(2.0, engine))
        append(audio, 2.0)
        assertEquals("Section 1", stage.draft(2.0, engine))
        append(audio, 1.0)
        assertNull("Less than 2 s of new audio", stage.draft(2.0, engine))
        append(audio, 1.0)
        assertEquals("Section 2", stage.draft(2.0, engine))
        assertEquals(LiveTranscriptionStage.audioContext(16000 * 10), 564)
        assertEquals(1500, LiveTranscriptionStage.audioContext(16000 * 40))
    }

    @Test fun wallClockStartEndPausesAndAudioDuration() {
        val session = Store.create(RuntimeEnvironment.getApplication())
        val start = 1_780_000_000_123L
        RecordingInfo.start(session, start, "audio-clock")
        RecordingInfo.pause(session, 10L * 16000, start + 10_000)
        RecordingInfo.resume(session, start + 70_000)
        RecordingInfo.finish(session, start + 90_765, 30L * 16000, false)
        val info = requireNotNull(RecordingInfo.read(session))
        assertEquals(start, info.getLong("startEpochMs"))
        assertEquals(start + 90_765, info.getLong("endEpochMs"))
        assertEquals(30L * 16000, info.getLong("capturedSamples"))
        assertEquals("audio-clock", info.getString("clockSource"))
        assertTrue(info.getString("startZoneId").isNotBlank())
        assertEquals(start + 5_000, RecordingInfo.wallClockAt(session, 5_000))
        assertEquals("Audio after the 60 s pause maps to later wall time", start + 75_000, RecordingInfo.wallClockAt(session, 15_000))
        assertTrue(RecordingInfo.defaultTitle(start).startsWith("Recording · "))
    }
}

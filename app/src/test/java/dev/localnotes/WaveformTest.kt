package dev.localnotes

import java.io.File
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [HostAtomicFileShadow::class])
class WaveformTest {
    private val context get() = RuntimeEnvironment.getApplication()

    /** [seconds] of a 220 Hz tone whose loudness rises steadily from silence to full. */
    private fun rampingTone(session: File, seconds: Int) {
        val count = seconds * RATE
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = (sin(i * 2 * Math.PI * 220 / RATE) * 28000 * i / count).toInt()
            bytes[i * 2] = v.toByte(); bytes[i * 2 + 1] = (v shr 8).toByte()
        }
        File(session, "audio.pcm").writeBytes(bytes)
    }

    @Test fun envelopeFollowsLoudnessAndIsCachedUntilTheAudioChanges() {
        val session = Store.create(context)
        assertNull(Waveforms.envelope(session))
        rampingTone(session, 20)
        val values = requireNotNull(Waveforms.envelope(session))
        assertEquals(Waveforms.BUCKETS, values.size)
        assertTrue("quiet start, loud end", values.first() < 0.4f && values.last() > 0.9f)
        assertTrue(values.all { it in 0.05f..1f })
        assertTrue(File(session, "waveform.json").exists())
        // A second read comes from the cache: the file is not recomputed.
        val cached = File(session, "waveform.json").readText()
        assertArrayEquals(values, requireNotNull(Waveforms.envelope(session)), 1e-6f)
        assertEquals(cached, File(session, "waveform.json").readText())
        // Longer audio invalidates it.
        rampingTone(session, 30)
        Waveforms.envelope(session)
        assertNotEquals(cached, File(session, "waveform.json").readText())
    }

    @Test fun growingRecordingsAreNotCachedAndShrinkKeepsPeaks() {
        val session = Store.create(context)
        rampingTone(session, 10)
        assertNotNull(Waveforms.envelope(session, cache = false))
        assertFalse(File(session, "waveform.json").exists())
        val small = Waveforms.shrink(floatArrayOf(0.1f, 0.9f, 0.2f, 0.3f), 2)
        assertArrayEquals(floatArrayOf(0.9f, 0.3f), small, 1e-6f)
    }

    @Test fun flagsAreSortedAndNotDuplicated() {
        val session = Store.create(context)
        assertEquals(emptyList<Long>(), Bookmarks.list(session))
        Bookmarks.add(session, 90_000)
        Bookmarks.add(session, 5_000)
        assertEquals(2, Bookmarks.add(session, 90_000).let { Bookmarks.list(session).size })
        assertEquals(listOf(5_000L, 90_000L), Bookmarks.list(session))
    }
}

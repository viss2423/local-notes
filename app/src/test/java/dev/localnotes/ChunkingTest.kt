package dev.localnotes

import org.junit.Assert.*
import org.junit.Test

class ChunkingTest {
    @Test fun severalHoursHaveNoMissingCoreSamples() {
        val samples = 7L * 3600 * 16000 + 17
        val windows = Chunking.windows(samples)
        assertEquals(421, windows.size)
        assertEquals(0L, windows.first().startSample)
        assertEquals(samples, windows.last().endSample)
        windows.zipWithNext().forEach { (left, right) ->
            assertEquals(left.endSample, right.coreStartSample)
            assertEquals(16000L, left.endSample - right.startSample)
        }
    }
    @Test fun emptyAndExactBoundariesDoNotCreatePhantomChunks() {
        assertTrue(Chunking.windows(0).isEmpty())
        assertEquals(1, Chunking.windows(Chunking.WINDOW).size)
        assertEquals(2, Chunking.windows(Chunking.WINDOW + 1).size)
    }
    @Test fun summarySplittingRetainsEveryCharacterOfLongTranscript() {
        val transcript = (1..10000).joinToString("\n") { "[01:02:03] Item $it: owner Alice; deadline Friday; amount £1,234.56. 😀" }
        val parts = Chunking.textParts(transcript)
        assertEquals(transcript, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 4000 && it.isNotEmpty() })
        assertTrue(parts.none { Character.isHighSurrogate(it.last()) })
    }
    @Test fun unbrokenTextIsBoundedAndPreserved() {
        val text = "x".repeat(12003)
        assertEquals(listOf(4000, 4000, 4000, 3), Chunking.textParts(text).map { it.length })
        assertEquals(text, Chunking.textParts(text).joinToString(""))
    }
    @Test fun timestampsContinuePastOneHour() {
        assertEquals("07:04:09", Chunking.timestamp((7 * 3600L + 4 * 60 + 9) * 1000))
    }
}

package dev.localnotes

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt

/**
 * Rolling preview while recording. Completed sections are committed to live-transcript.txt;
 * the unfinished tail is returned as a draft. The post-recording pass creates the final transcript.
 * Sections end at the quietest moment near the target length so words are rarely cut in half.
 */
class LiveTranscriptionStage(private val session: File) {
    companion object {
        const val RATE = 16000
        private const val MIN_SECTION = 6L * RATE
        private const val MAX_SECTION = 10L * RATE
        private const val CONTEXT = RATE / 2L
        private const val FRAME = RATE / 10
        private const val SILENCE_RMS = 0.001f

        /** Whisper encoder frames for a clip (50 per second) with headroom; never more than the full 30 s. */
        fun audioContext(samples: Int): Int = minOf(1500, ((samples * 50L + RATE - 1) / RATE).toInt() + 64)
    }
    private val audio = File(session, "audio.pcm")
    private val transcript = StringBuilder()
    private var nextSample = 0L
    private var draftedUpTo = 0L

    init {
        File(session, "live-transcript.txt").takeIf { it.exists() }?.readText()?.let { transcript.append(it) }
    }

    /** Commits every completed section; final=true also commits the remaining short tail. */
    fun processAvailable(final: Boolean, transcribe: (FloatArray) -> String): Int {
        var sections = 0
        while (true) {
            val available = audio.length() / 2
            val end = when {
                final -> minOf(available, nextSample + MAX_SECTION)
                available < nextSample + MAX_SECTION -> break
                else -> quietestBoundary(nextSample + MIN_SECTION, nextSample + MAX_SECTION)
            }
            if (end <= nextSample) break
            val text = transcribeRange(maxOf(0, nextSample - CONTEXT), nextSample, end, transcribe)
            if (text.isNotBlank()) {
                if (transcript.isNotEmpty()) transcript.append('\n')
                transcript.append(text)
                Store.write(File(session, "live-transcript.txt"), transcript.toString())
            }
            nextSample = end
            sections++
        }
        return sections
    }

    /** Transcribes the unfinished tail once at least [stepSeconds] of new audio exists; null when not due. */
    fun draft(stepSeconds: Double, transcribe: (FloatArray) -> String): String? {
        val available = audio.length() / 2
        if (available - nextSample < RATE || available - maxOf(draftedUpTo, nextSample) < (stepSeconds * RATE).toLong()) return null
        draftedUpTo = available
        return transcribeRange(maxOf(0, nextSample - CONTEXT), nextSample, available, transcribe)
            .lineSequence().joinToString(" ") { it.substringAfter("] ") }.trim()
    }

    private fun read(from: Long, to: Long): FloatArray {
        val bytes = ByteArray(((to - from) * 2).toInt())
        RandomAccessFile(audio, "r").use { source -> source.seek(from * 2); source.readFully(bytes) }
        return FloatArray(bytes.size / 2) { i ->
            ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort() / 32768f
        }
    }

    private fun rms(samples: FloatArray, from: Int, to: Int): Float {
        var sum = 0.0
        for (i in from until to) sum += samples[i] * samples[i]
        return if (to > from) sqrt(sum / (to - from)).toFloat() else 0f
    }

    private fun quietestBoundary(from: Long, to: Long): Long {
        val samples = read(from, to)
        var best = samples.size
        var bestLevel = rms(samples, samples.size - FRAME, samples.size)
        var start = samples.size - 2 * FRAME
        while (start >= 0) { // Only a clearly quieter frame moves the cut, so steady sound uses the full section.
            val level = rms(samples, start, start + FRAME)
            if (level < bestLevel * 0.8f) { bestLevel = level; best = start + FRAME / 2 }
            start -= FRAME
        }
        return from + best
    }

    private fun transcribeRange(from: Long, keepFrom: Long, end: Long, transcribe: (FloatArray) -> String): String {
        val raw = read(from, end)
        if (rms(raw, (keepFrom - from).toInt(), raw.size) < SILENCE_RMS) return "" // Whisper invents words in silence.
        val samples = if (raw.size >= RATE) raw else raw.copyOf(RATE)
        return transcribe(samples).lineSequence().mapNotNull { line ->
            val fields = line.split('\t', limit = 3)
            val start = fields.getOrNull(0)?.toLongOrNull()?.plus(from * 1000 / RATE) ?: return@mapNotNull null
            val finish = fields.getOrNull(1)?.toLongOrNull()?.plus(from * 1000 / RATE) ?: return@mapNotNull null
            val keepMs = keepFrom * 1000 / RATE
            val endMs = end * 1000 / RATE
            // A segment belongs to the section containing its midpoint, so overlap context is not repeated.
            if (fields[2].isBlank() || (start + finish) / 2 < keepMs || (start + finish) / 2 >= endMs) null
            else "[${Chunking.timestamp(maxOf(start, keepMs))}–${Chunking.timestamp(minOf(finish, endMs))}] ${fields[2].trim()}"
        }.joinToString("\n")
    }
}

package dev.localnotes

data class AudioWindow(val startSample: Long, val endSample: Long, val coreStartSample: Long)

object Chunking {
    const val SAMPLE_RATE = 16000
    const val WINDOW = 60L * SAMPLE_RATE
    fun windows(sampleCount: Long): List<AudioWindow> {
        require(sampleCount >= 0)
        val result = mutableListOf<AudioWindow>()
        var start = 0L
        while (start < sampleCount) {
            result += AudioWindow(maxOf(0, start - SAMPLE_RATE), minOf(sampleCount, start + WINDOW), start)
            start += WINDOW
        }
        return result
    }
    // Keep every character; split at line/word boundaries where possible.
    fun textParts(text: String, limit: Int = 4000): List<String> {
        require(limit > 0)
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + limit, text.length)
            if (end < text.length) {
                val boundary = text.lastIndexOf('\n', end - 1)
                if (boundary > start + limit / 2) end = boundary + 1
                else {
                    val space = text.lastIndexOf(' ', end - 1)
                    if (space > start + limit / 2) end = space + 1
                }
                if (end > start && Character.isHighSurrogate(text[end - 1])) end--
            }
            check(end > start)
            result += text.substring(start, end)
            start = end
        }
        return result
    }
    fun timestamp(ms: Long): String {
        val seconds = ms / 1000
        return "%02d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
    }
}

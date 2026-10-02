package dev.localnotes

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One finished sentence. Times are offsets into the recorded audio (pauses excluded). */
data class Segment(val startMs: Long, val endMs: Long, val text: String, val refined: Boolean,
    val speakerId: Int? = null, val speechStartMs: Long? = null)

/**
 * The transcript of one session, kept in memory and saved atomically to segments.json.
 * Live text appends segments; the accuracy pass replaces their text in place.
 */
class Transcript(private val session: File) {
    private val file = File(session, "segments.json")
    private val items = mutableListOf<Segment>()
    init { load() }

    @Synchronized private fun load() {
        items.clear()
        val raw = runCatching { file.readText() }.getOrNull() ?: return
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return
        for (i in 0 until array.length()) array.getJSONObject(i).let {
            items += Segment(it.getLong("s"), it.getLong("e"), it.getString("t"), it.optBoolean("r"),
                it.optInt("speaker", 0).takeIf { id -> id > 0 })
        }
    }
    @Synchronized private fun save() {
        val array = JSONArray()
        items.forEach { segment ->
            array.put(JSONObject().put("s", segment.startMs).put("e", segment.endMs).put("t", segment.text)
                .put("r", segment.refined).apply { segment.speakerId?.let { put("speaker", it) } })
        }
        Store.write(file, array.toString())
        Live.transcriptVersion.value++
    }
    @Synchronized fun add(segment: Segment): Int { items += segment; save(); return items.size - 1 }
    @Synchronized fun refine(index: Int, text: String) {
        val old = items.getOrNull(index) ?: return
        items[index] = old.copy(text = text.ifBlank { old.text }, refined = true); save()
    }
    /** Correct a finished transcript; the audio is kept unchanged. */
    @Synchronized fun editText(startMs: Long, text: String) {
        val index = items.indexOfFirst { it.startMs == startMs && it.text.isNotBlank() }
        require(index >= 0 && text.isNotBlank())
        items[index] = items[index].copy(text = text.trim(), refined = true)
        Store.write(File(session, "transcript-edited"), "")
        save()
    }
    /**
     * Replaces sentences [first..last] with one re-checked paragraph: the first keeps the whole text and span,
     * the rest become empty (kept so indices stay stable; empty segments are never shown or exported).
     */
    @Synchronized fun refineParagraph(first: Int, last: Int, text: String) {
        if (first !in items.indices || last !in items.indices || text.isBlank()) { for (i in first..last) items.getOrNull(i)?.let { items[i] = it.copy(refined = true) }; save(); return }
        require((first..last).map { items[it].speakerId }.distinct().size == 1) { "A paragraph cannot cross speakers" }
        items[first] = items[first].copy(endMs = items[last].endMs, text = text, refined = true)
        for (i in first + 1..last) items[i] = items[i].copy(text = "", refined = true)
        save()
    }
    @Synchronized fun replaceAll(segments: List<Segment>) { items.clear(); items += segments; save() }
    @Synchronized fun all(): List<Segment> = items.toList()
    @Synchronized fun size() = items.size
    fun exists() = file.exists()

    /** Sentences with text (paragraph merges leave empty placeholders). */
    fun visible(): List<Segment> = all().filter { it.text.isNotBlank() }
    /** Plain text with a time-of-day stamp per sentence, as exported. */
    fun text(times: Boolean = true): String {
        val speakerNames = SpeakerNames(session)
        return visible().joinToString("\n") { segment ->
        val line = "${segment.speakerId?.let { speakerNames.name(it) + ": " } ?: ""}${segment.text}"
        if (!times) line else {
            val clock = RecordingInfo.wallClockAt(session, segment.startMs)?.let { RecordingInfo.format(it, "HH:mm:ss") }
                ?: Chunking.timestamp(segment.startMs)
            "[$clock] $line"
        }
        }
    }
    fun words() = all().sumOf { it.text.split(' ').count(String::isNotBlank) }
}

/** State shared between the services and the screen. */
object Live {
    val recording = MutableStateFlow(false)
    val paused = MutableStateFlow(false)
    val session = MutableStateFlow<String?>(null)
    /** Audio captured so far, in milliseconds (pauses excluded). */
    val elapsedMs = MutableStateFlow(0L)
    /** Microphone level 0..1, for the waveform. */
    val level = MutableStateFlow(0f)
    /** Words of the sentence being spoken, not yet final. */
    val partial = MutableStateFlow("")
    val transcriptVersion = MutableStateFlow(0)
    /** Summary text as the model writes it, and what it is working on. */
    val summaryDraft = MutableStateFlow("")
    val summaryStatus = MutableStateFlow("")
    val notesVersion = MutableStateFlow(0)
    /** Short line about background work (finishing, downloading, errors). */
    val status = MutableStateFlow("")
    /** True while finishing a recording or processing an imported one. */
    val working = MutableStateFlow(false)
    val downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
}

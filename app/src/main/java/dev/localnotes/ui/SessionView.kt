package dev.localnotes.ui

import dev.localnotes.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Read-only snapshot of a recording for display. Older (v0.4) recordings fall back to their saved files. */
class SessionView(val dir: File) {
    val id: String = dir.name
    val title: String = File(dir, "title.txt").takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
        ?: RecordingInfo.defaultTitle(RecordingInfo.startEpoch(dir))
    val startMs = RecordingInfo.startEpoch(dir)
    val durationMs = File(dir, "audio.pcm").length() / 32
    val transcript = Transcript(dir)
    private val legacyRun: File? = File(dir, "current-run.txt").takeIf { it.exists() }?.readText()
        ?.takeIf { it.matches(Regex("run-[a-f0-9]{20}")) }?.let { File(dir, it) }

    val summary: String? = File(dir, "summary.md").takeIf { it.exists() }?.readText()
        ?: legacyRun?.let { File(it, "concise.md") }?.takeIf { it.exists() }?.readText()
    val notes: String? = NotesBuilder(dir, transcript).detailed().takeIf { it.isNotBlank() }
        ?: legacyRun?.let { File(it, "detailed.md") }?.takeIf { it.exists() }?.readText()
    /** Transcript from v0.4 (final pass, else live preview) when there are no segments. */
    val legacyTranscript: String? = if (transcript.size() > 0) null else
        legacyRun?.let { File(it, "transcript.txt") }?.takeIf { it.exists() }?.readText()
            ?: File(dir, "live-transcript.txt").takeIf { it.exists() }?.readText()
    val error: String? = File(dir, "error.txt").takeIf { it.exists() }?.readText()
    val pauses: Int = RecordingInfo.read(dir)?.optJSONArray("pauses")?.length() ?: 0

    fun range() = RecordingInfo.displayRange(dir)
    fun date() = RecordingInfo.displayDate(dir)
    fun day(): String {
        val date = Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        return when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> RecordingInfo.format(startMs, "EEEE d MMMM yyyy")
        }
    }
    val pending: Boolean = ProcessingService.pending(dir)
    /** Short state shown next to a recording; empty once everything is done. */
    fun status(): String = when {
        id == Live.session.value && Live.recording.value -> "Recording"
        id == Live.session.value && Live.working.value -> "Writing summary"
        pending -> "Summary queued"
        summary == null && transcript.size() == 0 && legacyTranscript == null -> "Audio only"
        else -> ""
    }
}

fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
fun spokenDuration(ms: Long): String {
    val m = ms / 60000
    return when { m >= 60 -> "${m / 60} h ${m % 60} min"; m >= 1 -> "$m min"; else -> "${ms / 1000} s" }
}

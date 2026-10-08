package dev.localnotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import dev.localnotes.Segment
import dev.localnotes.Waveforms
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The loudness shape of a saved recording, computed off the main thread and cached beside the audio. */
@Composable
fun rememberEnvelope(session: File, live: Boolean = false): FloatArray? {
    val audioSize = File(session, "audio.pcm").length()
    val state = produceState<FloatArray?>(null, session, if (live) 0L else audioSize) {
        value = withContext(Dispatchers.IO) { runCatching { Waveforms.envelope(session, cache = !live) }.getOrNull() }
    }
    return state.value
}

/** Small waveform on each list entry: thin vertical bars, like the trace on a recorder's display. */
@Composable
fun MiniWave(envelope: FloatArray?, modifier: Modifier = Modifier, color: Color = P.ink.copy(alpha = 0.55f)) {
    val bars = remember(envelope) { envelope?.let { Waveforms.shrink(it, 56) } }
    val idle = P.line
    Canvas(modifier.fillMaxWidth().height(22.dp)) {
        val count = bars?.size ?: 56
        val step = size.width / count
        for (i in 0 until count) {
            val level = bars?.get(i) ?: 0.12f
            val h = (level * size.height).coerceAtLeast(1.5.dp.toPx())
            val x = i * step + step / 2
            drawLine(if (bars == null) idle else color, Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2), 1.6.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** Colour strip showing who spoke when, across the whole recording. */
@Composable
fun SpeakerLane(segments: List<Segment>, totalMs: Long, modifier: Modifier = Modifier, onDark: Boolean = false, height: androidx.compose.ui.unit.Dp = 6.dp) {
    val voices = segments.filter { it.speakerId != null }
    val colors = voices.map { voiceColor(it.speakerId!!, onDark) }
    val rest = if (onDark) P.deckLine else P.line
    Canvas(modifier.fillMaxWidth().height(height)) {
        drawRect(rest, Offset.Zero, Size(size.width, size.height))
        if (totalMs <= 0) return@Canvas
        voices.forEachIndexed { i, s ->
            val x = (s.startMs.toFloat() / totalMs).coerceIn(0f, 1f) * size.width
            val w = ((s.endMs - s.startMs).toFloat() / totalMs * size.width).coerceAtLeast(1.5.dp.toPx())
            drawRect(colors[i], Offset(x, 0f), Size(w.coerceAtMost(size.width - x), size.height))
        }
    }
}

/**
 * Playback scrubber: the whole recording as a waveform, the part already played in ink, the speaker lane
 * underneath and any flags you dropped above. Drag or tap to jump; the red line is the playhead.
 */
@Composable
fun Scrubber(envelope: FloatArray?, positionMs: Long, totalMs: Long, segments: List<Segment>, flags: List<Long>,
    onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (totalMs > 0) (positionMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
    val ink = P.ink; val faded = P.muted.copy(alpha = 0.45f); val rec = P.rec; val markColor = P.mark
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            if (totalMs <= 0) return@Canvas
            flags.forEach { t ->
                val x = (t.toFloat() / totalMs).coerceIn(0f, 1f) * size.width
                drawLine(markColor, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                drawCircle(markColor, 3.5.dp.toPx(), Offset(x, 3.5.dp.toPx()))
            }
        }
        Canvas(Modifier.fillMaxWidth().height(64.dp)
            .semantics {
                contentDescription = "Playback position"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { value -> onSeek((value.coerceIn(0f, 1f) * totalMs).toLong()); true }
            }
            .pointerInput(totalMs) {
                if (totalMs <= 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                    dragging = at(down.position.x)
                    drag(down.id) { change -> dragging = at(change.position.x); change.consume() }
                    onSeek(((dragging ?: 0f) * totalMs).toLong())
                    dragging = null
                }
            }) {
            val bars = envelope ?: FloatArray(Waveforms.BUCKETS) { 0.08f }
            val step = size.width / bars.size
            val playedUntil = fraction * size.width
            bars.forEachIndexed { i, level ->
                val h = (level * size.height).coerceAtLeast(2.dp.toPx())
                val x = i * step + step / 2
                drawLine(if (x <= playedUntil) ink else faded, Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2),
                    (step * 0.62f).coerceAtLeast(1.2.dp.toPx()), StrokeCap.Round)
            }
            drawLine(rec, Offset(playedUntil, 0f), Offset(playedUntil, size.height), 2.dp.toPx())
            drawCircle(rec, 4.5.dp.toPx(), Offset(playedUntil, 0f))
        }
        Spacer(Modifier.height(5.dp))
        SpeakerLane(segments, totalMs)
    }
}

/** A flag chip on the detail page: tap to hear from that moment. */
@Composable
fun FlagLink(atMs: Long, onClick: () -> Unit) {
    Row(
        Modifier.clickable(onClick = onClick).heightIn(min = 40.dp).padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AppIcons.Flag, "Flag", Modifier.size(15.dp), tint = P.ink)
        Spacer(Modifier.width(4.dp))
        Text(duration(atMs), style = Type.mono, color = P.ink)
    }
}


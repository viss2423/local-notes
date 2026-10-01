package dev.localnotes

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wall-clock instants are stored as epoch milliseconds of the first and last captured samples
 * (from the audio clock when Android provides it); audio duration is sample-derived.
 * Pauses are kept so any audio offset maps back to the time of day it was spoken.
 */
object RecordingInfo {
    private fun file(session: File) = File(session, "recording-info.json")

    fun start(session: File, epochMs: Long, clock: String) {
        Store.write(file(session), JSONObject().put("startEpochMs", epochMs)
            .put("startZoneId", ZoneId.systemDefault().id).put("clockSource", clock)
            .put("sampleRate", 16000).put("capturedSamples", 0).put("pauses", JSONArray()).toString(2))
    }

    /** Records a pause beginning after [atSample] captured samples; resumedEpochMs is added on resume. */
    fun pause(session: File, atSample: Long, pausedEpochMs: Long) {
        val info = read(session) ?: return
        val pauses = info.optJSONArray("pauses") ?: JSONArray()
        pauses.put(JSONObject().put("atSample", atSample).put("pausedEpochMs", pausedEpochMs))
        Store.write(file(session), info.put("pauses", pauses).put("capturedSamples", atSample).toString(2))
    }

    fun resume(session: File, resumedEpochMs: Long) {
        val info = read(session) ?: return
        val last = info.optJSONArray("pauses")?.let { it.optJSONObject(it.length() - 1) } ?: return
        if (!last.has("resumedEpochMs")) Store.write(file(session), info.apply { last.put("resumedEpochMs", resumedEpochMs) }.toString(2))
    }

    fun finish(session: File, endEpochMs: Long, capturedSamples: Long, interrupted: Boolean) {
        val info = read(session) ?: JSONObject()
        Store.write(file(session), info.put("endEpochMs", endEpochMs)
            .put("endZoneId", ZoneId.systemDefault().id)
            .put("capturedSamples", capturedSamples).put("interrupted", interrupted).toString(2))
    }

    fun read(session: File): JSONObject? = runCatching {
        file(session).takeIf { it.exists() }?.let { JSONObject(it.readText()) }
    }.getOrNull()

    fun startEpoch(session: File): Long = read(session)?.optLong("startEpochMs", 0L)?.takeIf { it > 0 }
        ?: File(session, "created.txt").lastModified().takeIf { it > 0 }
        ?: session.lastModified()

    fun endEpoch(session: File): Long? = read(session)?.optLong("endEpochMs", 0L)?.takeIf { it > 0 }

    /** Time of day at which audio offset [audioMs] was captured, accounting for pauses. */
    fun wallClockAt(session: File, audioMs: Long): Long? {
        val info = read(session) ?: return null
        var wall = info.optLong("startEpochMs", 0L).takeIf { it > 0 }?.plus(audioMs) ?: return null
        val pauses = info.optJSONArray("pauses") ?: return wall
        for (i in 0 until pauses.length()) {
            val pause = pauses.getJSONObject(i)
            if (pause.optLong("atSample") * 1000 / 16000 > audioMs) break
            val resumed = pause.optLong("resumedEpochMs", 0L)
            if (resumed > 0) wall += resumed - pause.optLong("pausedEpochMs", resumed)
        }
        return wall
    }

    private fun zone(session: File, key: String): ZoneId =
        read(session)?.optString(key)?.takeIf(String::isNotBlank)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    fun format(epochMs: Long, pattern: String, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun displayDate(session: File): String = format(startEpoch(session), "EEE d MMM yyyy", zone(session, "startZoneId"))

    fun displayStart(session: File): String = read(session)?.optLong("startEpochMs", 0L)?.takeIf { it > 0 }
        ?.let { format(it, "d MMM yyyy, HH:mm:ss z", zone(session, "startZoneId")) }
        ?: File(session, "created.txt").takeIf { it.exists() }?.readText() ?: "Start unknown"

    fun displayEnd(session: File): String = endEpoch(session)
        ?.let { format(it, "d MMM yyyy, HH:mm:ss z", zone(session, "endZoneId")) }
        ?: "End time unavailable"

    /** Compact "10:41:07 – 11:52:30" range, or just the start while still recording. */
    fun displayRange(session: File): String {
        val start = format(startEpoch(session), "HH:mm:ss", zone(session, "startZoneId"))
        return endEpoch(session)?.let { "$start – ${format(it, "HH:mm:ss", zone(session, "endZoneId"))}" } ?: start
    }

    fun defaultTitle(epochMs: Long): String = "Recording · ${format(epochMs, "d MMM yyyy, HH:mm")}"

    fun capturedDuration(session: File): String = Chunking.timestamp(File(session, "audio.pcm").length() / 32)
}

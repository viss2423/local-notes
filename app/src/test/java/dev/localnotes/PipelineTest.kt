package dev.localnotes

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [HostAtomicFileShadow::class])
class PipelineTest {
    private val context get() = RuntimeEnvironment.getApplication()

    /** Fake summary model: echoes how many transcript lines it saw, streaming the reply in two pieces. */
    private class FakeModel : TextModel {
        val prompts = mutableListOf<String>()
        override fun write(system: String, user: String, maxTokens: Int, start: String, onText: (String) -> Unit): String {
            prompts += user
            val reply = if (user.contains("TRANSCRIPT:")) "- ${user.substringAfter("TRANSCRIPT:\n").lines().size} lines" else "## Summary\nOverview of the notes"
            onText(reply.take(3)); onText(reply)
            return reply
        }
    }
    private fun words(n: Int, tag: String) = (1..n).joinToString(" ") { "$tag$it" }

    @Test fun transcriptPersistsAndRefinesInPlace() {
        val session = Store.create(context)
        RecordingInfo.start(session, 1_780_000_000_000L, "audio-clock")
        val t = Transcript(session)
        assertEquals(0, t.add(Segment(0, 2000, "helo world", false)))
        assertEquals(1, t.add(Segment(2500, 4000, "second", false)))
        t.refine(0, "Hello, world.")
        t.refine(1, "   ") // A blank accurate result keeps the live text.
        val reloaded = Transcript(session).all()
        assertEquals(listOf("Hello, world.", "second"), reloaded.map { it.text })
        assertEquals(listOf(true, true), reloaded.map { it.refined })
        assertTrue(Transcript(session).text().startsWith("[" + RecordingInfo.format(1_780_000_000_000L, "HH:mm:ss") + "] Hello, world."))
    }

    @Test fun speakerIdsNamesAndParagraphsSurviveReload() {
        val session = Store.create(context)
        val transcript = Transcript(session)
        transcript.add(Segment(0, 4000, "first voice", false, 1))
        transcript.add(Segment(5000, 9000, "same voice", false, 1))
        transcript.add(Segment(10000, 14000, "different voice", false, 2))
        transcript.refineParagraph(0, 1, "Corrected first speaker.")
        SpeakerNames(session).rename(2, "Priya")
        val reloaded = Transcript(session)
        assertEquals(listOf(1, 1, 2), reloaded.all().map { it.speakerId })
        assertEquals("Priya", SpeakerNames(session).name(2))
        assertTrue(reloaded.text().contains("Priya: different voice"))
        assertThrows(IllegalArgumentException::class.java) {
            reloaded.refineParagraph(0, 2, "This must never merge two speakers")
        }
        reloaded.editText(10_000, "Corrected accented words.")
        assertTrue(File(session, "transcript-edited").exists())
        assertEquals("Priya: Corrected accented words.", Transcript(session).text(times = false).lines().last())
    }

    @Test fun speakerClusteringKeepsFirstAppearanceNumbers() {
        val speakers = SpeakerClusters()
        assertEquals(1, speakers.identify(floatArrayOf(1f, 0f, 0f)))
        assertEquals(2, speakers.identify(floatArrayOf(0f, 1f, 0f)))
        assertEquals(1, speakers.identify(floatArrayOf(.9f, .1f, 0f)))
        assertEquals(2, speakers.size)
        assertNull(speakers.identify(floatArrayOf(0f, 0f, 0f)))
    }

    @Test fun quietSpeechBoostLeavesNormalAudioAndSilenceAlone() {
        val quiet = FloatArray(1600) { 0.002f }
        val normal = FloatArray(1600) { 0.05f }
        assertTrue(boostQuiet(quiet)[0] > quiet[0] * 10)
        assertTrue(boostQuiet(normal) === normal)
        assertTrue(boostQuiet(FloatArray(1600)).all { it == 0f })
        val live = LiveInputGain()
        assertTrue(live.apply(quiet, 0.002f)[0] > quiet[0] * 2)
        assertEquals(normal[0], live.apply(normal, 0.05f)[0], 0.0001f)
        val veryQuiet = FloatArray(1600) { 0.0003f }
        assertTrue(live.apply(veryQuiet, 0.0003f)[0] > veryQuiet[0] * 2)
    }

    @Test fun stalledLiveRecognizerCannotBlockOrGrowTheRecordingQueue() {
        val backlog = CaptureBacklog(2)
        assertTrue(backlog.offer(floatArrayOf(1f)))
        assertTrue(backlog.offer(floatArrayOf(2f)))
        assertFalse(backlog.offer(floatArrayOf(3f)))
        assertTrue(backlog.dropped)
        assertEquals(0, backlog.size)
        assertNull(backlog.poll(0, TimeUnit.MILLISECONDS))
        assertFalse(backlog.offer(floatArrayOf(4f)))
        backlog.reset()
        assertFalse(backlog.dropped)
        assertTrue(backlog.offer(floatArrayOf(5f)))
    }

    @Test fun paragraphRecheckMergesSentencesAndKeepsIndicesStable() {
        val session = Store.create(context)
        val t = Transcript(session)
        repeat(4) { t.add(Segment(it * 5000L, it * 5000L + 4500, "live $it", false)) }
        t.refineParagraph(0, 2, "Accurate paragraph.")
        val all = Transcript(session).all()
        assertEquals(4, all.size) // Later indices (still queued for the re-check) are unchanged.
        assertEquals(Segment(0, 14_500, "Accurate paragraph.", true), all[0])
        assertEquals(listOf("Accurate paragraph.", "live 3"), Transcript(session).visible().map { it.text })
        t.refineParagraph(3, 3, "  ") // A blank re-check keeps the live text.
        assertEquals("live 3", Transcript(session).visible().last().text)
        assertFalse(Transcript(session).text().contains("\n\n"))
    }

    @Test fun notesAreWrittenPerSectionWhileRecordingAndFinishedAtStop() {
        val session = Store.create(context)
        val t = Transcript(session)
        val model = FakeModel()
        val notes = NotesBuilder(session, t)
        repeat(5) { t.add(Segment(it * 10_000L, it * 10_000L + 9000, words(110, "w$it"), refined = it < 4)) }
        // 440 refined words: not yet a full section (500).
        assertFalse(notes.step(model, final = false, needRefined = true))
        t.refine(4, words(110, "r"))
        assertTrue(notes.step(model, final = false, needRefined = true)) // 550 words ≥ 500: section 1 = segments 0..4
        assertEquals(5, notes.summarizedSegments())
        t.add(Segment(60_000, 65_000, words(30, "tail"), false))
        assertFalse(notes.step(model, final = false, needRefined = true))
        assertTrue(notes.step(model, final = true, needRefined = false)) // Stop: the short tail is summarized too.
        assertFalse(notes.step(model, final = true, needRefined = false))
        assertTrue(notes.overviewDue())
        notes.overview(model)
        assertTrue(File(session, "summary.md").readText().contains("Overview of the notes"))
        assertEquals(2, NotesBuilder(session, t).sectionCount()) // Survives a restart.
        assertTrue(NotesBuilder(session, t).detailed().contains("###"))
        assertEquals(3, model.prompts.size)
    }

    @Test fun wallClockAccountsForPauses() {
        val session = Store.create(context)
        val start = 1_780_000_000_123L
        RecordingInfo.start(session, start, "audio-clock")
        RecordingInfo.pause(session, 10L * RATE, start + 10_000)
        RecordingInfo.resume(session, start + 70_000)
        RecordingInfo.finish(session, start + 90_765, 30L * RATE, false)
        assertEquals(start + 5_000, RecordingInfo.wallClockAt(session, 5_000))
        assertEquals(start + 75_000, RecordingInfo.wallClockAt(session, 15_000))
        assertEquals(start + 90_765, RecordingInfo.endEpoch(session))
        assertEquals("audio-clock", RecordingInfo.read(session)!!.getString("clockSource"))
    }

    @Test fun readAudioReturnsPaddedRange() {
        val session = Store.create(context)
        val pcm = File(session, "audio.pcm")
        pcm.writeBytes(ByteArray(RATE * 2 * 3) { if (it % 2 == 1) 0x40 else 0 }) // 3 s of a constant 0.5 signal.
        val clip = readAudio(pcm, 1000, 2000, padMs = 200)
        assertEquals(RATE * 14 / 10, clip.size)
        assertEquals(0.5f, clip[100], 0.001f)
        assertEquals(0, readAudio(pcm, 5000, 6000).size)
    }

    @Test fun modelDownloadResumesAndUnpacksArchives() {
        // A fake model archive with a top folder and a test folder that must be skipped.
        val archive = ByteArrayOutputStream().also { bytes ->
            TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
                val noise = ByteArray(300_000).also { java.util.Random(1).nextBytes(it) } // Incompressible, so the archive stays large.
                for ((name, data) in listOf("m/encoder.onnx" to noise, "m/decoder.onnx" to "D".toByteArray(), "m/joiner.onnx" to "J".toByteArray(), "m/tokens.txt" to "a 0".toByteArray(), "m/test_wavs/0.wav" to "x".toByteArray(), "m/../../escape" to "unsafe".toByteArray())) {
                    tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() }); tar.write(data); tar.closeArchiveEntry()
                }
            }
        }.toByteArray()
        val ranges = mutableListOf<String?>()
        // Minimal HTTP server: /start.tar.bz2 redirects (as GitHub does); /model.tar.bz2 honours Range.
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1]
                    val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                    val range = headers.firstOrNull { it.startsWith("Range:", true) }?.substringAfter(':')?.trim()
                    val out = s.getOutputStream()
                    if (path == "/start.tar.bz2") {
                        out.write("HTTP/1.1 302 Found\r\nLocation: /model.tar.bz2\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    } else {
                        ranges += range
                        val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                        out.write("HTTP/1.1 ${if (range != null) "206 Partial Content" else "200 OK"}\r\nContent-Length: ${archive.size - from}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(archive, from, archive.size - from)
                    }
                    out.flush()
                }
            }
        }
        try {
            // Starts at a redirect, as GitHub release downloads do.
            val sha = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
            val spec = ModelSpec("test-live", Role.LIVE, "Test", "", "http://127.0.0.1:${server.localPort}/start.tar.bz2",
                archive.size.toLong(), listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"), sha256 = sha)
            // Simulate an interrupted earlier attempt: the first 1000 bytes are already on disk.
            File(Models.root(context), "test-live.part").writeBytes(archive.copyOf(1000))
            var last = 0L
            Models.download(context, spec, { false }) { done, _ -> last = done }
            assertEquals("bytes=1000-", ranges.single())
            assertEquals(archive.size.toLong(), last)
            assertTrue(Models.installed(context, spec))
            assertEquals(300_000L, File(Models.file(context, spec, "encoder.onnx")).length())
            assertFalse(File(Models.folder(context, spec), "test_wavs").exists())
            assertFalse(File(context.filesDir, "escape").exists())
            assertFalse(File(Models.root(context), "test-live.part").exists())
            val corrupt = spec.copy(id = "test-bad", sha256 = "0".repeat(64))
            val failure = runCatching { Models.download(context, corrupt, { false }) { _, _ -> } }.exceptionOrNull()
            assertTrue(failure?.message?.contains("verification failed") == true)
            assertFalse(Models.installed(context, corrupt))
            assertFalse(File(Models.root(context), "test-bad.part").exists())
        } finally { server.close() }
    }

    @Test fun activeModelPrefersChoiceThenRecommended() {
        val summary = Models.all.filter { it.role == Role.SUMMARY }
        assertNull(Models.active(context, Role.SUMMARY))
        for (spec in summary) {
            Models.folder(context, spec).mkdirs()
            File(Models.folder(context, spec), ".complete").writeText("x")
            spec.files.forEach { File(Models.folder(context, spec), it).writeText("model") }
        }
        assertEquals(summary.first { it.recommended }.id, Models.active(context, Role.SUMMARY)!!.id)
        val other = summary.first { !it.recommended }
        Models.choose(context, other)
        assertEquals(other.id, Models.active(context, Role.SUMMARY)!!.id)
        Models.delete(context, other)
        assertEquals(summary.first { it.recommended }.id, Models.active(context, Role.SUMMARY)!!.id)
        assertEquals(1, Models.all.count { it.role == Role.LIVE && it.recommended })
    }

    @Test fun numberGuardDropsChangedOrInventedNumbers() {
        val source = "pricing for clinics we agreed on forty nine euros per month, then sixty nine. " +
            "We have twelve thousand five hundred euros left. The launch moves to October 14th."
        val notes = "## Key points\n- Pricing is 49 euros, then 69 euros.\n- Pricing is 45 euros per month.\n" +
            "- 2023-08-15: Budget is 12,500 euros.\n6. Launch moves to October 14.\n- Budget is 12,500 euros."
        assertEquals("## Key points\n- Pricing is 49 euros, then 69 euros.\n6. Launch moves to October 14.\n- Budget is 12,500 euros.",
            NumberGuard.filter(notes, source))
    }

    @Test fun cleanRemovesReasoningAndChatMarkers() {
        assertEquals("- Fact", Writer.clean("<think>\nhmm\n</think>\n- Fact<|im_end|>"))
        assertEquals("## Summary", Writer.clean("## Summary<turn|>"))
        // Repeated bullets (the loop seen on the phone) collapse to one; short lines like headings may repeat.
        val loop = "- Launch moves to October 14.\n" + "- DN is cheaper for small volumes.\n".repeat(5) + "## Key points\n## Key points"
        assertEquals("- Launch moves to October 14.\n- DN is cheaper for small volumes.\n## Key points\n## Key points", Writer.clean(loop))
        // A reply cut off by the length limit loses its unfinished last bullet.
        assertEquals("- Alex will fix the date picker.", Writer.clean("- Alex will fix the date picker.\n- Provider comparison must be done by"))
    }
}

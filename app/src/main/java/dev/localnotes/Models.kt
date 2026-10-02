package dev.localnotes

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** What a model is used for. Exactly one model per role is active. */
enum class Role { LIVE, ACCURATE, SPEAKER, SUMMARY }

/**
 * A downloadable model. [files] are the files the engine needs, relative to the model folder.
 * Archives (.tar.bz2) are unpacked with their top folder stripped; single files are stored as-is.
 */
data class ModelSpec(
    val id: String, val role: Role, val name: String, val detail: String,
    val url: String, val bytes: Long, val files: List<String>,
    val template: String = "", val recommended: Boolean = false,
)

object Models {
    private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
    private const val HF = "https://huggingface.co/"

    // Chosen by on-phone tests (OnePlus 13R, 1 Oct 2026): see BENCHMARK_REPORT.md.
    val all = listOf(
        ModelSpec("kroko-en", Role.LIVE, "Kroko streaming", "Words appear as you speak · 57 MB",
            GH + "sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06.tar.bz2", 57_267_600,
            listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"), recommended = true),
        ModelSpec("parakeet-unified", Role.ACCURATE, "NVIDIA Parakeet", "Re-checks each paragraph with a larger model · 501 MB",
            GH + "sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming.tar.bz2", 501_350_460,
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"), recommended = true),
        ModelSpec("speaker-en", Role.SPEAKER, "Voice ID", "Labels new voices automatically · 26 MB",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx",
            26_485_263, listOf("speaker.onnx"), recommended = true),
        ModelSpec("gemma4-e2b", Role.SUMMARY, "Google Gemma 4", "Faithful notes and summary · 3.3 GB",
            HF + "google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/main/gemma-4-E2B_q4_0-it.gguf", 3_349_516_256,
            listOf("model.gguf"), template = "gemma", recommended = true),
        ModelSpec("qwen35-2b", Role.SUMMARY, "Qwen3.5 2B", "Smaller and faster, less reliable with numbers · 1.2 GB",
            HF + "unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_0.gguf", 1_214_873_856,
            listOf("model.gguf"), template = "chatml-nothink"),
    )
    /** Silero voice-activity model, used to split imported audio into sentences. Tiny, always fetched with the accurate model. */
    val vad = ModelSpec("silero-vad", Role.ACCURATE, "Voice detector", "", GH + "silero_vad.onnx", 643_854, listOf("silero_vad.onnx"))

    fun root(context: Context) = File(context.filesDir, "models").apply { mkdirs() }
    fun folder(context: Context, spec: ModelSpec) = File(root(context), spec.id)
    fun installed(context: Context, spec: ModelSpec) = File(folder(context, spec), ".complete").exists()
    fun file(context: Context, spec: ModelSpec, name: String) = File(folder(context, spec), name).absolutePath

    private fun prefs(context: Context) = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    /** The active model for a role: the user's choice if installed, else the first installed, recommended first. */
    fun active(context: Context, role: Role): ModelSpec? {
        val chosen = prefs(context).getString(role.name, null)
        val candidates = all.filter { it.role == role && installed(context, it) }
        return candidates.firstOrNull { it.id == chosen } ?: candidates.sortedByDescending { it.recommended }.firstOrNull()
    }
    fun choose(context: Context, spec: ModelSpec) { prefs(context).edit().putString(spec.role.name, spec.id).apply() }
    fun vadPath(context: Context): String? = file(context, vad, "silero_vad.onnx").takeIf { installed(context, vad) }

    fun delete(context: Context, spec: ModelSpec) { folder(context, spec).deleteRecursively() }

    /**
     * Downloads (resuming a partial file) and installs [spec]. [progress] gets bytes done/total.
     * Throws on failure; a half-downloaded file is kept so the next attempt resumes.
     */
    fun download(context: Context, spec: ModelSpec, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        val target = folder(context, spec)
        if (installed(context, spec)) return
        val partial = File(root(context), "${spec.id}.part")
        var url = URL(spec.url)
        var total = spec.bytes
        for (attempt in 0 until 8) { // Follows redirects across hosts (GitHub → object storage); resumes with Range.
            val done = partial.length()
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 20_000; readTimeout = 60_000
                setRequestProperty("User-Agent", "LocalNotes")
                if (done > 0) setRequestProperty("Range", "bytes=$done-")
            }
            val code = connection.responseCode
            if (code in 300..399) { url = URL(url, connection.getHeaderField("Location")); connection.disconnect(); continue }
            if (code == 416) break // Already complete.
            check(code == 200 || code == 206) { "Download failed (HTTP $code). Check the connection and try again." }
            val append = code == 206
            val length = connection.contentLengthLong.takeIf { it > 0 } ?: (spec.bytes - if (append) done else 0L)
            total = if (append) done + length else length
            check(root(context).usableSpace > (total - done) * 2 + 200_000_000L) { "Not enough free space for ${spec.name}." }
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var written = if (append) done else 0L
                    var last = 0L
                    while (true) {
                        if (cancelled()) throw InterruptedException("Download paused")
                        val n = input.read(buffer); if (n < 0) break
                        output.write(buffer, 0, n); written += n
                        if (written - last > 1_000_000) { progress(written, total); last = written }
                    }
                    progress(written, total)
                }
            }
            break
        }
        check(partial.length() == total) { "Download incomplete; try again to resume." }
        target.deleteRecursively(); target.mkdirs()
        if (spec.url.endsWith(".tar.bz2")) {
            TarArchiveInputStream(BZip2CompressorInputStream(partial.inputStream().buffered(1 shl 16))).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val name = entry.name.substringAfter('/') // Strip the archive's top folder.
                    if (entry.isDirectory || name.isEmpty() || name.contains("..") || name.startsWith("test")) continue
                    val out = File(target, name); out.parentFile?.mkdirs()
                    FileOutputStream(out).use { tar.copyTo(it, 1 shl 16) }
                }
            }
        } else {
            check(partial.renameTo(File(target, spec.files.single()))) { "Could not store ${spec.name}." }
        }
        partial.delete()
        val missing = spec.files.filterNot { File(target, it).exists() }
        check(missing.isEmpty()) { "${spec.name} is missing ${missing.joinToString()}; delete it and download again." }
        File(target, ".complete").writeText(spec.url)
    }

    /** Bytes of a partial download, for "resume" display. */
    fun partialBytes(context: Context, spec: ModelSpec) = File(root(context), "${spec.id}.part").length()
}

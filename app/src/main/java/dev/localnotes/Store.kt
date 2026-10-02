package dev.localnotes

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object Store {
    // A single owner prevents recording, inference and model replacement from racing.
    val busy = AtomicBoolean(false)
    fun root(context: Context) = File(context.filesDir, "sessions").apply { mkdirs() }
    fun create(context: Context): File = File(root(context), UUID.randomUUID().toString()).apply {
        mkdirs()
        write(File(this, "created.txt"), java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.UK).format(java.util.Date()))
    }
    fun sessions(context: Context) = root(context).listFiles()?.filter { it.isDirectory }?.sortedByDescending { RecordingInfo.startEpoch(it) } ?: emptyList()
    fun write(file: File, value: String) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(value.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
}

package dev.localnotes

import android.util.AtomicFile
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Robolectric uses host java.io.File.renameTo, which cannot replace an existing
 * destination on Windows. Android/POSIX can. Match Android's atomic replacement
 * semantics for these UI tests without changing production persistence code.
 */
@Implements(AtomicFile::class)
class HostAtomicFileShadow {
    @RealObject private lateinit var real: AtomicFile
    @Implementation
    protected fun finishWrite(stream: FileOutputStream?) {
        if (stream == null) return
        stream.fd.sync()
        stream.close()
        Files.move(File(real.baseFile.path + ".new").toPath(), real.baseFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

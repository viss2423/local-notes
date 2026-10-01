package dev.localnotes

fun interface SpeechProgress { fun onProgress(percent: Int) }

object Speech {
    init { System.loadLibrary("speech_bridge") }
    external fun open(path: String): Long
    external fun transcribe(handle: Long, samples: FloatArray, progress: SpeechProgress, threads: Int, audioContext: Int): ByteArray
    external fun requestStop()
    external fun resetStop()
    external fun close(handle: Long)
}

object Language {
    init { System.loadLibrary("language_bridge") }
    external fun open(path: String): Long
    external fun generate(handle: Long, input: ByteArray): ByteArray
    external fun requestStop()
    external fun resetStop()
    external fun close(handle: Long)
}

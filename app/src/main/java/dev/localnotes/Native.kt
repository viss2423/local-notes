package dev.localnotes

/** Receives each new piece of generated text; return false to stop generating. */
fun interface TextListener { fun onText(utf8: ByteArray): Boolean }

object Language {
    init { System.loadLibrary("language_bridge") }
    external fun open(path: String): Long
    /** [prompt] is already chat-formatted UTF-8; returns the full reply, streaming pieces to [listener]. */
    external fun generate(handle: Long, prompt: ByteArray, maxTokens: Int, threads: Int, listener: TextListener?): ByteArray
    external fun requestStop()
    external fun resetStop()
    external fun close(handle: Long)
}

package dev.localnotes

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableStateFlow

/** A seekable WAV view of the saved PCM. It adds only a 44-byte header, never copies hours of audio. */
class PcmWavDataSource(audio: File) : MediaDataSource() {
    private val file = RandomAccessFile(audio, "r")
    val pcmBytes: Long = file.length() and -2L
    private val header = wavHeader(pcmBytes)
    private var closed = false

    override fun getSize(): Long = header.size + pcmBytes

    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        check(!closed) { "Recording is closed" }
        require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size)
        if (size == 0) return 0
        if (position >= getSize()) return -1
        var written = 0
        if (position < header.size) {
            val n = minOf(size.toLong(), header.size - position).toInt()
            header.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
            written = n
        }
        if (written < size) {
            val rawAt = (position + written - header.size).coerceAtLeast(0L)
            if (rawAt < pcmBytes) {
                file.seek(rawAt)
                val n = file.read(buffer, offset + written, minOf((size - written).toLong(), pcmBytes - rawAt).toInt())
                if (n > 0) written += n
            }
        }
        return if (written == 0) -1 else written
    }

    @Synchronized override fun close() {
        if (!closed) { closed = true; file.close() }
    }
}

/** Standard 16 kHz, mono, signed 16-bit RIFF header for the raw recording. */
fun wavHeader(pcmBytes: Long): ByteArray {
    require(pcmBytes >= 0 && pcmBytes % 2 == 0L && pcmBytes <= 0xffffffffL - 36) { "Recording is too large for a WAV file" }
    return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt((pcmBytes + 36).toInt())
        .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        .putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(pcmBytes.toInt()).array()
}

/** Playback belongs to the open recording screen and releases audio focus when it closes. */
class RecordingPlayer(context: Context, private val audio: File) : Closeable {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if (change <= 0) pause()
        }.build()
    private var player: MediaPlayer? = null
    private var source: PcmWavDataSource? = null
    private var ready = false
    private var seekAfterPrepare: Long? = null
    private var playAfterSeek = false
    val playing = MutableStateFlow(false)
    val positionMs = MutableStateFlow(0L)
    val error = MutableStateFlow<String?>(null)
    val durationMs: Long = audio.length() / 32

    fun toggle() { if (playing.value) pause() else playFrom(if (positionMs.value >= durationMs) 0 else positionMs.value) }

    fun playFrom(atMs: Long) {
        if (!audio.isFile || audio.length() < 2) { error.value = "No audio is available for this recording."; return }
        if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            error.value = "Audio is in use by another app."; return
        }
        error.value = null
        playAfterSeek = true
        val target = atMs.coerceIn(0L, durationMs)
        if (player == null) {
            runCatching {
                val data = PcmWavDataSource(audio)
                source = data
                seekAfterPrepare = target
                player = MediaPlayer().apply {
                    setAudioAttributes(attributes)
                    setDataSource(data)
                    setOnPreparedListener { ready = true; seekAfterPrepare?.let { seekAfterPrepare = null; seekTo(it, MediaPlayer.SEEK_CLOSEST) } ?: startPlaying() }
                    setOnSeekCompleteListener { positionMs.value = currentPosition.toLong(); if (playAfterSeek) startPlaying() }
                    setOnCompletionListener { playing.value = false; positionMs.value = durationMs; abandonFocus() }
                    setOnErrorListener { _, _, _ -> error.value = "Playback stopped. Try opening this recording again."; playing.value = false; abandonFocus(); true }
                    prepareAsync()
                }
            }.onFailure { error.value = "Could not play this recording: ${it.message}"; releasePlayer() }
        } else if (ready) {
            seekTo(target)
        } else seekAfterPrepare = target
    }

    fun seekTo(atMs: Long) {
        val target = atMs.coerceIn(0L, durationMs)
        positionMs.value = target
        if (!ready) { seekAfterPrepare = target; return }
        runCatching { player?.seekTo(target, MediaPlayer.SEEK_CLOSEST) }
            .onFailure { error.value = "Could not seek in this recording." }
    }

    fun updatePosition() {
        if (playing.value) runCatching { player?.currentPosition?.toLong() }.getOrNull()?.let { positionMs.value = it }
    }

    fun pause() {
        playAfterSeek = false
        runCatching { if (player?.isPlaying == true) player?.pause() }
        playing.value = false
        abandonFocus()
    }

    private fun startPlaying() {
        runCatching { if (player?.isPlaying != true) player?.start(); playing.value = true; playAfterSeek = false }
            .onFailure { error.value = "Could not start playback."; playing.value = false; abandonFocus() }
    }

    private fun abandonFocus() { audioManager.abandonAudioFocusRequest(focus) }
    private fun releasePlayer() {
        runCatching { player?.release() }; player = null; ready = false
        runCatching { source?.close() }; source = null
        playing.value = false
        abandonFocus()
    }
    override fun close() { releasePlayer() }
}

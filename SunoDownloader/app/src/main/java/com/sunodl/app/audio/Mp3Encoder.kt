package com.sunodl.app.audio

import de.sciss.jump3r.lowlevel.LameEncoder
import java.io.OutputStream
import javax.sound.sampled.AudioFormat

/** Codificador MP3 local (LAME portado a Java por jump3r). Entrada: PCM 16 bits little-endian. */
class Mp3Encoder(sampleRate: Int, channels: Int, bitrateKbps: Int, private val out: OutputStream) {
    private val encoder = LameEncoder(
        AudioFormat(sampleRate.toFloat(), 16, channels, true, false),
        bitrateKbps,
        if (channels == 1) LameEncoder.CHANNEL_MODE_MONO else LameEncoder.CHANNEL_MODE_JOINT_STEREO,
        LameEncoder.QUALITY_HIGH,
        false,
    )
    private val inSize = encoder.pcmBufferSize
    private val mp3Buf = ByteArray(encoder.mP3BufferSize)
    private val pending = ByteArray(inSize)
    private var pendingLen = 0

    fun write(pcm: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = minOf(inSize - pendingLen, len - off)
            System.arraycopy(pcm, off, pending, pendingLen, n)
            pendingLen += n; off += n
            if (pendingLen == inSize) flushPending()
        }
    }

    private fun flushPending() {
        if (pendingLen == 0) return
        val produced = encoder.encodeBuffer(pending, 0, pendingLen, mp3Buf)
        if (produced > 0) out.write(mp3Buf, 0, produced)
        pendingLen = 0
    }

    fun finish() {
        flushPending()
        val produced = encoder.encodeFinish(mp3Buf)
        if (produced > 0) out.write(mp3Buf, 0, produced)
        encoder.close()
    }
}

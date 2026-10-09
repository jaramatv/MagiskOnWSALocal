package com.sunodl.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import java.io.File

/** Decodifica la pista de audio de [input] a PCM de 16 bits intercalado (little-endian). */
class PcmDecoder(private val input: File) {

    class Format(val sampleRate: Int, val channels: Int, val durationUs: Long)

    /**
     * @param onFormat se llama una vez con el formato definitivo de salida.
     * @param onPcm recibe bloques PCM; el progreso (0..1) se calcula sobre la duración.
     */
    fun decode(onFormat: (Format) -> Unit, onPcm: (ByteArray, Int) -> Unit, onProgress: (Float) -> Unit = {}) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("El archivo no contiene audio que este dispositivo pueda leer.")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            if (Build.VERSION.SDK_INT >= 24) inFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) {
                throw IllegalStateException("Este dispositivo no tiene decodificador para $mime.", e)
            }
            codec.configure(inFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var formatSent = false
            var floatPcm = false
            var chunk = ByteArray(0)
            fun sendFormat(f: MediaFormat) {
                if (formatSent) return
                formatSent = true
                floatPcm = Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                    f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                onFormat(Format(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT), durationUs))
            }
            while (true) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> sendFormat(codec.outputFormat)
                    out >= 0 -> {
                        if (info.size > 0) {
                            sendFormat(codec.outputFormat)
                            val buf = codec.getOutputBuffer(out)!!
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            if (chunk.size < info.size) chunk = ByteArray(info.size)
                            buf.get(chunk, 0, info.size)
                            if (floatPcm) {
                                val n = floatToPcm16(chunk, info.size)
                                onPcm(chunk, n)
                            } else onPcm(chunk, info.size)
                            if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /** Convierte en el mismo buffer muestras float32 a int16; devuelve el nuevo tamaño en bytes. */
    private fun floatToPcm16(b: ByteArray, len: Int): Int {
        val samples = len / 4
        for (i in 0 until samples) {
            val bits = (b[i * 4].toInt() and 0xff) or ((b[i * 4 + 1].toInt() and 0xff) shl 8) or
                ((b[i * 4 + 2].toInt() and 0xff) shl 16) or ((b[i * 4 + 3].toInt() and 0xff) shl 24)
            val v = (java.lang.Float.intBitsToFloat(bits).coerceIn(-1f, 1f) * 32767f).toInt()
            b[i * 2] = v.toByte(); b[i * 2 + 1] = (v shr 8).toByte()
        }
        return samples * 2
    }
}

package com.sunodl.app.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Copia la pista de audio de un MP4 a un archivo M4A sin recodificar: los paquetes
 * comprimidos se trasladan tal cual, así que la calidad es idéntica a la del vídeo.
 */
object AudioTrackExtractor {

    fun extractAudio(input: File, output: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("El archivo no contiene ninguna pista de audio.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val outTrack = muxer.addTrack(format)
                muxer.start()
                val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                val buffer = ByteBuffer.allocate(maxOf(maxSize, 256 * 1024))
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.set(0, size, extractor.sampleTime,
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(outTrack, buffer, info)
                    extractor.advance()
                }
                muxer.stop()
            } finally {
                muxer.release()
            }
        } finally {
            extractor.release()
        }
    }

    /** Descripción breve del códec de audio (p. ej. "AAC 48 kHz estéreo"). */
    fun describe(file: File): String? = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.let { f ->
                    val codec = when (val m = f.getString(MediaFormat.KEY_MIME)) {
                        "audio/mp4a-latm" -> "AAC"; "audio/mpeg" -> "MP3"; "audio/opus" -> "Opus"
                        else -> m?.removePrefix("audio/")?.uppercase()
                    }
                    val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 1000.0
                    val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val bitrate = if (f.containsKey(MediaFormat.KEY_BIT_RATE)) " · ${f.getInteger(MediaFormat.KEY_BIT_RATE) / 1000} kbps" else ""
                    "$codec ${"%.1f".format(rate).removeSuffix(".0")} kHz ${if (ch == 1) "mono" else "estéreo"}$bitrate"
                }
        } finally { ex.release() }
    }.getOrNull()
}

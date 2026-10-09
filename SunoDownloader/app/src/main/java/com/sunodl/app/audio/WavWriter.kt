package com.sunodl.app.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Escribe PCM de 16 bits en un archivo WAV, con bloques LIST/INFO e "id3 " para los metadatos. */
class WavWriter(private val file: File, private val sampleRate: Int, private val channels: Int) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(44)) }
    private var dataBytes = 0L

    fun write(pcm: ByteArray, len: Int) {
        raf.write(pcm, 0, len)
        dataBytes += len
    }

    fun finish(tags: Tags?) {
        if (dataBytes % 2 == 1L) raf.write(0)
        tags?.let {
            writeChunk("LIST", infoList(it))
            writeChunk("id3 ", Id3v2.build(it))
        }
        val total = raf.length()
        raf.seek(0)
        raf.write(header(total))
    }

    private fun writeChunk(id: String, data: ByteArray) {
        raf.write(id.toByteArray(Charsets.US_ASCII))
        raf.write(le32(data.size))
        raf.write(data)
        if (data.size % 2 == 1) raf.write(0)
    }

    private fun infoList(t: Tags): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("INFO".toByteArray(Charsets.US_ASCII))
        fun sub(id: String, v: String?) {
            if (v.isNullOrBlank()) return
            val b = v.toByteArray(Charsets.UTF_8) + 0
            out.write(id.toByteArray(Charsets.US_ASCII)); out.write(le32(b.size)); out.write(b)
            if (b.size % 2 == 1) out.write(0)
        }
        sub("INAM", t.title); sub("IART", t.artist); sub("IPRD", "Suno"); sub("ICMT", t.comment); sub("IGNR", t.genre?.take(120))
        return out.toByteArray()
    }

    private fun header(totalLength: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((totalLength - 8).toInt()); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(channels.toShort())
        b.putInt(sampleRate); b.putInt(sampleRate * channels * 2); b.putShort((channels * 2).toShort()); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(dataBytes.toInt())
        return b.array()
    }

    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    override fun close() = raf.close()
}

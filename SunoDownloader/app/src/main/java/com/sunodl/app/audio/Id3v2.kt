package com.sunodl.app.audio

import java.io.ByteArrayOutputStream

/**
 * Escritor mínimo de etiquetas ID3v2.3 (título, artista, letra, portada, comentario, URL).
 * Se usa para MP3 y también como bloque "id3 " dentro de WAV, que leen la mayoría de reproductores.
 */
object Id3v2 {

    fun build(tags: Tags): ByteArray {
        val frames = ByteArrayOutputStream()
        frames.write(textFrame("TIT2", tags.title))
        frames.write(textFrame("TPE1", tags.artist))
        frames.write(textFrame("TALB", "Suno"))
        tags.genre?.takeIf { it.isNotBlank() }?.let { frames.write(textFrame("TCON", it.take(120))) }
        tags.lyrics?.let { frames.write(langFrame("USLT", it)) }
        tags.comment?.let { frames.write(langFrame("COMM", it)) }
        tags.url?.let { frames.write(frame("WOAS", it.toByteArray(Charsets.ISO_8859_1))) }
        tags.cover?.let { frames.write(apic(it, tags.coverMime)) }
        val body = frames.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
        out.write(syncsafe(body.size))
        out.write(body)
        return out.toByteArray()
    }

    /** Tamaño en bytes de una etiqueta ID3v2 al principio de [head], o 0 si no hay. */
    fun existingTagSize(head: ByteArray): Int {
        if (head.size < 10 || head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return 0
        val size = ((head[6].toInt() and 0x7f) shl 21) or ((head[7].toInt() and 0x7f) shl 14) or
            ((head[8].toInt() and 0x7f) shl 7) or (head[9].toInt() and 0x7f)
        val footer = if (head[5].toInt() and 0x10 != 0) 10 else 0
        return 10 + size + footer
    }

    // Texto en UTF-16 con BOM (codificación 1), compatible con ID3v2.3 y caracteres no latinos.
    private fun utf16(s: String): ByteArray = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)

    private fun textFrame(id: String, text: String) = frame(id, byteArrayOf(1) + utf16(text))

    private fun langFrame(id: String, text: String): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(1); b.write("spa".toByteArray(Charsets.ISO_8859_1))
        b.write(utf16("")); b.write(0); b.write(0)
        b.write(utf16(text))
        return frame(id, b.toByteArray())
    }

    private fun apic(image: ByteArray, mime: String): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(0); b.write(mime.toByteArray(Charsets.ISO_8859_1)); b.write(0)
        b.write(3) // portada frontal
        b.write(0) // descripción vacía
        b.write(image)
        return frame("APIC", b.toByteArray())
    }

    private fun frame(id: String, data: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(id.toByteArray(Charsets.ISO_8859_1))
        b.write(byteArrayOf((data.size ushr 24).toByte(), (data.size ushr 16).toByte(), (data.size ushr 8).toByte(), data.size.toByte()))
        b.write(byteArrayOf(0, 0))
        b.write(data)
        return b.toByteArray()
    }

    private fun syncsafe(n: Int) = byteArrayOf(
        ((n ushr 21) and 0x7f).toByte(), ((n ushr 14) and 0x7f).toByte(),
        ((n ushr 7) and 0x7f).toByte(), (n and 0x7f).toByte()
    )
}

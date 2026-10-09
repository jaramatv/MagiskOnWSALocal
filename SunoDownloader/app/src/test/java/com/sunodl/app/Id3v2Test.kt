package com.sunodl.app

import com.sunodl.app.audio.Id3v2
import com.sunodl.app.audio.Tags
import org.junit.Assert.*
import org.junit.Test

class Id3v2Test {
    @Test fun headerAndSize() {
        val tag = Id3v2.build(Tags("Título ñ", "Artista", "Letra\nlínea 2", byteArrayOf(1, 2, 3), comment = "c", url = "https://suno.com/song/x"))
        assertEquals('I'.code.toByte(), tag[0]); assertEquals(3.toByte(), tag[3])
        assertEquals(tag.size, Id3v2.existingTagSize(tag))
        val ascii = String(tag, Charsets.ISO_8859_1)
        listOf("TIT2", "TPE1", "USLT", "APIC", "WOAS", "COMM").forEach { assertTrue(it, ascii.contains(it)) }
        assertTrue(String(tag, Charsets.UTF_16LE).contains("Título ñ"))
    }

    @Test fun noTag() = assertEquals(0, Id3v2.existingTagSize(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)))
}

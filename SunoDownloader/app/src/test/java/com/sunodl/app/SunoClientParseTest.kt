package com.sunodl.app

import com.sunodl.app.suno.SourceKind
import com.sunodl.app.suno.SunoClient
import com.sunodl.app.suno.SunoException
import org.junit.Assert.*
import org.junit.Test

/** Estructura real de /api/clip/{id} (octubre 2026) con valores sintéticos. */
class SunoClientParseTest {
    private val id = "057fa44a-a2c2-4089-8fca-52a637117f00"
    private fun clip(audio: String, video: String) = """
        {"status":"complete","title":"Canción de prueba ","id":"$id","entity_type":"song_schema",
         "video_url":"$video","audio_url":"$audio",
         "media_urls":[{"url":"https://d2lwuy8qc234o3.cloudfront.net/1/clip/$id.m4a","content_type":"m4a-opus","delivery":"progressive","encoding":"1.0.0"}],
         "image_url":"https://cdn2.suno.ai/image_$id.jpeg","image_large_url":"https://cdn2.suno.ai/image_large_$id.jpeg",
         "major_model_version":"v6","display_name":"Artista","handle":"artista","is_public":true,
         "metadata":{"tags":"pop, guitarra","prompt":"[Verso]\nTexto de prueba","duration":224.96}}
    """.trimIndent()

    @Test fun forbiddenAudioWithVideo() {
        val s = SunoClient.parseClip(clip("https://studio-api.prod.suno.com/api/forbidden", "https://cdn1.suno.ai/$id.mp4"))
        assertEquals("Canción de prueba", s.title)
        assertEquals("Artista", s.artist)
        assertEquals(224.96, s.durationSec!!, 0.001)
        assertEquals("https://cdn2.suno.ai/image_large_$id.jpeg", s.coverUrl)
        assertEquals("[Verso]\nTexto de prueba", s.lyrics)
        assertEquals(listOf(SourceKind.VIDEO_AAC), s.sources.map { it.kind })
        assertTrue(s.onlyEncryptedStream)
        // El flujo cifrado del reproductor nunca se usa como fuente.
        assertFalse(s.sources.any { it.url.contains("cloudfront") })
    }

    @Test fun realMp3PreferredFirst() {
        val s = SunoClient.parseClip(clip("https://cdn1.suno.ai/$id.mp3", ""))
        assertEquals(SourceKind.ORIGINAL_MP3, s.bestSource!!.kind)
        assertEquals("https://cdn1.suno.ai/$id.mp4", s.sources[1].url) // vídeo por defecto si video_url vacío
    }

    @Test(expected = SunoException::class) fun notReady() {
        SunoClient.parseClip("""{"id":"$id","status":"submitted"}""")
    }

    @Test fun forbiddenMarker() {
        assertFalse(SunoClient.isDownloadableAudioUrl("https://studio-api.prod.suno.com/api/forbidden"))
        assertFalse(SunoClient.isDownloadableAudioUrl("http://cdn1.suno.ai/x.mp3"))
        assertTrue(SunoClient.isDownloadableAudioUrl("https://cdn1.suno.ai/x.mp3"))
    }
}

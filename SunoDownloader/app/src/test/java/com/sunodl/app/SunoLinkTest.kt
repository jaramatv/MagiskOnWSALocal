package com.sunodl.app

import com.sunodl.app.suno.SunoLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SunoLinkTest {
    private val id = "9696c2ff-1d22-462c-aead-1ad324b118e8"

    @Test fun songUrl() = assertEquals(SunoLink.Song(id), SunoLink.parse("https://suno.com/song/$id"))
    @Test fun songUrlWithQuery() = assertEquals(SunoLink.Song(id), SunoLink.parse("https://suno.com/song/$id?sh=XceKbVQ7EZaCje85"))
    @Test fun legacyApp() = assertEquals(SunoLink.Song(id), SunoLink.parse("https://app.suno.ai/song/$id/"))
    @Test fun embed() = assertEquals(SunoLink.Song(id), SunoLink.parse("https://suno.com/embed/$id"))
    @Test fun upperCase() = assertEquals(SunoLink.Song(id), SunoLink.parse("https://suno.com/song/${id.uppercase()}"))
    @Test fun shortLink() = assertEquals(SunoLink.Short("https://suno.com/s/XceKbVQ7EZaCje85"), SunoLink.parse("https://suno.com/s/XceKbVQ7EZaCje85"))
    @Test fun sharedText() = assertEquals(
        SunoLink.Short("https://suno.com/s/XceKbVQ7EZaCje85"),
        SunoLink.parse("Escucha \"Despierta\" de JaramgoTV en Suno: https://suno.com/s/XceKbVQ7EZaCje85 ¡Te gustará!"))
    @Test fun withoutScheme() = assertEquals(SunoLink.Short("https://suno.com/s/XceKbVQ7EZaCje85"), SunoLink.parse("suno.com/s/XceKbVQ7EZaCje85"))
    @Test fun otherHostRejected() = assertNull(SunoLink.parse("https://example.com/song/$id"))
    @Test fun lookalikeHostRejected() = assertNull(SunoLink.parse("https://notsuno.com/song/$id"))
    @Test fun garbage() = assertNull(SunoLink.parse("hola"))
}

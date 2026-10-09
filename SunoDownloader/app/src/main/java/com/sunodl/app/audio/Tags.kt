package com.sunodl.app.audio

/** Metadatos que se incrustan en los archivos guardados. */
data class Tags(
    val title: String,
    val artist: String,
    val lyrics: String?,
    val cover: ByteArray?,
    val coverMime: String = "image/jpeg",
    val comment: String?,
    val url: String?,
    val genre: String? = null,
)

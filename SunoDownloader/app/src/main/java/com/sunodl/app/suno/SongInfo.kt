package com.sunodl.app.suno

/** Origen del archivo de audio que se descargará. */
enum class SourceKind(val label: String, val ext: String, val mime: String) {
    /** `audio_url` real de Suno (MP3). Solo existe cuando Suno lo ofrece sin sesión. */
    ORIGINAL_MP3("MP3 original de Suno", "mp3", "audio/mpeg"),
    /** Pista AAC del vídeo público para compartir (`video_url`), extraída sin recodificar. */
    VIDEO_AAC("M4A (AAC) extraído del vídeo público", "m4a", "audio/mp4"),
}

data class AudioSource(val kind: SourceKind, val url: String)

/** Datos públicos de una canción, tal como los devuelve Suno. */
data class SongInfo(
    val id: String,
    val title: String,
    val artist: String,
    val handle: String?,
    val coverUrl: String?,
    val durationSec: Double?,
    val lyrics: String?,
    val tags: String?,
    val modelVersion: String?,
    val isPublic: Boolean,
    /** Fuentes de audio descargables comprobadas, en orden de preferencia (vacía si no hay ninguna). */
    val sources: List<AudioSource>,
    /** true si Suno solo sirve el audio a través del flujo cifrado del reproductor. */
    val onlyEncryptedStream: Boolean = false,
    val pageUrl: String = "https://suno.com/song/$id",
) {
    val bestSource: AudioSource? get() = sources.firstOrNull()
}

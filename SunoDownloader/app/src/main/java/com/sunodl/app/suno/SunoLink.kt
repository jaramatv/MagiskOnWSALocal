package com.sunodl.app.suno

/**
 * Reconoce los enlaces públicos de Suno que puede recibir la app, ya sea escritos,
 * pegados o compartidos desde otra aplicación (el texto compartido suele incluir
 * más palabras alrededor del enlace).
 */
sealed class SunoLink {
    /** Enlace que ya contiene el identificador (UUID) de la canción. */
    data class Song(val id: String) : SunoLink()

    /** Enlace corto de compartir (suno.com/s/XXXX) que hay que resolver siguiendo la redirección. */
    data class Short(val url: String) : SunoLink()

    companion object {
        private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val URL = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
        private val SUNO_HOST = Regex("""^(?:[a-z0-9-]+\.)*(?:suno\.com|suno\.ai)$""", RegexOption.IGNORE_CASE)
        private val SHORT_PATH = Regex("""^/s/([A-Za-z0-9]+)/?$""")

        fun parse(text: String): SunoLink? {
            val candidates = URL.findAll(text).map { it.value.trimEnd('.', ',', ')', ']', '!', '?') }.toMutableList()
            // Permite pegar el enlace sin "https://".
            if (candidates.isEmpty()) {
                Regex("""(?:[a-z0-9-]+\.)*suno\.(?:com|ai)/\S+""", RegexOption.IGNORE_CASE)
                    .find(text)?.let { candidates += "https://" + it.value }
            }
            for (raw in candidates) {
                val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: continue
                val host = uri.host ?: continue
                if (!SUNO_HOST.matches(host)) continue
                val path = uri.rawPath ?: ""
                // suno.com/song/<id>, /embed/<id>, app.suno.ai/song/<id>, cdn1.suno.ai/<id>.mp4, ?id=...
                UUID.find(path)?.let { return Song(it.value.lowercase()) }
                UUID.find(uri.rawQuery ?: "")?.let { return Song(it.value.lowercase()) }
                SHORT_PATH.find(path)?.let { return Short("https://suno.com/s/${it.groupValues[1]}") }
            }
            return null
        }

        fun extractId(text: String): String? = UUID.find(text)?.value?.lowercase()
    }
}

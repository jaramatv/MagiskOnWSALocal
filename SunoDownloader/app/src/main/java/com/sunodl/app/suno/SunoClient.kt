package com.sunodl.app.suno

import com.sunodl.app.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class SunoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Obtiene los datos públicos de una canción con las mismas peticiones anónimas que hace
 * la web de Suno al abrir un enlace (no usa UseSuno ni ningún servidor intermedio):
 *
 *  - `GET https://studio-api.prod.suno.com/api/clip/{id}` → JSON con título, artista,
 *    portada, duración, letra y URLs multimedia.
 *  - Los enlaces cortos `suno.com/s/XXXX` responden con una redirección 30x a `/song/{id}`.
 *
 * Fuentes de audio, en orden de preferencia:
 *  1. `audio_url` → MP3 original, solo si Suno lo ofrece (para anónimos suele ser `/api/forbidden`).
 *  2. `video_url` / `https://cdn1.suno.ai/{id}.mp4` → vídeo público sin cifrar; se extrae su
 *     pista AAC sin recodificar. Solo existe si el autor generó el vídeo para compartir.
 *
 * El flujo del reproductor (`media_urls`, `…cloudfront.net/1/clip/{id}.m4a`) está cifrado con AES
 * y las claves solo se entregan a clientes autorizados. La app NO intenta descifrarlo.
 */
class SunoClient(private val http: OkHttpClient = Http.client) {

    suspend fun resolve(link: SunoLink): SongInfo = withContext(Dispatchers.IO) {
        val id = when (link) {
            is SunoLink.Song -> link.id
            is SunoLink.Short -> resolveShort(link.url)
        }
        val info = fetchClip(id)
        info.copy(sources = info.sources.filter { isReachable(it.url) })
    }

    /** Comprueba con una petición de 1 byte si el recurso es accesible (los HEAD de Suno dan 403). */
    private fun isReachable(url: String): Boolean = try {
        http.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").get().build()).execute()
            .use { it.code == 200 || it.code == 206 }
    } catch (e: IOException) {
        throw SunoException("Sin conexión con Suno. Revisa tu conexión a Internet.", e)
    }

    private fun resolveShort(url: String): String {
        var current = url
        repeat(5) {
            val req = Request.Builder().url(current).get().build()
            try {
                Http.noRedirects.newCall(req).execute().use { resp ->
                    SunoLink.extractId(current)?.let { id -> return id }
                    val location = resp.header("Location")
                        ?: throw SunoException(
                            if (resp.code == 404) "El enlace corto no existe o ha caducado."
                            else "No se pudo resolver el enlace corto (HTTP ${resp.code})."
                        )
                    current = resp.request.url.resolve(location)?.toString() ?: location
                    SunoLink.extractId(current)?.let { id -> return id }
                    if (current.trimEnd('/').endsWith("suno.com")) {
                        throw SunoException("Suno no reconoce este enlace corto: redirige a la portada.")
                    }
                }
            } catch (e: IOException) {
                throw SunoException("Sin conexión con Suno. Revisa tu conexión a Internet.", e)
            }
        }
        throw SunoException("Demasiadas redirecciones al resolver el enlace.")
    }

    fun fetchClip(id: String): SongInfo {
        val req = Request.Builder()
            .url("$API/api/clip/$id")
            .header("Accept", "application/json")
            .get().build()
        val body = try {
            http.newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 -> throw SunoException("La canción no existe o no es pública.")
                    resp.code == 401 || resp.code == 403 ->
                        throw SunoException("Suno no permite ver esta canción sin iniciar sesión (privada o restringida).")
                    resp.code == 429 -> throw SunoException("Suno está limitando las peticiones. Inténtalo de nuevo en unos minutos.")
                    !resp.isSuccessful -> throw SunoException("Suno respondió con un error (HTTP ${resp.code}).")
                }
                resp.body?.string() ?: throw SunoException("Respuesta vacía de Suno.")
            }
        } catch (e: IOException) {
            throw SunoException("Sin conexión con Suno. Revisa tu conexión a Internet.", e)
        }
        return parseClip(body, id)
    }

    companion object {
        const val API = "https://studio-api.prod.suno.com"

        fun parseClip(json: String, expectedId: String? = null): SongInfo {
            val o = try { JSONObject(json) } catch (e: Exception) {
                throw SunoException("Formato de respuesta de Suno no reconocido.", e)
            }
            val id = o.optStringOrNull("id") ?: expectedId ?: throw SunoException("La respuesta no contiene el ID de la canción.")
            val status = o.optStringOrNull("status")
            if (status != null && status != "complete" && status != "streaming") {
                throw SunoException("La canción aún no está lista en Suno (estado: $status).")
            }
            val meta = o.optJSONObject("metadata")
            return SongInfo(
                id = id,
                title = o.optStringOrNull("title")?.trim()?.ifEmpty { null } ?: "Sin título",
                artist = o.optStringOrNull("display_name") ?: o.optStringOrNull("handle") ?: "Desconocido",
                handle = o.optStringOrNull("handle"),
                coverUrl = o.optStringOrNull("image_large_url") ?: o.optStringOrNull("image_url"),
                durationSec = meta?.optDouble("duration")?.takeIf { !it.isNaN() && it > 0 },
                lyrics = meta?.optStringOrNull("prompt")?.trim()?.ifEmpty { null },
                tags = o.optStringOrNull("display_tags") ?: meta?.optStringOrNull("tags"),
                modelVersion = o.optStringOrNull("major_model_version"),
                isPublic = o.optBoolean("is_public", true),
                sources = buildSources(o, id),
                onlyEncryptedStream = o.optJSONArray("media_urls")?.length()?.let { it > 0 } == true,
            )
        }

        private fun buildSources(o: JSONObject, id: String): List<AudioSource> {
            val list = mutableListOf<AudioSource>()
            o.optStringOrNull("audio_url")?.takeIf { isDownloadableAudioUrl(it) }?.let {
                list += AudioSource(SourceKind.ORIGINAL_MP3, it)
            }
            val video = o.optStringOrNull("video_url")?.takeIf { it.startsWith("https://") }
                ?: "https://cdn1.suno.ai/$id.mp4"
            list += AudioSource(SourceKind.VIDEO_AAC, video)
            return list
        }

        /** Una URL de audio es descargable si es HTTPS y no es el marcador `/api/forbidden` de Suno. */
        fun isDownloadableAudioUrl(url: String): Boolean {
            if (!url.startsWith("https://")) return false
            val path = runCatching { java.net.URI(url).path }.getOrNull() ?: return false
            return !path.contains("forbidden", ignoreCase = true)
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() }
    }
}

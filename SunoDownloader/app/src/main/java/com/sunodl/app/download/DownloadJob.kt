package com.sunodl.app.download

import com.sunodl.app.suno.SongInfo
import org.json.JSONObject
import java.io.File

/**
 * Descripción de un trabajo de descarga/conversión. Se guarda en un JSON en caché porque la
 * letra puede superar el límite de 10 KB de los datos de entrada de WorkManager.
 */
data class DownloadJob(
    val title: String,
    val artist: String,
    val lyrics: String?,
    val tags: String?,
    val coverUrl: String?,
    val pageUrl: String?,
    /** URL remota a descargar, o null si se convierte un archivo importado. */
    val sourceUrl: String?,
    /** "mp3", "video" o "import". */
    val sourceKind: String,
    /** Uri (content://) del archivo importado. */
    val importUri: String?,
    val keepOriginal: Boolean,
    val toMp3: Boolean,
    val toWav: Boolean,
) {
    fun toJson(): String = JSONObject().apply {
        put("title", title); put("artist", artist); put("lyrics", lyrics); put("tags", tags)
        put("coverUrl", coverUrl); put("pageUrl", pageUrl); put("sourceUrl", sourceUrl)
        put("sourceKind", sourceKind); put("importUri", importUri)
        put("keepOriginal", keepOriginal); put("toMp3", toMp3); put("toWav", toWav)
    }.toString()

    fun writeTo(file: File) { file.parentFile?.mkdirs(); file.writeText(toJson()) }

    companion object {
        fun read(file: File): DownloadJob {
            val o = JSONObject(file.readText())
            fun s(k: String) = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotEmpty() }
            return DownloadJob(
                title = s("title") ?: "Sin título", artist = s("artist") ?: "Desconocido",
                lyrics = s("lyrics"), tags = s("tags"), coverUrl = s("coverUrl"), pageUrl = s("pageUrl"),
                sourceUrl = s("sourceUrl"), sourceKind = s("sourceKind") ?: "mp3", importUri = s("importUri"),
                keepOriginal = o.optBoolean("keepOriginal"), toMp3 = o.optBoolean("toMp3"), toWav = o.optBoolean("toWav"),
            )
        }

        fun forSong(song: SongInfo?, sourceUrl: String?, sourceKind: String, importUri: String?,
                    keepOriginal: Boolean, toMp3: Boolean, toWav: Boolean) = DownloadJob(
            title = song?.title ?: "Sin título", artist = song?.artist ?: "Desconocido",
            lyrics = song?.lyrics, tags = song?.tags, coverUrl = song?.coverUrl, pageUrl = song?.pageUrl,
            sourceUrl = sourceUrl, sourceKind = sourceKind, importUri = importUri,
            keepOriginal = keepOriginal, toMp3 = toMp3, toWav = toWav,
        )
    }
}

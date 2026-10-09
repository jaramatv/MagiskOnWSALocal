package com.sunodl.app.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/** Guarda archivos en la carpeta pública Música/Suno (MediaStore en Android 10+, ruta directa antes). */
object MusicSaver {
    const val FOLDER = "Suno"
    val relativePath: String get() = "${Environment.DIRECTORY_MUSIC}/$FOLDER"

    /** Carpeta pública (relativa) de cada tipo: audio → Music/Suno, vídeo → Movies/Suno, imagen → Pictures/Suno, resto → Download/Suno. */
    fun folderFor(mime: String): String = "${baseDir(mime)}/$FOLDER"

    private fun baseDir(mime: String): String = when {
        mime.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
        mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
        mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
        else -> Environment.DIRECTORY_DOWNLOADS
    }

    fun safeName(s: String): String =
        s.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").replace(Regex("\\s+"), " ").trim().trimEnd('.').take(120).ifEmpty { "Suno" }

    /** Copia [source] (con [header] opcional delante, p. ej. una etiqueta ID3) y devuelve su Uri. */
    fun save(context: Context, displayName: String, mime: String, source: File, header: ByteArray? = null, skipSourceBytes: Long = 0): Uri =
        write(context, displayName, mime) { out ->
            header?.let { out.write(it) }
            source.inputStream().use { input ->
                var toSkip = skipSourceBytes
                while (toSkip > 0) { val s = input.skip(toSkip); if (s <= 0) break; toSkip -= s }
                input.copyTo(out, 256 * 1024)
            }
        }

    fun write(context: Context, displayName: String, mime: String, block: (OutputStream) -> Unit): Uri {
        return if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val vol = MediaStore.VOLUME_EXTERNAL_PRIMARY
            val collection = when {
                mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(vol)
                mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(vol)
                mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(vol)
                else -> MediaStore.Downloads.getContentUri(vol)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folderFor(mime))
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: throw IllegalStateException("No se pudo crear el archivo en Música/Suno.")
            try {
                resolver.openOutputStream(uri)?.use(block) ?: throw IllegalStateException("No se pudo abrir el archivo para escribir.")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val base = Environment.getExternalStoragePublicDirectory(baseDir(mime))
            val dir = File(base, FOLDER).apply { mkdirs() }
            var file = File(dir, displayName)
            var i = 2
            while (file.exists()) {
                file = File(dir, displayName.substringBeforeLast('.') + " ($i)." + displayName.substringAfterLast('.'))
                i++
            }
            file.outputStream().use(block)
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
            Uri.fromFile(file)
        }
    }
}

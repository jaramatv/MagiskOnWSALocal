package com.sunodl.app.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sunodl.app.Http
import com.sunodl.app.R
import com.sunodl.app.audio.AudioTrackExtractor
import com.sunodl.app.audio.Id3v2
import com.sunodl.app.audio.Mp3Encoder
import com.sunodl.app.audio.PcmDecoder
import com.sunodl.app.audio.Tags
import com.sunodl.app.audio.WavWriter
import com.sunodl.app.storage.MusicSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext

/** Descarga y convierte en segundo plano, mostrando el progreso en una notificación. */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val notificationId = id.hashCode()
    private var lastStage = ""

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobFile = File(inputData.getString(KEY_JOB) ?: return@withContext Result.failure(error("Trabajo inválido.")))
        val work = File(applicationContext.cacheDir, "work/$id").apply { mkdirs() }
        try {
            val job = DownloadJob.read(jobFile)
            runCatching { setForeground(foregroundInfo(job.title, "Preparando…", 0f)) }
            val saved = process(job, work)
            Result.success(workDataOf(KEY_SAVED to saved.joinToString("\n")))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(error(friendly(e)))
        } finally {
            work.deleteRecursively()
            jobFile.delete()
        }
    }

    private suspend fun process(job: DownloadJob, work: File): List<String> {
        // 1) Obtener el archivo de entrada.
        val raw = File(work, "input")
        if (job.importUri != null) {
            progress("Leyendo archivo importado…", 0.02f)
            applicationContext.contentResolver.openInputStream(Uri.parse(job.importUri))?.use { input ->
                raw.outputStream().use { input.copyTo(it) }
            } ?: throw IOException("No se pudo leer el archivo seleccionado.")
        } else {
            download(job.sourceUrl ?: throw IllegalStateException("No hay fuente de audio."), raw, 0f, 0.55f)
        }

        // 2) Dejar el audio en su formato original sin recodificar.
        val base: File
        val baseMime: String
        if (job.sourceKind == "video") {
            progress("Extrayendo la pista de audio del vídeo (sin recodificar)…", 0.57f)
            base = File(work, "audio.m4a")
            AudioTrackExtractor.extractAudio(raw, base)
            baseMime = "audio/mp4a-latm"
        } else {
            base = raw
            baseMime = detectMime(raw)
        }
        val isMp3 = baseMime == "audio/mpeg"

        // 3) Portada (opcional).
        val cover = job.coverUrl?.let { url ->
            progress("Descargando portada…", 0.6f)
            runCatching { Http.client.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null } }.getOrNull()
        }
        val tags = Tags(
            title = job.title, artist = job.artist, lyrics = job.lyrics, cover = cover,
            comment = "Descargado de Suno con Suno Downloader", url = job.pageUrl, genre = job.tags,
        )
        val name = MusicSaver.safeName("${job.title} - ${job.artist}")
        val saved = mutableListOf<String>()

        // 4) Original.
        if (job.keepOriginal || (job.toMp3 && isMp3)) {
            progress("Guardando archivo original…", 0.65f)
            if (isMp3) {
                val head = base.inputStream().use { s -> ByteArray(10).also { s.read(it) } }
                val skip = Id3v2.existingTagSize(head).toLong()
                MusicSaver.save(applicationContext, "$name.mp3", "audio/mpeg", base, Id3v2.build(tags), skip)
                saved += "$name.mp3"
            } else {
                val ext = extensionFor(baseMime)
                MusicSaver.save(applicationContext, "$name.$ext", mimeForFile(ext), base)
                saved += "$name.$ext"
            }
        }

        // 5) Conversiones locales (una sola decodificación para MP3 y WAV).
        val needMp3 = job.toMp3 && !isMp3
        if (needMp3 || job.toWav) {
            val mp3Tmp = File(work, "out.mp3")
            val wavTmp = File(work, "out.wav")
            var mp3: Mp3Encoder? = null
            var mp3Out: java.io.OutputStream? = null
            var wav: WavWriter? = null
            val label = listOfNotNull(if (needMp3) "MP3" else null, if (job.toWav) "WAV" else null).joinToString(" y ")
            try {
                PcmDecoder(base).decode(
                    onFormat = { f ->
                        if (needMp3) {
                            mp3Out = mp3Tmp.outputStream().buffered()
                            mp3 = Mp3Encoder(f.sampleRate, f.channels, MP3_KBPS, mp3Out!!)
                        }
                        if (job.toWav) wav = WavWriter(wavTmp, f.sampleRate, f.channels)
                    },
                    onPcm = { b, n -> mp3?.write(b, n); wav?.write(b, n) },
                    onProgress = { p -> progressBlocking("Convirtiendo a $label en el teléfono…", 0.67f + 0.28f * p) },
                )
                mp3?.finish(); mp3Out?.close()
                wav?.finish(tags)
            } finally {
                runCatching { mp3Out?.close() }
                runCatching { wav?.close() }
            }
            currentCoroutineContext().ensureActive()
            if (needMp3) {
                progress("Guardando MP3…", 0.96f)
                MusicSaver.save(applicationContext, "$name.mp3", "audio/mpeg", mp3Tmp, Id3v2.build(tags))
                saved += "$name.mp3"
            }
            if (job.toWav) {
                progress("Guardando WAV…", 0.98f)
                val wavName = "$name (WAV convertido).wav"
                MusicSaver.save(applicationContext, wavName, "audio/wav", wavTmp)
                saved += wavName
            }
        }
        progress("Completado", 1f)
        return saved
    }

    private suspend fun download(url: String, dest: File, from: Float, to: Float) {
        progress("Conectando con Suno…", from)
        val req = Request.Builder().url(url).get().build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpError(resp.code)
            val body = resp.body ?: throw IOException("Respuesta vacía")
            val total = body.contentLength()
            var read = 0L
            var lastReport = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (read - lastReport > 128 * 1024) {
                            lastReport = read
                            val frac = if (total > 0) read.toFloat() / total else 0f
                            val mb = "%.1f".format(read / 1048576f)
                            val totalMb = if (total > 0) " de %.1f MB".format(total / 1048576f) else " MB"
                            progress("Descargando… $mb$totalMb", from + (to - from) * frac)
                        }
                    }
                }
            }
            if (total > 0 && read < total) throw IOException("La descarga se interrumpió.")
        }
    }

    private fun detectMime(file: File): String {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) ?: "" }
                .firstOrNull { it.startsWith("audio/") } ?: throw IllegalStateException("El archivo no contiene audio reconocible.")
        } catch (e: IOException) {
            throw IllegalStateException("El archivo no es un audio válido o está cifrado.", e)
        } finally { ex.release() }
    }

    private fun extensionFor(mime: String) = when (mime) {
        "audio/mpeg" -> "mp3"; "audio/raw" -> "wav"; "audio/flac" -> "flac"; "audio/vorbis", "audio/ogg" -> "ogg"
        "audio/opus" -> "m4a"; else -> "m4a"
    }

    private fun mimeForFile(ext: String) = when (ext) {
        "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "flac" -> "audio/flac"; "ogg" -> "audio/ogg"; else -> "audio/mp4"
    }

    private suspend fun progress(stage: String, p: Float) {
        setProgress(workDataOf(KEY_STAGE to stage, KEY_PROGRESS to p))
        if (stage != lastStage || p >= 1f) {
            lastStage = stage
            runCatching { setForeground(foregroundInfo(null, stage, p)) }
        }
    }

    private var lastBlocking = -1
    private fun progressBlocking(stage: String, p: Float) {
        val pct = (p * 100).toInt()
        if (pct == lastBlocking) return
        lastBlocking = pct
        setProgressAsync(workDataOf(KEY_STAGE to stage, KEY_PROGRESS to p))
        runCatching { setForegroundAsync(foregroundInfo(null, stage, p)) }
    }

    private var notifTitle = "Suno Downloader"
    private fun foregroundInfo(title: String?, text: String, p: Float): ForegroundInfo {
        if (title != null) notifTitle = title
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Descargas", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(notifTitle)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, (p * 100).toInt(), false)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notificationId, n)
    }

    private class HttpError(val code: Int) : IOException("HTTP $code")

    private fun friendly(e: Exception): String = when (e) {
        is HttpError -> when (e.code) {
            403 -> "Suno ha denegado el acceso al archivo (HTTP 403). Puede que ya no sea público."
            404 -> "El archivo ya no existe en Suno (HTTP 404)."
            429 -> "Suno está limitando las descargas. Inténtalo más tarde."
            else -> "Error del servidor de Suno (HTTP ${e.code})."
        }
        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException ->
            "Sin conexión a Internet o Suno no responde."
        is IOException -> e.message?.let { "Error de lectura/escritura: $it" } ?: "Error de red."
        is SecurityException -> "Sin permiso para guardar en Música/Suno."
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun error(msg: String): Data = workDataOf(KEY_ERROR to msg)

    companion object {
        const val KEY_JOB = "job"
        const val KEY_STAGE = "stage"
        const val KEY_PROGRESS = "progress"
        const val KEY_SAVED = "saved"
        const val KEY_ERROR = "error"
        const val CHANNEL = "downloads"
        const val TAG = "suno-download"
        const val MP3_KBPS = 256
    }
}

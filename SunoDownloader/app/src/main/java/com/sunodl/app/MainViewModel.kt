package com.sunodl.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.sunodl.app.download.DownloadJob
import com.sunodl.app.download.DownloadWorker
import com.sunodl.app.suno.SongInfo
import com.sunodl.app.suno.SourceKind
import com.sunodl.app.suno.SunoClient
import com.sunodl.app.suno.SunoException
import com.sunodl.app.suno.SunoLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.UUID

data class DownloadState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val stage: String = "",
    val saved: List<String> = emptyList(),
    val error: String? = null,
)

data class UiState(
    val input: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val song: SongInfo? = null,
    val cover: Bitmap? = null,
    val keepOriginal: Boolean = true,
    val toMp3: Boolean = false,
    val toWav: Boolean = false,
    val download: DownloadState = DownloadState(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val client = SunoClient()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var lookup: Job? = null
    private val workManager = WorkManager.getInstance(app)

    /** true cuando hay una descarga lanzada en esta sesión cuyo resultado interesa mostrar. */
    private var tracking = false

    init {
        // Si la app se cerró durante una descarga, recupera su progreso.
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK).collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                if (!info.state.isFinished) tracking = true
                if (tracking) onWorkInfo(info)
            }
        }
    }

    fun onInput(text: String) = _state.update { it.copy(input = text, error = null) }

    fun onSharedText(text: String) {
        _state.update { it.copy(input = text.trim()) }
        lookupLink()
    }

    fun lookupLink() {
        val text = _state.value.input
        val link = SunoLink.parse(text)
        if (link == null) {
            _state.update { it.copy(error = if (text.isBlank()) "Pega un enlace de Suno." else "No es un enlace válido de Suno. Ejemplos: https://suno.com/song/… o https://suno.com/s/…") }
            return
        }
        lookup?.cancel()
        lookup = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, song = null, cover = null, download = DownloadState()) }
            try {
                val song = client.resolve(link)
                val src = song.bestSource?.kind
                _state.update {
                    it.copy(loading = false, song = song,
                        keepOriginal = true,
                        toMp3 = src == SourceKind.VIDEO_AAC,
                        toWav = false)
                }
                song.coverUrl?.let { url -> loadCover(url) }
            } catch (e: SunoException) {
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Error inesperado: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private suspend fun loadCover(url: String) {
        val bmp = withContext(Dispatchers.IO) {
            runCatching {
                Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }
            }.getOrNull()
        }
        _state.update { if (it.song?.coverUrl == url) it.copy(cover = bmp) else it }
    }

    fun setKeepOriginal(v: Boolean) = _state.update { it.copy(keepOriginal = v) }
    fun setMp3(v: Boolean) = _state.update { it.copy(toMp3 = v) }
    fun setWav(v: Boolean) = _state.update { it.copy(toWav = v) }

    fun startDownload() {
        val s = _state.value
        val song = s.song ?: return
        val source = song.bestSource ?: return
        enqueue(DownloadJob.forSong(song, source.url,
            if (source.kind == SourceKind.ORIGINAL_MP3) "mp3" else "video", null,
            s.keepOriginal, s.toMp3, s.toWav))
    }

    /** Convierte/etiqueta un archivo que el usuario descargó oficialmente desde Suno. */
    fun importFile(uri: Uri) {
        val s = _state.value
        enqueue(DownloadJob.forSong(s.song, null, "import", uri.toString(),
            keepOriginal = s.keepOriginal, toMp3 = s.toMp3, toWav = s.toWav))
    }

    private fun enqueue(job: DownloadJob) {
        if (!job.keepOriginal && !job.toMp3 && !job.toWav) {
            _state.update { it.copy(download = DownloadState(error = "Elige al menos un formato.")) }
            return
        }
        val file = File(getApplication<Application>().cacheDir, "jobs/${UUID.randomUUID()}.json")
        job.writeTo(file)
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_JOB to file.absolutePath))
            .addTag(DownloadWorker.TAG)
            .build()
        _state.update { it.copy(download = DownloadState(running = true, stage = "En cola…")) }
        tracking = true
        workManager.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, req)
    }

    fun cancelDownload() {
        workManager.cancelUniqueWork(UNIQUE_WORK)
        _state.update { it.copy(download = DownloadState(error = "Descarga cancelada.")) }
    }

    private fun onWorkInfo(info: WorkInfo) {
        val d = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState(running = true, stage = "En cola…")
            WorkInfo.State.RUNNING -> DownloadState(
                running = true,
                progress = info.progress.getFloat(DownloadWorker.KEY_PROGRESS, 0f),
                stage = info.progress.getString(DownloadWorker.KEY_STAGE) ?: "Iniciando…")
            WorkInfo.State.SUCCEEDED -> DownloadState(progress = 1f, stage = "Completado",
                saved = info.outputData.getString(DownloadWorker.KEY_SAVED)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList())
            WorkInfo.State.FAILED -> DownloadState(error = info.outputData.getString(DownloadWorker.KEY_ERROR) ?: "La descarga falló.")
            WorkInfo.State.CANCELLED -> DownloadState(error = "Descarga cancelada.")
        }
        _state.update { it.copy(download = d) }
        if (info.state.isFinished) tracking = false
    }

    companion object {
        const val UNIQUE_WORK = "download"
    }
}

package com.sunodl.app

import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.provider.MediaStore
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.sunodl.app.download.DownloadJob
import com.sunodl.app.download.DownloadWorker
import com.sunodl.app.suno.SourceKind
import com.sunodl.app.suno.SunoClient
import com.sunodl.app.suno.SunoLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Pruebas contra Suno real (requieren red). No escriben la letra en el log.
 * Argumentos opcionales: -e shortLink <url> -e videoSongId <uuid>
 */
@RunWith(AndroidJUnit4::class)
class LiveSunoTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val args = InstrumentationRegistry.getArguments()
    private val shortLink = args.getString("shortLink") ?: "https://suno.com/s/XceKbVQ7EZaCje85"
    private val videoSongId = args.getString("videoSongId") ?: "057fa44a-a2c2-4089-8fca-52a637117f00"

    @Test fun resolvesShortLinkAndMetadata() = runBlocking {
        val link = SunoLink.parse("Mira esta canción: $shortLink")
        assertTrue(link is SunoLink.Short)
        val song = SunoClient().resolve(link!!)
        Log.i(TAG, "short link -> id=${song.id} title='${song.title}' artist='${song.artist}' dur=${song.durationSec} " +
            "cover=${song.coverUrl} lyricsChars=${song.lyrics?.length ?: 0} sources=${song.sources.map { it.kind }} encryptedOnly=${song.onlyEncryptedStream}")
        assertEquals(36, song.id.length)
        assertTrue(song.title.isNotBlank())
        assertTrue(song.artist.isNotBlank())
        assertNotNull(song.coverUrl)
        assertTrue((song.durationSec ?: 0.0) > 10)
    }

    @Test fun fullPipelineFromPublicVideo() {
        val song = runBlocking { SunoClient().resolve(SunoLink.Song(videoSongId)) }
        val src = song.bestSource
        Log.i(TAG, "video song -> title='${song.title}' sources=${song.sources.map { it.kind }}")
        assertNotNull("La canción de prueba ya no tiene vídeo público", src)
        assertEquals(SourceKind.VIDEO_AAC, src!!.kind)

        val jobFile = File(ctx.cacheDir, "jobs/test.json")
        DownloadJob.forSong(song, src.url, "video", null, keepOriginal = true, toMp3 = true, toWav = true).writeTo(jobFile)
        val worker = TestListenableWorkerBuilder<DownloadWorker>(ctx)
            .setInputData(workDataOf(DownloadWorker.KEY_JOB to jobFile.absolutePath)).build()
        val t0 = System.currentTimeMillis()
        val result = runBlocking { worker.doWork() }
        Log.i(TAG, "worker result=$result in ${(System.currentTimeMillis() - t0) / 1000}s")
        assertTrue("Fallo: ${result.outputData.getString(DownloadWorker.KEY_ERROR)}", result is ListenableWorker.Result.Success)
        val saved = result.outputData.getString(DownloadWorker.KEY_SAVED)!!.split("\n")
        assertEquals(3, saved.size)

        // Comprobar los archivos en Música/Suno a través de MediaStore.
        val found = mutableMapOf<String, android.net.Uri>()
        ctx.contentResolver.query(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.SIZE),
            null, null, null)!!.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                Log.i(TAG, "MediaStore: ${c.getString(2)}$name (${c.getLong(3)} bytes)")
                if (c.getString(2).startsWith("Music/Suno") && name in saved)
                    found[name] = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), c.getLong(0))
            }
        }
        assertEquals(saved.toSet(), found.keys)
        for ((name, uri) in found) {
            val ex = MediaExtractor()
            ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { ex.setDataSource(it.fileDescriptor) }
            val f = ex.getTrackFormat(0)
            val mime = f.getString(MediaFormat.KEY_MIME)
            val durUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else -1
            Log.i(TAG, "$name -> $mime ${f.getInteger(MediaFormat.KEY_SAMPLE_RATE)} Hz ${f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)} ch dur=${durUs / 1000000.0}s")
            ex.release()
            when {
                name.endsWith(".m4a") -> assertEquals("audio/mp4a-latm", mime)
                name.endsWith(".mp3") -> assertEquals("audio/mpeg", mime)
                name.endsWith(".wav") -> assertEquals("audio/raw", mime)
            }
            if (durUs > 0) assertEquals(song.durationSec!!, durUs / 1e6, 3.0)
            if (name.endsWith(".mp3")) {
                val head = ctx.contentResolver.openInputStream(uri)!!.use { s -> ByteArray(4096).also { s.read(it) } }
                assertTrue("MP3 sin etiqueta ID3", String(head, 0, 3, Charsets.ISO_8859_1) == "ID3")
            }
        }
        found.values.forEach { ctx.contentResolver.delete(it, null, null) }
    }

    companion object { const val TAG = "SunoLiveTest" }
}

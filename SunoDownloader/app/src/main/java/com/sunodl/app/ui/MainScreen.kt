package com.sunodl.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sunodl.app.MainViewModel
import com.sunodl.app.R
import com.sunodl.app.UiState
import com.sunodl.app.storage.MusicSaver
import com.sunodl.app.suno.SongInfo
import com.sunodl.app.suno.SourceKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: UiState, vm: MainViewModel, onPickFile: () -> Unit, onBeforeDownload: () -> Unit) {
    val ctx = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.mipmap.ic_launcher_round), null, Modifier.size(32.dp).clip(RoundedCornerShape(50)))
                        Spacer(Modifier.width(10.dp))
                        Text("Suno Downloader", fontWeight = FontWeight.Bold)
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = state.input,
                onValueChange = vm::onInput,
                label = { Text("Enlace de Suno") },
                placeholder = { Text("https://suno.com/s/…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.lookupLink() }),
                isError = state.error != null,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                    if (text.isNullOrBlank()) Toast.makeText(ctx, "El portapapeles está vacío", Toast.LENGTH_SHORT).show()
                    else vm.onSharedText(text)
                }, modifier = Modifier.weight(1f)) { Text("Pegar") }
                Button(onClick = vm::lookupLink, enabled = !state.loading, modifier = Modifier.weight(1f)) { Text("Buscar") }
            }

            if (state.loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp)); Text("Consultando Suno…")
                }
            }
            state.error?.let { ErrorCard(it) }
            state.song?.let { song ->
                SongCard(song, state)
                if (song.bestSource != null) DownloadSection(song, state, vm, onBeforeDownload)
                else UnavailableCard(song, ctx)
                ImportCard(state, vm, onPickFile)
                song.lyrics?.let { LyricsCard(it, ctx) }
            }
            if (state.song == null && !state.loading) HelpCard(state, vm, onPickFile)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ErrorCard(msg: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(msg, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun SongCard(song: SongInfo, state: UiState) {
    Card {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                state.cover?.let { Image(it.asImageBitmap(), "Portada", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(song.artist + (song.handle?.let { "  ·  @$it" } ?: ""), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                val meta = listOfNotNull(song.durationSec?.let { formatDuration(it) }, song.modelVersion?.uppercase())
                Text(meta.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall)
                song.tags?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun DownloadSection(song: SongInfo, state: UiState, vm: MainViewModel, onBeforeDownload: () -> Unit) {
    val src = song.bestSource!!
    val d = state.download
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Formatos", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Fuente: ${src.kind.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val isMp3 = src.kind == SourceKind.ORIGINAL_MP3
            CheckRow(state.keepOriginal, vm::setKeepOriginal, !d.running,
                if (isMp3) "MP3 original" else "Original (.m4a, AAC)",
                "Tal cual lo sirve Suno, sin recodificar.")
            if (!isMp3) CheckRow(state.toMp3, vm::setMp3, !d.running, "MP3 (convertido)",
                "Recodificado en el teléfono a ${com.sunodl.app.download.DownloadWorker.MP3_KBPS} kbps. No mejora la calidad del original.")
            CheckRow(state.toWav, vm::setWav, !d.running, "WAV (convertido)",
                "PCM 16 bits descomprimido desde el ${if (isMp3) "MP3" else "AAC"}. Ocupa más, pero NO es un máster sin pérdida.")
            Spacer(Modifier.height(6.dp))
            DownloadProgress(d, vm) { onBeforeDownload(); vm.startDownload() }
        }
    }
}

@Composable
private fun DownloadProgress(d: com.sunodl.app.DownloadState, vm: MainViewModel, onStart: () -> Unit) {
    if (d.running) {
        LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
        Text("${(d.progress * 100).toInt()} %  ·  ${d.stage}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = vm::cancelDownload) { Text("Cancelar") }
    } else {
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Descargar") }
    }
    d.error?.let { ErrorCard(it) }
    if (d.saved.isNotEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f))) {
            Column(Modifier.padding(12.dp)) {
                Text("Guardado en ${MusicSaver.relativePath.replace("Music", "Música")}:", fontWeight = FontWeight.Bold)
                d.saved.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun UnavailableCard(song: SongInfo, ctx: Context) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Audio no descargable sin cuenta", fontWeight = FontWeight.Bold)
            Text(
                "Suno no ofrece un archivo de audio público para esta canción: el MP3 está bloqueado (audio_url = forbidden)" +
                    (if (song.onlyEncryptedStream) ", el reproductor web usa un flujo cifrado" else "") +
                    " y no hay vídeo público para compartir.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Esta app no descifra el flujo protegido ni usa servidores de terceros. Si es tu canción, descárgala desde " +
                    "la app de Suno con tu cuenta (o genera su vídeo para compartir) y usa «Importar archivo» para etiquetarla y convertirla.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(song.pageUrl)))
            }) { Text("Abrir en Suno") }
        }
    }
}

@Composable
private fun ImportCard(state: UiState, vm: MainViewModel, onPickFile: () -> Unit) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Importar archivo descargado de Suno", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Aplica título, artista, portada y letra de esta canción a un MP3/M4A/WAV que tengas y lo guarda en Música/Suno con los formatos marcados.",
                style = MaterialTheme.typography.bodySmall)
            if (state.song?.bestSource == null) {
                CheckRow(state.keepOriginal, vm::setKeepOriginal, !state.download.running, "Guardar copia con metadatos", null)
                CheckRow(state.toMp3, vm::setMp3, !state.download.running, "MP3 (si no lo es ya)", null)
                CheckRow(state.toWav, vm::setWav, !state.download.running, "WAV (convertido)", null)
            }
            OutlinedButton(onClick = onPickFile, enabled = !state.download.running) { Text("Elegir archivo…") }
            if (state.song?.bestSource == null) DownloadStatusOnly(state.download)
        }
    }
}

@Composable
private fun DownloadStatusOnly(d: com.sunodl.app.DownloadState) {
    if (d.running) {
        LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
        Text("${(d.progress * 100).toInt()} %  ·  ${d.stage}", style = MaterialTheme.typography.bodySmall)
    }
    d.error?.let { ErrorCard(it) }
    if (d.saved.isNotEmpty()) {
        Text("Guardado en Música/Suno:", fontWeight = FontWeight.Bold)
        d.saved.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun LyricsCard(lyrics: String, ctx: Context) {
    var open by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text("Letra", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Letra", lyrics))
                    Toast.makeText(ctx, "Letra copiada", Toast.LENGTH_SHORT).show()
                }) { Text("Copiar") }
                Text(if (open) "▲" else "▼")
            }
            if (open) Text(lyrics, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HelpCard(state: UiState, vm: MainViewModel, onPickFile: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Cómo usarla", fontWeight = FontWeight.Bold)
            Text("1. En Suno, pulsa Compartir → Copiar enlace (o compártelo directamente con esta app).", style = MaterialTheme.typography.bodySmall)
            Text("2. Pega el enlace y pulsa Buscar.", style = MaterialTheme.typography.bodySmall)
            Text("3. Elige formatos y pulsa Descargar. Los archivos se guardan en Música/Suno.", style = MaterialTheme.typography.bodySmall)
            Text("Sin anuncios, sin cuentas y sin servidores intermedios: la app habla directamente con Suno.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean, title: String, subtitle: String?) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, enabled = enabled)
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun formatDuration(sec: Double): String {
    val s = sec.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

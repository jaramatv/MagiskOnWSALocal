package com.sunodl.app.web

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.MimeTypeMap
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import com.sunodl.app.storage.MusicSaver
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Recibe, por mensajes y en trozos base64, los archivos que la página de UseSuno genera en el
 * navegador (URLs blob:) y los guarda en Música/Suno.
 *
 * Seguridad: se registra con addWebMessageListener limitado al origen https://usesuno.com, así que
 * ninguna otra página (anuncios, iframes de terceros) puede usarlo. Lo único que permite es
 * guardar un archivo en las carpetas públicas de medios; no expone ningún otro método nativo.
 */
class WebSaveBridge(
    private val context: Context,
    private val onEvent: (String) -> Unit,
) : WebViewCompat.WebMessageListener {

    private class Pending(val file: File, val out: FileOutputStream, val name: String, val mime: String, val size: Long) {
        var written = 0L
    }

    private val pending = HashMap<String, Pending>()
    private val main = Handler(Looper.getMainLooper())

    fun attach(webView: WebView) {
        WebViewCompat.addWebMessageListener(webView, JS_OBJECT, ALLOWED_ORIGINS, this)
    }

    override fun onPostMessage(view: WebView, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, replyProxy: JavaScriptReplyProxy) {
        if (!isMainFrame) return
        val o = runCatching { JSONObject(message.data ?: return) }.getOrNull() ?: return
        val id = o.optString("id").take(64).ifEmpty { return }
        when (o.optString("t")) {
            "begin" -> begin(id, o.optString("name"), o.optString("mime"), o.optLong("size"))
            "chunk" -> chunk(id, o.optString("data"))
            "end" -> end(id)
            "fail" -> fail(id, o.optString("error"))
        }
    }

    private fun begin(id: String, name: String?, mime: String?, size: Long) {
        pending.remove(id)?.let { it.out.close(); it.file.delete() }
        val type = mime?.substringBefore(';')?.trim()?.ifEmpty { null } ?: guessMime(name) ?: "application/octet-stream"
        val fileName = fileName(name, type)
        val tmp = File(context.cacheDir, "web/${pending.size}-${System.nanoTime()}.part").apply { parentFile?.mkdirs() }
        pending[id] = Pending(tmp, FileOutputStream(tmp), fileName, type, size)
        onEvent("Recibiendo $fileName…")
    }

    private fun chunk(id: String, base64: String) {
        val p = pending[id] ?: return
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        p.out.write(bytes)
        p.written += bytes.size
        if (p.size > 0) onEvent("Recibiendo ${p.name}… ${p.written * 100 / p.size} %")
    }

    private fun end(id: String) {
        val p = pending.remove(id) ?: return
        p.out.close()
        Thread {
            val msg = try {
                MusicSaver.save(context, p.name, p.mime, p.file)
                "Guardado en ${MusicSaver.folderFor(p.mime)}: ${p.name}"
            } catch (e: Exception) {
                "No se pudo guardar ${p.name}: ${e.message}"
            } finally {
                p.file.delete()
            }
            main.post { onEvent(msg) }
        }.start()
    }

    private fun fail(id: String, message: String?) {
        pending.remove(id)?.let { it.out.close(); it.file.delete() }
        onEvent("Error al recibir el archivo: ${message ?: "desconocido"}")
    }

    private fun guessMime(name: String?): String? =
        name?.substringAfterLast('.', "")?.lowercase()?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }

    private fun fileName(name: String?, mime: String): String {
        val clean = name?.substringAfterLast('/')?.let { MusicSaver.safeName(it) }?.takeIf { it.isNotBlank() && it != "Suno" }
        if (clean != null && clean.contains('.')) return clean
        val ext = when (mime) {
            "audio/mpeg" -> "mp3"; "audio/wav", "audio/x-wav", "audio/wave" -> "wav"; "audio/mp4" -> "m4a"
            "audio/flac" -> "flac"; "video/mp4" -> "mp4"; "application/zip" -> "zip"; "image/jpeg" -> "jpg"; "image/png" -> "png"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        }
        return "${clean ?: "Suno ${System.currentTimeMillis()}"}.$ext"
    }

    companion object {
        const val JS_OBJECT = "SunoDL"
        val ALLOWED_ORIGINS = setOf("https://usesuno.com")

        /**
         * Script que se inyecta solo en https://usesuno.com: cuando la página intenta descargar un
         * archivo generado en el navegador (enlace blob:/data:), lo envía a la app en trozos.
         */
        const val HOOK_JS = """
(function(){
  if (window.__sdlHooked || !window.SunoDL) return; window.__sdlHooked = true;
  function send(o){ SunoDL.postMessage(JSON.stringify(o)); }
  function readChunk(blob){ return new Promise(function(res, rej){
    var r = new FileReader(); r.onload = function(){ var s = r.result; res(s.substring(s.indexOf(',') + 1)); };
    r.onerror = function(){ rej(r.error); }; r.readAsDataURL(blob); }); }
  window.__sdlSave = async function(url, name){
    var id = Date.now() + '-' + Math.random().toString(36).slice(2);
    try {
      var blob = await (await fetch(url)).blob();
      send({t: 'begin', id: id, name: name || '', mime: blob.type || '', size: blob.size});
      var CH = 512 * 1024;
      for (var o = 0; o < blob.size; o += CH) send({t: 'chunk', id: id, data: await readChunk(blob.slice(o, o + CH))});
      send({t: 'end', id: id});
    } catch (e) { send({t: 'fail', id: id, error: String(e)}); }
  };
  function isLocal(h){ return h && (h.indexOf('blob:') === 0 || h.indexOf('data:') === 0); }
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function(){
    if (isLocal(this.href)) { window.__sdlSave(this.href, this.download || this.getAttribute('download')); return; }
    return origClick.call(this);
  };
  document.addEventListener('click', function(e){
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (a && isLocal(a.href)) { e.preventDefault(); e.stopImmediatePropagation(); window.__sdlSave(a.href, a.download || a.getAttribute('download')); }
  }, true);
  var origRevoke = URL.revokeObjectURL.bind(URL);
  URL.revokeObjectURL = function(u){ setTimeout(function(){ origRevoke(u); }, 180000); };
})();
"""
    }
}

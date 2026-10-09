package com.sunodl.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.sunodl.app.Http
import com.sunodl.app.storage.MusicSaver
import com.sunodl.app.ui.SunoTheme
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Abre la herramienta de descarga de UseSuno dentro de la app, tal cual funciona en su web
 * (su verificación, su servidor y su conversión). La app solo rellena el enlace y guarda en
 * Música/Suno los archivos que la página genera. La navegación principal queda limitada a
 * usesuno.com; cualquier otro enlace se abre en el navegador del sistema.
 */
class UseSunoActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var status by mutableStateOf("")
    private var pageProgress by mutableIntStateOf(0)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent.getStringExtra(EXTRA_LINK)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Toast.makeText(this, "Actualiza «Android System WebView» desde Google Play para usar UseSuno.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContent {
            SunoTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("UseSuno") },
                            navigationIcon = { TextButton(onClick = { finish() }) { Text("Cerrar") } },
                            actions = { TextButton(onClick = { webView?.reload() }) { Text("Recargar") } },
                        )
                    },
                    bottomBar = {
                        if (status.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                            Text(status, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                ) { pad ->
                    Column(Modifier.padding(pad).fillMaxSize()) {
                        if (pageProgress in 1..99) LinearProgressIndicator(progress = { pageProgress / 100f }, modifier = Modifier.fillMaxWidth())
                        AndroidView(factory = { ctx -> createWebView(ctx, link) }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView?.canGoBack() == true) webView?.goBack() else finish()
            }
        })
    }

    private fun onBridgeEvent(msg: String) {
        status = msg
        if (msg.startsWith("Guardado") || msg.startsWith("No se pudo") || msg.startsWith("Error"))
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(ctx: Context, link: String?): WebView = WebView(ctx).apply {
        webView = this
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

        WebSaveBridge(applicationContext, ::onBridgeEvent).attach(this)
        val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStart) WebViewCompat.addDocumentStartJavaScript(this, WebSaveBridge.HOOK_JS, WebSaveBridge.ALLOWED_ORIGINS)

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { pageProgress = newProgress }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!docStart && isUseSuno(url)) view.evaluateJavascript(WebSaveBridge.HOOK_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (!isUseSuno(url)) return
                if (!docStart) view.evaluateJavascript(WebSaveBridge.HOOK_JS, null)
                if (link != null && url?.contains("/tools/downloader") == true) view.evaluateJavascript(fillJs(link), null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Solo la navegación principal dentro de usesuno.com se queda en la app.
                if (!request.isForMainFrame || isUseSuno(request.url.toString())) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }
        }
        setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            when {
                url.startsWith("blob:") || url.startsWith("data:") ->
                    if (isUseSuno(this.url)) evaluateJavascript("window.__sdlSave && window.__sdlSave(${JSONObject.quote(url)}, '')", null)
                url.startsWith("https://") -> downloadHttp(url, userAgent, contentDisposition, mimetype)
            }
        }
        loadUrl(START_URL)
    }

    private fun isUseSuno(url: String?): Boolean {
        val u = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        return u.scheme == "https" && (u.host == "usesuno.com" || u.host == "www.usesuno.com")
    }

    /** Descargas https normales que la página enlaza directamente (p. ej. el vídeo de Suno). */
    private fun downloadHttp(url: String, userAgent: String?, disposition: String?, mime: String?) {
        val name = MusicSaver.safeName(URLUtil.guessFileName(url, disposition, mime))
        status = "Descargando $name…"
        Thread {
            val msg = try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", userAgent ?: Http.USER_AGENT)
                    .apply { CookieManager.getInstance().getCookie(url)?.let { header("Cookie", it) } }
                    .build()
                val tmp = File(cacheDir, "web/http-${System.nanoTime()}.part").apply { parentFile?.mkdirs() }
                val type = Http.client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                    r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    r.header("Content-Type")?.substringBefore(';') ?: mime ?: "application/octet-stream"
                }
                try { MusicSaver.save(applicationContext, name, type, tmp) } finally { tmp.delete() }
                "Guardado en ${MusicSaver.folderFor(type)}: $name"
            } catch (e: Exception) {
                "No se pudo descargar $name: ${e.message}"
            }
            runOnUiThread { onBridgeEvent(msg) }
        }.start()
    }

    /** Rellena el campo del enlace de la página y envía el formulario. */
    private fun fillJs(link: String) = """
(function(){
  var tries = 0;
  (function fill(){
    var f = document.getElementById('dl-form');
    var i = f && (f.querySelector('input[type=url]') || f.querySelector('input[type=text]') || f.querySelector('input'));
    if (!i) { if (++tries < 40) setTimeout(fill, 250); return; }
    if (i.value) return;
    var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    setter.call(i, ${JSONObject.quote(link)});
    i.dispatchEvent(new Event('input', {bubbles: true}));
    i.dispatchEvent(new Event('change', {bubbles: true}));
    setTimeout(function(){ if (f.requestSubmit) f.requestSubmit(); else f.submit(); }, 300);
  })();
})();
"""

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        const val START_URL = "https://usesuno.com/es/tools/downloader/"
        const val EXTRA_LINK = "link"
        fun intent(ctx: Context, link: String?) = Intent(ctx, UseSunoActivity::class.java).putExtra(EXTRA_LINK, link)
    }
}

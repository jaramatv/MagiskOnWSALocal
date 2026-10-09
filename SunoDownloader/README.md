# Suno Downloader (Android)

Aplicación nativa para Android, en español, sin anuncios, sin cuentas y sin servidores propios,
que reconoce enlaces públicos de Suno, muestra sus datos y guarda el audio en **Música/Suno**
siempre que Suno lo publique sin protección.

- **APK:** [`release/SunoDownloader-1.0.0.apk`](release/SunoDownloader-1.0.0.apk) (Android 7.0 o superior)
- **Código:** Kotlin · Jetpack Compose · OkHttp · WorkManager · MediaStore · MediaCodec/MediaMuxer · jump3r (MP3)

## Funciones

| Función | Estado |
|---|---|
| Pegar/escribir enlace, botón «Pegar» | ✅ |
| Compartir desde otras apps (texto con enlace) y abrir enlaces `suno.com/song/…`, `suno.com/s/…` | ✅ |
| Título, artista, portada, duración, estilo, versión del modelo y letra | ✅ (API pública de Suno, sin cuenta) |
| Descargar audio original sin recodificar | ✅ cuando Suno lo publica (ver más abajo) |
| Conversión local a MP3 (256 kbps, LAME/jump3r) y WAV (PCM 16 bits) | ✅ |
| Guardar en Música/Suno (MediaStore en Android 10+, carpeta pública en 7–9) | ✅ |
| Descarga en segundo plano con notificación, barra de progreso, cancelación y errores claros | ✅ (WorkManager) |
| Metadatos: título, artista, portada, letra, URL de Suno (ID3v2.3 en MP3; LIST/INFO + `id3 ` en WAV) | ✅ |
| Importar un archivo descargado oficialmente de Suno para etiquetarlo y convertirlo | ✅ |

## Investigación técnica (octubre de 2026)

Toda la investigación se hizo con peticiones anónimas desde un runner de GitHub Actions
(scripts en [`tools/probe_suno.sh`](tools/probe_suno.sh) y [`tools/probe_usesuno.sh`](tools/probe_usesuno.sh)).

### Enlaces de Suno

- `https://suno.com/s/{código}` → redirección `307` a `/song/{uuid}?sh={código}`.
- `https://suno.com/song/{uuid}`, `/embed/{uuid}`, `app.suno.ai/song/{uuid}` contienen el ID directamente.

### Metadatos

`GET https://studio-api.prod.suno.com/api/clip/{uuid}` responde **sin autenticación** con JSON:
`title`, `display_name`, `handle`, `image_large_url`, `metadata.duration`, `metadata.prompt` (letra),
`metadata.tags`, `major_model_version`, `audio_url`, `video_url`, `media_urls`.

### Audio: qué publica Suno y qué no

Muestra de 11 canciones públicas (10 de «Explorar» + la de prueba `suno.com/s/XceKbVQ7EZaCje85`):

| Recurso | Resultado |
|---|---|
| `audio_url` | **`https://studio-api.prod.suno.com/api/forbidden`** en todas: Suno no ofrece el MP3 a usuarios anónimos |
| `https://cdn1.suno.ai/{id}.mp3` | `403` en todas |
| `media_urls[0]` → `https://d2lwuy8qc234o3.cloudfront.net/1/clip/{id}.m4a` | Accesible, pero **cifrado** (entropía 7,9999 bits/byte, sin cajas MP4 `ftyp`/`moov`) |
| `video_url` → `https://cdn1.suno.ai/{id}.mp4` | Vídeo público **sin cifrar** (H.264 + **AAC‑LC 48 kHz ~195 kbps**), solo si el autor generó el vídeo para compartir. La canción de prueba no lo tiene (`403`) |
| Portadas `cdn2.suno.ai/…jpeg` | Públicas |

### Cómo lo hace UseSuno

El código público de `usesuno.com/es/tools/downloader/` muestra que:

1. Descarga el flujo cifrado de `d2lwuy8qc234o3.cloudfront.net/1/clip/{id}.m4a`.
2. Obliga a superar un **captcha (Cloudflare Turnstile / reCAPTCHA)** y obtiene de **su propio servidor**
   (`api.usesuno.com`, con token *Bearer*) la clave para descifrar el audio («key-unwrapping»).
3. **Descifra el audio con AES‑CTR/GCM** en el navegador y lo convierte con lamejs/WASM.
4. Si falla, usa el vídeo público `cdn1.suno.ai/{id}.mp4` y extrae su pista de audio.

**La app no reimplementa los pasos 1–3** (no pide claves ni descifra nada por su cuenta). Para que
funcione igual que la web, incluye **UseSuno integrado**: la pantalla «Abrir UseSuno en la app» carga
`usesuno.com/es/tools/downloader/` en un WebView interno (sin abrir el navegador), rellena el enlace y
envía el formulario. La página hace su verificación, su descarga y su conversión como siempre; cuando
genera el archivo (MP3, WAV u original), la app lo recibe y lo guarda en Música/Suno.

- El puente página → app usa `addWebMessageListener` limitado al origen `https://usesuno.com` y solo
  permite guardar archivos en las carpetas públicas de medios.
- La navegación principal se limita a usesuno.com; otros enlaces se abren fuera.
- Depende del servicio de UseSuno (puede mostrar anuncios, pedir verificación o cambiar sin aviso).

Además, la app implementa por sí sola el paso 4 (vídeo público sin cifrar) y el uso de `audio_url`
cuando Suno lo ofrezca.

### Orden de fuentes en la app

1. `audio_url` real (MP3 original de Suno) → se guarda tal cual, con etiquetas ID3 nuevas.
2. Vídeo público → la pista **AAC se copia sin recodificar** a `.m4a` (MediaExtractor + MediaMuxer).
3. Ninguna → la app lo explica y ofrece «Abrir en Suno» e «Importar archivo».

Antes de mostrar los formatos, la app comprueba cada fuente con una petición de 1 byte (`Range: bytes=0-0`),
porque Suno responde `403` a `HEAD`.

## Calidad

- «Original» es siempre el archivo tal cual lo sirve Suno (MP3 o AAC), sin recodificar.
- **MP3 convertido** (solo cuando la fuente es AAC): recodificación con pérdida a 256 kbps; no mejora la calidad.
- **WAV convertido**: PCM 16 bits decodificado desde el MP3/AAC. El archivo se llama
  `… (WAV convertido).wav` y la interfaz indica que **no es un máster sin pérdida**. Los WAV de máster
  de Suno solo se obtienen desde la propia Suno con una cuenta que lo permita.
- Los `.m4a` originales se guardan sin etiquetas (escribir átomos MP4 requeriría reescribir el contenedor).

## Limitaciones

- **La mayoría de canciones no se pueden descargar sin cuenta** desde octubre de 2026: Suno solo publica el
  audio cifrado, y el vídeo público existe únicamente si el autor lo generó. Es una decisión de Suno,
  no un fallo de la app.
- Para tus propias canciones: descárgalas en la app/web de Suno con tu cuenta (o genera su vídeo de compartir)
  y usa **«Importar archivo»** para añadir portada, letra y metadatos y convertirlas a MP3/WAV en Música/Suno.
- La API de Suno no está documentada públicamente y puede cambiar sin aviso.
- Úsala solo con contenido que tengas derecho a descargar y respetando los términos de Suno.

## Compilar

```bash
cd SunoDownloader
./gradlew assembleRelease     # APK en app/build/outputs/apk/release/
./gradlew testDebugUnitTest   # tests unitarios
./gradlew connectedDebugAndroidTest  # tests contra Suno real (emulador/dispositivo con red)
```

El keystore `app/sunodl-release.jks` (contraseña `sunodl-local`) solo sirve para que la APK sea instalable
y actualizable entre compilaciones; no es una clave de Play Store.

La CI (`.github/workflows/suno-downloader.yml`) ejecuta los tests unitarios, compila, ejecuta los tests
instrumentados contra Suno real en un emulador Android 14, prueba la APK release de extremo a extremo
(compartir enlace → descargar → verificar archivos con ffprobe) y guarda capturas en `release/test-results/`.

## Licencias de terceros

- [jump3r](https://github.com/Sciss/jump3r) (LAME portado a Java) — LGPL 2.1.
- AndroidX, Jetpack Compose, Media3, WorkManager — Apache 2.0. OkHttp — Apache 2.0.

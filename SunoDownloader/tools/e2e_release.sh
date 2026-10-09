#!/usr/bin/env bash
# Prueba de extremo a extremo de la APK release en un emulador: comparte enlaces con la app,
# captura pantallas, pulsa "Descargar" y verifica los archivos guardados en Música/Suno.
set -u
APK="$1"; OUT="$2"; mkdir -p "$OUT"
PKG=com.sunodl.app
SHORT="${SHORT_LINK:-https://suno.com/s/XceKbVQ7EZaCje85}"
VIDEO_SONG="${VIDEO_SONG:-https://suno.com/song/057fa44a-a2c2-4089-8fca-52a637117f00}"
shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "screenshot $1"; }
share() { adb shell am start -W -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'$1'" -n $PKG/.MainActivity >/dev/null; }
tap_text() { # pulsa el primer nodo cuyo texto coincide
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  python3 - "$OUT/ui.xml" "$1" <<'PY' > "$OUT/tap.txt"
import sys,re,xml.etree.ElementTree as ET
t=ET.parse(sys.argv[1]); want=sys.argv[2]
for n in t.iter('node'):
    if n.get('text')==want:
        x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds'))); print((x1+x2)//2,(y1+y2)//2); break
PY
  read X Y < "$OUT/tap.txt" || true
  if [ -n "${X:-}" ]; then adb shell input tap $X $Y; echo "tap '$1' at $X,$Y"; return 0; fi
  echo "no encontrado: $1"; return 1
}
scroll_down() { adb shell input swipe 540 1700 540 700 300; sleep 1; }

adb install -r -g "$APK" || exit 1
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb logcat -c

echo "== 1) Enlace corto compartido (canción sin audio público)"
share "Escucha esta canción en Suno: $SHORT"; sleep 12; shot 01-short-link
scroll_down; shot 02-short-link-scrolled

echo "== 2) Canción con vídeo público: descarga completa (original + MP3 + WAV)"
share "$VIDEO_SONG"; sleep 12; shot 03-video-song
tap_text "WAV (convertido)" || true
sleep 1; scroll_down; shot 04-before-download
tap_text "Descargar" || { scroll_down; tap_text "Descargar"; }
for i in $(seq 1 60); do
  sleep 5
  if [ $i = 2 ]; then shot 05-progress; fi
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  if grep -q 'Guardado en' "$OUT/ui.xml"; then echo "terminado tras $((i*5)) s"; break; fi
  if grep -qE 'Error|falló|denegado' "$OUT/ui.xml"; then echo "ERROR en la UI"; break; fi
done
shot 06-done
echo "== Archivos en /sdcard/Music/Suno"
adb shell ls -l /sdcard/Music/Suno/
mkdir -p "$OUT/files"; adb pull /sdcard/Music/Suno/. "$OUT/files/" >/dev/null
for f in "$OUT"/files/*; do
  echo "--- $(basename "$f") ($(stat -c %s "$f") bytes)"
  ffprobe -hide_banner -show_entries format=format_name,duration,bit_rate:stream=codec_name,sample_rate,channels,bit_rate:format_tags=title,artist -of compact "$f" 2>&1 | grep -vE 'lyrics|USLT' | head -6
done
adb logcat -d | grep -iE "sunodl|AndroidRuntime|FATAL" | grep -viE "lyrics" | tail -40 > "$OUT/logcat.txt"
rm -f "$OUT/ui.xml" "$OUT/tap.txt"; rm -rf "$OUT/files"

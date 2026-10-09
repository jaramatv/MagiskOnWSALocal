#!/usr/bin/env bash
# Investiga, desde una red sin restricciones, cómo responde Suno a los enlaces públicos.
# Solo hace peticiones GET/HEAD anónimas a páginas y recursos públicos.
set -u
UA="Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
OUT=probe-out; mkdir -p $OUT
h() { echo; echo "=================== $* ==================="; }

h "Home / explore pages -> collect song ids"
for p in "https://suno.com/" "https://suno.com/explore" "https://suno.com/trending" "https://suno.com/discover"; do
  code=$(curl -sL -A "$UA" -o $OUT/page.html -w '%{http_code}' "$p"); echo "$p -> $code ($(wc -c <$OUT/page.html) bytes)"
  grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' $OUT/page.html >> $OUT/ids.txt || true
done
ID="${SUNO_TEST_ID:-}"
if [ -z "$ID" ]; then
  for c in $(sort $OUT/ids.txt | uniq -c | sort -rn | awk '{print $2}' | head -40); do
    code=$(curl -sL -A "$UA" -o $OUT/cand.html -w '%{http_code}' "https://suno.com/song/$c")
    n=$(grep -c 'audio_url' $OUT/cand.html || true)
    echo "candidate $c song page -> $code audio_url-lines=$n"
    if [ "$code" = "200" ] && [ "$n" != "0" ]; then ID=$c; break; fi
  done
fi
echo "TEST ID: $ID"
[ -z "$ID" ] && exit 0

h "Song page"
curl -sL -A "$UA" -D $OUT/song.hdr -o $OUT/song.html "https://suno.com/song/$ID"; head -20 $OUT/song.hdr
echo "size: $(wc -c < $OUT/song.html)"
echo "--- meta tags"; grep -oE '<meta[^>]+>' $OUT/song.html | head -40
echo "--- title"; grep -oE '<title>[^<]*</title>' $OUT/song.html
echo "--- interesting keys (context)"
for k in audio_url image_large_url image_url video_url display_name duration '\\"prompt' '\\"title' handle major_model_version '\\"status' is_public; do
  echo "## $k"; grep -oE ".{0,120}$k.{0,200}" $OUT/song.html | head -3
done
echo "--- cdn urls"; grep -oE 'https?:\\?/\\?/[a-z0-9.-]*suno[a-z0-9.-]*\\?/[^"\\ ]{0,120}' $OUT/song.html | sort -u | head -30

h "Embed page"
curl -sL -A "$UA" -o $OUT/embed.html -w '%{http_code}\n' "https://suno.com/embed/$ID"; grep -oE 'audio_url.{0,160}' $OUT/embed.html | head -2

h "Studio API (anonymous)"
for u in "https://studio-api.prod.suno.com/api/clip/$ID" "https://studio-api.prod.suno.com/api/gen/$ID/increment_play_count/v2"; do
  echo "GET $u"; curl -s -A "$UA" -w '\nHTTP %{http_code}\n' "$u" | head -c 3000; echo
done

h "CDN resources"
AU=$(grep -oE 'audio_url[^,]{0,200}' $OUT/song.html | grep -oE 'https:[^"\\]+' | head -1); echo "audio_url from page: $AU"
for u in "$AU" "https://cdn1.suno.ai/$ID.mp3" "https://cdn1.suno.ai/$ID.m4a" "https://cdn1.suno.ai/$ID.wav" "https://cdn1.suno.ai/$ID.mp4" "https://cdn2.suno.ai/image_$ID.jpeg" "https://cdn2.suno.ai/image_large_$ID.jpeg" "https://audiopipe.suno.ai/?item_id=$ID"; do
  [ -z "$u" ] && continue
  echo "--- HEAD $u"; curl -sI -A "$UA" "$u" | grep -iE '^(HTTP|content-type|content-length|location)'
  echo "--- GET range (no referer)"; curl -s -A "$UA" -r 0-1023 -o /dev/null -D - "$u" | grep -iE '^(HTTP|content-type|content-range|location)'
  echo "--- GET range (referer suno.com)"; curl -s -A "$UA" -e "https://suno.com/" -r 0-1023 -o /dev/null -D - "$u" | grep -iE '^(HTTP|content-type|content-range)'
  echo "--- GET plain curl UA"; curl -s -r 0-1023 -o /dev/null -w 'HTTP %{http_code} %{content_type}\n' "$u"
done
curl -sL -A "$UA" -o $OUT/full.mp3 "https://cdn1.suno.ai/$ID.mp3"; echo "full mp3 bytes: $(wc -c < $OUT/full.mp3)"; xxd $OUT/full.mp3 | head -3
(ffprobe -hide_banner $OUT/full.mp3 2>&1 | tail -8) || file $OUT/full.mp3

h "Short share link pattern"
grep -oE 'suno\.com\\?/s\\?/[A-Za-z0-9]+' $OUT/song.html $OUT/page.html | head -3
S=$(grep -ohE 'suno\.com/s/[A-Za-z0-9]+' $OUT/*.html | head -1)
[ -n "$S" ] && curl -sI -A "$UA" "https://$S" | grep -iE '^(HTTP|location)'
exit 0

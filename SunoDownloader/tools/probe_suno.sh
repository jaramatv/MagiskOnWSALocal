#!/usr/bin/env bash
# Investiga, desde una red sin restricciones, cómo responde Suno a los enlaces públicos.
# Solo hace peticiones GET/HEAD anónimas a páginas y recursos públicos. No imprime letras.
set -u
UA="Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
OUT=probe-out; mkdir -p $OUT
h() { echo; echo "=================== $* ==================="; }
sudo apt-get install -y -qq ffmpeg jq >/dev/null 2>&1 || true

h "Collect song ids"
for p in "https://suno.com/" "https://suno.com/explore" "https://suno.com/trending" "https://suno.com/discover"; do
  curl -sL -A "$UA" -o $OUT/page.html "$p"
  grep -oE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' $OUT/page.html >> $OUT/ids.txt || true
done
[ -n "${SUNO_TEST_ID:-}" ] && echo "$SUNO_TEST_ID" > $OUT/ids.txt
sort -u $OUT/ids.txt > $OUT/uids.txt; echo "unique ids: $(wc -l < $OUT/uids.txt)"

h "Clip API survey (audio_url / media_urls / downloadable-like keys)"
n=0
for c in $(cat $OUT/uids.txt); do
  code=$(curl -s -A "$UA" -o $OUT/clip.json -w '%{http_code}' "https://studio-api.prod.suno.com/api/clip/$c")
  [ "$code" != "200" ] && continue
  jq -e '.audio_url' $OUT/clip.json >/dev/null 2>&1 || continue
  n=$((n+1)); cp $OUT/clip.json $OUT/clip_$n.json
  jq -r --arg c "$c" '[$c, .status, .is_public, .audio_url, ((.media_urls//[])|map(.content_type+"@"+(.url|split("/")[2]))|join(";")), .video_url, .metadata.duration, .major_model_version] | map(tostring) | join(" | ")' $OUT/clip.json
  [ $n -ge 25 ] && break
done
echo "--- all top-level keys of a clip:"; jq -r 'keys|join(",")' $OUT/clip_1.json
echo "--- metadata keys:"; jq -r '.metadata|keys|join(",")' $OUT/clip_1.json
echo "--- keys mentioning download/allow/share:"; jq -r '[paths(scalars)|map(tostring)|join(".")]|map(select(test("download|allow|share|forbid|wav|license";"i")))|join("\n")' $OUT/clip_1.json
for i in 1 2 3; do jq -c '{download: (to_entries|map(select(.key|test("download|allow";"i")))|from_entries)}' $OUT/clip_$i.json 2>/dev/null; done

ID=$(jq -r .id $OUT/clip_1.json)
h "Resources for $ID"
M=$(jq -r '.media_urls[0].url // empty' $OUT/clip_1.json); V=$(jq -r '.video_url // empty' $OUT/clip_1.json); I=$(jq -r '.image_large_url // empty' $OUT/clip_1.json)
for u in "$M" "$V" "$I"; do [ -z "$u" ] && continue; echo "--- $u"; curl -s -A "$UA" -r 0-1023 -o /dev/null -D - "$u" | grep -iE '^(HTTP|content-type|content-range|x-cache|cache-control)'; done
curl -s -A "$UA" -o $OUT/v.mp4 "$V" && ffprobe -hide_banner $OUT/v.mp4 2>&1 | grep -E 'Duration|Stream' 

h "oEmbed"
curl -s -A "$UA" "https://studio-api-prod.suno.com/api/oembed?url=https%3A%2F%2Fsuno.com%2Fsong%2F$ID" | head -c 800; echo

h "Short links"
grep -ohE 'suno\.com/s/[A-Za-z0-9]+' $OUT/*.html | sort -u | head -3
for s in $(grep -ohE 'suno\.com/s/[A-Za-z0-9]+' $OUT/*.html | sort -u | head -2); do curl -sI -A "$UA" "https://$s" | grep -iE '^(HTTP|location)'; done
curl -sI -A "$UA" "https://suno.com/s/abcdefgh" | grep -iE '^(HTTP|location)'
curl -sI -A "$UA" "https://app.suno.ai/song/$ID" | grep -iE '^(HTTP|location)'
exit 0

#!/usr/bin/env bash
# Analiza cómo UseSuno obtiene el audio de un enlace público de Suno (sin imprimir letras).
set -u
UA="Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
LINK="${SUNO_LINK:-https://suno.com/s/XceKbVQ7EZaCje85}"
O=uout; mkdir -p $O
h() { echo; echo "=================== $* ==================="; }
sudo apt-get install -y -qq ffmpeg jq >/dev/null 2>&1 || true

h "Resolve $LINK"
curl -sI -A "$UA" "$LINK" | grep -iE '^(HTTP|location)'
ID=$(curl -sIL -A "$UA" "$LINK" | grep -ioE 'song/[0-9a-f-]{36}' | head -1 | cut -d/ -f2); echo "ID=$ID"
curl -s -A "$UA" "https://studio-api.prod.suno.com/api/clip/$ID" > $O/clip.json
jq -c '{title,display_name,audio_url,media_urls,video_url,image_large_url,dur:.metadata.duration,model:.major_model_version}' $O/clip.json

h "UseSuno downloader page"
curl -sL -A "$UA" -o $O/page.html -w '%{http_code}\n' "https://usesuno.com/es/tools/downloader/"
echo "size $(wc -c <$O/page.html)"
grep -oE '<script[^>]+src="[^"]+"' $O/page.html | head -40
grep -oE '(fetch|axios[.a-z]*)\([^)]{0,200}' $O/page.html | head -20
grep -oE '"/api/[^"]{0,80}"|/api/[a-zA-Z0-9/_-]{2,60}' $O/page.html | sort -u | head -30
grep -oE 'https?://[a-zA-Z0-9.-]+\.(suno|cloudfront|usesuno)[a-zA-Z0-9./_-]*' $O/page.html | sort -u | head -30
# Inline module / form action
grep -oE '<form[^>]*>' $O/page.html | head
mkdir -p $O/js
for s in $(grep -oE '<script[^>]+src="[^"]+"' $O/page.html | grep -oE 'src="[^"]+"' | cut -d'"' -f2 | sort -u); do
  case "$s" in http*) u=$s;; //*) u="https:$s";; /*) u="https://usesuno.com$s";; *) u="https://usesuno.com/es/tools/downloader/$s";; esac
  f=$O/js/$(echo "$s" | md5sum | cut -c1-8).js; curl -sL -A "$UA" -o $f "$u"
  if grep -qiE 'suno\.(ai|com)|cloudfront|studio-api|media_urls|audio_url|download' $f; then
    echo "##### $u ($(wc -c <$f) bytes)"
    grep -oE '.{0,160}(studio-api|cdn1\.suno|cloudfront|media_urls|audio_url|/api/[a-z]+|\.m4a|\.mp3|\.mp4|ffmpeg|forbidden).{0,160}' $f | head -40
  fi
done
h "Inline scripts mentioning api"
grep -oE '.{0,200}(studio-api|media_urls|audio_url|cdn1\.suno|cloudfront|/api/).{0,200}' $O/page.html | head -30
exit 0

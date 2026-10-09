#!/usr/bin/env bash
# Analiza el formato del audio público y el código de conversión de UseSuno (sin imprimir letras).
set -u
UA="Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
ID="${SUNO_TEST_ID:-9696c2ff-1d22-462c-aead-1ad324b118e8}"
O=uout; mkdir -p $O
h() { echo; echo "=================== $* ==================="; }
sudo apt-get install -y -qq ffmpeg >/dev/null 2>&1 || true
h "m4a stream"
U="https://d2lwuy8qc234o3.cloudfront.net/1/clip/$ID.m4a"
curl -sI "$U" | grep -iE '^(HTTP|content-|access-control|accept-ranges|cache|etag|last-mod)'
curl -s -A "$UA" -o $O/a.m4a "$U"; ls -l $O/a.m4a
ffprobe -hide_banner -show_entries stream=codec_name,codec_tag_string,sample_rate,channels,bit_rate:format=format_name,duration,bit_rate -of compact $O/a.m4a 2>&1 | tail -4
ffprobe -hide_banner $O/a.m4a 2>&1 | grep -E 'major_brand|compatible|Stream|Duration'
xxd $O/a.m4a | head -3
h "image urls"
for u in "https://cdn2.suno.ai/image_large_$ID.jpeg" "https://cdn2.suno.ai/image_$ID.jpeg"; do curl -s -r 0-10 -o /dev/null -w "$u %{http_code}\n" "$u"; done
h "UseSuno JS details"
B=https://usesuno.com
for f in /tools/downloader/suno-audio-transcoder.js /tools/downloader/suno-media-source.js /tools/shared/download-quality.js /tools/downloader/suno-audio-info.js /tools/downloader/suno-public-video-audio.js; do
  curl -s -A "$UA" -o $O/x.js "$B$f"; echo "##### $f"
  grep -oE '.{0,140}(cdn\.jsdelivr|cdnjs|lame|ffmpeg|wav|mp3|opus|bitrate|kbps|AudioContext|decodeAudioData|WebCodecs|AudioEncoder|Range|headers|referr|credentials|cloudfront|mode:).{0,140}' $O/x.js | sort -u | head -45
done
exit 0

#!/usr/bin/env bash
# Comprueba si el flujo .m4a está cifrado, si hay vídeo público y qué endpoints usa UseSuno.
set -u
UA="Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
O=uout; mkdir -p $O
h() { echo; echo "=================== $* ==================="; }
sudo apt-get install -y -qq ffmpeg ent >/dev/null 2>&1 || true
for ID in 9696c2ff-1d22-462c-aead-1ad324b118e8 057fa44a-a2c2-4089-8fca-52a637117f00; do
  h "$ID"
  curl -s -o $O/a.m4a "https://d2lwuy8qc234o3.cloudfront.net/1/clip/$ID.m4a"
  echo "m4a size $(wc -c <$O/a.m4a); box names found: $(grep -aobE 'ftyp|moov|mdat|Opus|dOps|mp4a' $O/a.m4a | head -5 | tr '\n' ' ')"
  python3 -c "
import math,collections,sys
d=open('$O/a.m4a','rb').read()
c=collections.Counter(d);e=-sum(v/len(d)*math.log2(v/len(d)) for v in c.values())
print('entropy bits/byte: %.4f'%e)"
  echo "cdn1 mp4: $(curl -s -r 0-1 -o /dev/null -w '%{http_code}' https://cdn1.suno.ai/$ID.mp4)  cdn1 mp3: $(curl -s -r 0-1 -o /dev/null -w '%{http_code}' https://cdn1.suno.ai/$ID.mp3)"
  curl -s -o $O/v.mp4 "https://cdn1.suno.ai/$ID.mp4" && ffprobe -hide_banner $O/v.mp4 2>&1 | grep -E 'Duration|Stream' | head -3
done
h "UseSuno opus fallback / media source endpoints"
curl -s -A "$UA" -o $O/f.js "https://usesuno.com/tools/downloader/suno-opus-mp4-fallback.js?v=1948140dcf03"; echo "fallback.js $(wc -c <$O/f.js) bytes"
grep -oE '.{0,120}(decrypt|AES|crypto\.subtle|importKey|key|xor|cipher|api\.usesuno).{0,120}' $O/f.js | head -15
curl -s -A "$UA" -o $O/m.js "https://usesuno.com/tools/downloader/suno-media-source.js?v=0f744c20c0fa"
grep -oE '.{0,100}(api\.usesuno\.com|rights-api|/v[0-9]/[a-z/_-]+|decrypt|crypto\.subtle|AES|turnstile|recaptcha).{0,140}' $O/m.js | sort -u | head -30
exit 0

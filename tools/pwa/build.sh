#!/usr/bin/env bash
# PWA 사이트 조립: pwa/ + 종목 목록 → site/   (스냅샷은 snapshot.py 가 site/data/snapshot 에 생성)
set -euo pipefail
OUT=${1:-site}
mkdir -p "$OUT/data"
for f in index.html config.js manifest.webmanifest sw.js css icons js; do cp -r "pwa/$f" "$OUT/"; done
cp data/stocks_kr.json data/stocks_us.json "$OUT/data/"
VER=${VERSION:-dev-$(date -u +%Y%m%d%H%M)}
BUILT=$(TZ=Asia/Seoul date '+%Y-%m-%d %H:%M KST')
cat > "$OUT/config.js" <<CFG
// GitHub Actions 배포 시 생성 (비밀값 아님: 공개 프록시 주소만)
window.APP_CONFIG = { proxy: '${PROXY_URL:-}', version: '${VER}', builtAt: '${BUILT}' };
CFG
sed -i "s/__VERSION__/${VER}/" "$OUT/sw.js"
touch "$OUT/.nojekyll"
echo "built $OUT ($VER)"

#!/usr/bin/env bash
# Copies the Dhaksha Live page (../live-windows/src, shared with the Windows / Linux app and the browser
# version) into the APK's assets, with hls.js next to it like the browser version (server/live_web.sh).
# Run before building: bash bundle_page.sh && gradle assembleDebug
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/../live-windows/src"
ASSETS="$HERE/app/src/main/assets"
# The same hls.js version the desktop app is built with (tools/dhaksha/live-windows/package.json)
HLS_JS="https://cdn.jsdelivr.net/npm/hls.js@1.5.20/dist/hls.min.js"
HLS_NPM="https://registry.npmjs.org/hls.js/-/hls.js-1.5.20.tgz"

rm -rf "$ASSETS"
mkdir -p "$ASSETS"
cp "$SRC/index.html" "$SRC/app.js" "$SRC/style.css" "$SRC/logo.svg" "$ASSETS/"

if ! curl -fsSL --retry 3 -o "$ASSETS/hls.min.js" "$HLS_JS"; then
    curl -fsSL --retry 3 "$HLS_NPM" | tar -xzO package/dist/hls.min.js > "$ASSETS/hls.min.js"
fi
[[ -s "$ASSETS/hls.min.js" ]] || { echo "hls.js download is empty" >&2; exit 1; }

# The desktop app loads hls.js from its node_modules; here it sits next to app.js
sed -i 's|\.\./node_modules/hls\.js/dist/hls\.min\.js|hls.min.js|' "$ASSETS/index.html"
grep -q 'src="hls.min.js"' "$ASSETS/index.html" || { echo "index.html did not take the hls.js path" >&2; exit 1; }
# Phones have no keyboard: Back does what Esc does
sed -i 's|Esc to return &middot; F11 full screen|Back to return|' "$ASSETS/index.html"
echo "Dhaksha Live page bundled into $ASSETS"

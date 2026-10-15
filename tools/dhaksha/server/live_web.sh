#!/usr/bin/env bash
# Dhaksha Live in the browser: serves the Dhaksha Live grid from the video server, so any browser
# (JioPC, a laptop, a phone, a TV) can watch the drones at http://<SERVER_IP>:8080 with nothing to install.
#
# Run on the video server (the one running MediaMTX), as root. Run it again to update to the latest version.
#   curl -fsSL -o live_web.sh https://raw.githubusercontent.com/mariappanchellam/qgroundcontrol-v4.2.3/build/dhaksha-live-grid/tools/dhaksha/server/live_web.sh
#   sudo bash live_web.sh
#
# Open TCP 8080 in the cloud firewall (Lightsail: Networking > IPv4 Firewall). The page plays the streams
# from MediaMTX on the same server (HLS 8888, WebRTC 8889), exactly like the desktop app.

set -euo pipefail

PORT="${PORT:-8080}"
BRANCH="${BRANCH:-build/dhaksha-live-grid}"
INSTALL_DIR="/opt/dhaksha-live-web"
SOURCE="https://raw.githubusercontent.com/mariappanchellam/qgroundcontrol-v4.2.3/$BRANCH/tools/dhaksha/live-windows/src"
# The same hls.js version the desktop app is built with (tools/dhaksha/live-windows/package.json)
HLS_JS="https://cdn.jsdelivr.net/npm/hls.js@1.5.20/dist/hls.min.js"

die() { echo "ERROR: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "run with sudo"
command -v python3 >/dev/null || die "python3 is needed: apt install -y python3"

# Download into a new folder first, so a failed download never leaves a half-updated page
NEW_DIR="$(mktemp -d)"
trap 'rm -rf "$NEW_DIR"' EXIT
for f in index.html app.js style.css logo.svg; do
    curl -fsSL --retry 3 -o "$NEW_DIR/$f" "$SOURCE/$f" || die "could not download $f"
done
curl -fsSL --retry 3 -o "$NEW_DIR/hls.min.js" "$HLS_JS" || die "could not download hls.js"
# The desktop app loads hls.js from its node_modules; the web page serves it next to app.js
sed -i 's|\.\./node_modules/hls\.js/dist/hls\.min\.js|hls.min.js|' "$NEW_DIR/index.html"
grep -q 'src="hls.min.js"' "$NEW_DIR/index.html" || die "index.html did not take the hls.js path"

mkdir -p "$INSTALL_DIR"
cp "$NEW_DIR"/* "$INSTALL_DIR"/
chmod 755 "$INSTALL_DIR"
chmod 644 "$INSTALL_DIR"/*

cat > /etc/systemd/system/dhaksha-live-web.service <<EOF
[Unit]
Description=Dhaksha Live in the browser (port $PORT)
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/bin/python3 -m http.server $PORT --bind 0.0.0.0 --directory $INSTALL_DIR
Restart=always
RestartSec=3
DynamicUser=yes

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now dhaksha-live-web.service
systemctl restart dhaksha-live-web.service

if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
    ufw allow "$PORT"/tcp
fi

PUBLIC_IP="$(curl -fsS --max-time 5 https://api.ipify.org || echo '<SERVER_IP>')"
cat <<EOF

Dhaksha Live is served at  http://$PUBLIC_IP:$PORT   (service: dhaksha-live-web, starts on boot)
Open TCP $PORT in the cloud firewall if the page does not load from outside.
To update later, run this script again.
EOF

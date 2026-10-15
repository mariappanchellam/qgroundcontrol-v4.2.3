#!/usr/bin/env bash
# Dhaksha Live in the browser: serves the Dhaksha Live grid from the video server, so any browser
# (JioPC, a laptop, a phone, a TV) can watch the drones with nothing to install:
#   http://<SERVER_IP>        page and video through the normal web port 80; works on networks that
#                             block other ports (JioPC, many offices)
#   http://<SERVER_IP>:8080   same page, video straight from MediaMTX on 8888 / 8889
#
# Run on the video server (the one running MediaMTX), as root. Run it again to update to the latest version.
#   curl -fsSL -o live_web.sh https://raw.githubusercontent.com/mariappanchellam/qgroundcontrol-v4.2.3/build/dhaksha-live-grid/tools/dhaksha/server/live_web.sh
#   sudo bash live_web.sh
#
# Open TCP 80 (and 8080 if wanted) in the cloud firewall (Lightsail: Networking > IPv4 Firewall).
# The drones keep sending to MediaMTX on 1935 as before; nothing changes on the MK15.

set -euo pipefail

BRANCH="${BRANCH:-build/dhaksha-live-grid}"
INSTALL_DIR="/opt/dhaksha-live-web"
SITE="/etc/nginx/sites-available/dhaksha-live"
OLD_SERVICE="/etc/systemd/system/dhaksha-live-web.service"
SOURCE="https://raw.githubusercontent.com/mariappanchellam/qgroundcontrol-v4.2.3/$BRANCH/tools/dhaksha/live-windows/src"
# The same hls.js version the desktop app is built with (tools/dhaksha/live-windows/package.json)
HLS_JS="https://cdn.jsdelivr.net/npm/hls.js@1.5.20/dist/hls.min.js"
HLS_NPM="https://registry.npmjs.org/hls.js/-/hls.js-1.5.20.tgz"

die() { echo "ERROR: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "run with sudo"

# Port 80 must be free, or already ours (nginx)
if ss -ltnpH 'sport = :80' 2>/dev/null | grep -v nginx | grep -q .; then
    die "port 80 is used by another program: $(ss -ltnpH 'sport = :80' | head -n 1)"
fi

# Download into a new folder first, so a failed download never leaves a half-updated page
NEW_DIR="$(mktemp -d)"
trap 'rm -rf "$NEW_DIR"' EXIT
for f in index.html app.js style.css logo.svg; do
    curl -fsSL --retry 3 -o "$NEW_DIR/$f" "$SOURCE/$f" || die "could not download $f"
done
if ! curl -fsSL --retry 3 -o "$NEW_DIR/hls.min.js" "$HLS_JS"; then
    # Same file from the npm package itself, when the CDN cannot be reached
    curl -fsSL --retry 3 "$HLS_NPM" | tar -xzO package/dist/hls.min.js > "$NEW_DIR/hls.min.js" \
        || die "could not download hls.js"
fi
[[ -s "$NEW_DIR/hls.min.js" ]] || die "hls.js download is empty"
# The desktop app loads hls.js from its node_modules; the web page serves it next to app.js
sed -i 's|\.\./node_modules/hls\.js/dist/hls\.min\.js|hls.min.js|' "$NEW_DIR/index.html"
grep -q 'src="hls.min.js"' "$NEW_DIR/index.html" || die "index.html did not take the hls.js path"

mkdir -p "$INSTALL_DIR"
cp "$NEW_DIR"/* "$INSTALL_DIR"/
chmod 755 "$INSTALL_DIR"
chmod 644 "$INSTALL_DIR"/*

# The first version served the page with Python on 8080; nginx serves both ports now
if [[ -f "$OLD_SERVICE" ]]; then
    systemctl disable --now dhaksha-live-web.service 2>/dev/null || true
    rm -f "$OLD_SERVICE"
    systemctl daemon-reload
fi

command -v nginx >/dev/null || { apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nginx; }

cat > "$SITE" <<EOF
# Dhaksha Live in the browser (written by live_web.sh)
server {
    listen 80 default_server;
    listen [::]:80 default_server;
    listen 8080;
    root $INSTALL_DIR;
    index index.html;

    location / {
        # Always the newest page after an update
        add_header Cache-Control "no-cache";
    }

    # Video through the same address: /hls/live/drone1/index.m3u8 -> MediaMTX HLS on 8888
    location /hls/ {
        proxy_pass http://127.0.0.1:8888/;
        proxy_http_version 1.1;
        proxy_buffering off;
        proxy_read_timeout 60s;
    }

    # WebRTC set-up (WHEP) through the same address; the video itself still uses UDP / TCP 8189
    location /webrtc/ {
        proxy_pass http://127.0.0.1:8889/;
        proxy_http_version 1.1;
        proxy_redirect / /webrtc/;
    }
}
EOF
ln -sf "$SITE" /etc/nginx/sites-enabled/dhaksha-live
rm -f /etc/nginx/sites-enabled/default
nginx -t -q || die "nginx did not accept the settings"
systemctl enable nginx >/dev/null 2>&1
systemctl restart nginx

if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
    ufw allow 80/tcp
    ufw allow 8080/tcp
fi

PUBLIC_IP="$(curl -fsS --max-time 5 https://api.ipify.org || echo '<SERVER_IP>')"
cat <<EOF

Dhaksha Live is served at  http://$PUBLIC_IP   (and http://$PUBLIC_IP:8080; nginx, starts on boot)
Open TCP 80 in the cloud firewall if the page does not load from outside.
To update later, run this script again.
EOF

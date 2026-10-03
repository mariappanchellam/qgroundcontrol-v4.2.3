#!/usr/bin/env bash
# DhakshaGroundControl: public video server for watching the drone over the internet.
#
# Run once on an internet server (Ubuntu VPS with a public IP, e.g. AWS, Azure,
# Google Cloud, DigitalOcean, Oracle Cloud). It installs MediaMTX as a service
# that starts on boot. The MK15 (on 4G or any network) sends its video here over
# RTMP; phones and PCs watch it in a browser.
#
#   MK15 app --RTMP--> server:1935 --> phone browser (WebRTC :8889 / HLS :8888) or VLC (:8554)
#
# Usage:   sudo ./stream_server.sh <publish-password> [view-password]
#   publish-password  required; only the drone app can send video with it
#   view-password     optional; without it anyone with the link can watch
#
# Open these ports in the cloud firewall / security group:
#   1935/tcp (RTMP from the drone), 8889/tcp + 8189/udp (WebRTC), 8888/tcp (HLS), 8554/tcp (RTSP)

set -euo pipefail

MEDIAMTX_VERSION="v1.12.3"
INSTALL_DIR="/opt/dhaksha-stream"

die() { echo "ERROR: $*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "run with sudo"
[[ $# -ge 1 && -n "$1" ]] || die "usage: sudo $0 <publish-password> [view-password]"
PUBLISH_PASS="$1"
VIEW_PASS="${2:-}"
[[ "$PUBLISH_PASS$VIEW_PASS" =~ ^[A-Za-z0-9._-]+$ ]] || die "use only letters, digits, '.', '_' and '-' in passwords (they go into URLs)"

case "$(uname -m)" in
    x86_64)         ARCH=amd64 ;;
    aarch64|arm64)  ARCH=arm64 ;;
    *) die "unsupported CPU $(uname -m)" ;;
esac

PUBLIC_IP="$(curl -fsS --max-time 5 https://api.ipify.org || true)"
[[ -n "$PUBLIC_IP" ]] || die "could not detect the public IP address"

mkdir -p "$INSTALL_DIR"
if [[ ! -x "$INSTALL_DIR/mediamtx" ]]; then
    echo "Downloading MediaMTX $MEDIAMTX_VERSION ..."
    curl -fsSL "https://github.com/bluenviron/mediamtx/releases/download/$MEDIAMTX_VERSION/mediamtx_${MEDIAMTX_VERSION}_linux_${ARCH}.tar.gz" \
        | tar -xz -C "$INSTALL_DIR" mediamtx
fi

if [[ -n "$VIEW_PASS" ]]; then
    VIEWER_USER="  - user: viewer
    pass: $VIEW_PASS"
else
    VIEWER_USER="  - user: any
    pass:"
fi

cat > "$INSTALL_DIR/mediamtx.yml" <<EOF
logLevel: info
# Plain MPEG-TS HLS plays on every phone browser (the low-latency variant breaks on iPhones)
hlsVariant: mpegts
# Lets WebRTC viewers on mobile networks reach the server through the cloud NAT
webrtcAdditionalHosts: [$PUBLIC_IP]
authMethod: internal
authInternalUsers:
  - user: drone
    pass: $PUBLISH_PASS
    permissions:
      - action: publish
$VIEWER_USER
    permissions:
      - action: read
      - action: playback
paths:
  all_others:
EOF
chmod 600 "$INSTALL_DIR/mediamtx.yml"

cat > /etc/systemd/system/dhaksha-stream.service <<EOF
[Unit]
Description=DhakshaGroundControl video server (MediaMTX)
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=$INSTALL_DIR/mediamtx $INSTALL_DIR/mediamtx.yml
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now dhaksha-stream.service
systemctl restart dhaksha-stream.service

if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
    ufw allow 1935/tcp; ufw allow 8889/tcp; ufw allow 8189/udp; ufw allow 8888/tcp; ufw allow 8554/tcp
fi

cat <<EOF

Video server running on $PUBLIC_IP (service: dhaksha-stream, starts on boot).

DhakshaGroundControl > Application Settings > General > Gimbal Camera:
  Stream to Server: ticked
  Server URL      : rtmp://$PUBLIC_IP:1935/live/drone1?user=drone&pass=$PUBLISH_PASS
  then press "Apply and Start Video"

Watch on a phone (any network):
  Browser, low delay : http://$PUBLIC_IP:8889/live/drone1
  Browser, HLS       : http://$PUBLIC_IP:8888/live/drone1
  VLC                : rtsp://$PUBLIC_IP:8554/live/drone1
$( [[ -n "$VIEW_PASS" ]] && echo "  Viewers log in as user 'viewer', password '$VIEW_PASS'." || echo "  Anyone with these links can watch. Re-run with a view password to restrict it." )

Logs: journalctl -u dhaksha-stream -f
EOF

#!/usr/bin/env bash
# DhakshaGroundControl: stand-in for the gimbal camera, for testing without hardware.
#
# Runs MediaMTX on this computer and plays a video file (looped) as an RTSP
# camera. The same MediaMTX also accepts the RTMP stream DhakshaGroundControl
# forwards ("Stream to Server") and serves it to phones and PCs as RTSP,
# browser video (HLS) and low-latency browser video (WebRTC).
#
#   video file --> rtsp://<this-pc>:8554/cam --> DhakshaGroundControl (MK15)
#   DhakshaGroundControl --> rtmp://<this-pc>:1935/live/drone1 --> phone / browser
#
# Usage:   ./fake_camera.sh [video file]     (no file: a generated test pattern)
# Needs:   ffmpeg (sudo apt install ffmpeg), Linux x86_64 or arm64, internet once
#          to download MediaMTX. Stop with Ctrl+C.

set -euo pipefail

MEDIAMTX_VERSION="v1.12.3"
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/dhaksha-fake-camera"

die() { echo "ERROR: $*" >&2; exit 1; }

command -v ffmpeg >/dev/null || die "ffmpeg is not installed. Run: sudo apt install ffmpeg"

if [[ $# -ge 1 ]]; then
    VIDEO="$(realpath "$1")"
    [[ -f "$VIDEO" ]] || die "video file not found: $1"
    INPUT="-re -stream_loop -1 -i \"$VIDEO\""
    SOURCE_TEXT="$VIDEO (looped)"
else
    INPUT="-re -f lavfi -i testsrc2=size=1280x720:rate=30"
    SOURCE_TEXT="generated test pattern"
fi

# MediaMTX: use one on the PATH, otherwise download it once into the cache
if command -v mediamtx >/dev/null; then
    MEDIAMTX="$(command -v mediamtx)"
else
    case "$(uname -m)" in
        x86_64)         ARCH=amd64 ;;
        aarch64|arm64)  ARCH=arm64 ;;
        *) die "unsupported CPU $(uname -m); install MediaMTX manually from github.com/bluenviron/mediamtx" ;;
    esac
    MEDIAMTX="$CACHE_DIR/$MEDIAMTX_VERSION/mediamtx"
    if [[ ! -x "$MEDIAMTX" ]]; then
        echo "Downloading MediaMTX $MEDIAMTX_VERSION ..."
        mkdir -p "$CACHE_DIR/$MEDIAMTX_VERSION"
        curl -fsSL "https://github.com/bluenviron/mediamtx/releases/download/$MEDIAMTX_VERSION/mediamtx_${MEDIAMTX_VERSION}_linux_${ARCH}.tar.gz" \
            | tar -xz -C "$CACHE_DIR/$MEDIAMTX_VERSION" mediamtx
    fi
fi

# H.264 with a keyframe every second: what the camera sends and what RTMP can carry
CONFIG="$CACHE_DIR/mediamtx.yml"
mkdir -p "$CACHE_DIR"
cat > "$CONFIG" <<EOF
logLevel: warn
# Plain MPEG-TS HLS plays on every phone browser (the low-latency variant breaks on iPhones)
hlsVariant: mpegts
# TCP only: the Android emulator's network drops the incoming UDP that RTSP uses by default
rtspTransports: [tcp]
paths:
  cam:
    runOnInit: 'ffmpeg -loglevel error $INPUT -an -c:v libx264 -preset veryfast -tune zerolatency -pix_fmt yuv420p -g 30 -b:v 2M -f rtsp -rtsp_transport tcp rtsp://localhost:\$RTSP_PORT/\$MTX_PATH'
    runOnInitRestart: yes
  all_others:
EOF

IPS="$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+\.' | tr '\n' ' ' || true)"
IP="${IPS%% *}"
IP="${IP:-<this-pc-ip>}"

cat <<EOF

Fake camera source: $SOURCE_TEXT
This computer's IP address(es): $IPS

1. MK15 and phone: join the same Wi-Fi/network as this computer.

2. DhakshaGroundControl > Application Settings > General > Gimbal Camera:
     Camera Vendor   : ViewPro   (video only; Skydroid also works but shows "no reply")
     RTSP URL        : rtsp://$IP:8554/cam
     Stream to Server: ticked
     Server URL      : rtmp://$IP:1935/live/drone1
   Press "Apply and Start Video". Video appears on the MK15 and Server Status turns green.

3. Watch the forwarded stream on the phone:
     Browser (low delay) : http://$IP:8889/live/drone1
     Browser (HLS)       : http://$IP:8888/live/drone1
     VLC app             : rtsp://$IP:8554/live/drone1
   The camera itself (without the app in between): rtsp://$IP:8554/cam

4. Coordinates in each frame (on this computer):
     python3 $(dirname "$(realpath "$0")")/read_stream_telemetry.py rtmp://localhost:1935/live/drone1

Press Ctrl+C to stop.
EOF

exec "$MEDIAMTX" "$CONFIG"

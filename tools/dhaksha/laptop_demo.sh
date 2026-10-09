#!/usr/bin/env bash
# laptop_demo.sh - MediaMTX server for receiving RTMP from MK15 with real drone camera
# Usage: ./laptop_demo.sh
# Receives RTMP stream from MK15, outputs HLS for viewers (no fake camera)

echo "=== MediaMTX Streaming Server ==="
echo ""

# Check if MediaMTX is installed
if ! command -v mediamtx &> /dev/null; then
    echo "Error: mediamtx not found"
    echo "Install with: sudo apt-get install mediamtx"
    echo "Or download from: https://github.com/bluenviron/mediamtx/releases"
    exit 1
fi

# Create temporary MediaMTX config
TEMP_CONFIG="/tmp/mediamtx-demo.yml"
cat > "$TEMP_CONFIG" << 'EOF'
# MediaMTX config - receive RTMP from MK15, output HLS for viewers

# RTMP listener (MK15 pushes here)
rtmpAddress: :1935

# HLS listener (browsers/Dhaksha Live pull from here)
hlsAddress: :8888

# WebRTC listener (low-latency alternative)
webrtcAddress: :8889

paths:
  live:
    # Allow RTMP push from MK15 with real drone camera
    publishUser: optional
EOF

trap "rm -f $TEMP_CONFIG; exit 0" EXIT INT TERM

# Get laptop IP
IPS="$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+\.' | tr '\n' ' ' || true)"
IP="${IPS%% *}"

cat <<INFO

Starting MediaMTX...

=== MK15 Configuration ===
Go to: Application Settings > General > Gimbal Camera

  Camera Vendor   : ViewPro (receives RTSP from real drone camera)
  Stream to Server: ticked
  Server URL      : rtmp://${IP:-<laptop-ip>}:1935/live/drone1
  Press "Apply and Start Video"

=== View Stream ===
Browser: http://${IP:-<laptop-ip>}:8888/live/index.m3u8
Phone (Dhaksha Live app): Enter same URL above

Listening on:
  RTMP: :1935 (for MK15 to push)
  HLS:  :8888 (for viewers)
  WebRTC: :8889 (low-latency alternative)

Press Ctrl+C to stop.

INFO

mediamtx "$TEMP_CONFIG"

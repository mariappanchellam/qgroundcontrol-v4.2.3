#!/usr/bin/env bash
# DhakshaGroundControl: the whole video chain on one Linux laptop, no drone or camera needed.
#
#   video file --RTSP--> MediaMTX --> DhakshaGroundControl (desktop build or Android emulator)
#   DhakshaGroundControl --RTMP--> MediaMTX --> Dhaksha Live in the browser (http://localhost:8080)
#
# Usage:   ./laptop_demo.sh [video file]      (no file: a generated test pattern)
# Needs:   ffmpeg, python3. Stop with Ctrl+C.

set -euo pipefail

HERE="$(dirname "$(realpath "$0")")"
LOG="${XDG_CACHE_HOME:-$HOME/.cache}/dhaksha-fake-camera/camera.log"
mkdir -p "$(dirname "$LOG")"

"$HERE/fake_camera.sh" "$@" > "$LOG" 2>&1 &
CAMERA_PID=$!
trap 'kill "$CAMERA_PID" 2>/dev/null; pkill -P "$CAMERA_PID" 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
    if ! kill -0 "$CAMERA_PID" 2>/dev/null; then cat "$LOG"; exit 1; fi
    grep -q "Press Ctrl+C" "$LOG" && break
    sleep 1
done

IPS="$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+\.' | tr '\n' ' ' || true)"
IP="${IPS%% *}"

cat <<INFO

Camera and video server are running (log: $LOG).

DhakshaGroundControl > Application Settings > General > Gimbal Camera
  Camera Vendor   : ViewPro
  Stream to Server: ticked
                     Desktop build on this laptop            Android emulator on this laptop
  RTSP URL        :  rtsp://127.0.0.1:8554/cam               rtsp://10.0.2.2:8554/cam
  Server URL      :  rtmp://127.0.0.1:1935/live/drone1       rtmp://10.0.2.2:1935/live/drone1
  Press "Apply and Start Video".

Dhaksha Live (browser): http://localhost:8080
  From a phone on the same Wi-Fi: http://${IP:-<laptop-ip>}:8080

INFO

python3 "$HERE/dhaksha_live.py"

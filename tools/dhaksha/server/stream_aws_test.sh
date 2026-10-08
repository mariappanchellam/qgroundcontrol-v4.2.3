#!/usr/bin/env bash
# Test drones for the Dhaksha video server: run ON the server (a second SSH session) to send
# test video as drone1..drone10 to its own MediaMTX, so Dhaksha Live can be tried with many boxes.
#
# Usage:  PASSWORD=<publish-password> bash stream_aws_test.sh [count] [first] [video.mp4]
#   count      how many test drones (default 10)
#   first      number of the first test drone (default 1; use 2 while a real MK15 sends drone1)
#   video.mp4  your own video to loop; without it a test pattern is made
# Stop with Ctrl+C (stops all test drones).
#
# Each drone's video is prepared once (labelled "TEST DRONE n", 360p, 15 fps, 150 kbit/s, a full
# picture every second) and then only copied in a loop, so the server's CPU is hardly used.

set -euo pipefail

COUNT="${1:-10}"
FIRST="${2:-1}"
SOURCE="${3:-}"
PASSWORD="${PASSWORD:-YOUR_SERVER_PASSWORD}"
WORK="$HOME/dhaksha-test"
FONT=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf

die() { echo "ERROR: $*" >&2; exit 1; }

[[ "$COUNT" =~ ^[0-9]+$ && "$FIRST" =~ ^[0-9]+$ && "$COUNT" -ge 1 ]] || die "count and first must be numbers"
[[ "$PASSWORD" != "YOUR_SERVER_PASSWORD" ]] || die "give the publish password: PASSWORD=Dhaksha2026 bash $0"
[[ -z "$SOURCE" || -f "$SOURCE" ]] || die "video file not found: $SOURCE"
if ! command -v ffmpeg >/dev/null; then
    echo "Installing ffmpeg ..."
    sudo apt-get update -qq && sudo apt-get install -y -qq ffmpeg fonts-dejavu-core
fi
[[ -f "$FONT" ]] || die "font missing: sudo apt-get install -y fonts-dejavu-core"
mkdir -p "$WORK"

LAST=$((FIRST + COUNT - 1))
for n in $(seq "$FIRST" "$LAST"); do
    clip="$WORK/drone$n.mp4"
    [[ -f "$clip" && -z "$SOURCE" ]] && continue
    echo "Preparing test video for drone$n ..."
    if [[ -n "$SOURCE" ]]; then
        input=(-t 60 -i "$SOURCE")
    else
        input=(-f lavfi -i "testsrc2=size=640x360:rate=15:duration=60")
    fi
    ffmpeg -nostdin -loglevel error -y "${input[@]}" -an \
        -vf "scale=-2:360,fps=15,drawtext=fontfile=$FONT:text='TEST DRONE $n':fontsize=36:fontcolor=white:box=1:boxcolor=black@0.6:x=16:y=16,drawtext=fontfile=$FONT:text='%{pts\:hms}':fontsize=24:fontcolor=white:box=1:boxcolor=black@0.6:x=16:y=h-40" \
        -c:v libx264 -preset veryfast -profile:v baseline -pix_fmt yuv420p \
        -b:v 150k -maxrate 150k -bufsize 150k -g 15 -keyint_min 15 -sc_threshold 0 \
        -movflags +faststart "$clip"
done

pids=()
stop_all() {
    trap - INT TERM EXIT
    echo
    echo "Stopping test drones ..."
    kill "${pids[@]}" 2>/dev/null || true
    pkill -f "rtmp://127.0.0.1:1935/live/drone" 2>/dev/null || true
}
trap stop_all INT TERM EXIT

for n in $(seq "$FIRST" "$LAST"); do
    (
        while true; do
            ffmpeg -nostdin -loglevel error -re -stream_loop -1 -i "$WORK/drone$n.mp4" -c copy \
                -f flv "rtmp://127.0.0.1:1935/live/drone$n?user=drone&pass=$PASSWORD" || true
            sleep 3
        done
    ) &
    pids+=("$!")
done

IP="$(curl -fsS --max-time 5 https://api.ipify.org || echo SERVER_IP)"
echo
echo "Sending test drones drone$FIRST to drone$LAST. Press Ctrl+C to stop them."
echo "Watch in Dhaksha Live (or a browser):"
for n in $(seq "$FIRST" "$LAST"); do
    echo "  http://$IP:8888/live/drone$n"
done
wait

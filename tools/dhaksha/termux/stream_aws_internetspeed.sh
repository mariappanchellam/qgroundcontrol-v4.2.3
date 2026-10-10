#!/data/data/com.termux/files/usr/bin/bash
# MK15 camera -> Dhaksha video server (MediaMTX on AWS) over RTMP, low delay, with a choice of quality.
#
# Run:   bash ~/stream_aws_internetspeed.sh <SERVER_IP> [quality] [drone]
#   quality  auto (default)  starts at 480p, drops a step when the internet cannot keep up, climbs back when it can
#            720 | 480 | 360 | 240   fixed size
#            copy            the camera's own video unchanged (no re-encoding; needs a fast connection)
#   drone    stream name, default drone1 (watch at http://<SERVER_IP>:8888/live/<drone>)
# Stop with Ctrl+C, then: pkill ffmpeg

SERVER_IP="${1:-PUT_SERVER_IP_HERE}"
QUALITY="${2:-auto}"
DRONE="${3:-drone1}"
PASSWORD="YOUR_SERVER_PASSWORD"
CAMERA="${CAMERA:-rtsp://192.168.144.25:8554/main.264}"
RTMP_PORT="${RTMP_PORT:-1935}"
PROGRESS="$HOME/.stream_aws_progress"

LEVELS=(720 480 360 240)
AUTO_START=1          # index in LEVELS where auto starts (480p)
CHECK_EVERY=3         # seconds between upload checks
SETTLE=15             # seconds after a (re)start before checks count
SLOW_CHECKS=2         # this many slow checks in a row -> one step down
SLOW_DELAY=1.0        # seconds of video waiting in the upload queue that count as slow
JAM_DELAY=3.0         # ... and that step down at once
SMOOTH_DELAY=0.3      # ... and below which sending counts as smooth
UP_AFTER=180          # seconds of smooth sending before auto tries one step up
UP_AFTER_DROP=600     # ... and how long it waits instead right after it had to step down

if [ "$SERVER_IP" = "PUT_SERVER_IP_HERE" ]; then
  echo "Usage: bash ~/stream_aws_internetspeed.sh <SERVER_IP> [auto|720|480|360|240|copy] [drone]"
  exit 1
fi

# Height, frames per second and kbit/s for each size; a full picture every second
profile() {
  case "$1" in
  720) HEIGHT=720; FPS=20; RATE=1000 ;;
  480) HEIGHT=480; FPS=15; RATE=500 ;;
  360) HEIGHT=360; FPS=15; RATE=250 ;;
  240) HEIGHT=240; FPS=10; RATE=120 ;;
  esac
}

case "$QUALITY" in
auto) level=$AUTO_START ;;
720) level=0 ;;
480) level=1 ;;
360) level=2 ;;
240) level=3 ;;
copy) level=-1 ;;
*) echo "Unknown quality '$QUALITY': use auto, 720, 480, 360, 240 or copy"; exit 1 ;;
esac

FF_PID=""
stop_ffmpeg() {
  if [ -n "$FF_PID" ]; then
    kill "$FF_PID" 2>/dev/null
    wait "$FF_PID" 2>/dev/null
    FF_PID=""
  fi
}
trap 'stop_ffmpeg; echo; echo "Stopped."; exit 0' INT TERM

start_ffmpeg() {
  local video
  if [ "$level" -lt 0 ]; then
    video=(-c:v copy)
    RATE=""
    label="camera original"
  else
    profile "${LEVELS[$level]}"
    video=(-vf "scale=$((HEIGHT * 16 / 9 / 2 * 2)):$HEIGHT,fps=$FPS"
      -c:v libx264 -preset ultrafast -tune zerolatency -profile:v baseline -pix_fmt yuv420p
      -b:v "${RATE}k" -maxrate "${RATE}k" -bufsize "$((RATE / 2))k"
      -g "$FPS" -keyint_min "$FPS" -sc_threshold 0)
    label="${HEIGHT}p ${FPS}fps ${RATE}kbit/s"
  fi
  : > "$PROGRESS"
  ffmpeg -hide_banner -loglevel error -nostdin -nostats -progress "$PROGRESS" \
    -fflags nobuffer -flags low_delay -flags2 +fast -rtsp_transport tcp -i "$CAMERA" -an "${video[@]}" \
    -f flv "rtmp://$SERVER_IP:$RTMP_PORT/live/$DRONE?user=drone&pass=$PASSWORD" &
  FF_PID=$!
  started=$SECONDS
  slow=0
  echo "$(date '+%F %T') sending $DRONE at $label"
}

# Seconds of video waiting in the phone's upload queue to the server: this is the delay the internet adds.
# Read from the kernel's connection table; empty when the table cannot be read.
queued_seconds() {
  local hex
  hex=$(awk -v port="$(printf '%04X' "$RTMP_PORT")" \
    'FNR > 1 { split($3, r, ":"); if (r[2] == port && $4 == "01") { split($5, q, ":"); print q[1]; exit } }' \
    /proc/net/tcp /proc/net/tcp6 2>/dev/null)
  [ -n "$hex" ] || return
  awk -v bytes="$((16#$hex))" -v kbps="${RATE:-2000}" 'BEGIN { printf "%.2f", bytes * 8 / (kbps * 1000) }'
}

# Fallback when the queue cannot be read: ffmpeg's speed, as a delay (1.0x keeps up; below that video piles up)
speed_as_delay() {
  local speed
  speed=$(grep '^speed=' "$PROGRESS" 2>/dev/null | tail -n 1 | sed 's/speed=//; s/x//; s/ //g')
  case "$speed" in ''|N/A|*[!0-9.]*) return ;; esac
  awk -v s="$speed" -v slow="$SLOW_DELAY" 'BEGIN { if (s < 0.92) print slow + 1; else print 0 }'
}

up_wait=$UP_AFTER
smooth_since=$SECONDS
start_ffmpeg
while true; do
  sleep "$CHECK_EVERY"
  if ! kill -0 "$FF_PID" 2>/dev/null; then
    wait "$FF_PID" 2>/dev/null
    FF_PID=""
    echo "$(date '+%F %T') stream stopped (camera or internet), retrying in 3 s"
    sleep 3
    start_ffmpeg
    smooth_since=$SECONDS
    continue
  fi
  [ $((SECONDS - started)) -lt "$SETTLE" ] && continue
  delay=$(queued_seconds)
  [ -n "$delay" ] || delay=$(speed_as_delay)
  [ -n "$delay" ] || continue
  if awk -v d="$delay" -v t="$SLOW_DELAY" 'BEGIN { exit !(d > t) }'; then
    slow=$((slow + 1))
    smooth_since=$SECONDS
    if [ "$slow" -ge "$SLOW_CHECKS" ] || awk -v d="$delay" -v t="$JAM_DELAY" 'BEGIN { exit !(d > t) }'; then
      if [ "$QUALITY" = "auto" ] && [ "$level" -lt $((${#LEVELS[@]} - 1)) ]; then
        echo "$(date '+%F %T') internet too slow (${delay}s of video waiting): stepping down"
        stop_ffmpeg
        level=$((level + 1))
        up_wait=$UP_AFTER_DROP
        start_ffmpeg
      else
        echo "$(date '+%F %T') internet too slow (${delay}s of video waiting): the delay will grow; try a smaller quality"
        slow=0
      fi
    fi
  elif awk -v d="$delay" -v t="$SMOOTH_DELAY" 'BEGIN { exit !(d > t) }'; then
    slow=0
    smooth_since=$SECONDS
  else
    slow=0
    if [ "$QUALITY" = "auto" ] && [ "$level" -gt 0 ] && [ $((SECONDS - smooth_since)) -ge "$up_wait" ]; then
      echo "$(date '+%F %T') internet keeping up: trying one step up"
      stop_ffmpeg
      level=$((level - 1))
      up_wait=$UP_AFTER
      start_ffmpeg
      smooth_since=$SECONDS
    fi
  fi
done

#!/data/data/com.termux/files/usr/bin/bash
# Made by DhakshaKey: home test video test.mp4 -> Dhaksha video server, 360p, about 150 kbit/s, low delay.
# Watch: http://<SERVER_IP>:8888/live/drone1
# Stop with Ctrl+C, or the Stop button in DhakshaKey.

SERVER_IP='YOUR_SERVER_IP'      # DhakshaKey fills in the real values
PASSWORD='YOUR_SERVER_PASSWORD'
DRONE='drone1'
WIDTH=640
HEIGHT=360
FPS=15
BITRATE=150k
BUFSIZE=75k

# Stop a run that was not stopped cleanly, so it cannot hold the camera, the upload or port 14445
TELEMETRY_PID=""
for p in $(cat $HOME/.dhakshakey.pid $HOME/.dhaksha_telemetry.pid 2>/dev/null) /proc/[0-9]*; do
  p=${p#/proc/}
  [ "$p" = "$$" ] && continue
  case "$(tr '\0' ' ' 2>/dev/null < /proc/$p/cmdline)" in
  *bash*stream_aws*.sh*|python*dhaksha_telemetry.py*) kill "$p" 2>/dev/null ;;
  esac
done
pkill -x ffmpeg 2>/dev/null
sleep 1
pkill -9 -x ffmpeg 2>/dev/null
echo $$ > $HOME/.dhakshakey.pid
termux-wake-lock 2>/dev/null
echo "Watch this drone at http://$SERVER_IP:8888/live/$DRONE"

# Home test without a camera: test.mp4 in the Download folder (needs  termux-setup-storage  once)
VIDEO=""
for f in "$HOME/test.mp4" "$HOME/storage/shared/Download/test.mp4" "/sdcard/Download/test.mp4"; do
  if [ -r "$f" ]; then VIDEO="$f"; break; fi
done
if [ -z "$VIDEO" ]; then
  echo "test.mp4 not found. Copy it to the phone's Download folder, run  termux-setup-storage"
  echo "once in Termux and allow access to files, then press OK in DhakshaKey again."
  rm -f $HOME/.dhakshakey.pid
  exit 1
fi
echo "Home test: sending $VIDEO again and again instead of the camera"

# The width is written out: ffmpeg 8 turned scale=-2:360 into a 2-pixel-wide picture
FILTER="scale=$WIDTH:$HEIGHT,fps=$FPS"

# Position on the video: a made-up TEST position for the home test (no drone needed)
TEXT_FILE="$HOME/telemetry.txt"
echo "${DRONE^^}  waiting for position..." > "$TEXT_FILE"
FONT=""
for f in /system/fonts/Roboto-Regular.ttf /system/fonts/DroidSans.ttf /system/fonts/NotoSans-Regular.ttf; do
  if [ -f "$f" ]; then FONT="$f"; break; fi
done
if ! command -v python3 > /dev/null; then
  echo "No position on the video: run  pkg install python  in Termux once"
elif [ -z "$FONT" ] || ! ffmpeg -hide_banner -filters 2>/dev/null | grep -q drawtext; then
  echo "No position on the video: this ffmpeg cannot draw text"
else
  TEXT="drawtext=fontfile=$FONT:textfile=$TEXT_FILE:reload=1:fontsize=$((HEIGHT / 18)):fontcolor=white:box=1:boxcolor=black@0.55:boxborderw=6:x=10:y=h-th-12"
  # Try the text on a tiny test picture first, so it can never stop the real video
  if ffmpeg -nostdin -hide_banner -loglevel error -f lavfi -i color=size=320x180:duration=0.2 -vf "$TEXT" -f null - ; then
    start_reader() {
      python3 ~/dhaksha_telemetry.py "${DRONE^^}" "$TEXT_FILE" 14445 --fake &
      TELEMETRY_PID=$!
      echo $TELEMETRY_PID > $HOME/.dhaksha_telemetry.pid
    }
    start_reader
    FILTER="$FILTER,$TEXT"
  else
    echo "No position on the video: ffmpeg could not draw the text. Sending the video without it."
  fi
fi

PROGRESS="$HOME/.dhakshakey_progress"
STALL_SECONDS=8
# More than 2 s of video waiting to upload, for QUEUE_SECONDS, means the internet is not keeping up
QUEUE_LIMIT_KB=$(( ${BITRATE%k} / 4 ))
QUEUE_SECONDS=4
FFMPEG_PID=""
# ffmpeg stuck writing to a dead connection may ignore a polite stop, so it is forced after 3 s
stop_ffmpeg() {
  [ -n "$FFMPEG_PID" ] || return
  kill "$FFMPEG_PID" 2>/dev/null
  for i in 1 2 3 4 5 6; do kill -0 "$FFMPEG_PID" 2>/dev/null || return; sleep 0.5; done
  kill -9 "$FFMPEG_PID" 2>/dev/null
}
cleanup() { stop_ffmpeg; kill $TELEMETRY_PID 2>/dev/null; rm -f $HOME/.dhakshakey.pid $HOME/.dhaksha_telemetry.pid; exit 0; }
trap cleanup INT TERM

# KB of video waiting in this phone's upload queue to the server; empty when it cannot be read
queued_kb() {
  local hex
  hex=$(awk -v port="$(printf '%04X' 1935)" 'FNR > 1 { split($3, r, ":"); if (r[2] == port && $4 == "01") { split($5, q, ":"); print q[1]; exit } }' /proc/net/tcp /proc/net/tcp6 2>/dev/null)
  [ -n "$hex" ] && echo $(( 16#$hex / 1024 ))
}

while true; do
  echo "$(date '+%F %T') sending to $SERVER_IP ($DRONE)"
  : > "$PROGRESS"
  # -re: send the file at its real speed, like a camera; -stream_loop -1: play it again and again
  ffmpeg -nostdin -stats -progress "$PROGRESS" -flags2 +fast \
    -re -stream_loop -1 -i "$VIDEO" \
    -an -vf "$FILTER" \
    -c:v libx264 -preset ultrafast -tune zerolatency -profile:v baseline -pix_fmt yuv420p \
    -b:v "$BITRATE" -maxrate "$BITRATE" -bufsize "$BUFSIZE" \
    -g "$FPS" -keyint_min "$FPS" -sc_threshold 0 \
    -f flv "rtmp://$SERVER_IP:1935/live/$DRONE?user=drone&pass=$PASSWORD" &
  FFMPEG_PID=$!
  # Watchdog: when the video stops moving or runs below 0.8x for STALL_SECONDS, or piles up
  # waiting to upload for QUEUE_SECONDS, restart at once.
  # The video waiting behind the stall is dropped, so viewers get live video back instead of a growing delay.
  last=""; stuck=0; slow=0; queued=0; starting=0
  while kill -0 "$FFMPEG_PID" 2>/dev/null; do
    sleep 2
    if [ -n "$TELEMETRY_PID" ] && ! kill -0 "$TELEMETRY_PID" 2>/dev/null; then
      echo "$(date '+%F %T') position reader stopped: starting it again"
      start_reader
    fi
    now=$(grep '^out_time_us=' "$PROGRESS" | tail -n 1)
    speed=$(grep '^speed=' "$PROGRESS" | tail -n 1 | sed 's/speed=//; s/x//; s/ //g')
    case "$now" in
    ''|*N/A) starting=$((starting + 2)) ;;
    "$last") stuck=$((stuck + 2)) ;;
    *) stuck=0 ;;
    esac
    last="$now"
    case "$speed" in
    ''|N/A|*[!0-9.]*) slow=0 ;;
    *) if awk -v s="$speed" 'BEGIN { exit !(s < 0.8) }'; then slow=$((slow + 2)); else slow=0; fi ;;
    esac
    kb=$(queued_kb)
    if [ -n "$kb" ] && [ "$kb" -ge "$QUEUE_LIMIT_KB" ]; then queued=$((queued + 2)); else queued=0; fi
    if [ "$stuck" -ge "$STALL_SECONDS" ] || [ "$slow" -ge "$STALL_SECONDS" ] \
        || [ "$queued" -ge "$QUEUE_SECONDS" ] || [ "$starting" -ge 20 ]; then
      if [ -n "$kb" ] && [ "$kb" -ge "$QUEUE_LIMIT_KB" ]; then
        echo "$(date '+%F %T') internet stalled ($kb KB waiting to upload): restarting"
      else
        echo "$(date '+%F %T') test video stalled: restarting"
      fi
      stop_ffmpeg
      break
    fi
  done
  wait "$FFMPEG_PID" 2>/dev/null
  FFMPEG_PID=""
  echo "$(date '+%F %T') stream stopped, retrying in 3 s"
  sleep 3
done

#!/data/data/com.termux/files/usr/bin/bash
# stream_aws.sh with the drone's position written on the video:
#   DRONE1  Lat 12.971600  Lon 77.594600  Alt 35.2 m  10:42:07
# The position comes from DhakshaGroundControl: Application Settings > MAVLink >
# "Enable MAVLink forwarding" ticked, host name localhost:14445 (restart the app after ticking).
# Needs once in Termux: pkg install python   and ~/dhaksha_telemetry.py next to this script.
# Run:  bash ~/stream_aws_position.sh [SERVER_IP]      Watch: http://<SERVER_IP>:8888/live/drone1
# Stop with Ctrl+C.

DEFAULT_SERVER_IP="YOUR_SERVER_IP"
SERVER_IP="${1:-$DEFAULT_SERVER_IP}"
PASSWORD="YOUR_SERVER_PASSWORD"
DRONE="drone1"
CAMERA="rtsp://192.168.144.25:8554/main.264"
HEIGHT=360
FPS=15
BITRATE=150k
BUFSIZE=75k
TELEMETRY_PORT=14445
TEXT_FILE="$HOME/telemetry.txt"

if [ "$SERVER_IP" = "YOUR_SERVER_IP" ]; then
  echo "Put your server's IP in DEFAULT_SERVER_IP (nano ~/stream_aws_position.sh), or run: bash ~/stream_aws_position.sh <SERVER_IP>"
  exit 1
fi

# Position reader: keeps $TEXT_FILE up to date while this script runs
TELEMETRY_PID=""
if command -v python3 > /dev/null && [ -f "$HOME/dhaksha_telemetry.py" ]; then
  python3 "$HOME/dhaksha_telemetry.py" "${DRONE^^}" "$TEXT_FILE" "$TELEMETRY_PORT" &
  TELEMETRY_PID=$!
  trap 'kill $TELEMETRY_PID 2>/dev/null; exit 0' INT TERM EXIT
else
  echo "No position on the video: run  pkg install python  and copy dhaksha_telemetry.py to ~"
fi

# drawtext needs a font file; Android has its own
FONT=""
for f in /system/fonts/Roboto-Regular.ttf /system/fonts/DroidSans.ttf /system/fonts/NotoSans-Regular.ttf \
         /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf; do
  if [ -f "$f" ]; then FONT="$f"; break; fi
done

FILTER="scale=-2:$HEIGHT,fps=$FPS"
if [ -n "$TELEMETRY_PID" ] && [ -n "$FONT" ] && ffmpeg -hide_banner -filters 2>/dev/null | grep -q drawtext; then
  FILTER="$FILTER,drawtext=fontfile=$FONT:textfile=$TEXT_FILE:reload=1:fontsize=$((HEIGHT / 18)):fontcolor=white:box=1:boxcolor=black@0.55:boxborderw=6:x=10:y=h-th-12"
elif [ -n "$TELEMETRY_PID" ]; then
  echo "No position on the video: this ffmpeg has no drawtext filter or no font was found"
fi

while true; do
  echo "$(date '+%F %T') sending to $SERVER_IP ($DRONE)"
  ffmpeg -fflags nobuffer -flags low_delay -rtsp_transport tcp -i "$CAMERA" \
    -an -vf "$FILTER" \
    -c:v libx264 -preset ultrafast -tune zerolatency -profile:v baseline -pix_fmt yuv420p \
    -b:v "$BITRATE" -maxrate "$BITRATE" -bufsize "$BUFSIZE" \
    -g "$FPS" -keyint_min "$FPS" -sc_threshold 0 \
    -f flv "rtmp://$SERVER_IP:1935/live/$DRONE?user=drone&pass=$PASSWORD"
  echo "$(date '+%F %T') stream stopped, retrying in 3 s"
  sleep 3
done

#!/data/data/com.termux/files/usr/bin/bash
# ZR10 camera -> Livepush at 480p, about 180 kbit/s, low delay. Reconnects when the stream drops.
# Stop with: pkill -f stream_480.sh; pkill ffmpeg

CAMERA="rtsp://192.168.144.25:8554/main.264"
KEY="YOUR_LIVEPUSH_KEY"
HEIGHT=480
FPS=10
BITRATE=180k
BUFSIZE=90k

while true; do
  echo "$(date '+%F %T') starting ${HEIGHT}p stream"
  ffmpeg -fflags nobuffer -flags low_delay -rtsp_transport tcp -i "$CAMERA" \
    -an -vf "scale=-2:$HEIGHT,fps=$FPS" \
    -c:v libx264 -preset ultrafast -tune zerolatency -profile:v baseline -pix_fmt yuv420p \
    -b:v "$BITRATE" -maxrate "$BITRATE" -bufsize "$BUFSIZE" \
    -g "$FPS" -keyint_min "$FPS" -sc_threshold 0 \
    -f flv "rtmp://stream.livepush.io/live/$KEY"
  echo "$(date '+%F %T') stream stopped, retrying in 5 s"
  sleep 5
done

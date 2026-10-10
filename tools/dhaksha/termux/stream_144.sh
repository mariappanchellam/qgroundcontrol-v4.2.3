#!/data/data/com.termux/files/usr/bin/bash
# ZR10 camera -> Livepush at 144p, about 60 kbit/s, low delay. Reconnects when the stream drops.
# Stop with: pkill -f stream_144.sh; pkill ffmpeg

CAMERA="rtsp://192.168.144.25:8554/main.264"
KEY="YOUR_LIVEPUSH_KEY"
HEIGHT=144
FPS=10
BITRATE=60k
BUFSIZE=30k

while true; do
  echo "$(date '+%F %T') starting ${HEIGHT}p stream"
  ffmpeg -fflags nobuffer -flags low_delay -flags2 +fast -rtsp_transport tcp -i "$CAMERA" \
    -an -vf "scale=$((HEIGHT * 16 / 9 / 2 * 2)):$HEIGHT,fps=$FPS" \
    -c:v libx264 -preset ultrafast -tune zerolatency -profile:v baseline -pix_fmt yuv420p \
    -b:v "$BITRATE" -maxrate "$BITRATE" -bufsize "$BUFSIZE" \
    -g "$FPS" -keyint_min "$FPS" -sc_threshold 0 \
    -f flv "rtmp://stream.livepush.io/live/$KEY"
  echo "$(date '+%F %T') stream stopped, retrying in 5 s"
  sleep 5
done

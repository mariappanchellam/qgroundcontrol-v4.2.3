#!/data/data/com.termux/files/usr/bin/bash
# ZR10 camera -> own AWS Lightsail server (MediaMTX) at 360p, about 150 kbit/s, low delay.
# Run:  bash ~/stream_aws.sh <SERVER_IP>     (or put the IP below and run without it)
# Watch: http://<SERVER_IP>:8889/live/drone1
# Stop with Ctrl+C, then: pkill ffmpeg

SERVER_IP="${1:-PUT_SERVER_IP_HERE}"
PASSWORD="YOUR_SERVER_PASSWORD"
DRONE="drone1"
CAMERA="rtsp://192.168.144.25:8554/main.264"
HEIGHT=360
FPS=15
BITRATE=150k
BUFSIZE=75k

if [ "$SERVER_IP" = "PUT_SERVER_IP_HERE" ]; then
  echo "Give the server IP: bash ~/stream_aws.sh 13.233.x.x"
  exit 1
fi

while true; do
  echo "$(date '+%F %T') sending to $SERVER_IP ($DRONE)"
  ffmpeg -fflags nobuffer -flags low_delay -rtsp_transport tcp -i "$CAMERA" \
    -an -vf "scale=-2:$HEIGHT,fps=$FPS" \
    -c:v libx264 -preset ultrafast -tune zerolatency -profile:v baseline -pix_fmt yuv420p \
    -b:v "$BITRATE" -maxrate "$BITRATE" -bufsize "$BUFSIZE" \
    -g "$FPS" -keyint_min "$FPS" -sc_threshold 0 \
    -f flv "rtmp://$SERVER_IP:1935/live/$DRONE?user=drone&pass=$PASSWORD"
  echo "$(date '+%F %T') stream stopped, retrying in 3 s"
  sleep 3
done

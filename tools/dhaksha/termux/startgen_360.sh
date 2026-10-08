#!/data/data/com.termux/files/usr/bin/bash
ffmpeg -fflags nobuffer -flags low_delay -rtsp_transport tcp -i rtsp://192.168.144.25:8554/main.264 -an -vf "scale=-2:360,fps=10" -c:v libx264 -preset ultrafast -tune zerolatency -profile:v baseline -pix_fmt yuv420p -b:v 150k -maxrate 150k -bufsize 75k -g 10 -keyint_min 10 -sc_threshold 0 -f flv rtmp://stream.livepush.io/live/YOUR_LIVEPUSH_KEY

#!/data/data/com.termux/files/usr/bin/bash
# Termux:Boot: when the MK15 starts, waits for the camera and the server, then runs ~/stream_aws.sh
# (which streams to its default server). Log: ~/stream_log.txt
#
# Install (once):  mkdir -p ~/.termux/boot && cp ~/boot_stream_aws.sh ~/.termux/boot/ && chmod +x ~/.termux/boot/boot_stream_aws.sh
# stream_aws.sh stays in the home folder (~), not in ~/.termux/boot. Keep only ONE script in ~/.termux/boot.

SERVER_IP="YOUR_SERVER_IP"   # same as DEFAULT_SERVER_IP in stream_aws.sh
CAMERA_IP="192.168.144.25"
STREAM="$HOME/stream_aws.sh"
LOG="$HOME/stream_log.txt"

log() { echo "$(date '+%F %T') $*" >> "$LOG"; }

termux-wake-lock
log "MK15 started; waiting 30 s"
sleep 30

if [ ! -f "$STREAM" ]; then
  log "$STREAM not found: copy it with  cp ~/storage/shared/Download/stream_aws.sh ~/"
  exit 1
fi
until ping -c 1 -W 2 "$CAMERA_IP" > /dev/null 2>&1; do
  log "waiting for camera $CAMERA_IP"
  sleep 5
done
# The server answers on its RTMP port (AWS does not answer ping unless allowed)
until timeout 5 bash -c "exec 3<>/dev/tcp/$SERVER_IP/1935" 2> /dev/null; do
  log "waiting for internet / server $SERVER_IP"
  sleep 5
done
log "camera and server reachable; starting stream_aws.sh"
bash "$STREAM" "$SERVER_IP" >> "$LOG" 2>&1

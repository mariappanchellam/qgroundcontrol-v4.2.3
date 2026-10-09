#!/data/data/com.termux/files/usr/bin/bash
# Termux:Boot: when the MK15 starts, waits for the camera and the internet, then streams to the
# Dhaksha video server with stream_aws_internetspeed.sh. Log: ~/stream_log.txt
#
# Install (once):  mkdir -p ~/.termux/boot && cp ~/boot_autostart_aws.sh ~/.termux/boot/ && chmod +x ~/.termux/boot/boot_autostart_aws.sh
# stream_aws_internetspeed.sh goes in the home folder (~), not in ~/.termux/boot.
# Keep only ONE autostart script in ~/.termux/boot, or two streams start.

SERVER_IP="PUT_SERVER_IP_HERE"
QUALITY="auto"            # auto, 720, 480, 360, 240 or copy
DRONE="drone1"            # a different name for every MK15
CAMERA_IP="192.168.144.25"
LOG="$HOME/stream_log.txt"

log() { echo "$(date '+%F %T') $*" >> "$LOG"; }

termux-wake-lock
log "MK15 started; waiting 30 s"
sleep 30

if [ "$SERVER_IP" = "PUT_SERVER_IP_HERE" ]; then
  log "boot_autostart_aws.sh: put the server IP in SERVER_IP first"
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

# The stream script: in the home folder, else wherever it was copied
STREAM=""
for candidate in "$HOME/stream_aws_internetspeed.sh" "$HOME/storage/shared/Download/stream_aws_internetspeed.sh" \
    "$HOME/storage/shared/Download/stream_rate/stream_aws_internetspeed.sh"; do
  if [ -f "$candidate" ]; then
    STREAM="$candidate"
    break
  fi
done
if [ -z "$STREAM" ]; then
  log "stream_aws_internetspeed.sh not found: copy it with  cp ~/storage/shared/Download/stream_aws_internetspeed.sh ~/"
  exit 1
fi
log "camera and server reachable; starting $DRONE ($QUALITY) with $STREAM"
bash "$STREAM" "$SERVER_IP" "$QUALITY" "$DRONE" >> "$LOG" 2>&1

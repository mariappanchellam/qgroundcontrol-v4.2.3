#!/data/data/com.termux/files/usr/bin/bash
# Termux:Boot: when the MK15 starts, waits for the camera and the internet, then streams to the
# Dhaksha video server with stream_aws_internetspeed.sh. Log: ~/stream_log.txt
#
# Install (once):  mkdir -p ~/.termux/boot && cp ~/autostart_aws.sh ~/.termux/boot/ && chmod +x ~/.termux/boot/autostart_aws.sh
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
  log "autostart_aws.sh: put the server IP in SERVER_IP first"
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
log "camera and server reachable; starting $DRONE ($QUALITY)"
bash "$HOME/stream_aws_internetspeed.sh" "$SERVER_IP" "$QUALITY" "$DRONE" >> "$LOG" 2>&1

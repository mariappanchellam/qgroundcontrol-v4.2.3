#!/data/data/com.termux/files/usr/bin/bash
# Camera check for the MK15 (Skydroid, ViewPro, ...): finds the camera, tries its day (554) and night (555) video
# addresses and the control port, and writes the result to ~/skydroid_check.txt (also copied to Download).
# Run:  bash ~/skydroid_check.sh [camera IP]        default 192.168.144.108. Takes about 1-2 minutes.

IP="${1:-192.168.144.108}"
LOG="$HOME/skydroid_check.txt"
: > "$LOG"
say() { echo "$*" | tee -a "$LOG"; }
port_open() { timeout 2 bash -c "exec 3<>/dev/tcp/$1/$2" 2>/dev/null; }

say "Skydroid check $(date '+%F %T')"
say "This MK15's network addresses:"
ip -4 addr 2>/dev/null | grep -o 'inet [0-9.]*' | sed 's/^/  /' | tee -a "$LOG"

say ""
say "== 1. Is the camera at $IP?"
if ping -c 2 -W 2 "$IP" > /dev/null 2>&1; then
  say "  yes, $IP answers ping"
else
  say "  $IP does not answer ping; looking for cameras on 192.168.144.x (RTSP ports 554/555) ..."
  for n in $(seq 1 254); do
    ( for p in 554 555; do port_open 192.168.144.$n $p && echo "  found 192.168.144.$n port $p"; done ) &
    [ $((n % 32)) -eq 0 ] && wait
  done | tee -a "$LOG"
  wait
  say "  If a camera was found above, run again with its IP:  bash ~/skydroid_check.sh <IP>"
fi

say ""
say "== 2. Video addresses on $IP"
FOUND=""
for port in 554 555 8554; do
  if ! port_open "$IP" "$port"; then
    say "  port $port: closed"
    continue
  fi
  say "  port $port: open, trying addresses ..."
  for path in "stream=0" "stream=1" "stream0" "stream1" "live" "main.264" "video0" "video1" ""; do
    url="rtsp://$IP:$port/$path"
    info=$(timeout 10 ffprobe -v error -rtsp_transport tcp -select_streams v:0 \
      -show_entries stream=codec_name,width,height -of csv=p=0 "$url" 2>/dev/null | head -n 1)
    if [ -n "$info" ]; then
      say "    VIDEO  $url   ($info)"
      FOUND="$FOUND $url"
      break
    fi
  done
done
[ -z "$FOUND" ] && say "  no video found on $IP"

say ""
say "== 3. Camera control (UDP 5000, used by DhakshaGroundControl's camera buttons)"
if command -v python3 > /dev/null; then
  python3 - "$IP" <<'PY' | tee -a "$LOG"
import socket, sys
frame = b"#TPUD2rREC00"
frame += f"{sum(frame) & 0xFF:02X}".encode()
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.settimeout(2)
try:
    s.sendto(frame, (sys.argv[1], 5000))
    data = s.recv(256)
    print("  camera answered:", data.decode(errors="replace"))
except OSError as error:
    print("  no answer on UDP 5000 (" + str(error) + ")")
PY
else
  say "  skipped: run  pkg install python  once"
fi

say ""
say "== Result"
for url in $FOUND; do
  case "$url" in
  *:555/*) say "  NIGHT (thermal): $url" ;;
  *) say "  DAY:             $url" ;;
  esac
done
cp "$LOG" "$HOME/storage/shared/Download/" 2>/dev/null && say "(copied to Download/skydroid_check.txt)"

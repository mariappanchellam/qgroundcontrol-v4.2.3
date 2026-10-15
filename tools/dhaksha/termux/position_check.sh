#!/data/data/com.termux/files/usr/bin/bash
# Checks every link of "lat / lon / alt on the video":
#   DhakshaGroundControl forwarding -> UDP 14445 -> dhaksha_telemetry.py -> ~/telemetry.txt -> ffmpeg drawtext
# Run with DhakshaGroundControl open and the drone connected:  bash ~/position_check.sh
# Report: Download/position_check.txt (and a test picture Download/position_test.jpg)

LOG="$HOME/position_check.txt"
: > "$LOG"
say() { echo "$*" | tee -a "$LOG"; }
ok()  { say "  OK    $*"; }
bad() { say "  FAIL  $*"; }

say "Position check $(date '+%F %T')"

say ""; say "1. Programs"
command -v python3 > /dev/null && ok "python3" || bad "python3 missing: run  pkg install python"
ffmpeg -hide_banner -filters 2>/dev/null | grep -q ' drawtext ' && ok "ffmpeg can draw text" || bad "this ffmpeg has no drawtext filter"
FONT=""
for f in /system/fonts/Roboto-Regular.ttf /system/fonts/DroidSans.ttf /system/fonts/NotoSans-Regular.ttf; do
  [ -f "$f" ] && { FONT="$f"; break; }
done
[ -n "$FONT" ] && ok "font $FONT" || bad "no font found in /system/fonts"
[ -f "$HOME/dhaksha_telemetry.py" ] && ok "~/dhaksha_telemetry.py present" || bad "~/dhaksha_telemetry.py missing: copy it from Download"

say ""; say "2. Stream scripts with the position text"
for s in "$HOME"/stream_aws_*.sh; do
  [ -f "$s" ] || continue
  if grep -q dhaksha_telemetry "$s"; then ok "$(basename "$s") draws the position"; else bad "$(basename "$s") has NO position text (old script)"; fi
done

say ""; say "3. Position arriving from DhakshaGroundControl on UDP 14445"
READER=""
for p in /proc/[0-9]*; do
  case "$(tr '\0' ' ' 2>/dev/null < "$p/cmdline")" in python*dhaksha_telemetry.py*) READER="${p#/proc/}" ;; esac
done
if [ -n "$READER" ]; then
  say "  a stream is running (position reader $READER); reading its file instead of the port"
  TXT="$HOME/telemetry.txt"
  if [ -f "$TXT" ]; then
    say "  ~/telemetry.txt: $(cat "$TXT")"
    AGE=$(( $(date +%s) - $(stat -c %Y "$TXT") ))
    [ "$AGE" -le 3 ] && ok "file updated ${AGE}s ago" || bad "file not updated for ${AGE}s: the reader is stuck"
    case "$(cat "$TXT")" in
      *Lat*) ok "position is in the file" ;;
      *"no position"*|*waiting*) bad "no position arriving: see the forwarding steps below" ;;
      *busy*) bad "port 14445 held by another program" ;;
    esac
  else
    bad "~/telemetry.txt missing"
  fi
else
  python3 - <<'PY' | tee -a "$LOG"
import socket, struct, time
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
try:
    s.bind(("0.0.0.0", 14445))
except OSError as e:
    print("  FAIL  cannot listen on 14445:", e)
    raise SystemExit
s.settimeout(0.5)
end = time.time() + 6
packets = positions = 0
last = None
while time.time() < end:
    try:
        d = s.recv(4096)
    except OSError:
        continue
    packets += 1
    i = 0
    while i < len(d):
        if d[i] == 0xFD and i + 10 <= len(d):
            n, msg = d[i + 1], d[i + 7] | d[i + 8] << 8 | d[i + 9] << 16
            payload, size = d[i + 10:i + 10 + n], 12 + n + (13 if d[i + 2] & 1 else 0)
        elif d[i] == 0xFE and i + 6 <= len(d):
            n, msg = d[i + 1], d[i + 5]
            payload, size = d[i + 6:i + 6 + n], 8 + n
        else:
            i += 1
            continue
        if msg == 33:
            positions += 1
            _, lat, lon, _, rel = struct.unpack_from("<Iiiii", payload.ljust(28, b"\0"))
            last = (lat / 1e7, lon / 1e7, rel / 1000)
        i += size
print(f"  listened 6 s: {packets} MAVLink packets, {positions} position messages")
if packets == 0:
    print("  FAIL  nothing arrives: in DhakshaGroundControl tick MAVLink forwarding (localhost:14445),")
    print("        RESTART the app, and check the drone is connected (not 'Disconnected' at the top)")
elif positions == 0:
    print("  FAIL  MAVLink arrives but no position messages (drone not sending GLOBAL_POSITION_INT)")
else:
    print(f"  OK    position: lat {last[0]:.6f} lon {last[1]:.6f} alt {last[2]:.1f} m")
    if abs(last[0]) < 0.0001 and abs(last[1]) < 0.0001:
        print("  NOTE  lat/lon are 0: no GPS fix yet (go outdoors and wait for satellites)")
PY
fi

say ""; say "4. Drawing test"
if [ -n "$FONT" ]; then
  echo "DRONE1  Lat 13.000000  Lon 80.000000  Alt 10.0 m  TEST" > "$HOME/.position_test.txt"
  if ffmpeg -nostdin -hide_banner -loglevel error -y -f lavfi -i color=c=gray:size=640x360:duration=0.1 \
      -vf "drawtext=fontfile=$FONT:textfile=$HOME/.position_test.txt:fontsize=20:fontcolor=white:box=1:boxcolor=black@0.55:x=10:y=h-th-12" \
      -frames:v 1 "$HOME/position_test.jpg"; then
    ok "drew the text on a test picture (Download/position_test.jpg)"
    cp "$HOME/position_test.jpg" "$HOME/storage/shared/Download/" 2>/dev/null
  else
    bad "ffmpeg could not draw the text"
  fi
fi

cp "$LOG" "$HOME/storage/shared/Download/" 2>/dev/null && say "" && say "Saved Download/position_check.txt - send it"

#!/data/data/com.termux/files/usr/bin/python3
"""1-minute camera test, without DhakshaGroundControl. Close DhakshaGroundControl first.

Numbered steps: centre, RIGHT 3 s (C13 style), LEFT 3 s (C10 style), UP 3 s (C10 style), centre,
then ZOOM IN 3 s with four different commands (each followed by zoom out).
Watch the CAMERA ITSELF. Prints every reply and saves everything to Download/gimbal_test.txt.
Run:  python3 ~/gimbal_test.py [camera IP]
"""
import os, socket, sys, time

IP = sys.argv[1] if len(sys.argv) > 1 else "192.168.144.108"
lines = []


def say(text):
    print(text, flush=True)
    lines.append(text)


def frame(address, cmd, data, rw="w"):
    body = f"#{'TP' if len(data) == 2 else 'tp'}U{address}{len(data):X}{rw}{cmd}{data}".encode()
    return body + f"{sum(body) & 0xFF:02X}".encode()


sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
for port in (5000, 0):
    try:
        sock.bind(("0.0.0.0", port))
        break
    except OSError as error:
        say(f"local port {port} busy ({error}) - is DhakshaGroundControl or the Skydroid app still open?")
sock.settimeout(0.1)
say(f"gimbal test {time.strftime('%F %T')}  camera {IP}  from local port {sock.getsockname()[1]}")


def run(label, pkt, seconds):
    say(f"\n{label}: sending {pkt.decode()} for {seconds} s")
    replies = set()
    end = time.time() + seconds
    while True:
        sock.sendto(pkt, (IP, 5000))
        t = time.time() + 0.1
        while time.time() < t:
            try:
                replies.add(sock.recv(256).decode(errors="replace"))
            except OSError:
                break
        if time.time() >= end:
            break
    for r in sorted(replies):
        say(f"  reply {r}")
    if not replies:
        say("  no reply")


run("Status (recording state)", frame("D", "REC", "00", "r"), 0.5)
run("Model name", frame("G", "PA2", "00", "r"), 0.5)
run("1. CENTRE", frame("G", "PTZ", "05"), 0.3)
time.sleep(2)
run("2. RIGHT, C13 style (YPR)", frame("G", "YPR", "1E0000"), 3)
run("   stop", frame("G", "YPR", "000000"), 0.3)
time.sleep(1)
run("3. LEFT, C10 style (GSY)", frame("G", "GSY", "E2"), 3)
run("   stop", frame("G", "GSY", "00"), 0.3)
time.sleep(1)
run("4. UP, C10 style (GSP)", frame("G", "GSP", "1E"), 3)
run("   stop", frame("G", "GSP", "00"), 0.3)
time.sleep(1)
run("5. CENTRE", frame("G", "PTZ", "05"), 0.3)
time.sleep(2)
for n, (address, cmd) in enumerate([("M", "ZMC"), ("D", "ZMC"), ("M", "DZM"), ("D", "DZM")], start=6):
    run(f"{n}. ZOOM IN ({cmd} to {address})", frame(address, cmd, "02"), 3)
    run("   zoom stop", frame(address, cmd, "00"), 0.3)
    run("   zoom back out", frame(address, cmd, "01"), 3)
    run("   zoom stop", frame(address, cmd, "00"), 0.3)
    time.sleep(1)
say("\nDone. Which step numbers (1-9) changed the camera?")
try:
    with open(os.path.expanduser("~/storage/shared/Download/gimbal_test.txt"), "w") as f:
        f.write("\n".join(lines) + "\n")
    print("Saved Download/gimbal_test.txt - send it with the step numbers that worked")
except OSError:
    pass

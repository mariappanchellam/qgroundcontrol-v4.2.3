#!/data/data/com.termux/files/usr/bin/python3
"""Finds which gimbal, zoom, PIP, tilt-down and laser-range commands this Skydroid camera understands.

Sends candidate commands to the camera's control port (UDP 5000) one at a time. Watch the video in
DhakshaGroundControl: after each command, type y (it worked), n (nothing happened) or Enter (same as n).
Every reply from the camera is printed. The results go to ~/skydroid_probe.txt and Download.
Close DhakshaGroundControl's camera panel Log or leave it; both can run together.

Run:  python3 ~/skydroid_probe.py [camera IP]       (default 192.168.144.108)
"""
import os
import shutil
import socket
import sys
import time

IP = sys.argv[1] if len(sys.argv) > 1 else "192.168.144.108"
LOG = os.path.expanduser("~/skydroid_probe.txt")


def frame(header, address, rw, cmd, data):
    body = f"#{header}U{address}{len(data):X}{rw}{cmd}{data}".encode()
    return body + f"{sum(body) & 0xFF:02X}".encode()


sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.settimeout(0.3)
out = open(LOG, "w")


def say(text):
    print(text, flush=True)
    out.write(text + "\n")
    out.flush()


def send(label, address, rw, cmd, data, hold=0.0, stop=None):
    """Sends one command (repeated for `hold` seconds), then an optional stop command, and prints replies."""
    header = "TP" if len(data) == 2 else "tp"
    pkt = frame(header, address, rw, cmd, data)
    end = time.time() + hold
    replies = []
    while True:
        sock.sendto(pkt, (IP, 5000))
        t = time.time() + 0.3
        while time.time() < t:
            try:
                replies.append(sock.recv(256).decode(errors="replace"))
            except OSError:
                break
        if time.time() >= end:
            break
    if stop:
        sock.sendto(frame("TP", address, "w", stop[0], stop[1]), (IP, 5000))
    say(f"\n{label}\n  sent  {pkt.decode()}")
    for r in replies:
        say(f"  reply {r}")
    return replies


def ask():
    try:
        answer = input("  Did it work? (y/n): ").strip().lower()
    except EOFError:
        answer = ""
    say(f"  operator: {'WORKED' if answer.startswith('y') else 'no'}")


say(f"Skydroid probe {time.strftime('%F %T')}  camera {IP}")
say("\n=== Identity")
send("Model name (PA2)", "G", "r", "PA2", "00")
send("Firmware version (VSN)", "G", "r", "VSN", "00")
send("Firmware version (VSN, system)", "D", "r", "VSN", "00")

say("\n=== GIMBAL tests: each turns the camera RIGHT for 3 s. Note which one moves SMOOTHLY.")
send("Style A: YPR only (C13 style)", "G", "w", "YPR", "1E0000", hold=3.0, stop=("PTZ", "00"))
send("  stop", "G", "w", "YPR", "000000")
ask()
time.sleep(1)
send("Style B: GSY only (C10 style)", "G", "w", "GSY", "1E", hold=3.0, stop=("GSY", "00"))
send("  stop", "G", "w", "PTZ", "00")
ask()
send("Centre: PTZ 05", "G", "w", "PTZ", "05")
time.sleep(2)

say("\n=== ZOOM IN tests: each zooms in for 2 s. Watch the video.")
for label, address, cmd, data, stop in [
    ("ZMC in, lens (M) - what the app sends now", "M", "ZMC", "02", ("ZMC", "00")),
    ("ZMC in, system/image (D)", "D", "ZMC", "02", ("ZMC", "00")),
    ("ZMC in, gimbal (G)", "G", "ZMC", "02", ("ZMC", "00")),
    ("DZM in, lens (M)", "M", "DZM", "02", ("DZM", "00")),
    ("DZM in, system/image (D)", "D", "DZM", "02", ("DZM", "00")),
    ("ZOM absolute 4x, lens (M)", "M", "ZOM", "40", None),
    ("ZOM absolute 4x, system/image (D)", "D", "ZOM", "40", None),
]:
    send(label, address, "w", cmd, data, hold=2.0, stop=stop)
    ask()
say("\n=== Zoom back out (sends every zoom-out variant for 3 s)")
for address, cmd in [("M", "ZMC"), ("D", "ZMC"), ("G", "ZMC"), ("M", "DZM"), ("D", "DZM")]:
    send(f"{cmd} out ({address})", address, "w", cmd, "01", hold=0.6, stop=(cmd, "00"))
send("ZOM 1x (M)", "M", "w", "ZOM", "10")
send("ZOM 1x (D)", "D", "w", "ZOM", "10")

say("\n=== PIP / picture tests: watch whether the day / thermal picture layout changes.")
for label, address, data in [
    ("PIP next, system/image (D)", "D", "0A"),
    ("PIP sub only (thermal?), system/image (D)", "D", "03"),
    ("PIP main + sub, system/image (D)", "D", "01"),
    ("PIP next, lens (M)", "M", "0A"),
    ("PIP next, gimbal (G)", "G", "0A"),
]:
    send(label, address, "w", "PIP", data)
    ask()
send("PIP main only (D) - back to normal", "D", "w", "PIP", "00")

say("\n=== TILT DOWN 90 tests: watch whether the camera points straight down.")
send("One-key down: PTZ 0A (G)", "G", "w", "PTZ", "0A")
ask()
send("Centre again: PTZ 05 (G)", "G", "w", "PTZ", "05")
time.sleep(2)
send("Pitch angle -90: GIP (G)", "G", "w", "GIP", "FC7C63")
ask()
send("Centre again: PTZ 05 (G)", "G", "w", "PTZ", "05")

say("\n=== LASER RANGE tests: point the camera at a wall or a building 5-500 m away.")
for label, address, data in [
    ("LRF single, lens (M) - what the app sends now", "M", "02"),
    ("LRF start, lens (M)", "M", "01"),
    ("LRF continuous, lens (M)", "M", "03"),
    ("LRF single, system/image (D)", "D", "02"),
    ("LRF continuous, system/image (D)", "D", "03"),
    ("LRF single, gimbal (G)", "G", "02"),
]:
    replies = send(label, address, "w", "LRF", data, hold=1.5)
    send("  read LRF (M)", "M", "r", "LRF", "00")
    send("  read LRF (D)", "D", "r", "LRF", "00")
    ask()
for address in "MDG":
    send(f"LRF stop ({address})", address, "w", "LRF", "00")

say("\nDone.")
out.close()
try:
    shutil.copy(LOG, os.path.expanduser("~/storage/shared/Download/skydroid_probe.txt"))
    print("Copied to Download/skydroid_probe.txt - send that file.")
except OSError:
    print("Send ~/skydroid_probe.txt")

#!/data/data/com.termux/files/usr/bin/python3
"""Writes the drone's position to a text file that ffmpeg prints on the video.

DhakshaGroundControl forwards the drone's MAVLink messages to this phone when
Application Settings > MAVLink > "Enable MAVLink forwarding" is ticked (host name
localhost:14445). This program listens there, reads GLOBAL_POSITION_INT and keeps
one line up to date, e.g.

    DRONE1  Lat 12.971600  Lon 77.594600  Alt 35.2 m  10:42:07

ffmpeg's drawtext filter re-reads the file for every frame (textfile=...:reload=1).
Python standard library only.

Usage: python3 dhaksha_telemetry.py [label] [file] [udp port]
"""

import os
import socket
import struct
import sys
import time

LABEL = sys.argv[1] if len(sys.argv) > 1 else "DRONE"
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.expanduser("~/telemetry.txt")
PORT = int(sys.argv[3]) if len(sys.argv) > 3 else 14445

GLOBAL_POSITION_INT = 33
STALE_SECONDS = 3.0
WRITE_EVERY = 0.2


def messages(datagram):
    """Yields (message id, payload) for every MAVLink v1/v2 frame in a UDP datagram."""
    i = 0
    while i < len(datagram):
        magic = datagram[i]
        if magic == 0xFD and i + 10 <= len(datagram):
            length, incompat = datagram[i + 1], datagram[i + 2]
            msgid = datagram[i + 7] | datagram[i + 8] << 8 | datagram[i + 9] << 16
            start = i + 10
            end = start + length
            size = 10 + length + 2 + (13 if incompat & 0x01 else 0)
        elif magic == 0xFE and i + 6 <= len(datagram):
            length = datagram[i + 1]
            msgid = datagram[i + 5]
            start = i + 6
            end = start + length
            size = 6 + length + 2
        else:
            i += 1
            continue
        if i + size > len(datagram):
            return
        yield msgid, datagram[start:end]
        i += size


def write_line(text):
    """Replaces the file in one step so ffmpeg never reads half a line."""
    tmp = OUT + ".tmp"
    with open(tmp, "w") as f:
        f.write(text)
    os.replace(tmp, OUT)


def main():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("0.0.0.0", PORT))
    sock.settimeout(0.5)
    print(f"Listening for DhakshaGroundControl telemetry on UDP {PORT}; writing {OUT}", flush=True)

    line = f"{LABEL}  waiting for position..."
    last_fix = 0.0
    last_write = 0.0
    write_line(line)
    while True:
        try:
            datagram = sock.recv(4096)
        except socket.timeout:
            datagram = b""
        for msgid, payload in messages(datagram):
            if msgid != GLOBAL_POSITION_INT:
                continue
            payload = payload.ljust(28, b"\0")      # MAVLink 2 drops trailing zero bytes
            _, lat, lon, _alt_msl, alt_rel = struct.unpack_from("<Iiiii", payload)
            line = f"{LABEL}  Lat {lat / 1e7:.6f}  Lon {lon / 1e7:.6f}  Alt {alt_rel / 1000:.1f} m"
            last_fix = time.monotonic()
        now = time.monotonic()
        shown = line if now - last_fix < STALE_SECONDS else f"{LABEL}  no position (is DhakshaGroundControl forwarding?)"
        shown += time.strftime("  %H:%M:%S")
        if now - last_write >= WRITE_EVERY:
            write_line(shown)
            last_write = now


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass

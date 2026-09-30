#!/usr/bin/env python3
"""Skydroid (Topotek protocol) camera simulator for testing DhakshaGroundControl.

Listens on UDP port 5000 like a Skydroid C10 camera, prints every command the
ground station sends in readable form, and answers the recording status query
so the app shows the camera as connected.

Usage (Python 3, no extra packages; works on Windows, Linux, macOS and
Android/Termux):

    python3 skydroid_camera_simulator.py [--port 5000]

Then in DhakshaGroundControl: Application Settings > General > Gimbal Camera,
set Camera IP to the computer running this script (127.0.0.1 when running on
the same tablet in Termux) and press "Apply and Start Video".
"""

import argparse
import datetime
import socket

MODES = {0x00: "stop", 0x05: "centre (home)", 0x06: "lock", 0x07: "follow"}
ZOOM = {0x00: "stop", 0x01: "zoom OUT", 0x02: "zoom IN"}
FOCUS = {0x00: "stop", 0x01: "focus NEAR", 0x02: "focus FAR", 0x10: "AUTO focus"}
PIP = {0: "main only", 1: "main + sub", 2: "sub + main", 3: "sub only"}


def checksum(payload: bytes) -> bytes:
    return f"{sum(payload) & 0xFF:02X}".encode()


def build_frame(address: str, write: bool, command_id: str, data: int) -> bytes:
    body = f"#TPD{address}2{'w' if write else 'r'}{command_id}{data:02X}".encode()
    return body + checksum(body)


def signed(value: int) -> int:
    return value - 256 if value > 127 else value


class Camera:
    def __init__(self) -> None:
        self.recording = False
        self.photos = 0
        self.yaw_speed = 0
        self.pitch_speed = 0

    def describe(self, rw: str, command_id: str, data: int) -> str:
        if rw == "r":
            return f"read {command_id}"
        if command_id == "PTZ":
            if data == 0x00:
                self.yaw_speed = self.pitch_speed = 0
            return f"gimbal {MODES.get(data, f'mode {data:02X}')}"
        if command_id == "GSY":
            self.yaw_speed = signed(data)
            direction = "RIGHT" if self.yaw_speed > 0 else "LEFT" if self.yaw_speed < 0 else "stop"
            return f"gimbal yaw speed {self.yaw_speed:+d} ({direction})"
        if command_id == "GSP":
            self.pitch_speed = signed(data)
            direction = "UP" if self.pitch_speed > 0 else "DOWN" if self.pitch_speed < 0 else "stop"
            return f"gimbal pitch speed {self.pitch_speed:+d} ({direction})"
        if command_id == "REC":
            self.recording = data == 0x01
            return "START recording" if self.recording else "STOP recording"
        if command_id == "CAP":
            self.photos += 1
            return f"TAKE PHOTO (#{self.photos})"
        if command_id == "ZMC":
            return ZOOM.get(data, f"zoom {data:02X}")
        if command_id == "FCC":
            return FOCUS.get(data, f"focus {data:02X}")
        if command_id == "IRC":
            return "day / night toggle"
        if command_id == "PIP":
            return f"PIP {PIP.get(data, data)}"
        return f"unknown command {command_id} data {data:02X}"


def parse(frame: bytes):
    """Returns (address, rw, id, data) or raises ValueError."""
    if len(frame) < 14 or not frame.startswith(b"#TP"):
        raise ValueError("not a Topotek frame")
    length = int(chr(frame[5]), 16)
    end = 10 + length
    if frame[end:end + 2].upper() != checksum(frame[:end]):
        raise ValueError(f"bad checksum (got {frame[end:end + 2]!r}, expected {checksum(frame[:end])!r})")
    text = frame[:end].decode("ascii")
    return text[4], text[6], text[7:10], int(text[10:end], 16)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=5000, help="UDP control port (default 5000)")
    parser.add_argument("--quiet-polls", action="store_true", help="hide the 2 s recording status polls")
    args = parser.parse_args()

    camera = Camera()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", args.port))
    print(f"Skydroid camera simulator listening on UDP port {args.port}. Ctrl+C to quit.")

    while True:
        datagram, sender = sock.recvfrom(1024)
        stamp = datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3]
        try:
            address, rw, command_id, data = parse(datagram)
        except ValueError as error:
            print(f"{stamp} {sender[0]}  {datagram!r}  INVALID: {error}")
            continue

        reply = None
        if rw == "r" and command_id == "REC":
            reply = build_frame("U", False, "REC", 1 if camera.recording else 0)
            if args.quiet_polls:
                sock.sendto(reply, sender)
                continue

        print(f"{stamp} {sender[0]}  {datagram.decode()}  {camera.describe(rw, command_id, data)}")
        if reply:
            sock.sendto(reply, sender)
            print(f"{'':12} reply {reply.decode()}  (recording {'ON' if camera.recording else 'OFF'})")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass

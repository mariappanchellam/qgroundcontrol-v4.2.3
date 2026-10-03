#!/usr/bin/env python3
"""Dhaksha Live: browser viewer for the drone video and the position in its frames.

Runs next to the MediaMTX server that receives DhakshaGroundControl's
"Stream to Server" video. Open http://<this-pc>:8080 in a browser on a laptop
or phone: it plays the live video (WebRTC from MediaMTX) and shows the
position DhakshaGroundControl embeds in every frame, plus the flown track.

The position is read from the stream with ffmpeg (H.264 SEI, see
read_stream_telemetry.py) and pushed to the page with Server-Sent Events.

Usage: python3 dhaksha_live.py [--server localhost] [--path live/drone1] [--port 8080]
Needs: Python 3.8+, ffmpeg.
"""

import argparse
import json
import os
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from read_stream_telemetry import nal_units, sei_payloads  # noqa: E402

MAX_TRACK_POINTS = 3000


class Telemetry:
    """Latest position, flown track and stream state, shared with the web clients."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.changed = threading.Condition(self.lock)
        self.state = {"stream": "waiting", "frames": 0, "fps": 0.0, "last": None}
        self.track = []
        self.version = 0

    def update(self, **fields) -> None:
        with self.lock:
            self.state.update(fields)
            self.version += 1
            self.changed.notify_all()

    def add_position(self, data: dict) -> None:
        with self.lock:
            self.state["last"] = data
            if not data.get("no_position"):
                point = [data["lat"], data["lon"]]
                if not self.track or self.track[-1] != point:
                    self.track.append(point)
                    del self.track[:-MAX_TRACK_POINTS]
            self.version += 1
            self.changed.notify_all()

    def snapshot(self) -> dict:
        with self.lock:
            return dict(self.state, track=list(self.track))


def read_stream(url: str, telemetry: Telemetry) -> None:
    """Follows the stream with ffmpeg forever, reconnecting while it is not published."""
    while True:
        process = subprocess.Popen(
            ["ffmpeg", "-loglevel", "quiet", "-rtsp_transport", "tcp", "-analyzeduration", "1000000",
             "-i", url, "-map", "0:v:0", "-c", "copy", "-bsf:v", "h264_mp4toannexb", "-f", "h264", "-"],
            stdout=subprocess.PIPE)
        frames = 0
        window_start = time.monotonic()
        window_frames = 0
        try:
            for nal in nal_units(process.stdout):
                if not nal:
                    continue
                nal_type = nal[0] & 0x1F
                if nal_type == 6:
                    for payload in sei_payloads(nal):
                        try:
                            telemetry.add_position(json.loads(payload))
                        except ValueError:
                            pass
                elif nal_type in (1, 5) and len(nal) > 1 and nal[1] & 0x80:
                    frames += 1
                    window_frames += 1
                    elapsed = time.monotonic() - window_start
                    if elapsed >= 1.0:
                        telemetry.update(stream="live", frames=frames, fps=round(window_frames / elapsed, 1))
                        window_start = time.monotonic()
                        window_frames = 0
        finally:
            process.kill()
            process.wait()
        telemetry.update(stream="waiting", fps=0.0)
        time.sleep(2)


def make_handler(telemetry: Telemetry, page: bytes):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args) -> None:
            pass

        def do_GET(self) -> None:
            if self.path in ("/", "/index.html"):
                self._send(200, "text/html; charset=utf-8", page)
            elif self.path == "/state":
                self._send(200, "application/json", json.dumps(telemetry.snapshot()).encode())
            elif self.path == "/events":
                self._events()
            else:
                self._send(404, "text/plain", b"not found")

        def _send(self, code: int, content_type: str, body: bytes) -> None:
            self.send_response(code)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _events(self) -> None:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            seen = -1
            try:
                while True:
                    with telemetry.lock:
                        telemetry.changed.wait_for(lambda: telemetry.version != seen, timeout=10)
                        seen = telemetry.version
                    snapshot = telemetry.snapshot()
                    snapshot["track"] = snapshot["track"][-1:]      # full track only in /state
                    self.wfile.write(b"data: " + json.dumps(snapshot).encode() + b"\n\n")
                    self.wfile.flush()
                    time.sleep(0.1)                                  # at most 10 updates per second
            except (BrokenPipeError, ConnectionResetError):
                pass

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser(description="Dhaksha Live viewer")
    parser.add_argument("--server", default="localhost", help="MediaMTX host (default localhost)")
    parser.add_argument("--path", default="live/drone1", help="stream path (default live/drone1)")
    parser.add_argument("--port", type=int, default=8080, help="web port (default 8080)")
    parser.add_argument("--rtsp-port", type=int, default=8554)
    parser.add_argument("--webrtc-port", type=int, default=8889)
    parser.add_argument("--hls-port", type=int, default=8888)
    args = parser.parse_args()

    page_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dhaksha_live.html")
    with open(page_path, encoding="utf-8") as page_file:
        page = (page_file.read()
                .replace("__STREAM_PATH__", args.path)
                .replace("__WEBRTC_PORT__", str(args.webrtc_port))
                .replace("__HLS_PORT__", str(args.hls_port))
                .encode())

    telemetry = Telemetry()
    url = f"rtsp://{args.server}:{args.rtsp_port}/{args.path}"
    threading.Thread(target=read_stream, args=(url, telemetry), daemon=True).start()

    server = ThreadingHTTPServer(("0.0.0.0", args.port), make_handler(telemetry, page))
    server.daemon_threads = True
    print(f"Dhaksha Live: http://localhost:{args.port}  (reading {url})")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

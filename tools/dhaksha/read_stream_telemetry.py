#!/usr/bin/env python3
"""Prints the position DhakshaGroundControl embeds in every video frame.

DhakshaGroundControl inserts an H.264 SEI "user data unregistered" message
(UUID "DHAKSHA-GCS-TLM1") with a JSON payload into each frame it forwards:

    {"ts": 1759320000123, "lat": 12.9716, "lon": 77.5946, "alt_msl": 921.4,
     "alt_rel": 35.2, "hdg": 87.0, "frame": 1234, "frame_ts": 1759320000140,
     "pts_ms": 49360}

ts       time the position was read (Unix ms, GCS clock)
frame_ts time the frame was forwarded (Unix ms, GCS clock)
alt_msl  altitude above mean sea level (m), alt_rel above home (m)
"no_position": true is sent while no vehicle position is available.

Usage (needs ffmpeg on the PATH):
    python3 read_stream_telemetry.py rtmp://server:1935/live/drone1
    python3 read_stream_telemetry.py recording.flv --csv out.csv
"""

import argparse
import csv
import json
import subprocess
import sys

UUID = b"DHAKSHA-GCS-TLM1"


def remove_emulation_prevention(data: bytes) -> bytes:
    out = bytearray()
    zeros = 0
    for byte in data:
        if zeros >= 2 and byte == 3:
            zeros = 0
            continue
        out.append(byte)
        zeros = zeros + 1 if byte == 0 else 0
    return bytes(out)


def sei_payloads(nal: bytes):
    """Yields the JSON payloads of DhakshaGroundControl SEI messages in one SEI NAL unit."""
    rbsp = remove_emulation_prevention(nal[1:])
    pos = 0
    while pos < len(rbsp) and rbsp[pos] != 0x80:
        payload_type = 0
        while rbsp[pos] == 0xFF:
            payload_type += 255
            pos += 1
        payload_type += rbsp[pos]
        pos += 1
        size = 0
        while rbsp[pos] == 0xFF:
            size += 255
            pos += 1
        size += rbsp[pos]
        pos += 1
        body = rbsp[pos:pos + size]
        pos += size
        if payload_type == 5 and body[:16] == UUID:
            yield body[16:]


def nal_units(stream):
    """Splits an Annex B byte stream into NAL units."""
    buffer = b""
    while True:
        chunk = stream.read(65536)
        if not chunk:
            break
        buffer += chunk
        parts = buffer.split(b"\x00\x00\x01")
        for part in parts[1:-1]:
            yield part.rstrip(b"\x00")
        buffer = b"\x00\x00\x01" + parts[-1] if len(parts) > 1 else buffer
    for part in buffer.split(b"\x00\x00\x01")[1:]:
        yield part.rstrip(b"\x00")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("source", help="RTMP URL or video file")
    parser.add_argument("--csv", help="also write the telemetry to this CSV file")
    args = parser.parse_args()

    ffmpeg = subprocess.Popen(
        ["ffmpeg", "-loglevel", "error", "-analyzeduration", "1000000", "-i", args.source, "-map", "0:v:0", "-c", "copy",
         "-bsf:v", "h264_mp4toannexb", "-f", "h264", "-"],
        stdout=subprocess.PIPE)

    fields = ["frame", "frame_ts", "pts_ms", "ts", "lat", "lon", "alt_msl", "alt_rel", "hdg", "no_position"]
    writer = None
    if args.csv:
        csv_file = open(args.csv, "w", newline="")
        writer = csv.DictWriter(csv_file, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()

    frames = with_telemetry = 0
    pending = False
    try:
        for nal in nal_units(ffmpeg.stdout):
            if not nal:
                continue
            nal_type = nal[0] & 0x1F
            if nal_type == 6:
                for payload in sei_payloads(nal):
                    data = json.loads(payload)
                    pending = True
                    if writer:
                        writer.writerow(data)
                    if data.get("no_position"):
                        print(f"frame {data.get('frame')}: no vehicle position")
                    else:
                        print(f"frame {data.get('frame'):>6}  lat {data['lat']:.7f}  lon {data['lon']:.7f}  "
                              f"alt_msl {data['alt_msl']:.1f} m  alt_rel {data['alt_rel']:.1f} m  hdg {data['hdg']:.0f}")
            elif nal_type in (1, 5) and len(nal) > 1 and nal[1] & 0x80:   # first slice of a frame
                frames += 1
                with_telemetry += 1 if pending else 0
                pending = False
    except KeyboardInterrupt:
        pass
    finally:
        ffmpeg.terminate()
    print(f"{frames} frames, {with_telemetry} with telemetry", file=sys.stderr)


if __name__ == "__main__":
    main()

# Dhaksha project history

What has been built for Dhaksha Drones in this repository (`mariappanchellam/qgroundcontrol-v4.2.3`),
in the order it was done, with a download link for every build.
Keep this file up to date at the end of every working session (see "Keeping the history" below).

Last updated: 2026-10-10.

## Keeping the history between Claude sessions

Claude does not remember earlier chats by itself, and a long chat is summarised when it gets too big,
so details can be lost. To keep the context:

1. **This file is the memory.** At the end of every session ask Claude:
   *"Update tools/dhaksha/HISTORY.md with what we did today and the new build links, then commit and push."*
2. **Start every new session with:**
   *"Read tools/dhaksha/HISTORY.md in mariappanchellam/qgroundcontrol-v4.2.3 first; it is the project history."*
3. **Save the chat itself as well** (optional): in Claude Code the `/export` command saves the current
   conversation to a text file; on claude.ai the chat stays in the left-hand history list.
4. **Keep the builds.** GitHub deletes build downloads after 90 days (the oldest ones below expire around
   late December 2026). Download the ones you want to keep, or ask Claude to publish them as a GitHub Release,
   which does not expire.

## The pieces

| Piece | What it is | Where the code is | Branch |
|---|---|---|---|
| **DhakshaGroundControl** | QGroundControl 4.2.3 rebranded for Dhaksha, for the SIYI MK15 (Android, 32-bit) | whole repository | `main` (+ fix on `fix/stream-forwarder-codec`) |
| **Dhaksha Live (phone)** | Android viewer, 4 Livepush players in a 2 x 2 grid | `tools/dhaksha/live-android` | `build/dhaksha-live-grid` |
| **Dhaksha Live (desktop)** | Windows / Linux viewer, 25 boxes, plays the Dhaksha video server (HLS or WebRTC) and Livepush | `tools/dhaksha/live-windows` | `build/dhaksha-live-grid` |
| **DhakshaKey** | Android app for the operator: pick camera, server IP, drone number and quality; writes `~/stream_<camera>.sh` and runs it in Termux (2.0: Dhaksha server on AWS) | `tools/dhaksha/key-android` | `build/dhaksha-key` |
| **Video server** | MediaMTX on AWS Lightsail: MK15 sends RTMP, viewers get HLS (:8888) or WebRTC (:8889) | `tools/dhaksha/stream_server.sh` | `main` |
| **Server test drones** | Sends looping test video as drone1..droneN from the server itself, to try Dhaksha Live with many boxes | `tools/dhaksha/server/stream_aws_test.sh` | `build/dhaksha-live-grid` |
| **Termux scripts** | ffmpeg commands that run on the MK15 (Livepush or own server, 144p to 720p) | `tools/dhaksha/termux` | `build/dhaksha-live-grid` |

## What was done, in order

### 24-25 Sep 2026: DhakshaGroundControl base
- Imported QGroundControl v4.2.3; GitHub Actions build a 32-bit Android APK (armeabi-v7a) for the MK15.
- Rebranded to DhakshaGroundControl (`com.dhaksha.groundcontrol`): Dhaksha Drones logo (vectorised to
  `resources/QGCLogoFull.svg`), app icons, installer images, animated drone boot screen.
- Parameters page protected by a password; flight mode menu and Flight Mode 1-6 setup limited to six modes.

### 28-30 Sep 2026: flying features
- Fly view camera photo/video panel hidden; motor test sliders replaced by a button-based test page.
- Vendor-neutral gimbal camera with Skydroid control, a camera command log and a Skydroid simulator.

### 1-4 Oct 2026: sending the camera video to the internet
- "Stream to Server": DhakshaGroundControl forwards the camera video to an RTMP server, with the vehicle
  position embedded in every frame; several fixes for codec data and safe shutdown.
- UVC camera support disabled on Android; x86_64 emulator build and 64-bit Linux desktop build added.
- Tools: fake camera script, public video server script (`stream_server.sh`, MediaMTX), first Dhaksha Live viewer.
- RTSP RECORD forwarding tried and then reverted.

### 5-7 Oct 2026: crash investigation and the Livepush method
- "Stream to Server" crashes inside GStreamer 1.18.6's rtmp2sink on the MK15. Fix on branch
  `fix/stream-forwarder-codec`: forward only H.264 and stop leaking bus messages. **Not merged; crash
  investigation paused.** Workaround: leave "Stream to Server" unticked and stream with Termux + ffmpeg.
- Company method adopted: Termux on the MK15 runs ffmpeg from the camera's RTSP to Livepush
  (`rtmp://stream.livepush.io/live/<key>`); viewers open `https://player.livepush.io/live/<id>`.
- DhakshaKey app: checks the camera feed (H.264 / H.265), then writes `stream.sh`, `stream_udp.sh` and the
  Termux:Boot script for the right codec. **Paused before testing on the MK15.**
- Dhaksha Live phone app: 4 Livepush boxes (2 x 2), addresses page, Dhaksha logo on empty boxes.
- Dhaksha Live Windows app: 8 boxes with hover effects, installer and portable .exe, animated logo,
  flying drone on click.

### 8 Oct 2026: Linux, delay and own server
- Dhaksha Live packages for Linux 64-bit and 32-bit (.deb, AppImage, .tar.gz). The 32-bit build uses
  Electron 18, the last version that supports 32-bit Linux.
- ZR10 camera (`rtsp://192.168.144.25:8554/main.264`): re-encoding scripts at 720p/480p, then at 144p-720p
  for a 250 kbit/s upload (`tools/dhaksha/termux`). A full picture every second; the upload bitrate is kept
  below the link speed so the delay does not build up.
- Finding: Livepush's web player adds about 20 s of delay by itself (HLS buffering); smaller video does
  not change that.
- Own video server on AWS Lightsail with `stream_server.sh` (MediaMTX). The MK15 sends with
  `stream_aws.sh`. HLS on port 8888 works; WebRTC on port 8889 showed "peer connection closed" (UDP 8189
  probably blocked; to be investigated). Server firewall: TCP 1935, 8888, 8889 and UDP 8189 (+ TCP 8189 fallback).
- Dhaksha Live desktop 3.0: 25 boxes (5 x 5) for drone1 to drone25 on the server. Plays HLS or WebRTC
  inside the app, keeps the Dhaksha logo until the video plays, start-up splash shows only the logo on
  dark blue.

### 9 Oct 2026: test drones on the server
- `tools/dhaksha/server/stream_aws_test.sh`: runs on the AWS server in a second SSH session and sends
  10 test drones (labelled "TEST DRONE n" with a running clock; or your own .mp4) to the server's own
  MediaMTX. The video is prepared once and then copied in a loop, so it hardly uses the server's CPU.
  `stream_aws.sh` on the MK15 is unchanged.
- Dhaksha Live desktop 3.1: a tick box per drone. Only ticked drones get a box and a player (unticked ones
  use no CPU or network), and the grid sizes itself to the ticked drones (1 = full window, 4 = 2 x 2, 9 = 3 x 3).

- `stream_aws_internetspeed.sh` (MK15; `stream_aws.sh` stays as the fixed 360p version): choose the quality,
  `bash ~/stream_aws_internetspeed.sh <SERVER_IP> [auto|720|480|360|240|copy] [drone]`.
  `auto` (default) starts at 480p, steps down within seconds when video starts queuing in the phone's upload
  (read from the kernel's connection table, so the delay is measured directly) and tries one step up after
  3 minutes of smooth sending (10 minutes after it had to step down). Sizes: 720p 20 fps 1000k, 480p 15 fps 500k,
  360p 15 fps 250k, 240p 10 fps 120k; `copy` sends the camera's own video unchanged.

- 9 Oct: the adaptive `stream_aws_internetspeed.sh` is parked for later. In use: `stream_aws.sh` (fixed 360p)
  with a default server IP inside, started at boot by `boot_stream_aws.sh`.

### 10 Oct 2026: DhakshaKey 2.0 for the AWS server
- One simple screen for a novice operator: camera type (ZR10, SIYI A8 mini, Skydroid, ViewPro, Other) with its IP
  filled in (ZR10 192.168.144.25, Skydroid .108, ViewPro .119; editable and remembered), server IP
  (default 15.252.170.151), drone number 1-25, video quality (240p-720p, 360p default), optional start at
  power-on. OK checks the camera, writes the tested `stream_aws.sh` with these values to `~/stream_<camera>.sh`
  and runs it in a Termux window; Stop ends it. Tested here: the generated script streams to a MediaMTX
  server set up like the AWS one.

- Position on the video: DhakshaGroundControl's MAVLink forwarding (Application Settings > MAVLink, host
  localhost:14445) feeds `dhaksha_telemetry.py` in Termux, which keeps `~/telemetry.txt` up to date; ffmpeg's
  drawtext writes it on every frame (`stream_aws_position.sh`). Tested here with simulated MAVLink and camera.

- DhakshaKey 2.1: fixes 2.0 not streaming (its stop step killed its own Termux command); adds "Show the drone's
  position on the video".

- Black video found: Termux's ffmpeg 8.1 turns `scale=-2:360` into a 2x360 picture. All scripts and DhakshaKey 2.2
  now write the width out (640x360 etc.) and decode with `-flags2 +fast`. MK15 tests: decoding the ZR10's
  1280x720 30 fps alone runs at about real time, so the MK15 has little spare processor.

- DhakshaKey 2.3: no boot setup at all (nothing written or deleted in ~/.termux/boot). It writes the operator's
  camera, IP, server, drone and quality into `~/stream_aws_<camera>.sh` and runs it in Termux; the script retries
  every 3 s when the camera or internet drops.

- DhakshaKey 2.4: a stall watchdog in the stream script. The MK15 test showed the processor keeps up (1.0x is the
  most a live camera can give), so the sudden speed drops are stalls in the camera radio link or the mobile upload.
  Every 2 s the script checks ffmpeg's progress and restarts it at once when the video stops moving or runs
  below 0.8x for 8 s, or when more than 2 s of video waits in the phone's upload queue (/proc/net/tcp, port 1935)
  for 4 s. The restart drops the backlog, so the delay never keeps growing. The Termux window says which side
  stalled: `internet stalled (N KB waiting to upload)` or `camera / radio link stalled`. ffmpeg also gives up on
  the camera after 5 s without data (`-timeout 5000000`). Tested locally with a frozen fake camera and a 40 kbit/s
  throttled upload: both restarted within about 5-10 s and streaming came back.

- DhakshaKey 2.5: first dropdown "Where are you?": Office / field (real camera, as before) or Home (no camera).
  Home writes `~/stream_aws_home.sh`, which sends `test.mp4` (from ~/, ~/storage/shared/Download or
  /sdcard/Download; needs `termux-setup-storage` once) with `-re -stream_loop -1`, same quality, retry and watchdog.
  The test video given to the operator: 1280x720, 30 fps, 60 s, 2.5 Mbit/s H.264, made with
  `ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30:duration=60` plus "DHAKSHA TEST VIDEO" and a running
  time stamp drawn on it (`drawtext ... text='%{pts\:hms}'`). Tested locally across the loop point: 640x360 arrives.
- Home test video replaced by an original cat-and-mouse cartoon (drawn with Python/PIL, 1280x720, 30 fps, 60 s,
  still named test.mp4); real Tom and Jerry is copyrighted and free cartoons could not be downloaded.
- DhakshaKey 2.6: Home always writes a made-up position on the video (`dhaksha_telemetry.py --fake`: slow circles
  over Chennai, 30-50 m, the line marked `DRONE<n> TEST`), so no drone or DhakshaGroundControl is needed at home.
  Office keeps the real position from DhakshaGroundControl's MAVLink forwarding (box now ticked by default).
  No mode switch was added to DhakshaGroundControl: the Home / Office choice in DhakshaKey decides it.
  The video always comes first: the text file is written before ffmpeg starts (a missing file makes drawtext
  fail) and the text is tried on a tiny test picture first; if ffmpeg cannot draw it, the stream goes without it.
  Tested: Home (cartoon + TEST position), Office (camera + fake MAVLink drone), Office with no drone data
  ("no position" line, video fine) and Office with drawtext failing (video sent without the text).
- DhakshaGroundControl (fix/stream-forwarder-codec, aef10f5): the in-app "Stream to Server" forwarder crashed the
  app when run alongside DhakshaKey; a tick saved during earlier tests kept it on at every start. It now always
  starts off (saved tick cleared) and its three settings rows are hidden. The forwarder code stays for later.
- DhakshaKey 2.7: recovery for field use. The stream script first stops any run left over (Termux closed, started
  twice): old script and position reader by PID file and by name, then ffmpeg, so nothing keeps the camera, the
  upload or UDP 14445. A stuck ffmpeg gets SIGKILL 3 s after SIGTERM. The watchdog restarts a dead position reader;
  the reader retries binding 14445 every 2 s (shown on the video) and survives socket/file errors. Tested: kill -9
  then restart, started twice, reader killed, port held by another program, 20 s upload outage (back by itself
  ~10 s after the link returned). MediaMTX 1.12.3 lets a reconnecting drone replace its old session.
- DhakshaGroundControl (1ba5b11): Skydroid C13 (visible + thermal + laser rangefinder). Same Topotek protocol as
  the C10; additions from ArduPilot's AP_Mount_Topotek: Laser Range button (LRF 02, reply "12345.6" m or ERR shown
  on the button), gimbal speeds also as one YPR frame (pitch positive = down) next to GSY/GSP, "#tp"
  variable-length replies parsed (also split across datagrams), and camera replies (except the REC heartbeat)
  in the Log. Checked by compiling against Qt 5 with a fake C13 on UDP 5000; not yet tried on a real C13.
  C13 RTSP path not confirmed (C10: rtsp://IP:554/stream=0; try stream=1 if no video).
- 2026-10-12: the fleet (25 drones) uses the Skydroid camera only. Addresses found on the camera:
  day rtsp://192.168.144.108:554/stream=1, night (thermal) rtsp://192.168.144.108:555/stream=2.
  tools: skydroid_check.sh (given to the operator) finds the camera, tries day/night addresses and UDP 5000.
- DhakshaKey 2.8 / 2.9: Skydroid night (thermal) camera; Skydroid day and night first with the real addresses,
  day the default; camera choice saved by name. Starting one Skydroid script stops the other (same drone link).
- DhakshaGroundControl (7cac360): Skydroid defaults to the real day/night addresses (old saved 554/stream=0
  replaced), Night RTSP URL setting, and "Show Night Camera / Show Day Camera" on the camera panel; Apply goes
  back to day. Checked by compiling the manager against Qt 5 without the QGC parts.
- DhakshaGroundControl (3016ac1): camera controls redesigned for a 5.5" screen like the Skydroid app: icon buttons
  split around the video (left: record, photo, proportional gimbal joystick; right: centre, tilt down 90 (PTZ 0A),
  lock/follow, zoom, laser; top: night/day, PIP, IR, focus, log), floating labels (REC time, range, confirmations),
  smaller compass/attitude HUD, telemetry values at the bottom. Gimbal speed resent every 100 ms (was 300) and only
  one command style (YPR default; GSY/GSP selectable under Gimbal Camera), laser as continuous toggle (LRF 03).
  Field report before this build: zoom, PIP and laser did not work on the C13, gimbal steppy, camera bitrate 2048k.
  tools: skydroid_probe.py (given to the operator) tries gimbal / zoom / PIP / tilt-down / laser command variants.
- 2026-10-13 field report: gimbal worked only the first time (not after app/MK15 restarts), no position on the
  video after enabling MAVLink forwarding, zoom / full-down not working, REC shown without an SD card.
- DhakshaGroundControl (fb817ca): MAVLink forwarding link bound the UDP listen port 14550 (same as the vehicle
  link), so it took the vehicle's packets and forwarded nothing; it now binds a free port. Skydroid control socket
  always uses local UDP 5000 (camera may remember its controller's port until powered off). Recording shown only
  when the camera confirms (REC); SD card status (SDC, NNNN = none, 0 = full) blocks photo/record with a message.
  IR icon removed. Operator: power-cycle the camera once after installing.
- DhakshaGroundControl (3d517f0): field Log showed every gimbal move stopped 60-80 ms later (MouseArea lost the
  touch on Android, onCanceled sent the stop; zoom holds the same). Joystick and icon buttons now use
  MultiPointTouchArea like QGC's JoystickThumbPad; joystick above other controls; telemetry bar right of the
  joystick at 80 % so it no longer covers it. Camera link itself was fine (green dot).
- Field test 2026-10-13: the C13 gimbal moves with GSY/GSP (C10 style) and ignores YPR.
- DhakshaGroundControl (c4d5a1e): GSY/GSP is the default gimbal command; zoom sends ZMC and DZM to both the lens
  (M) and image (D) address, repeated every 200 ms while held (C13 zoom command unpublished). The x86_64
  (emulator) job failed downloading GStreamer; the armeabi-v7a MK15 APK built.
- Field report: with c4d5a1e no camera controls were shown (settings wiped by a reinstall: camera None, forwarding
  off), position still missing on Dhaksha Live, delay accumulating in the viewer.
- DhakshaGroundControl (c439b21 + 47cb509): Skydroid is the default camera and MAVLink forwarding to
  localhost:14445 is on by default. CI: GStreamer server flaky (1.18.6 / 1.20.x downloads failed, fallback 1.22.12
  does not compile QGC 4.2's qmlglsink); 1.22 dropped from fallbacks, harder retries, GStreamer cached.
  This build used 1.18.6.
- Dhaksha Live 3.2.0 (ccd4508): HLS back buffer 2 s (was infinite), forward buffer 4-6 s, jump to live when more than
  3 s behind.
- tools: position_check.sh (given to the operator) checks forwarding -> UDP 14445 -> reader -> drawtext.
- Field results: position_check showed everything OK on the MK15 except 0 MAVLink packets; the ground station showed
  "Disconnected" (no flight-controller link), so there was no position to forward. Zoom went in one step and back.
- DhakshaGroundControl (b2e1e35): DZM takes the digital zoom level (02 = 2x, 00 = 1x); holding zoom steps the level
  1x..30x every 0.4 s and releasing keeps it, shown in the label; ZMC still runs while held.
- Field results 2026-10-14: zoom works 1x-4x (DZM 05 does nothing); full down (PTZ 0A) ignored; laser shows no value.
- DhakshaGroundControl (ae00fc8): zoom capped at 4x ("Max zoom 4x"); tilt down also drives GSP -99 for 3 s then stops;
  laser on/off/single/read sent to M, D and G addresses, label "measuring..." then distance or "no reply from laser";
  bigger range label; PIP, auto focus and lock/follow buttons removed; HUD width 12 font widths.
- Field: full down works (GSP drive). Requests: zoom beyond 4x, smaller icons and knob, HUD hiding the time stamp.
- DhakshaGroundControl (0d88cdb): zoom to 30x = camera DZM up to 4x, then the video magnified on screen up to 7.5x
  (stream and recordings stay at the camera's 4x); buttons 1.9 / 1.6 font heights, joystick 5.5 with a small knob;
  HUD below the camera's date/time stamp.
- Field 2026-10-14: zoom to 30x confirmed; lat/lon/alt stitching confirmed working with the Skydroid. ViewPro: video and
  position stitching ready (stream_aws_viewpro.sh, camera_check.sh), gimbal control not yet (needs model / wiring).
- Dhaksha Live note: every box has its own player, so the same drone in N boxes costs N times the download.
- DhakshaKey 3.0 (3dbc529): "Skydroid day + night (two streams)": day -> droneN, thermal -> droneN-night on the same
  MediaMTX (no server change), two send loops with their own watchdogs, one position reader, thermal fitted + padded.
- 15 Oct 2026: the field scripts given to the operator are now in `tools/dhaksha/termux` (credentials replaced by
  placeholders): Skydroid day / night / day + night, ViewPro, Home, the check tools and the probes.
  WebRTC blocking explained: the server must advertise its public IP (`webrtcAdditionalHosts`) and Lightsail must
  allow UDP 8189 (TCP 8189 as fallback for networks that block UDP) next to TCP 8889.
- DhakshaKey 4.0 (a21fd4d): one screen from the operator's sketch: message bar; camera (Dhaksha - Day, Night,
  Day + Night, ZR10) and quality (Normal 240p, Good 360p, Very Good 480p, Exceptional 720p) side by side; Camera IP
  and Server IP editable with Save buttons active only after an edit; Start / Stop side by side; password, home
  test and setup under "Setup and more". The script reports LIVE / SLOW / CAMERA / NET / DROP to UDP
  127.0.0.1:14446 and the bar shows "Data rate too low to stream video. Reduce resolution and try again." etc.
  Tested locally: camera off, server port closed and a 40 kbit/s upload each gave the right message, then Live.
  Added the INTERNET permission (the camera check could not connect without it). Termux scripts regenerated.
- Field test runbook written as a Claude doc (private, has the real password).

## Open items
- Find what blocks WebRTC (Lightsail UDP 8189 rule, TCP fallback, office/laptop network).
- Test Dhaksha Live 3.0 with the real server and several MK15s; check the delay with a stopwatch.
- GStreamer "Stream to Server" crash in DhakshaGroundControl: paused.
- DhakshaKey 2.0: test on the MK15 with the ZR10 and the AWS server.
- Phone Dhaksha Live still plays Livepush links only (4 boxes).
- Merge `fix/stream-forwarder-codec`, `build/dhaksha-key` and `build/dhaksha-live-grid` into `main` when approved.

## Using the streaming scripts (MK15, Termux)

The scripts in `tools/dhaksha/termux` have `YOUR_LIVEPUSH_KEY` / `YOUR_SERVER_PASSWORD` in place of the
real values, because this repository is public. Put the real values in your copy on the MK15.

| Script | Sends to | Size / data rate |
|---|---|---|
| `startgen_<size>.sh` | Livepush, one ffmpeg run | 144p 60k, 240p 100k, 360p 150k, 480p 180k, 720p 200k (10 fps) |
| `stream_<size>.sh` | Livepush, restarts when the stream drops | same |
| `autostart_<size>.sh` | Termux:Boot (copy only one into `~/.termux/boot/`) | runs `stream_<size>.sh` |
| `stream_aws.sh` | Own server: `bash ~/stream_aws.sh` (default server set inside) or `bash ~/stream_aws.sh <SERVER_IP>` | 360p 150k, 15 fps; watch `http://<SERVER_IP>:8888/live/drone1` |
| `stream_aws_position.sh` + `dhaksha_telemetry.py` | Like `stream_aws.sh`, with `DRONE1  Lat .. Lon .. Alt .. m  time` written on the video | DhakshaGroundControl: MAVLink forwarding on (localhost:14445); Termux: `pkg install python` |
| `boot_stream_aws.sh` | Termux:Boot: waits for camera and server, then runs `stream_aws.sh` (the boot script in use) | set `SERVER_IP` inside; copy to `~/.termux/boot/` |
| `boot_autostart_aws.sh` | Termux:Boot for the own server: waits for camera and server, then runs `stream_aws_internetspeed.sh` | set `SERVER_IP` (and `DRONE`) inside; copy to `~/.termux/boot/` |
| `stream_aws_skydroid.sh` / `_night.sh` | Skydroid C13 day (554/stream=1) or thermal (555/stream=2) with position, as DhakshaKey writes them | set `SERVER_IP` and `PASSWORD` inside |
| `stream_aws_skydroid_both.sh` | Skydroid day -> `droneN` and thermal -> `droneN-night` at the same time | same |
| `stream_aws_zr10.sh` | Dhaksha ZR10 with position | same |
| `stream_aws_viewpro.sh` | ViewPro camera (192.168.144.119) with position | same |
| `stream_aws_home.sh` | Home test: loops `test.mp4` with a made-up position (`dhaksha_telemetry.py --fake`) | same |
| `position_check.sh` | Checks forwarding -> UDP 14445 -> `dhaksha_telemetry.py` -> drawtext | `bash ~/position_check.sh` |
| `skydroid_check.sh` / `camera_check.sh` | Checks the Skydroid / ViewPro camera's network, RTSP and control port | `bash ~/skydroid_check.sh` |
| `gimbal_test.py` / `skydroid_probe.py` | Sends Skydroid gimbal / zoom / laser commands and prints the replies | `python ~/gimbal_test.py` |
| `stream_aws_internetspeed.sh` | Own server: `bash ~/stream_aws_internetspeed.sh <SERVER_IP> [auto\|720\|480\|360\|240\|copy] [drone]` | auto (default) adapts to the internet speed |

Test drones on the server (second SSH session on the AWS server):
`curl -fsSL -o stream_aws_test.sh https://raw.githubusercontent.com/mariappanchellam/qgroundcontrol-v4.2.3/build/dhaksha-live-grid/tools/dhaksha/server/stream_aws_test.sh`
then `PASSWORD=<server password> bash stream_aws_test.sh 10 2` (drone2..drone11, leaving drone1 for the MK15)
or `... 10 1` (drone1..drone10). Watch `http://<SERVER_IP>:8888/live/droneN`.

Copy to Termux: put the files in the MK15's Download folder, then
`termux-setup-storage; cp ~/storage/shared/Download/*.sh ~/; chmod +x ~/*.sh`.

## All builds

Download a build while logged in to GitHub; each link gives a zip with the APK / installer inside.
The newest build in each table is the last row.

### DhakshaGroundControl (Android APK, MK15)

| # | Date | Branch | Commit | Change | Download |
|---|---|---|---|---|---|
| 1 | 2026-09-24 | main | e5f7cec | ci: recreate v4.2.3 base tag when it is missing | [armeabi-v7a (45MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/35954751699/artifacts/10790029467) |
| 2 | 2026-09-24 | main | f850808 | feat: replace QGCLogoFull with the Dhaksha Drones logo | [armeabi-v7a (45MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/35956034473/artifacts/10790344363) |
| 3 | 2026-09-24 | main | a14169c | feat: Dhaksha app icons, installer images and Android boot screen | [armeabi-v7a (45MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/35964142207/artifacts/10794250388) |
| 4 | 2026-09-24 | main | f533d44 | feat: animated drone boot screen | [armeabi-v7a (45MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/35969771385/artifacts/10795644341) |
| 5 | 2026-09-24 | main | 67b5d36 | feat(Toolbar): spin the main toolbar logo slowly for liveliness | [armeabi-v7a (45MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/35991185203/artifacts/10804209213) |
| 6 | 2026-09-25 | main | e61ca5d | feat(ArduCopter): limit flight mode menu to six modes | [armeabi-v7a (45MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/36100049906/artifacts/10849446420) |
| 7 | 2026-09-25 | main | 1027b79 | feat: password-protect Parameters and remove toolbar brand logo | [armeabi-v7a (45MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/36107015003/artifacts/10851452914) |
| 8 | 2026-09-25 | main | 1ed94d0 | feat(ArduCopter): limit Flight Mode 1-6 setup choices to six modes | [armeabi-v7a (45MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/36121707498/artifacts/10858113892) |
| 9 | 2026-09-28 | main | cd1dc12 | feat(FlyView): hide camera photo/video control panel | [armeabi-v7a (45MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/36374758103/artifacts/10950173708) |
| 10 | 2026-09-28 | main | e888d51 | feat(Motors): replace motor test sliders with button-based test page | [armeabi-v7a (45MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/36406755565/artifacts/10962938216) |
| 11 | 2026-09-30 | main | 5eb5081 | feat(Camera): add vendor-neutral gimbal camera with Skydroid control | [armeabi-v7a (45MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/36700012388/artifacts/11089264903) |
| 12 | 2026-09-30 | main | 5aca3f1 | feat(Camera): add camera command log and Skydroid simulator | [armeabi-v7a (45MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/36702483053/artifacts/11091102894) |
| 13 | 2026-10-01 | main | 1139d3f | fix(Video): declare setStreamForwardUrl without override | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/36861187285/artifacts/11162955629) |
| 14 | 2026-10-01 | main | e81ea14 | feat(Video): embed vehicle position in every forwarded video frame | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/36936431002/artifacts/11197734545) |
| 15 | 2026-10-01 | main | e3a2031 | fix(Video): wait for codec_data before starting RTMP forwarding | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/36938058129/artifacts/11198968729) |
| 16 | 2026-10-02 | main | 3ce4606 | build(Android): disable UVC camera support | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/36964816769/artifacts/11209805641) |
| 17 | 2026-10-03 | main | 7591027 | feat(tools): add fake camera script for testing video without hardware | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37089694114/artifacts/11261643447) |
| 18 | 2026-10-03 | main | 226b641 | feat(tools): add public video server setup script | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37090716049/artifacts/11262529103) |
| 19 | 2026-10-03 | main | f151169 | feat(tools): add Dhaksha Live viewer and one-laptop demo | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37092663743/artifacts/11262921862) |
| 20 | 2026-10-03 | main | f7ebacc | fix(Android): link x86_64 with -Bsymbolic for GStreamer's libavcodec | [x86_64 (54MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37104775144/artifacts/11267952942)<br>[armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37104775144/artifacts/11267608213) |
| 21 | 2026-10-03 | main | e798734 | ci(Linux): build DhakshaGroundControl for 64-bit Linux desktops | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37106670819/artifacts/11268765731)<br>[x86_64 (54MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37106670819/artifacts/11268396934) |
| 22 | 2026-10-03 | main | c106ebc | feat(VideoReceiver): forward video to rtsp:// servers with RTSP RECORD | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37116598602/artifacts/11272445955)<br>[x86_64 (54MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37116598602/artifacts/11271732396) |
| 23 | 2026-10-03 | main | 72be9cb | revert: drop RTSP RECORD forwarding until RTMP is retested | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37120995855/artifacts/11273361503)<br>[x86_64 (54MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37120995855/artifacts/11273229316) |
| 24 | 2026-10-04 | main | fc07b76 | feat(tools): Dhaksha Live Android viewer app for the drone stream | [x86_64 (54MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37166588208/artifacts/11290485863)<br>[armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37166588208/artifacts/11289931645) |
| 25 | 2026-10-05 | main | fc07b76 | feat(tools): Dhaksha Live Android viewer app for the drone stream | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37373294274/artifacts/11371535841)<br>[x86_64 (54MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37373294274/artifacts/11371366099) |
| 26 | 2026-10-07 | fix/stream-forwarder-codec | 07cfd54 | fix(VideoReceiver): only forward H.264 to RTMP and stop leaking bus messages | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37587421241/artifacts/11467273491)<br>[x86_64 (54MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37587421241/artifacts/11467228541) |
| 27 | 2026-10-11 | fix/stream-forwarder-codec | aef10f5 | fix(Camera): keep in-app Stream to Server off; DhakshaKey streams instead | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38165216028/artifacts/11712805361)<br>[x86_64 (55MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38165216028/artifacts/11711732688) |
| 28 | 2026-10-12 | fix/stream-forwarder-codec | 1ba5b11 | feat(Camera): Skydroid C13 laser range, YPR gimbal rates and camera replies in the log | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38194991594/artifacts/11723705822)<br>[x86_64 (55MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38194991594/artifacts/11723805032) |
| 29 | 2026-10-12 | fix/stream-forwarder-codec | 7cac360 | feat(Camera): Skydroid day/night video addresses and a night camera switch | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38208593083/artifacts/11728391557)<br>[x86_64 (55MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38208593083/artifacts/11729355501) |
| 30 | 2026-10-12 | fix/stream-forwarder-codec | 3016ac1 | feat(Camera): icon camera controls split around the screen, smooth gimbal joystick | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38240716835/artifacts/11743054936)<br>[x86_64 (55MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38240716835/artifacts/11742844645) |
| 31 | 2026-10-13 | fix/stream-forwarder-codec | fb817ca | fix: MAVLink forwarding reaches the forwarding host; camera control after restarts; honest recording | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38350295409/artifacts/11790284769)<br>[x86_64 (55MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38350295409/artifacts/11790328976) |
| 32 | 2026-10-13 | fix/stream-forwarder-codec | 3d517f0 | fix(Camera): joystick and held buttons keep the touch on Android | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38357399802/artifacts/11794012918)<br>[x86_64 (55MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38357399802/artifacts/11794326520) |
| 33 | 2026-10-13 | fix/stream-forwarder-codec | c4d5a1e | fix(Camera): Skydroid zoom sends lens and digital zoom, repeated while held (+ GSY/GSP default) | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38364316896/artifacts/11796144821) |
| 34 | 2026-10-13 | fix/stream-forwarder-codec | 47cb509 | Skydroid and MAVLink forwarding defaults; CI GStreamer cache | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38374977934/artifacts/11800984587)<br>[x86_64 (55MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38374977934/artifacts/11800818513) |
| 35 | 2026-10-13 | fix/stream-forwarder-codec | b2e1e35 | fix(Camera): Skydroid digital zoom steps a level and keeps it | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38390368436/artifacts/11809250220)<br>[x86_64 (55MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38390368436/artifacts/11809073881) |
| 36 | 2026-10-14 | fix/stream-forwarder-codec | ae00fc8 | fix(Camera): Skydroid C13 tilt down, zoom up to 4x, laser on all addresses; simpler panel | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38508570498/artifacts/11869795819)<br>[x86_64 (55MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38508570498/artifacts/11869710985) |
| 37 | 2026-10-14 | fix/stream-forwarder-codec | 0d88cdb | feat(Camera): zoom up to 30x, smaller controls, HUD below the camera time stamp | [armeabi-v7a (46MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38516889598/artifacts/11872143471)<br>[x86_64 (55MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38516889598/artifacts/11872666351) |

### DhakshaGroundControl (Linux desktop)

| # | Date | Branch | Commit | Change | Download |
|---|---|---|---|---|---|
| 1 | 2026-10-08 | build/dhaksha-live-grid | 457fcef | DhakshaGroundControl for 64-bit Linux desktop (latest) | [linux-x86_64 (146MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37726566311/artifacts/11528675958) |

### Dhaksha Live (Android phone viewer)

| # | Date | Branch | Commit | Change | Download |
|---|---|---|---|---|---|
| 1 | 2026-10-04 | build/dhaksha-live | fc07b76 | feat(tools): Dhaksha Live Android viewer app for the drone stream | [DhakshaLive (4MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37166502860/artifacts/11289114875) |
| 2 | 2026-10-04 | main | fc07b76 | feat(tools): Dhaksha Live Android viewer app for the drone stream | [DhakshaLive (4MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37166588202/artifacts/11288674986) |
| 3 | 2026-10-07 | build/dhaksha-live-grid | 3efec1a | feat(tools): Dhaksha Live shows eight drone streams in one grid | [DhakshaLive (4MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37698537777/artifacts/11516606988) |
| 4 | 2026-10-07 | build/dhaksha-live-grid | 9946f69 | feat(tools): Dhaksha Live plays Livepush player links; Windows app with eight boxes | [DhakshaLive (<1MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37700577009/artifacts/11517087781) |

### Dhaksha Live (Windows desktop)

| # | Date | Branch | Commit | Change | Download |
|---|---|---|---|---|---|
| 1 | 2026-10-07 | build/dhaksha-live-grid | 9946f69 | feat(tools): Dhaksha Live plays Livepush player links; Windows app with eight boxes | [DhakshaLive-Windows (70MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37700577057/artifacts/11517044192) |
| 2 | 2026-10-07 | build/dhaksha-live-grid | efe4505 | feat(tools): Windows installer and icon for Dhaksha Live | [DhakshaLive-Windows (155MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37701302301/artifacts/11517891118) |
| 3 | 2026-10-07 | build/dhaksha-live-grid | 7a8d37c | feat(tools): animated Dhaksha logo and flying drone in the Windows viewer | [DhakshaLive-Windows (155MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37702016062/artifacts/11517881931) |
| 4 | 2026-10-08 | build/dhaksha-live-grid | fa2aea2 | feat(tools): Linux 64-bit and 32-bit packages for Dhaksha Live | [DhakshaLive-Windows (155MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37725700853/artifacts/11527424317) |
| 5 | 2026-10-08 | build/dhaksha-live-grid | 1d49cf1 | feat(tools): Dhaksha Live desktop shows 25 drones from the Dhaksha video server | [DhakshaLive-Windows (162MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37850558059/artifacts/11582090375) |
| 6 | 2026-10-09 | build/dhaksha-live-grid | 96a9bc8 | feat(tools): choose which drones Dhaksha Live shows (3.1.0) | [DhakshaLive-Windows (162MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37861319829/artifacts/11586512398) |
| 7 | 2026-10-13 | build/dhaksha-live-grid | ccd4508 | fix(tools): Dhaksha Live drops old video and jumps back to live (3.2.0) | [DhakshaLive-Windows (170MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38373769405/artifacts/11799633613) |

### Dhaksha Live (Linux desktop)

| # | Date | Branch | Commit | Change | Download |
|---|---|---|---|---|---|
| 1 | 2026-10-08 | build/dhaksha-live-grid | fa2aea2 | feat(tools): Linux 64-bit and 32-bit packages for Dhaksha Live | [DhakshaLive-Linux-64bit (271MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37725700871/artifacts/11528260108)<br>[DhakshaLive-Linux-32bit (210MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37725700871/artifacts/11527772119) |
| 2 | 2026-10-08 | build/dhaksha-live-grid | 457fcef | ci(tools): upload the Dhaksha Live Linux .deb as its own artifact | [DhakshaLive-Linux-64bit-other (199MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37726566375/artifacts/11528465215)<br>[DhakshaLive-Linux-64bit-deb (71MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37726566375/artifacts/11528450274)<br>[DhakshaLive-Linux-32bit-other (153MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37726566375/artifacts/11527309310)<br>[DhakshaLive-Linux-32bit-deb (56MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37726566375/artifacts/11527049769) |
| 3 | 2026-10-08 | build/dhaksha-live-grid | 1d49cf1 | feat(tools): Dhaksha Live desktop shows 25 drones from the Dhaksha video server | [DhakshaLive-Linux-64bit-deb (73MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37850558072/artifacts/11581877950)<br>[DhakshaLive-Linux-64bit-other (208MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37850558072/artifacts/11581927842)<br>[DhakshaLive-Linux-32bit-deb (58MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37850558072/artifacts/11581489019)<br>[DhakshaLive-Linux-32bit-other (162MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37850558072/artifacts/11581289226) |
| 4 | 2026-10-09 | build/dhaksha-live-grid | 96a9bc8 | feat(tools): choose which drones Dhaksha Live shows (3.1.0) | [DhakshaLive-Linux-64bit-deb (73MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37861319773/artifacts/11586014202)<br>[DhakshaLive-Linux-64bit-other (208MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37861319773/artifacts/11586730153)<br>[DhakshaLive-Linux-32bit-deb (58MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37861319773/artifacts/11586418303)<br>[DhakshaLive-Linux-32bit-other (162MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37861319773/artifacts/11586577291) |
| 5 | 2026-10-13 | build/dhaksha-live-grid | ccd4508 | fix(tools): Dhaksha Live drops old video and jumps back to live (3.2.0) | [DhakshaLive-Linux-64bit-deb (77MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38373769395/artifacts/11799704700)<br>[DhakshaLive-Linux-32bit-deb (61MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38373769395/artifacts/11800730229) |

### DhakshaKey (Android helper for Termux)

| # | Date | Branch | Commit | Change | Download |
|---|---|---|---|---|---|
| 1 | 2026-10-07 | build/dhaksha-key | 20a0542 | feat(tools): DhakshaKey app that starts the Livepush ffmpeg stream in Termux | [DhakshaKey (<1MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37607170813/artifacts/11475028777) |
| 2 | 2026-10-07 | build/dhaksha-key | 749e4e1 | feat(tools): DhakshaKey checks the camera feed and picks the stream command by codec | [DhakshaKey (<1MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37696732886/artifacts/11516225000) |
| 3 | 2026-10-10 | build/dhaksha-key | 3a54b46 | feat(tools): DhakshaKey 2.0 streams to the Dhaksha video server on AWS | [DhakshaKey 2.0 (<1MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38008721060/artifacts/11652930558) |
| 4 | 2026-10-10 | build/dhaksha-key | 0f7250d | fix(tools): DhakshaKey starts the stream in Termux; optional position on the video (2.1) | [DhakshaKey 2.1 (<1MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38027557011/artifacts/11660647689) |
| 5 | 2026-10-10 | build/dhaksha-key | 091f412 | fix(tools): DhakshaKey streams a real 640x360 picture with Termux's ffmpeg 8 (2.2) | [DhakshaKey (<1MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38038942195/artifacts/11664997170) |
| 6 | 2026-10-10 | build/dhaksha-key | b3296a9 | refactor(tools): DhakshaKey only writes and runs the stream script, no boot setup (2.3) | [DhakshaKey (<1MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38039587271/artifacts/11665515922) |
| 7 | 2026-10-10 | build/dhaksha-key | 7dc4cd9 | feat(dhaksha-key): restart the stream when the camera or upload stalls (2.4) | [DhakshaKey (<1MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38049072696/artifacts/11668384170) |
| 8 | 2026-10-11 | build/dhaksha-key | 9ef2f95 | feat(dhaksha-key): Home / Office choice; Home streams test.mp4 without a camera (2.5) | [DhakshaKey (<1MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38124939567/artifacts/11696683690) |
| 9 | 2026-10-11 | build/dhaksha-key | dca8e3a | feat(dhaksha-key): Home sends a TEST position, Office the real one; text never stops the video (2.6) | [DhakshaKey (<1MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38164333517/artifacts/11711024215) |
| 10 | 2026-10-12 | build/dhaksha-key | df03141 | fix(dhaksha-key): recover from unclean stops, a dead position reader and a stuck upload (2.7) | [DhakshaKey (<1MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38194808974/artifacts/11722188647) |
| 11 | 2026-10-12 | build/dhaksha-key | 1eff12e | feat(dhaksha-key): Skydroid first, with its real day and night addresses (2.9) | [DhakshaKey (<1MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38208473806/artifacts/11728780347) |
| 12 | 2026-10-14 | build/dhaksha-key | 3dbc529 | feat(dhaksha-key): Skydroid day + night as two streams at once (3.0) | [DhakshaKey (<1MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/38556096097/artifacts/11889732072) |


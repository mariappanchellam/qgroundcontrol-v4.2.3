# Dhaksha project history

What has been built for Dhaksha Drones in this repository (`mariappanchellam/qgroundcontrol-v4.2.3`),
in the order it was done, with a download link for every build.
Keep this file up to date at the end of every working session (see "Keeping the history" below).

Last updated: 2026-10-09.

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
| **DhakshaKey** | Android helper: checks the camera feed and writes/starts the ffmpeg scripts in Termux | `tools/dhaksha/key-android` | `build/dhaksha-key` |
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

## Open items
- Find what blocks WebRTC (Lightsail UDP 8189 rule, TCP fallback, office/laptop network).
- Test Dhaksha Live 3.0 with the real server and several MK15s; check the delay with a stopwatch.
- GStreamer "Stream to Server" crash in DhakshaGroundControl: paused.
- DhakshaKey: test on the MK15 (paused).
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
| `boot_stream_aws.sh` | Termux:Boot: waits for camera and server, then runs `stream_aws.sh` (the boot script in use) | set `SERVER_IP` inside; copy to `~/.termux/boot/` |
| `boot_autostart_aws.sh` | Termux:Boot for the own server: waits for camera and server, then runs `stream_aws_internetspeed.sh` | set `SERVER_IP` (and `DRONE`) inside; copy to `~/.termux/boot/` |
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

### Dhaksha Live (Linux desktop)

| # | Date | Branch | Commit | Change | Download |
|---|---|---|---|---|---|
| 1 | 2026-10-08 | build/dhaksha-live-grid | fa2aea2 | feat(tools): Linux 64-bit and 32-bit packages for Dhaksha Live | [DhakshaLive-Linux-64bit (271MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37725700871/artifacts/11528260108)<br>[DhakshaLive-Linux-32bit (210MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37725700871/artifacts/11527772119) |
| 2 | 2026-10-08 | build/dhaksha-live-grid | 457fcef | ci(tools): upload the Dhaksha Live Linux .deb as its own artifact | [DhakshaLive-Linux-64bit-other (199MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37726566375/artifacts/11528465215)<br>[DhakshaLive-Linux-64bit-deb (71MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37726566375/artifacts/11528450274)<br>[DhakshaLive-Linux-32bit-other (153MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37726566375/artifacts/11527309310)<br>[DhakshaLive-Linux-32bit-deb (56MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37726566375/artifacts/11527049769) |
| 3 | 2026-10-08 | build/dhaksha-live-grid | 1d49cf1 | feat(tools): Dhaksha Live desktop shows 25 drones from the Dhaksha video server | [DhakshaLive-Linux-64bit-deb (73MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37850558072/artifacts/11581877950)<br>[DhakshaLive-Linux-64bit-other (208MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37850558072/artifacts/11581927842)<br>[DhakshaLive-Linux-32bit-deb (58MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37850558072/artifacts/11581489019)<br>[DhakshaLive-Linux-32bit-other (162MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37850558072/artifacts/11581289226) |
| 4 | 2026-10-09 | build/dhaksha-live-grid | 96a9bc8 | feat(tools): choose which drones Dhaksha Live shows (3.1.0) | [DhakshaLive-Linux-64bit-deb (73MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37861319773/artifacts/11586014202)<br>[DhakshaLive-Linux-64bit-other (208MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37861319773/artifacts/11586730153)<br>[DhakshaLive-Linux-32bit-deb (58MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37861319773/artifacts/11586418303)<br>[DhakshaLive-Linux-32bit-other (162MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37861319773/artifacts/11586577291) |

### DhakshaKey (Android helper for Termux)

| # | Date | Branch | Commit | Change | Download |
|---|---|---|---|---|---|
| 1 | 2026-10-07 | build/dhaksha-key | 20a0542 | feat(tools): DhakshaKey app that starts the Livepush ffmpeg stream in Termux | [DhakshaKey (<1MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37607170813/artifacts/11475028777) |
| 2 | 2026-10-07 | build/dhaksha-key | 749e4e1 | feat(tools): DhakshaKey checks the camera feed and picks the stream command by codec | [DhakshaKey (<1MB)](https://github.com/mariappanchellam/qgroundcontrol-v4.2.3/actions/runs/37696732886/artifacts/11516225000) |


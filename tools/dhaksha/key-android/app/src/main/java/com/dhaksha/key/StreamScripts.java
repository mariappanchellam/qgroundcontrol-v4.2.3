package com.dhaksha.key;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Builds the Termux script that sends the drone camera to the Dhaksha video server (MediaMTX on AWS).
 *
 * The script is the tested stream_aws.sh with the operator's choices filled in: camera stream, server,
 * stream names, password, video size and how it uploads (RTMP, RTSP, SRT or WebRTC / WHIP, on an editable
 * port). It is saved as ~/stream_aws_<camera>.sh, e.g. ~/stream_aws_zr10.sh. Whatever it uploads with, the
 * server lets viewers watch over HLS (port 8888) or WebRTC (port 8889).
 * For testing at home without a camera, it loops test.mp4 from the Download folder instead and is saved as
 * ~/stream_aws_home.sh.
 */
final class StreamScripts {

    static final String DEFAULT_SERVER_IP = "15.252.170.151";
    static final String DEFAULT_PASSWORD = "Dhaksha2026";
    static final int MAX_DRONES = 25;

    static final String PID_FILE = "$HOME/.dhakshakey.pid";
    static final String TELEMETRY_PID_FILE = "$HOME/.dhaksha_telemetry.pid";
    static final String TELEMETRY_SCRIPT = "~/dhaksha_telemetry.py";
    static final int TELEMETRY_PORT = 14445;
    /** The script reports its state to DhakshaKey on this local UDP port (LIVE / SLOW / CAMERA / NET / DROP lines). */
    static final int STATUS_PORT = 14446;
    static final String SHEBANG = "#!/data/data/com.termux/files/usr/bin/bash";
    static final String HOME_SCRIPT = "~/stream_aws_home.sh";
    static final String TEST_VIDEO = "test.mp4";

    private static final String HEREDOC_END = "DHAKSHAKEY_END_OF_SCRIPT";
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern PASSWORD = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern STREAM_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    /** How the script sends the video to the server (MediaMTX). */
    static final class Upload {
        final String name;
        final String tag;
        final int defaultPort;
        /** The video itself goes over TCP to the port, so the phone's upload queue to it can be read. */
        final boolean tcpVideo;
        /** The port is TCP (the video, or the WebRTC offer), so the script can check the server answers on it. */
        final boolean tcpPort;
        /** What ffmpeg must have for this upload: a muxer (-muxers) or a protocol (-protocols); null for none. */
        final String needs;

        Upload(String name, String tag, int defaultPort, boolean tcpVideo, boolean tcpPort, String needs) {
            this.name = name;
            this.tag = tag;
            this.defaultPort = defaultPort;
            this.tcpVideo = tcpVideo;
            this.tcpPort = tcpPort;
            this.needs = needs;
        }

        /** The ffmpeg output for one stream, using the script's SERVER_IP, UPLOAD_PORT and PASSWORD. */
        String ffmpegOutput(String nameVar) {
            switch (tag) {
            case "rtsp":
                return "-f rtsp -rtsp_transport tcp \"rtsp://drone:$PASSWORD@$SERVER_IP:$UPLOAD_PORT/live/" + nameVar + "\"";
            case "srt":
                return "-f mpegts \"srt://$SERVER_IP:$UPLOAD_PORT?streamid=publish:live/" + nameVar
                        + ":drone:$PASSWORD&pkt_size=1316\"";
            case "whip":
                // MediaMTX takes the WHIP login as a Bearer token "user:password"
                return "-f whip -authorization \"drone:$PASSWORD\" \"http://$SERVER_IP:$UPLOAD_PORT/live/"
                        + nameVar + "/whip\"";
            default:
                return "-f flv \"rtmp://$SERVER_IP:$UPLOAD_PORT/live/" + nameVar + "?user=drone&pass=$PASSWORD\"";
            }
        }
    }

    static final Upload[] UPLOADS = {
        new Upload("RTMP (port 1935)", "rtmp", 1935, true, true, null),
        new Upload("RTSP (port 8554)", "rtsp", 8554, true, true, null),
        new Upload("SRT (port 8890, UDP)", "srt", 8890, false, false, "-protocols srt"),
        // ffmpeg 8 and newer, built with WebRTC (DTLS) support
        new Upload("WebRTC / WHIP (port 8889)", "whip", 8889, false, true, "-muxers whip"),
    };

    /** How viewers watch the stream on the server: the page at http://SERVER:PORT/live/NAME. */
    static final class Watch {
        final String name;
        final int port;

        Watch(String name, int port) {
            this.name = name;
            this.port = port;
        }
    }

    static final Watch[] WATCHES = {
        new Watch("HLS (port 8888)", 8888),
        new Watch("WebRTC (port 8889)", 8889),
    };

    /** A camera model: its default IP and how its RTSP address is built from the IP. */
    static final class Camera {
        final String name;
        final String fileTag;
        /** Cameras that are the same device (Dhaksha day / night) share one saved IP under this key. */
        final String ipKey;
        final String defaultIp;
        /** RTSP address with %s for the IP. */
        final String rtspTemplate;
        /** Second (night / thermal) RTSP address sent at the same time as droneN-night; null for one stream. */
        final String nightTemplate;

        Camera(String name, String fileTag, String ipKey, String defaultIp, String rtspTemplate) {
            this(name, fileTag, ipKey, defaultIp, rtspTemplate, null);
        }

        Camera(String name, String fileTag, String ipKey, String defaultIp, String rtspTemplate, String nightTemplate) {
            this.name = name;
            this.fileTag = fileTag;
            this.ipKey = ipKey;
            this.defaultIp = defaultIp;
            this.rtspTemplate = rtspTemplate;
            this.nightTemplate = nightTemplate;
        }

        String nightRtsp(String ip) {
            return nightTemplate == null ? null : String.format(Locale.ROOT, nightTemplate, ip);
        }

        String rtsp(String ip) {
            return String.format(Locale.ROOT, rtspTemplate, ip);
        }

        String scriptPath() {
            return "~/stream_aws_" + fileTag + ".sh";
        }

        boolean dual() {
            return nightTemplate != null;
        }
    }

    static final Camera[] CAMERAS = {
        // The Dhaksha (Skydroid C13) camera the fleet uses: day video on port 554, night (thermal) on port 555
        new Camera("Dhaksha - Day", "skydroid", "skydroid", "192.168.144.108", "rtsp://%s:554/stream=1"),
        new Camera("Dhaksha - Night", "skydroid_night", "skydroid", "192.168.144.108", "rtsp://%s:555/stream=2"),
        new Camera("Dhaksha - Day + Night", "skydroid_both", "skydroid", "192.168.144.108",
                   "rtsp://%s:554/stream=1", "rtsp://%s:555/stream=2"),
        new Camera("Dhaksha - ZR10", "zr10", "zr10", "192.168.144.25", "rtsp://%s:8554/main.264"),
    };

    /** A video size: the HEIGHT, FPS, BITRATE and BUFSIZE of the script. */
    static final class Quality {
        final String label;
        final int height;
        final int fps;
        final int kbps;

        Quality(String label, int height, int fps, int kbps) {
            this.label = label;
            this.height = height;
            this.fps = fps;
            this.kbps = kbps;
        }

        /** 16:9 width rounded to an even number, as H.264 needs: 240 -> 426, 360 -> 640, 480 -> 854, 720 -> 1280. */
        int width() {
            return Math.round(height * 16f / 9f / 2f) * 2;
        }
    }

    static final Quality[] QUALITIES = {
        new Quality("Normal - 240p", 240, 10, 100),
        new Quality("Good - 360p", 360, 15, 150),
        new Quality("Very Good - 480p", 480, 15, 300),
        new Quality("Exceptional - 720p", 720, 20, 800),
    };
    static final int DEFAULT_QUALITY = 1;

    private StreamScripts() {}

    /** Index of the upload with this tag, or 0 (RTMP) when there is none. */
    static int uploadIndex(String tag) {
        for (int i = 0; i < UPLOADS.length; i++) {
            if (UPLOADS[i].tag.equals(tag)) {
                return i;
            }
        }
        return 0;
    }

    /** Index of the camera with this file tag, or 0 (Dhaksha - Day) when there is none. */
    static int cameraIndex(String fileTag) {
        for (int i = 0; i < CAMERAS.length; i++) {
            if (CAMERAS[i].fileTag.equals(fileTag)) {
                return i;
            }
        }
        return 0;
    }

    static String droneName(int number) {
        return "drone" + number;
    }

    static String nightName(int number) {
        return droneName(number) + "-night";
    }

    static String watchUrl(String serverIp, Watch watch, String streamName) {
        return "http://" + serverIp + ":" + watch.port + "/live/" + streamName;
    }

    /** The upload address shown to the operator, with the password hidden. */
    static String uploadUrl(Upload upload, String serverIp, int port, String streamName) {
        String output = upload.ffmpegOutput(streamName)
                .replace("$SERVER_IP", serverIp).replace("$UPLOAD_PORT", String.valueOf(port))
                .replace("$PASSWORD", "****");
        int end = output.lastIndexOf('"');
        return output.substring(output.lastIndexOf('"', end - 1) + 1, end);
    }

    /** Host part of an rtsp:// address, or null when the address is not usable. */
    static String rtspHost(String rtspUrl) {
        if (!rtspUrl.regionMatches(true, 0, "rtsp://", 0, 7)) {
            return null;
        }
        String rest = rtspUrl.substring(7);
        int end = rest.length();
        for (char stop : new char[] { '/', '?' }) {
            int at = rest.indexOf(stop);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        String host = rest.substring(rest.lastIndexOf('@', end - 1) + 1, end);
        int port = host.indexOf(':');
        if (port >= 0) {
            host = host.substring(0, port);
        }
        return isHost(host) ? host : null;
    }

    static boolean isHost(String value) {
        if (IPV4.matcher(value).matches()) {
            for (String part : value.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            }
            return true;
        }
        return HOST.matcher(value).matches() && !value.matches("[0-9.]+");
    }

    /**
     * Returns what is wrong with the inputs in plain words, or null when the script can be made.
     * cameraRtsp is null for the home test video.
     */
    static String validate(String cameraRtsp, String serverIp, int droneNumber, String password) {
        return validate(cameraRtsp, serverIp, droneNumber, password, UPLOADS[0].defaultPort,
                        droneName(droneNumber), null);
    }

    /** As above, also checking the upload port and the stream names (nightName null for one stream). */
    static String validate(String cameraRtsp, String serverIp, int droneNumber, String password, int uploadPort,
                           String dayName, String nightName) {
        if (uploadPort < 1 || uploadPort > 65535) {
            return "The upload port must be a number from 1 to 65535.";
        }
        for (String name : new String[] { dayName, nightName }) {
            if (name != null && !STREAM_NAME.matcher(name).matches()) {
                return "A stream name may only use letters, numbers, '.', '_' and '-', like drone1 or drone1-night.";
            }
        }
        if (nightName != null && nightName.equals(dayName)) {
            return "The day and the night stream need different names.";
        }
        if (cameraRtsp != null && cameraRtsp.matches(".*[\\s'\"\\\\].*")) {
            return "The camera address must not contain spaces or quotes.";
        }
        if (cameraRtsp != null && rtspHost(cameraRtsp) == null) {
            return "The camera address is not right. It must look like rtsp://192.168.144.25:8554/main.264";
        }
        if (!isHost(serverIp)) {
            return "The server IP is not right. It must look like 15.252.170.151";
        }
        if (droneNumber < 1 || droneNumber > MAX_DRONES) {
            return "Choose a drone number from 1 to " + MAX_DRONES + ".";
        }
        if (!PASSWORD.matcher(password).matches()) {
            return "The server password may only use letters, numbers, '.', '_' and '-'.";
        }
        return null;
    }

    /**
     * The streaming script: the tested stream_aws.sh with the chosen values. With showPosition the
     * drone's lat, lon and altitude (forwarded by DhakshaGroundControl, read by dhaksha_telemetry.py)
     * are written at the bottom of every frame. With camera null (home, no camera) it loops test.mp4
     * from the Download folder instead of the camera, at its real speed, and the position is a made-up
     * TEST one, so no drone or DhakshaGroundControl is needed. Uploads with RTMP and shows the HLS link.
     */
    static String streamScript(Camera camera, String cameraRtsp, String serverIp, int droneNumber,
                               String password, Quality quality, boolean showPosition) {
        return streamScript(camera, cameraRtsp, null, serverIp, droneName(droneNumber), null, password, quality,
                            showPosition, UPLOADS[0], UPLOADS[0].defaultPort, WATCHES[0]);
    }

    /**
     * With nightRtsp, the night (thermal) camera is sent at the same time as nightName: two ffmpeg
     * loops, each with its own watchdog, and one position reader feeding the text on both.
     */
    static String streamScript(Camera camera, String cameraRtsp, String nightRtsp, String serverIp, String dayName,
                               String nightName, String password, Quality quality, boolean showPosition,
                               Upload upload, int uploadPort, Watch watch) {
        boolean home = camera == null;
        boolean dual = !home && nightRtsp != null;
        return SHEBANG + "\n"
                + "# Made by DhakshaKey: " + (home ? "home test video " + TEST_VIDEO : camera.name + " camera")
                + " -> Dhaksha video server, "
                + quality.height + "p, about " + quality.kbps + " kbit/s, low delay.\n"
                + "# Upload: " + upload.name + "\n"
                + "# Watch: " + watchUrl(serverIp, watch, dayName) + "\n"
                + (dual ? "# Night: " + watchUrl(serverIp, watch, nightName) + "\n" : "")
                + "# Stop with Ctrl+C, or the Stop button in DhakshaKey.\n"
                + "\n"
                + "SERVER_IP=" + shellQuote(serverIp) + "\n"
                + "PASSWORD=" + shellQuote(password) + "\n"
                + "UPLOAD_PORT=" + uploadPort + "\n"
                + "DRONE=" + shellQuote(dayName) + "\n"
                + (dual ? "NIGHT_DRONE=" + shellQuote(nightName) + "\n" : "")
                + (home ? "" : "CAMERA=" + shellQuote(cameraRtsp) + "\n")
                + (dual ? "NIGHT_CAMERA=" + shellQuote(nightRtsp) + "\n" : "")
                + "WIDTH=" + quality.width() + "\n"
                + "HEIGHT=" + quality.height + "\n"
                + "FPS=" + quality.fps + "\n"
                + "BITRATE=" + quality.kbps + "k\n"
                + "BUFSIZE=" + (quality.kbps / 2) + "k\n"
                + "\n"
                + stopPrevious()
                + "echo $$ > " + PID_FILE + "\n"
                + "termux-wake-lock 2>/dev/null\n"
                + "echo \"Watch this drone at http://$SERVER_IP:" + watch.port + "/live/$DRONE\"\n"
                + (dual ? "echo \"Night camera at    http://$SERVER_IP:" + watch.port + "/live/$NIGHT_DRONE\"\n" : "")
                + "\n"
                + (upload.needs == null ? "" : uploadCheck(upload))
                + (home ? findTestVideo() : "")
                + "# The width is written out: ffmpeg 8 turned scale=-2:360 into a 2-pixel-wide picture\n"
                + "FILTER=\"scale=$WIDTH:$HEIGHT,fps=$FPS\"\n"
                + (showPosition || home ? positionOverlay(home) : "")
                + (dual
                    // The thermal picture is not 16:9: fit it and pad with black instead of stretching it
                    ? "NIGHT_FILTER=\"scale=$WIDTH:$HEIGHT:force_original_aspect_ratio=decrease,"
                      + "pad=$WIDTH:$HEIGHT:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=$FPS${FILTER#scale=$WIDTH:$HEIGHT,fps=$FPS}\"\n"
                    : "")
                + "STALL_SECONDS=8\n"
                + "# More than 2 s of video waiting to upload, for QUEUE_SECONDS, means the internet is not keeping up\n"
                + "QUEUE_LIMIT_KB=$(( ${BITRATE%k} / 4 ))\n"
                + "QUEUE_SECONDS=4\n"
                + "FFMPEG_PID=\"\"\n"
                + "LOOP_PIDS=\"\"\n"
                + "# ffmpeg stuck writing to a dead connection may ignore a polite stop, so it is forced after 3 s\n"
                + "stop_ffmpeg() {\n"
                + "  [ -n \"$FFMPEG_PID\" ] || return\n"
                + "  kill \"$FFMPEG_PID\" 2>/dev/null\n"
                + "  for i in 1 2 3 4 5 6; do kill -0 \"$FFMPEG_PID\" 2>/dev/null || return; sleep 0.5; done\n"
                + "  kill -9 \"$FFMPEG_PID\" 2>/dev/null\n"
                + "}\n"
                + "cleanup() {\n"
                + "  stop_ffmpeg\n"
                + "  [ -n \"$LOOP_PIDS\" ] && kill $LOOP_PIDS 2>/dev/null && wait $LOOP_PIDS 2>/dev/null\n"
                + "  kill $TELEMETRY_PID $(cat " + TELEMETRY_PID_FILE + " 2>/dev/null) 2>/dev/null\n"
                + "  rm -f " + PID_FILE + " " + TELEMETRY_PID_FILE + "; exit 0\n"
                + "}\n"
                + "trap cleanup INT TERM\n"
                + "# Tells DhakshaKey how the stream is doing (shown on its screen); nothing happens when it is closed\n"
                + "report() { { echo \"$*\" > /dev/udp/127.0.0.1/" + STATUS_PORT + "; } 2>/dev/null; }\n"
                + "# ffmpeg ended by itself: tell whether the camera, the server or neither can be reached\n"
                + "# why_stopped CAMERA_ADDRESS (an rtsp:// address, or the test video file)\n"
                + "why_stopped() {\n"
                + "  local hp=${1#rtsp://} port=554\n"
                + "  if [ \"$hp\" != \"$1\" ]; then\n"
                + "    hp=${hp%%/*}; hp=${hp##*@}\n"
                + "    case \"$hp\" in *:*) port=${hp##*:} ;; esac\n"
                + "    if ! timeout 3 bash -c \"exec 3<>/dev/tcp/${hp%%:*}/$port\" 2>/dev/null; then\n"
                + "      echo \"$(date '+%F %T') camera not reachable at ${hp%%:*}:$port\"; report \"CAMERA $DRONE\"; return\n"
                + "    fi\n"
                + "  fi\n"
                + (upload.tcpPort
                    ? "  if ! timeout 3 bash -c \"exec 3<>/dev/tcp/$SERVER_IP/$UPLOAD_PORT\" 2>/dev/null; then\n"
                      + "    echo \"$(date '+%F %T') server not reachable at $SERVER_IP:$UPLOAD_PORT (internet, server IP or port)\"\n"
                      + "    report \"NET $DRONE\"; return\n"
                      + "  fi\n"
                    : "")
                + "  report \"DROP $DRONE\"\n"
                + "}\n"
                + "\n"
                + "# KB of video waiting in this phone's upload queue to the server; empty when it cannot be read\n"
                + (upload.tcpVideo
                    ? "queued_kb() {\n"
                      + "  local hex\n"
                      + "  hex=$(awk -v port=\"$(printf '%04X' \"$UPLOAD_PORT\")\" 'FNR > 1 { split($3, r, \":\"); if (r[2] == port && $4 == \"01\") { split($5, q, \":\"); print q[1]; exit } }' /proc/net/tcp /proc/net/tcp6 2>/dev/null)\n"
                      + "  [ -n \"$hex\" ] && echo $(( 16#$hex / 1024 ))\n"
                      + "}\n"
                    : "# (" + upload.name + " sends the video over UDP, which has no upload queue to read)\n"
                      + "queued_kb() { :; }\n")
                + "\n"
                + "# One camera to one stream name, restarted by its own watchdog:\n"
                + "# stream_loop CAMERA NAME PROGRESS_FILE FILTER LABEL   (LABEL empty for the only stream)\n"
                + "stream_loop() {\n"
                + "local CAMERA=\"$1\" DRONE=\"$2\" PROGRESS=\"$3\" FILTER=\"$4\" LABEL=\"$5\"\n"
                + "# Run in the background next to another stream: stop its own ffmpeg when stopped\n"
                + "[ -n \"$LABEL\" ] && trap 'stop_ffmpeg; exit 0' INT TERM\n"
                + "while true; do\n"
                + "  echo \"$(date '+%F %T') sending to $SERVER_IP ($DRONE)\"\n"
                + "  : > \"$PROGRESS\"\n"
                + (home
                    ? "  # -re: send the file at its real speed, like a camera; -stream_loop -1: play it again and again\n"
                      + "  ffmpeg -nostdin -stats -progress \"$PROGRESS\" -flags2 +fast \\\n"
                      + "    -re -stream_loop -1 -i \"$VIDEO\" \\\n"
                    : "  # -timeout: give up on the camera after 5 s without data, so the loop reconnects\n"
                      + "  ffmpeg -nostdin -stats -progress \"$PROGRESS\" -fflags nobuffer -flags low_delay -flags2 +fast \\\n"
                      + "    -timeout 5000000 -rtsp_transport tcp -i \"$CAMERA\" \\\n")
                + "    -an -vf \"$FILTER\" \\\n"
                + "    -c:v libx264 -preset ultrafast -tune zerolatency -profile:v baseline -pix_fmt yuv420p \\\n"
                + "    -b:v \"$BITRATE\" -maxrate \"$BITRATE\" -bufsize \"$BUFSIZE\" \\\n"
                + "    -g \"$FPS\" -keyint_min \"$FPS\" -sc_threshold 0 \\\n"
                + "    " + upload.ffmpegOutput("$DRONE") + " &\n"
                + "  FFMPEG_PID=$!\n"
                + "  # Watchdog: when the video stops moving or runs below 0.8x for STALL_SECONDS, or piles up\n"
                + "  # waiting to upload for QUEUE_SECONDS, restart at once.\n"
                + "  # The video waiting behind the stall is dropped, so viewers get live video back instead of a growing delay.\n"
                + "  last=\"\"; stuck=0; slow=0; queued=0; starting=0; restarted=0\n"
                + "  while kill -0 \"$FFMPEG_PID\" 2>/dev/null; do\n"
                + "    sleep 2\n"
                + "    if [ \"$LABEL\" != night ] && [ -n \"$TELEMETRY_PID\" ] && ! kill -0 \"$TELEMETRY_PID\" 2>/dev/null; then\n"
                + "      echo \"$(date '+%F %T') position reader stopped: starting it again\"\n"
                + "      start_reader\n"
                + "    fi\n"
                + "    now=$(grep '^out_time_us=' \"$PROGRESS\" | tail -n 1)\n"
                + "    speed=$(grep '^speed=' \"$PROGRESS\" | tail -n 1 | sed 's/speed=//; s/x//; s/ //g')\n"
                + "    case \"$now\" in\n"
                + "    ''|*N/A) starting=$((starting + 2)) ;;\n"
                + "    \"$last\") stuck=$((stuck + 2)) ;;\n"
                + "    *) stuck=0 ;;\n"
                + "    esac\n"
                + "    last=\"$now\"\n"
                + "    case \"$now\" in ''|*N/A) ;; *) [ \"$stuck\" -eq 0 ] && report \"LIVE $DRONE\" ;; esac\n"
                + "    case \"$speed\" in\n"
                + "    ''|N/A|*[!0-9.]*) slow=0 ;;\n"
                + "    *) if awk -v s=\"$speed\" 'BEGIN { exit !(s < 0.8) }'; then slow=$((slow + 2)); else slow=0; fi ;;\n"
                + "    esac\n"
                + "    kb=$(queued_kb)\n"
                + "    if [ -n \"$kb\" ] && [ \"$kb\" -ge \"$QUEUE_LIMIT_KB\" ]; then queued=$((queued + 2)); else queued=0; fi\n"
                + "    if [ \"$stuck\" -ge \"$STALL_SECONDS\" ] || [ \"$slow\" -ge \"$STALL_SECONDS\" ] \\\n"
                + "        || [ \"$queued\" -ge \"$QUEUE_SECONDS\" ] || [ \"$starting\" -ge 20 ]; then\n"
                + "      if [ -n \"$kb\" ] && [ \"$kb\" -ge \"$QUEUE_LIMIT_KB\" ]; then\n"
                + "        echo \"$(date '+%F %T') internet stalled ($kb KB waiting to upload): restarting\"\n"
                + "        report \"SLOW $DRONE $kb\"\n"
                + "      else\n"
                + "        echo \"$(date '+%F %T') " + (home ? "test video" : "camera / radio link")
                + " stalled: restarting\"\n"
                + "        report \"CAMERA $DRONE\"\n"
                + "      fi\n"
                + "      restarted=1\n"
                + "      stop_ffmpeg\n"
                + "      break\n"
                + "    fi\n"
                + "  done\n"
                + "  wait \"$FFMPEG_PID\" 2>/dev/null\n"
                + "  FFMPEG_PID=\"\"\n"
                + "  [ \"$restarted\" = 1 ] || why_stopped \"$CAMERA\"\n"
                + "  echo \"$(date '+%F %T') $DRONE stopped, retrying in 3 s\"\n"
                + "  sleep 3\n"
                + "done\n"
                + "}\n"
                + "\n"
                + (dual
                    ? "stream_loop \"$CAMERA\" \"$DRONE\" \"$HOME/.dhakshakey_progress\" \"$FILTER\" day &\n"
                      + "LOOP_PIDS=\"$!\"\n"
                      + "stream_loop \"$NIGHT_CAMERA\" \"$NIGHT_DRONE\" \"$HOME/.dhakshakey_progress_night\" \"$NIGHT_FILTER\" night &\n"
                      + "LOOP_PIDS=\"$LOOP_PIDS $!\"\n"
                      + "while true; do wait; sleep 1; done\n"
                    : "stream_loop \"" + (home ? "$VIDEO" : "$CAMERA") + "\" \"$DRONE\" \"$HOME/.dhakshakey_progress\" \"$FILTER\" \"\"\n");
    }

    /**
     * Bash that stops a run that was not stopped cleanly (Termux closed, script started twice), so it
     * cannot keep the camera, the upload or the position port: the previous script and position reader
     * by their saved process numbers and by name, then any ffmpeg.
     */
    private static String stopPrevious() {
        return "# Stop a run that was not stopped cleanly, so it cannot hold the camera, the upload or port "
                + TELEMETRY_PORT + "\n"
                + "TELEMETRY_PID=\"\"\n"
                + "for p in $(cat " + PID_FILE + " " + TELEMETRY_PID_FILE + " 2>/dev/null) /proc/[0-9]*; do\n"
                + "  p=${p#/proc/}\n"
                + "  [ \"$p\" = \"$$\" ] && continue\n"
                + "  case \"$(tr '\\0' ' ' 2>/dev/null < /proc/$p/cmdline)\" in\n"
                + "  *bash*stream_aws*.sh*|python*dhaksha_telemetry.py*) kill \"$p\" 2>/dev/null ;;\n"
                + "  esac\n"
                + "done\n"
                + "pkill -x ffmpeg 2>/dev/null\n"
                + "sleep 1\n"
                + "pkill -9 -x ffmpeg 2>/dev/null\n";
    }

    /** Stops with a clear message when this phone's ffmpeg cannot upload this way, instead of retrying forever. */
    private static String uploadCheck(Upload upload) {
        String[] needs = upload.needs.split(" ");
        return "# " + upload.name + " needs ffmpeg with " + needs[1] + "\n"
                + "if ! ffmpeg -hide_banner " + needs[0] + " 2>/dev/null | grep -qw " + needs[1] + "; then\n"
                + "  echo \"This ffmpeg cannot upload with " + upload.name + ". Run  pkg upgrade ffmpeg  in Termux,\"\n"
                + "  echo \"or choose RTMP in DhakshaKey (viewers can still watch over WebRTC).\"\n"
                + "  { echo \"UPLOAD $DRONE\" > /dev/udp/127.0.0.1/" + STATUS_PORT + "; } 2>/dev/null\n"
                + "  rm -f " + PID_FILE + "\n"
                + "  exit 1\n"
                + "fi\n"
                + "\n";
    }

    /** Sets VIDEO to test.mp4 in the Termux home or the phone's Download folder; stops with help when missing. */
    private static String findTestVideo() {
        return "# Home test without a camera: " + TEST_VIDEO + " in the Download folder (needs  termux-setup-storage  once)\n"
                + "VIDEO=\"\"\n"
                + "for f in \"$HOME/" + TEST_VIDEO + "\" \"$HOME/storage/shared/Download/" + TEST_VIDEO + "\" "
                + "\"/sdcard/Download/" + TEST_VIDEO + "\"; do\n"
                + "  if [ -r \"$f\" ]; then VIDEO=\"$f\"; break; fi\n"
                + "done\n"
                + "if [ -z \"$VIDEO\" ]; then\n"
                + "  echo \"" + TEST_VIDEO + " not found. Copy it to the phone's Download folder, run  termux-setup-storage\"\n"
                + "  echo \"once in Termux and allow access to files, then press OK in DhakshaKey again.\"\n"
                + "  rm -f " + PID_FILE + "\n"
                + "  exit 1\n"
                + "fi\n"
                + "echo \"Home test: sending $VIDEO again and again instead of the camera\"\n"
                + "\n";
    }

    /**
     * Starts the position reader and adds the text to FILTER. The video always comes first: when anything
     * for the text is missing, or ffmpeg cannot draw it, the script streams without it.
     */
    private static String positionOverlay(boolean fake) {
        return "\n"
                + (fake
                    ? "# Position on the video: a made-up TEST position for the home test (no drone needed)\n"
                    : "# Position on the video: DhakshaGroundControl > Application Settings > MAVLink >\n"
                      + "# \"Enable MAVLink forwarding\", host name localhost:" + TELEMETRY_PORT + "\n")
                + "TEXT_FILE=\"$HOME/telemetry.txt\"\n"
                + "echo \"${DRONE^^}  waiting for position...\" > \"$TEXT_FILE\"\n"
                + "FONT=\"\"\n"
                + "for f in /system/fonts/Roboto-Regular.ttf /system/fonts/DroidSans.ttf /system/fonts/NotoSans-Regular.ttf; do\n"
                + "  if [ -f \"$f\" ]; then FONT=\"$f\"; break; fi\n"
                + "done\n"
                + "if ! command -v python3 > /dev/null; then\n"
                + "  echo \"No position on the video: run  pkg install python  in Termux once\"\n"
                + "elif [ -z \"$FONT\" ] || ! ffmpeg -hide_banner -filters 2>/dev/null | grep -q drawtext; then\n"
                + "  echo \"No position on the video: this ffmpeg cannot draw text\"\n"
                + "else\n"
                + "  TEXT=\"drawtext=fontfile=$FONT:textfile=$TEXT_FILE:reload=1:fontsize=$((HEIGHT / 18))"
                + ":fontcolor=white:box=1:boxcolor=black@0.55:boxborderw=6:x=10:y=h-th-12\"\n"
                + "  # Try the text on a tiny test picture first, so it can never stop the real video\n"
                + "  if ffmpeg -nostdin -hide_banner -loglevel error -f lavfi -i color=size=320x180:duration=0.2 "
                + "-vf \"$TEXT\" -f null - ; then\n"
                + "    start_reader() {\n"
                + "      python3 " + TELEMETRY_SCRIPT + " \"${DRONE^^}\" \"$TEXT_FILE\" " + TELEMETRY_PORT
                + (fake ? " --fake" : "") + " &\n"
                + "      TELEMETRY_PID=$!\n"
                + "      echo $TELEMETRY_PID > " + TELEMETRY_PID_FILE + "\n"
                + "    }\n"
                + "    start_reader\n"
                + "    FILTER=\"$FILTER,$TEXT\"\n"
                + "  else\n"
                + "    echo \"No position on the video: ffmpeg could not draw the text. Sending the video without it.\"\n"
                + "  fi\n"
                + "fi\n"
                + "\n";
    }

    /**
     * Bash that stops whatever DhakshaKey started before: the streaming script and the position reader,
     * each by the process number it saved, then ffmpeg.
     */
    static String stopCommand() {
        StringBuilder cmd = new StringBuilder();
        for (String pidFile : new String[] { PID_FILE, TELEMETRY_PID_FILE }) {
            cmd.append("[ -f ").append(pidFile).append(" ] && kill $(cat ").append(pidFile).append(") 2>/dev/null; ")
                    .append("rm -f ").append(pidFile).append("; ");
        }
        return cmd.append("pkill -x ffmpeg 2>/dev/null; true\n").toString();
    }

    /**
     * Bash, run in a Termux window: stops the previous stream, saves the script, then runs it so its output
     * shows in that window. The script retries by itself when the camera or the internet drops.
     */
    static String saveAndStartCommand(String scriptPath, String stream, String telemetryReader) {
        StringBuilder cmd = new StringBuilder(stopCommand());
        if (telemetryReader != null) {
            cmd.append(writeFile(TELEMETRY_SCRIPT, telemetryReader));
        }
        cmd.append(writeFile(scriptPath, stream));
        cmd.append("chmod +x ").append(scriptPath).append("\n");
        cmd.append("echo \"Saved ").append(scriptPath).append(". Starting...\"\n");
        cmd.append("exec bash ").append(scriptPath).append("\n");
        return cmd.toString();
    }

    /** Single-quotes a value for bash so typed values can never break or extend the command. */
    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String writeFile(String path, String content) {
        return "cat > " + path + " <<'" + HEREDOC_END + "'\n" + content + HEREDOC_END + "\n";
    }
}

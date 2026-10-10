package com.dhaksha.key;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Builds the Termux script that sends the drone camera to the Dhaksha video server (MediaMTX on AWS).
 *
 * The script is the tested stream_aws.sh with the operator's choices filled in: camera stream, server,
 * drone name, password and video size. It is saved as ~/stream_<camera>.sh, e.g. ~/stream_zr10.sh.
 */
final class StreamScripts {

    static final String DEFAULT_SERVER_IP = "15.252.170.151";
    static final String DEFAULT_PASSWORD = "Dhaksha2026";
    static final int RTMP_PORT = 1935;
    static final int WATCH_PORT = 8888;
    static final int MAX_DRONES = 25;

    static final String PID_FILE = "$HOME/.dhakshakey.pid";
    static final String TELEMETRY_PID_FILE = "$HOME/.dhaksha_telemetry.pid";
    static final String TELEMETRY_SCRIPT = "~/dhaksha_telemetry.py";
    static final int TELEMETRY_PORT = 14445;
    static final String SHEBANG = "#!/data/data/com.termux/files/usr/bin/bash";

    private static final String HEREDOC_END = "DHAKSHAKEY_END_OF_SCRIPT";
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?");
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern PASSWORD = Pattern.compile("[A-Za-z0-9._-]+");

    /** A camera model: its default IP and how its RTSP address is built from the IP. */
    static final class Camera {
        final String name;
        final String fileTag;
        final String defaultIp;
        /** RTSP address with %s for the IP; null when the operator types the whole address. */
        final String rtspTemplate;

        Camera(String name, String fileTag, String defaultIp, String rtspTemplate) {
            this.name = name;
            this.fileTag = fileTag;
            this.defaultIp = defaultIp;
            this.rtspTemplate = rtspTemplate;
        }

        boolean customAddress() {
            return rtspTemplate == null;
        }

        String rtsp(String ip) {
            return rtspTemplate == null ? "" : String.format(Locale.ROOT, rtspTemplate, ip);
        }

        String scriptPath() {
            return "~/stream_aws_" + fileTag + ".sh";
        }
    }

    static final Camera[] CAMERAS = {
        new Camera("ZR10 (SIYI)", "zr10", "192.168.144.25", "rtsp://%s:8554/main.264"),
        new Camera("SIYI A8 mini", "siyi_a8", "192.168.144.25", "rtsp://%s:8554/main.264"),
        new Camera("Skydroid", "skydroid", "192.168.144.108", "rtsp://%s:554/stream=0"),
        new Camera("ViewPro", "viewpro", "192.168.144.119", "rtsp://%s:554/stream0"),
        new Camera("Other camera", "other", "192.168.144.10", null),
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
        new Quality("Low: 240p (very slow internet, 0.15 Mbit/s)", 240, 10, 100),
        new Quality("Normal: 360p (recommended, 0.2 Mbit/s)", 360, 15, 150),
        new Quality("Good: 480p (0.4 Mbit/s)", 480, 15, 300),
        new Quality("High: 720p (fast internet, 1 Mbit/s)", 720, 20, 800),
    };
    static final int DEFAULT_QUALITY = 1;

    private StreamScripts() {}

    static String droneName(int number) {
        return "drone" + number;
    }

    static String watchUrl(String serverIp, int droneNumber) {
        return "http://" + serverIp + ":" + WATCH_PORT + "/live/" + droneName(droneNumber);
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

    /** Returns what is wrong with the inputs in plain words, or null when the script can be made. */
    static String validate(String cameraRtsp, String serverIp, int droneNumber, String password) {
        if (cameraRtsp.matches(".*[\\s'\"\\\\].*")) {
            return "The camera address must not contain spaces or quotes.";
        }
        if (rtspHost(cameraRtsp) == null) {
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
     * are written at the bottom of every frame.
     */
    static String streamScript(Camera camera, String cameraRtsp, String serverIp, int droneNumber,
                               String password, Quality quality, boolean showPosition) {
        return SHEBANG + "\n"
                + "# Made by DhakshaKey: " + camera.name + " camera -> Dhaksha video server, "
                + quality.height + "p, about " + quality.kbps + " kbit/s, low delay.\n"
                + "# Watch: " + watchUrl(serverIp, droneNumber) + "\n"
                + "# Stop with Ctrl+C, or the Stop button in DhakshaKey.\n"
                + "\n"
                + "SERVER_IP=" + shellQuote(serverIp) + "\n"
                + "PASSWORD=" + shellQuote(password) + "\n"
                + "DRONE=" + shellQuote(droneName(droneNumber)) + "\n"
                + "CAMERA=" + shellQuote(cameraRtsp) + "\n"
                + "WIDTH=" + quality.width() + "\n"
                + "HEIGHT=" + quality.height + "\n"
                + "FPS=" + quality.fps + "\n"
                + "BITRATE=" + quality.kbps + "k\n"
                + "BUFSIZE=" + (quality.kbps / 2) + "k\n"
                + "\n"
                + "echo $$ > " + PID_FILE + "\n"
                + "termux-wake-lock 2>/dev/null\n"
                + "echo \"Watch this drone at http://$SERVER_IP:" + WATCH_PORT + "/live/$DRONE\"\n"
                + "\n"
                + "# The width is written out: ffmpeg 8 turned scale=-2:360 into a 2-pixel-wide picture\n"
                + "FILTER=\"scale=$WIDTH:$HEIGHT,fps=$FPS\"\n"
                + (showPosition ? positionOverlay() : "")
                + "PROGRESS=\"$HOME/.dhakshakey_progress\"\n"
                + "STALL_SECONDS=8\n"
                + "# More than 2 s of video waiting to upload, for QUEUE_SECONDS, means the internet is not keeping up\n"
                + "QUEUE_LIMIT_KB=$(( ${BITRATE%k} / 4 ))\n"
                + "QUEUE_SECONDS=4\n"
                + "FFMPEG_PID=\"\"\n"
                + "cleanup() { kill $FFMPEG_PID $TELEMETRY_PID 2>/dev/null; exit 0; }\n"
                + "trap cleanup INT TERM\n"
                + "\n"
                + "# KB of video waiting in this phone's upload queue to the server; empty when it cannot be read\n"
                + "queued_kb() {\n"
                + "  local hex\n"
                + "  hex=$(awk -v port=\"$(printf '%04X' " + RTMP_PORT + ")\" 'FNR > 1 { split($3, r, \":\"); if (r[2] == port && $4 == \"01\") { split($5, q, \":\"); print q[1]; exit } }' /proc/net/tcp /proc/net/tcp6 2>/dev/null)\n"
                + "  [ -n \"$hex\" ] && echo $(( 16#$hex / 1024 ))\n"
                + "}\n"
                + "\n"
                + "while true; do\n"
                + "  echo \"$(date '+%F %T') sending to $SERVER_IP ($DRONE)\"\n"
                + "  : > \"$PROGRESS\"\n"
                + "  # -timeout: give up on the camera after 5 s without data, so the loop reconnects\n"
                + "  ffmpeg -nostdin -stats -progress \"$PROGRESS\" -fflags nobuffer -flags low_delay -flags2 +fast \\\n"
                + "    -timeout 5000000 -rtsp_transport tcp -i \"$CAMERA\" \\\n"
                + "    -an -vf \"$FILTER\" \\\n"
                + "    -c:v libx264 -preset ultrafast -tune zerolatency -profile:v baseline -pix_fmt yuv420p \\\n"
                + "    -b:v \"$BITRATE\" -maxrate \"$BITRATE\" -bufsize \"$BUFSIZE\" \\\n"
                + "    -g \"$FPS\" -keyint_min \"$FPS\" -sc_threshold 0 \\\n"
                + "    -f flv \"rtmp://$SERVER_IP:" + RTMP_PORT + "/live/$DRONE?user=drone&pass=$PASSWORD\" &\n"
                + "  FFMPEG_PID=$!\n"
                + "  # Watchdog: when the video stops moving or runs below 0.8x for STALL_SECONDS, or piles up\n"
                + "  # waiting to upload for QUEUE_SECONDS, restart at once.\n"
                + "  # The video waiting behind the stall is dropped, so viewers get live video back instead of a growing delay.\n"
                + "  last=\"\"; stuck=0; slow=0; queued=0; starting=0\n"
                + "  while kill -0 \"$FFMPEG_PID\" 2>/dev/null; do\n"
                + "    sleep 2\n"
                + "    now=$(grep '^out_time_us=' \"$PROGRESS\" | tail -n 1)\n"
                + "    speed=$(grep '^speed=' \"$PROGRESS\" | tail -n 1 | sed 's/speed=//; s/x//; s/ //g')\n"
                + "    case \"$now\" in\n"
                + "    ''|*N/A) starting=$((starting + 2)) ;;\n"
                + "    \"$last\") stuck=$((stuck + 2)) ;;\n"
                + "    *) stuck=0 ;;\n"
                + "    esac\n"
                + "    last=\"$now\"\n"
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
                + "      else\n"
                + "        echo \"$(date '+%F %T') camera / radio link stalled: restarting\"\n"
                + "      fi\n"
                + "      kill \"$FFMPEG_PID\" 2>/dev/null\n"
                + "      break\n"
                + "    fi\n"
                + "  done\n"
                + "  wait \"$FFMPEG_PID\" 2>/dev/null\n"
                + "  FFMPEG_PID=\"\"\n"
                + "  echo \"$(date '+%F %T') stream stopped, retrying in 3 s\"\n"
                + "  sleep 3\n"
                + "done\n";
    }

    /** Starts the position reader and adds the text to FILTER; streams without it when something is missing. */
    private static String positionOverlay() {
        return "\n"
                + "# Position on the video: DhakshaGroundControl > Application Settings > MAVLink >\n"
                + "# \"Enable MAVLink forwarding\", host name localhost:" + TELEMETRY_PORT + "\n"
                + "TEXT_FILE=\"$HOME/telemetry.txt\"\n"
                + "FONT=\"\"\n"
                + "for f in /system/fonts/Roboto-Regular.ttf /system/fonts/DroidSans.ttf /system/fonts/NotoSans-Regular.ttf; do\n"
                + "  if [ -f \"$f\" ]; then FONT=\"$f\"; break; fi\n"
                + "done\n"
                + "if ! command -v python3 > /dev/null; then\n"
                + "  echo \"No position on the video: run  pkg install python  in Termux once\"\n"
                + "elif [ -z \"$FONT\" ] || ! ffmpeg -hide_banner -filters 2>/dev/null | grep -q drawtext; then\n"
                + "  echo \"No position on the video: this ffmpeg cannot draw text\"\n"
                + "else\n"
                + "  python3 " + TELEMETRY_SCRIPT + " \"${DRONE^^}\" \"$TEXT_FILE\" " + TELEMETRY_PORT + " &\n"
                + "  TELEMETRY_PID=$!\n"
                + "  echo $TELEMETRY_PID > " + TELEMETRY_PID_FILE + "\n"
                + "  FILTER=\"$FILTER,drawtext=fontfile=$FONT:textfile=$TEXT_FILE:reload=1:fontsize=$((HEIGHT / 18))"
                + ":fontcolor=white:box=1:boxcolor=black@0.55:boxborderw=6:x=10:y=h-th-12\"\n"
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
    static String saveAndStartCommand(Camera camera, String stream, String telemetryReader) {
        StringBuilder cmd = new StringBuilder(stopCommand());
        if (telemetryReader != null) {
            cmd.append(writeFile(TELEMETRY_SCRIPT, telemetryReader));
        }
        cmd.append(writeFile(camera.scriptPath(), stream));
        cmd.append("chmod +x ").append(camera.scriptPath()).append("\n");
        cmd.append("echo \"Saved ").append(camera.scriptPath()).append(". Starting...\"\n");
        cmd.append("exec bash ").append(camera.scriptPath()).append("\n");
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

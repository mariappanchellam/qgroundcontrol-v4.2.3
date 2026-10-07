package com.dhaksha.key;

import java.net.URI;

/**
 * Builds the Termux streaming scripts from the streaming document:
 * stream.sh (TCP, auto reconnect), stream_udp.sh (UDP) and the Termux:Boot autostream.sh.
 *
 * An H.264 camera is copied as it is; an H.265 camera is converted to H.264 on the MK15,
 * because RTMP servers such as Livepush only accept H.264.
 */
final class StreamScripts {

    static final String DEFAULT_SERVER = "rtmp://stream.livepush.io/live/";
    static final String PID_FILE = "$HOME/.dhakshakey.pid";
    static final String SHEBANG = "#!/data/data/com.termux/files/usr/bin/bash";

    static final String STREAM_SCRIPT = "~/stream.sh";
    static final String UDP_SCRIPT = "~/stream_udp.sh";
    static final String BOOT_SCRIPT = "~/.termux/boot/autostream.sh";

    private static final String HEREDOC_END = "DHAKSHAKEY_END_OF_SCRIPT";

    private StreamScripts() {}

    /** Full RTMP destination: the server with the key appended, or the key itself when it is already a URL. */
    static String destination(String server, String key) {
        if (key.contains("://")) {
            return key;
        }
        if (server.isEmpty()) {
            server = DEFAULT_SERVER;
        }
        return server.endsWith("/") ? server + key : server + "/" + key;
    }

    /** Host part of an rtsp:// address, or null when the address is not usable. */
    static String cameraHost(String rtspUrl) {
        try {
            URI uri = new URI(rtspUrl);
            if (!"rtsp".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns what is wrong with the inputs, or null when a script can be built from them. */
    static String validate(String key, String server, String camera, String codec) {
        if (key.isEmpty()) {
            return "Enter the stream key.";
        }
        if (key.matches(".*\\s.*")) {
            return "The stream key must not contain spaces.";
        }
        if (key.contains("://")) {
            if (!key.startsWith("rtmp://") && !key.startsWith("rtmps://")) {
                return "A full stream address must start with rtmp:// or rtmps://.";
            }
        } else if (!server.isEmpty() && !server.startsWith("rtmp://") && !server.startsWith("rtmps://")) {
            return "The server must start with rtmp:// or rtmps://.";
        }
        if (cameraHost(camera) == null) {
            return "The camera address must look like rtsp://192.168.144.119:554/stream0.";
        }
        if (codec == null) {
            return "Check the camera feed first.";
        }
        if (!codec.equals(RtspProbe.H264) && !codec.equals(RtspProbe.H265)) {
            return "The camera sends " + codec + " video; only H.264 and H.265 cameras are supported.";
        }
        return null;
    }

    static String ffmpegCommand(String camera, String destination, String codec, boolean udp, boolean silentAudio) {
        StringBuilder cmd = new StringBuilder("ffmpeg -rtsp_transport ").append(udp ? "udp" : "tcp")
                .append(" -i ").append(shellQuote(camera));
        if (silentAudio) {
            cmd.append(" -f lavfi -i anullsrc=r=44100:cl=stereo -map 0:v -map 1:a");
        }
        if (RtspProbe.H265.equals(codec)) {
            // RTMP needs H.264: convert on the MK15, at 720p so the MK15 keeps up in real time
            cmd.append(" -vf scale=-2:720 -c:v libx264 -preset ultrafast -tune zerolatency"
                    + " -b:v 2000k -maxrate 2000k -bufsize 4000k -g 50 -pix_fmt yuv420p");
        } else {
            cmd.append(" -c:v copy");
        }
        cmd.append(silentAudio ? " -c:a aac -b:a 128k -shortest" : " -an");
        return cmd.append(" -f flv ").append(shellQuote(destination)).toString();
    }

    static String streamScript(String command, String codec, String transport) {
        return SHEBANG + "\n"
                + header(codec, transport)
                + "echo $$ > " + PID_FILE + "\n"
                + "termux-wake-lock 2>/dev/null\n"
                + "echo \"Stream starting... Press CTRL+C to stop\"\n"
                + reconnectLoop(command);
    }

    static String bootScript(String command, String codec, String cameraHost) {
        return SHEBANG + "\n"
                + header(codec, "TCP, started by Termux:Boot")
                + "termux-wake-lock 2>/dev/null\n"
                + "exec > ~/stream_log.txt 2>&1\n"
                + "echo $$ > " + PID_FILE + "\n"
                + "\n"
                + "# Wait for Android to fully boot\n"
                + "sleep 30\n"
                + "\n"
                + "# Wait until the air unit is reachable (drone must be powered on)\n"
                + "until ping -c 1 " + shellQuote(cameraHost) + " > /dev/null 2>&1; do\n"
                + "  echo \"Waiting for air unit...\"\n"
                + "  sleep 5\n"
                + "done\n"
                + "\n"
                + "echo \"Air unit reachable. Starting stream...\"\n"
                + reconnectLoop(command);
    }

    /** Bash that writes the three scripts into Termux and makes them executable. */
    static String installCommand(String stream, String udp, String boot) {
        return "mkdir -p ~/.termux/boot\n"
                + writeFile(STREAM_SCRIPT, stream)
                + writeFile(UDP_SCRIPT, udp)
                + writeFile(BOOT_SCRIPT, boot)
                + "chmod +x " + STREAM_SCRIPT + " " + UDP_SCRIPT + " " + BOOT_SCRIPT + "\n";
    }

    /** Single-quotes a value for bash so keys and addresses can never break or extend the command. */
    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String header(String codec, String transport) {
        String video = RtspProbe.H265.equals(codec)
                ? "camera sends H.265, converted to H.264 on the MK15"
                : "camera sends H.264, copied as it is";
        return "# Generated by DhakshaKey: " + video + ", camera read over " + transport + "\n";
    }

    private static String reconnectLoop(String command) {
        return "while true; do\n"
                + "  " + command + "\n"
                + "  echo \"Stream dropped. Reconnecting in 5 seconds...\"\n"
                + "  sleep 5\n"
                + "done\n";
    }

    private static String writeFile(String path, String content) {
        return "cat > " + path + " <<'" + HEREDOC_END + "'\n" + content + HEREDOC_END + "\n";
    }
}

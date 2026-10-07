package com.dhaksha.key;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Asks an RTSP camera which video codec it sends, the same information
 * "ffprobe rtsp://... | grep Video" prints: an RTSP DESCRIBE returns the stream's SDP,
 * whose video rtpmap line names the codec.
 */
final class RtspProbe {

    static final String H264 = "H264";
    static final String H265 = "H265";

    static final class Result {
        final String url;
        final String codec;     // H264, H265, another codec name, or null when no feed was found
        final String problem;   // why no feed was found

        Result(String url, String codec, String problem) {
            this.url = url;
            this.codec = codec;
            this.problem = problem;
        }

        boolean hasFeed() {
            return codec != null;
        }
    }

    private RtspProbe() {}

    static Result probe(String url, int timeoutMs) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return new Result(url, null, "not a valid address");
        }
        if (!"rtsp".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return new Result(url, null, "address must start with rtsp://");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 554;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            String request = "DESCRIBE " + url + " RTSP/1.0\r\n"
                    + "CSeq: 1\r\n"
                    + "Accept: application/sdp\r\n"
                    + "User-Agent: DhakshaKey\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            InputStream in = socket.getInputStream();
            String statusLine = readLine(in);
            if (statusLine == null || !statusLine.startsWith("RTSP/")) {
                return new Result(url, null, "the device at this address is not an RTSP camera");
            }
            String[] status = statusLine.split(" ", 3);
            int code = status.length > 1 ? parseInt(status[1]) : -1;
            int contentLength = 0;
            for (String line = readLine(in); line != null && !line.isEmpty(); line = readLine(in)) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    contentLength = parseInt(line.substring(colon + 1).trim());
                }
            }
            if (code == 401) {
                return new Result(url, null, "the camera asks for a username and password");
            }
            if (code != 200) {
                return new Result(url, null, "the camera answered \"" + statusLine.substring(9).trim() + "\"");
            }
            byte[] body = readBytes(in, Math.max(0, Math.min(contentLength, 64 * 1024)));
            String codec = codecFromSdp(new String(body, StandardCharsets.UTF_8));
            if (codec == null) {
                return new Result(url, null, "the camera is reachable but sends no video");
            }
            return new Result(url, codec, null);
        } catch (java.net.SocketTimeoutException e) {
            return new Result(url, null, "no answer (drone or camera off, or not connected)");
        } catch (IOException e) {
            return new Result(url, null, "cannot connect (" + e.getMessage() + ")");
        }
    }

    /** Codec of the first video stream in an SDP: H264, H265, another rtpmap name, or null without video. */
    static String codecFromSdp(String sdp) {
        boolean inVideo = false;
        for (String raw : sdp.split("\r?\n")) {
            String line = raw.trim();
            if (line.startsWith("m=")) {
                inVideo = line.startsWith("m=video");
            } else if (inVideo && line.startsWith("a=rtpmap:")) {
                int space = line.indexOf(' ');
                if (space < 0) {
                    continue;
                }
                String encoding = line.substring(space + 1);
                int slash = encoding.indexOf('/');
                String name = (slash > 0 ? encoding.substring(0, slash) : encoding).trim().toUpperCase(Locale.ROOT);
                if (name.equals("H265") || name.equals("HEVC")) {
                    return H265;
                }
                return name;
            }
        }
        return null;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                line.write(c);
            }
            if (line.size() > 8192) {
                break;
            }
        }
        if (c == -1 && line.size() == 0) {
            return null;
        }
        return line.toString("ISO-8859-1");
    }

    private static byte[] readBytes(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(data, read, length - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        return data;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

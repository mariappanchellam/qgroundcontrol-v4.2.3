package com.dhaksha.key;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

/**
 * DhakshaKey: sends the drone camera to the Dhaksha video server with a few taps.
 *
 * One screen: the camera and the video quality, the camera IP and the server IP (filled in, editable, kept
 * with their Save buttons), the drone number, the stream name(s), how the video is uploaded (RTMP, RTSP, SRT
 * or WebRTC / WHIP, with its port) and how viewers watch it (HLS or WebRTC), and Start / Stop streaming.
 * Start checks that the camera
 * answers, writes the tested streaming script with these values to ~/stream_aws_<camera>.sh in Termux and
 * runs it in a Termux window. The script retries by itself when the camera or the internet drops, and reports
 * how it is doing on a local UDP port, shown in the message bar at the top ("Data rate too low ...").
 * The drone's lat, lon and altitude are always written on the video. The server password is built in (or the
 * one saved by an earlier version); the home test video runs from Termux with ~/stream_aws_home.sh.
 *
 * Termux runs commands through its RUN_COMMAND service, which needs once:
 * allow-external-apps = true in Termux, and the "Run commands in Termux environment" permission.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "dhaksha_key_aws";
    private static final String GCS_PACKAGE = "com.dhaksha.groundcontrol";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_SERVICE = "com.termux.app.RunCommandService";
    private static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final int PROBE_TIMEOUT_MS = 3000;

    // How long a report from the stream script stays on screen
    private static final long LIVE_SHOWN_MS = 6000;
    private static final long PROBLEM_SHOWN_MS = 30000;
    private static final long STATUS_TICK_MS = 1000;

    private static final int GREEN = Color.rgb(30, 130, 60);
    private static final int RED = Color.rgb(180, 40, 40);
    private static final int ORANGE = Color.rgb(200, 120, 20);
    private static final int GREY = Color.rgb(90, 90, 90);

    static final String TERMUX_SETUP =
            "mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings";

    private SharedPreferences prefs;
    private TextView banner;
    private Spinner cameraSpinner;
    private Spinner qualitySpinner;
    private EditText cameraIp;
    private Button saveCameraIp;
    private TextView cameraAddress;
    private EditText serverIp;
    private Button saveServerIp;
    private Spinner droneSpinner;
    private EditText dayName;
    private Button saveDayName;
    private TextView nightLabel;
    private LinearLayout nightRow;
    private EditText nightName;
    private Button saveNightName;
    private TextView uploadAddress;
    private Spinner uploadSpinner;
    private EditText uploadPort;
    private Button saveUploadPort;
    private Spinner watchSpinner;
    private TextView watchLink;
    private Button startButton;
    private Button startAnyway;

    private boolean checking;
    private boolean streaming;

    // Latest reports from the stream script (SystemClock.elapsedRealtime), 0 = none since Start
    private final Handler handler = new Handler(Looper.getMainLooper());
    private DatagramSocket statusSocket;
    private long liveAt;
    private long slowAt;
    private long cameraAt;
    private long netAt;
    private long dropAt;
    private long uploadAt;
    private String problemStream = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        page.setPadding(pad, pad, pad, pad);

        LinearLayout titleRow = row(page);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_logo);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        logoParams.rightMargin = dp(10);
        titleRow.addView(logo, logoParams);
        TextView title = new TextView(this);
        title.setText("Dhaksha Key");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title);

        banner = new TextView(this);
        banner.setTextSize(16);
        banner.setTypeface(Typeface.DEFAULT_BOLD);
        banner.setTextColor(Color.WHITE);
        banner.setPadding(dp(12), dp(10), dp(12), dp(10));
        page.addView(banner, fullWidth(dp(8)));

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(columns, fullWidth(dp(4)));
        LinearLayout left = column(columns, 0);
        LinearLayout right = column(columns, dp(16));

        // Left: camera, its IP, drone number
        addLabel(left, "Camera");
        String[] cameraNames = new String[StreamScripts.CAMERAS.length];
        for (int i = 0; i < cameraNames.length; i++) {
            cameraNames[i] = StreamScripts.CAMERAS[i].name;
        }
        // Saved by name, so changes to the camera list keep the operator's choice
        cameraSpinner = addSpinner(left, cameraNames,
                StreamScripts.cameraIndex(prefs.getString("cameraTag", StreamScripts.CAMERAS[0].fileTag)));

        addLabel(left, "Camera IP");
        LinearLayout cameraRow = row(left);
        cameraIp = addField(cameraRow, "192.168.144.108");
        saveCameraIp = addSaveButton(cameraRow, v -> saveCameraIp(true));
        cameraAddress = new TextView(this);
        cameraAddress.setTextColor(Color.GRAY);
        cameraAddress.setTextSize(12);
        left.addView(cameraAddress);

        addLabel(left, "Drone");
        String[] drones = new String[StreamScripts.MAX_DRONES];
        for (int i = 0; i < drones.length; i++) {
            drones[i] = "Drone " + (i + 1) + "   (" + StreamScripts.droneName(i + 1) + ")";
        }
        droneSpinner = addSpinner(left, drones, prefs.getInt("drone", 0));

        // The names the video gets on the server (live/<name>); Dhaksha Live boxes look for droneN and droneN-night
        addLabel(left, "Stream name");
        LinearLayout dayRow = row(left);
        dayName = addField(dayRow, "drone1");
        saveDayName = addSaveButton(dayRow, v -> saveStreamNames(true));
        nightLabel = addLabel(left, "Night stream name");
        nightRow = row(left);
        nightName = addField(nightRow, "drone1-night");
        saveNightName = addSaveButton(nightRow, v -> saveStreamNames(true));
        uploadAddress = new TextView(this);
        uploadAddress.setTextColor(Color.GRAY);
        uploadAddress.setTextSize(12);
        left.addView(uploadAddress);

        // Right: quality, server IP, watch link
        addLabel(right, "Video quality");
        String[] qualities = new String[StreamScripts.QUALITIES.length];
        for (int i = 0; i < qualities.length; i++) {
            qualities[i] = StreamScripts.QUALITIES[i].label;
        }
        qualitySpinner = addSpinner(right, qualities, prefs.getInt("quality", StreamScripts.DEFAULT_QUALITY));

        addLabel(right, "Server IP");
        LinearLayout serverRow = row(right);
        serverIp = addField(serverRow, StreamScripts.DEFAULT_SERVER_IP);
        serverIp.setText(savedServerIp());
        saveServerIp = addSaveButton(serverRow, v -> saveServerIp(true));

        addLabel(right, "Upload with");
        String[] uploads = new String[StreamScripts.UPLOADS.length];
        for (int i = 0; i < uploads.length; i++) {
            uploads[i] = StreamScripts.UPLOADS[i].name;
        }
        uploadSpinner = addSpinner(right, uploads,
                StreamScripts.uploadIndex(prefs.getString("upload", StreamScripts.UPLOADS[0].tag)));
        addLabel(right, "Upload port");
        LinearLayout portRow = row(right);
        uploadPort = addField(portRow, "1935");
        uploadPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        saveUploadPort = addSaveButton(portRow, v -> saveUploadPort(true));

        addLabel(right, "Watch with");
        String[] watches = new String[StreamScripts.WATCHES.length];
        for (int i = 0; i < watches.length; i++) {
            watches[i] = StreamScripts.WATCHES[i].name;
        }
        watchSpinner = addSpinner(right, watches, prefs.getInt("watch", 0));

        addLabel(right, "Watch at (tap to copy)");
        watchLink = new TextView(this);
        watchLink.setTextSize(13);
        watchLink.setTypeface(Typeface.MONOSPACE);
        watchLink.setOnClickListener(v -> copy(watchLink.getText().toString()));
        right.addView(watchLink);

        // Start / Stop side by side
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(buttons, fullWidth(dp(16)));
        startButton = addBigButton(buttons, "Start streaming", GREEN, 0, v -> start(false));
        addBigButton(buttons, "Stop streaming", RED, dp(16), v -> stop());
        startAnyway = addBigButton(page, "Start anyway (camera not answering yet)", ORANGE, 0, v -> start(true));
        ((LinearLayout.LayoutParams) startAnyway.getLayoutParams()).topMargin = dp(8);
        startAnyway.setVisibility(View.GONE);

        // Setup and less used things, folded away
        TextView setupToggle = new TextView(this);
        setupToggle.setText("Setup and more  ▾");
        setupToggle.setTextColor(Color.GRAY);
        setupToggle.setPadding(0, dp(20), 0, dp(8));
        page.addView(setupToggle);
        LinearLayout setupBlock = new LinearLayout(this);
        setupBlock.setOrientation(LinearLayout.VERTICAL);
        setupBlock.setVisibility(View.GONE);
        page.addView(setupBlock);
        setupToggle.setOnClickListener(v -> {
            boolean show = setupBlock.getVisibility() != View.VISIBLE;
            setupBlock.setVisibility(show ? View.VISIBLE : View.GONE);
            setupToggle.setText(show ? "Setup and more  ▴" : "Setup and more  ▾");
        });
        buildSetup(setupBlock);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        setContentView(scroll);

        cameraSpinner.setOnItemSelectedListener(new SimpleSelection(this::showCamera));
        droneSpinner.setOnItemSelectedListener(new SimpleSelection(this::showStreamNames));
        qualitySpinner.setOnItemSelectedListener(new SimpleSelection(this::refresh));
        uploadSpinner.setOnItemSelectedListener(new SimpleSelection(this::showUploadPort));
        watchSpinner.setOnItemSelectedListener(new SimpleSelection(this::refresh));
        cameraIp.addTextChangedListener(new SimpleWatcher(this::refresh));
        serverIp.addTextChangedListener(new SimpleWatcher(this::refresh));
        dayName.addTextChangedListener(new SimpleWatcher(this::refresh));
        nightName.addTextChangedListener(new SimpleWatcher(this::refresh));
        uploadPort.addTextChangedListener(new SimpleWatcher(this::refresh));
        showUploadPort();
        showCamera();

        showBanner("Choose the camera and the video quality, then press Start streaming.", GREY);
        if (needsPermission()) {
            requestPermissions(new String[] { TERMUX_PERMISSION }, 1);
        }
    }

    private void buildSetup(LinearLayout block) {
        addLabel(block, "First time on this MK15:\n"
                + "1. Install Termux from F-Droid.\n"
                + "2. In Termux run once:  pkg install ffmpeg python  and then the command below.\n"
                + "3. Allow \"Run commands in Termux environment\" when DhakshaKey asks.\n"
                + "4. For the position on the video: DhakshaGroundControl > Application Settings > MAVLink > "
                + "\"Enable MAVLink forwarding\", host localhost:" + StreamScripts.TELEMETRY_PORT + ".");
        TextView setup = new TextView(this);
        setup.setTypeface(Typeface.MONOSPACE);
        setup.setTextIsSelectable(true);
        setup.setText(TERMUX_SETUP);
        block.addView(setup);
        LinearLayout tools = row(block);
        addButton(tools, "Copy setup command", v -> copy(TERMUX_SETUP));
        addButton(tools, "Open DhakshaGroundControl", v -> openGroundControl());
    }

    @Override
    protected void onStart() {
        super.onStart();
        listenForStatus();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacksAndMessages(null);
        if (statusSocket != null) {
            statusSocket.close();
            statusSocket = null;
        }
        prefs.edit()
                .putString("cameraTag", camera().fileTag)
                .putInt("drone", droneSpinner.getSelectedItemPosition())
                .putInt("quality", qualitySpinner.getSelectedItemPosition())
                .putString("upload", upload().tag)
                .putInt("watch", watchSpinner.getSelectedItemPosition())
                .apply();
    }

    // ---- Camera IP and server IP with their Save buttons ----

    private String savedCameraIp() {
        StreamScripts.Camera camera = camera();
        return prefs.getString("ip_" + camera.ipKey, camera.defaultIp);
    }

    private String savedServerIp() {
        return prefs.getString("server", StreamScripts.DEFAULT_SERVER_IP);
    }

    private void saveCameraIp(boolean announce) {
        prefs.edit().putString("ip_" + camera().ipKey, text(cameraIp)).apply();
        refresh();
        if (announce) {
            showBanner("Camera IP saved for " + camera().name + ".", GREEN);
        }
    }

    private void saveServerIp(boolean announce) {
        prefs.edit().putString("server", text(serverIp)).apply();
        refresh();
        if (announce) {
            showBanner("Server IP saved.", GREEN);
        }
    }

    // ---- Stream names (per camera and drone) and upload port (per upload type), with their Save buttons ----

    private String savedDayName() {
        return prefs.getString("name_" + camera().fileTag + "_" + droneNumber(), StreamScripts.droneName(droneNumber()));
    }

    private String savedNightName() {
        return prefs.getString("night_" + camera().fileTag + "_" + droneNumber(), StreamScripts.nightName(droneNumber()));
    }

    private String savedUploadPort() {
        return prefs.getString("port_" + upload().tag, String.valueOf(upload().defaultPort));
    }

    private void saveStreamNames(boolean announce) {
        SharedPreferences.Editor edit = prefs.edit()
                .putString("name_" + camera().fileTag + "_" + droneNumber(), text(dayName));
        if (camera().dual()) {
            edit.putString("night_" + camera().fileTag + "_" + droneNumber(), text(nightName));
        }
        edit.apply();
        refresh();
        if (announce) {
            showBanner("Stream name saved for " + camera().name + ", drone " + droneNumber() + ".", GREEN);
        }
    }

    private void saveUploadPort(boolean announce) {
        prefs.edit().putString("port_" + upload().tag, text(uploadPort)).apply();
        refresh();
        if (announce) {
            showBanner("Upload port saved for " + upload().name + ".", GREEN);
        }
    }

    /** Shows the chosen camera with its saved IP; an unsaved edit of the previous camera's IP is dropped. */
    private void showCamera() {
        cameraIp.setText(savedCameraIp());
        showStreamNames();
    }

    /** The saved stream names of this camera and drone; the night name only for Day + Night. */
    private void showStreamNames() {
        dayName.setText(savedDayName());
        nightName.setText(savedNightName());
        int night = camera().dual() ? View.VISIBLE : View.GONE;
        nightLabel.setVisibility(night);
        nightRow.setVisibility(night);
        refresh();
    }

    private void showUploadPort() {
        uploadPort.setText(savedUploadPort());
        refresh();
    }

    private void refresh() {
        setSaveEnabled(saveCameraIp, !text(cameraIp).equals(savedCameraIp()));
        setSaveEnabled(saveServerIp, !text(serverIp).equals(savedServerIp()));
        setSaveEnabled(saveDayName, !text(dayName).equals(savedDayName()));
        setSaveEnabled(saveNightName, !text(nightName).equals(savedNightName()));
        setSaveEnabled(saveUploadPort, !text(uploadPort).equals(savedUploadPort()));
        StreamScripts.Camera camera = camera();
        String night = camera.nightRtsp(text(cameraIp));
        cameraAddress.setText(camera.rtsp(text(cameraIp)) + (night == null ? "" : "\n" + night));
        String server = text(serverIp);
        int port = port();
        uploadAddress.setText("Uploads to " + StreamScripts.uploadUrl(upload(), server, port, text(dayName))
                + (night == null ? "" : "\nand " + StreamScripts.uploadUrl(upload(), server, port, text(nightName))));
        watchLink.setText(StreamScripts.watchUrl(server, watch(), text(dayName))
                + (night == null ? "" : "\n" + StreamScripts.watchUrl(server, watch(), text(nightName))));
        startAnyway.setVisibility(View.GONE);
    }

    private void setSaveEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.35f);
    }

    private StreamScripts.Camera camera() {
        return StreamScripts.CAMERAS[cameraSpinner.getSelectedItemPosition()];
    }

    private StreamScripts.Quality quality() {
        return StreamScripts.QUALITIES[qualitySpinner.getSelectedItemPosition()];
    }

    private int droneNumber() {
        return droneSpinner.getSelectedItemPosition() + 1;
    }

    private StreamScripts.Upload upload() {
        return StreamScripts.UPLOADS[uploadSpinner.getSelectedItemPosition()];
    }

    private StreamScripts.Watch watch() {
        return StreamScripts.WATCHES[watchSpinner.getSelectedItemPosition()];
    }

    /** The typed upload port, or 0 when it is not a number. */
    private int port() {
        try {
            return Integer.parseInt(text(uploadPort));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The night stream name, or null when the camera sends one stream. */
    private String nightStream() {
        return camera().dual() ? text(nightName) : null;
    }

    /** The built-in server password, or the one an earlier version let the operator save */
    private String password() {
        return prefs.getString("password", StreamScripts.DEFAULT_PASSWORD);
    }

    // ---- Start / Stop ----

    /** Checks the inputs and the camera, then saves the script and runs it in Termux. */
    private void start(boolean skipCameraCheck) {
        if (checking) {
            return;
        }
        final String rtsp = camera().rtsp(text(cameraIp));
        String problem = StreamScripts.validate(rtsp, text(serverIp), droneNumber(), password(), port(),
                                                text(dayName), nightStream());
        if (problem != null) {
            showBanner(problem, RED);
            return;
        }
        // Starting uses what is typed, so it is kept as the saved values too
        saveCameraIp(false);
        saveServerIp(false);
        saveStreamNames(false);
        saveUploadPort(false);
        if (skipCameraCheck) {
            saveAndRun(rtsp);
            return;
        }
        checking = true;
        startButton.setEnabled(false);
        showBanner("Checking the camera at " + rtsp + " ...", GREY);
        new Thread(() -> {
            final RtspProbe.Result result = RtspProbe.probe(rtsp, PROBE_TIMEOUT_MS);
            runOnUiThread(() -> {
                checking = false;
                startButton.setEnabled(true);
                if (result.hasFeed()) {
                    saveAndRun(rtsp);
                } else {
                    showBanner("The camera is not answering (" + result.problem + "). Check that the drone is on "
                            + "and the camera and its IP are right, then press Start streaming again.", RED);
                    startAnyway.setVisibility(View.VISIBLE);
                }
            });
        }).start();
    }

    private void saveAndRun(String rtsp) {
        StreamScripts.Camera camera = camera();
        String scriptPath = camera.scriptPath();
        String nightRtsp = camera.nightRtsp(text(cameraIp));
        String stream = StreamScripts.streamScript(camera, rtsp, nightRtsp, text(serverIp), text(dayName),
                nightStream(), password(), quality(), true, upload(), port(), watch());
        String reader = readAsset("dhaksha_telemetry.py");
        if (reader == null) {
            showBanner("The position reader is missing from this app.", RED);
            return;
        }
        if (runInTermux(StreamScripts.saveAndStartCommand(scriptPath, stream, reader), false)) {
            startAnyway.setVisibility(View.GONE);
            streaming = true;
            liveAt = slowAt = cameraAt = netAt = dropAt = uploadAt = 0;
            showBanner("Starting " + text(dayName) + " at " + quality().label + " with " + upload().name
                    + ". The Termux window shows its progress.", GREY);
        }
    }

    private void stop() {
        if (runInTermux(StreamScripts.stopCommand(), true)) {
            streaming = false;
            showBanner("Streaming stopped.", GREY);
        }
    }

    // ---- Reports from the stream script ----

    /** Receives the LIVE / SLOW / CAMERA / NET / DROP lines the running script sends to 127.0.0.1:STATUS_PORT. */
    private void listenForStatus() {
        try {
            statusSocket = new DatagramSocket(null);
            statusSocket.setReuseAddress(true);
            statusSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), StreamScripts.STATUS_PORT));
        } catch (IOException e) {
            statusSocket = null;
            return;
        }
        final DatagramSocket socket = statusSocket;
        new Thread(() -> {
            byte[] buffer = new byte[256];
            while (!socket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketException e) {
                    return;     // closed in onStop
                } catch (IOException e) {
                    continue;
                }
                final String line = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                runOnUiThread(() -> onStatus(line));
            }
        }).start();
        handler.post(new Runnable() {
            @Override public void run() {
                showStreamState();
                handler.postDelayed(this, STATUS_TICK_MS);
            }
        });
    }

    private void onStatus(String line) {
        String[] parts = line.split(" ");
        long now = SystemClock.elapsedRealtime();
        switch (parts[0]) {
        case "LIVE":
            liveAt = now;
            break;
        case "SLOW":
            slowAt = now;
            problemStream = parts.length > 1 ? parts[1] : "";
            break;
        case "CAMERA":
            cameraAt = now;
            problemStream = parts.length > 1 ? parts[1] : "";
            break;
        case "NET":
            netAt = now;
            problemStream = parts.length > 1 ? parts[1] : "";
            break;
        case "DROP":
            dropAt = now;
            problemStream = parts.length > 1 ? parts[1] : "";
            break;
        case "UPLOAD":
            uploadAt = now;
            break;
        default:
            return;
        }
        streaming = true;       // also after DhakshaKey was reopened while the script runs
        showStreamState();
    }

    /** The message bar while streaming: a recent problem first, then live, else no news from the script. */
    private void showStreamState() {
        if (!streaming || checking) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        String which = camera().dual() && problemStream.equals(text(nightName)) ? "Night camera: " : "";
        if (uploadAt > 0 && now - uploadAt < PROBLEM_SHOWN_MS) {
            showBanner("This phone's ffmpeg cannot upload with " + upload().name + ". Run  pkg upgrade ffmpeg  "
                    + "in Termux, or choose RTMP (viewers can still watch over WebRTC).", RED);
        } else if (slowAt > 0 && now - slowAt < PROBLEM_SHOWN_MS) {
            int index = qualitySpinner.getSelectedItemPosition();
            String lower = index > 0 ? " (" + StreamScripts.QUALITIES[index - 1].label + ")" : "";
            showBanner(which + "Data rate too low to stream video. Reduce resolution" + lower
                    + " and try again.", RED);
        } else if (cameraAt > 0 && now - cameraAt < PROBLEM_SHOWN_MS) {
            showBanner(which + "No video from the camera. Check the camera is on and its IP is right.", RED);
        } else if (netAt > 0 && now - netAt < PROBLEM_SHOWN_MS) {
            showBanner(which + "Cannot reach the server. Check the internet (SIM / Wi-Fi), the server IP and the "
                    + "upload port.", RED);
        } else if (dropAt > 0 && now - dropAt < LIVE_SHOWN_MS) {
            showBanner(which + "The stream dropped. Reconnecting...", ORANGE);
        } else if (liveAt > 0 && now - liveAt < LIVE_SHOWN_MS) {
            showBanner("Live: " + text(dayName) + " is on the server (" + quality().label + ", "
                    + upload().name + ").", GREEN);
        } else if (liveAt > 0) {
            showBanner("No news from the stream for a few seconds. Check the Termux window.", ORANGE);
        }
    }

    // ---- Termux and helpers ----

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    private void openGroundControl() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(GCS_PACKAGE);
        if (launch == null) {
            showBanner("DhakshaGroundControl is not installed.", RED);
            return;
        }
        startActivity(launch);
    }

    private boolean runInTermux(String script, boolean background) {
        if (!isInstalled(TERMUX_PACKAGE)) {
            showBanner("Termux is not installed. Install Termux from F-Droid first.", RED);
            return false;
        }
        if (needsPermission()) {
            requestPermissions(new String[] { TERMUX_PERMISSION }, 1);
            showBanner("Allow \"Run commands in Termux environment\", then press the button again.", RED);
            return false;
        }
        Intent intent = new Intent("com.termux.RUN_COMMAND");
        intent.setComponent(new ComponentName(TERMUX_PACKAGE, TERMUX_SERVICE));
        intent.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[] { "-c", script });
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME);
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background);
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");    // open Termux on the new window
        try {
            // DhakshaKey is in the foreground when a button is pressed, so a plain start is allowed
            startService(intent);
            return true;
        } catch (SecurityException e) {
            showBanner("Termux refused the command. Do the first-time steps under \"Setup and more\", "
                    + "then try again.", RED);
        } catch (RuntimeException e) {
            showBanner("Could not reach Termux: " + e.getMessage(), RED);
        }
        return false;
    }

    private void showBanner(String message, int color) {
        banner.setText(message);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(6));
        banner.setBackground(background);
    }

    private boolean isInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean needsPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED;
    }

    private void copy(String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("DhakshaKey", value));
        showBanner("Copied: " + value, GREY);
    }

    private static String text(EditText field) {
        return field.getText().toString().trim();
    }

    private LinearLayout.LayoutParams fullWidth(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private LinearLayout column(LinearLayout parent, int leftMargin) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        params.leftMargin = leftMargin;
        parent.addView(column, params);
        return column;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row, fullWidth(0));
        return row;
    }

    private Spinner addSpinner(LinearLayout parent, String[] items, int selected) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected >= 0 && selected < items.length ? selected : 0);
        spinner.setMinimumHeight(dp(48));
        parent.addView(spinner, fullWidth(0));
        return spinner;
    }

    /** A one-line field: in a row it takes the width left by the Save button, in a column the full width. */
    private EditText addField(LinearLayout parent, String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextSize(17);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) {
            parent.addView(field, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        } else {
            parent.addView(field, fullWidth(0));
        }
        return field;
    }

    /** Small Save button, dimmed and inactive until its field is changed. */
    private Button addSaveButton(LinearLayout parent, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText("Save");
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(6);
        parent.addView(button, params);
        setSaveEnabled(button, false);
        return button;
    }

    private TextView addLabel(LinearLayout parent, String label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(Color.GRAY);
        view.setPadding(0, dp(10), 0, dp(2));
        parent.addView(view);
        return view;
    }

    private void addButton(LinearLayout parent, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        parent.addView(button);
    }

    /** A big coloured button; in a row the buttons share the width equally. */
    private Button addBigButton(LinearLayout parent, String label, int color, int leftMargin,
                                View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(18);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(8));
        button.setBackground(background);
        button.setMinHeight(dp(56));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = parent.getOrientation() == LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1)
                : fullWidth(0);
        params.leftMargin = leftMargin;
        parent.addView(button, params);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class SimpleWatcher implements TextWatcher {
        private final Runnable onChange;

        SimpleWatcher(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) { onChange.run(); }
    }

    private static final class SimpleSelection implements AdapterView.OnItemSelectedListener {
        private final Runnable onChange;

        SimpleSelection(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            onChange.run();
        }

        @Override public void onNothingSelected(AdapterView<?> parent) {}
    }
}

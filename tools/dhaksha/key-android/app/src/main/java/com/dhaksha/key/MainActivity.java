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
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * DhakshaKey: checks the drone camera feed, picks the right ffmpeg command for its codec,
 * writes the Termux streaming scripts and starts them.
 *
 *   1. Camera check: DhakshaGroundControl connected to the drone, camera answering, codec found.
 *   2. OK: inputs validated, ~/stream.sh, ~/stream_udp.sh and ~/.termux/boot/autostream.sh written.
 *   3. Start / Stop in Termux.
 *
 * Termux runs commands through its RUN_COMMAND service, which needs once:
 * allow-external-apps = true in Termux, and the "Run commands in Termux environment" permission.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "dhaksha_key";
    private static final String GCS_PACKAGE = "com.dhaksha.groundcontrol";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_SERVICE = "com.termux.app.RunCommandService";
    private static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final int PROBE_TIMEOUT_MS = 3000;

    /** Camera addresses tried when looking for the feed: ViewPro, SIYI, Skydroid. */
    private static final String[] KNOWN_CAMERAS = {
        "rtsp://192.168.144.119:554/stream0",
        "rtsp://192.168.144.25:8554/main.264",
        "rtsp://192.168.144.108:554/stream=0",
    };

    static final String TERMUX_SETUP =
            "mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings";

    private TextView banner;
    private Button checkButton;
    private EditText camera;
    private EditText key;
    private EditText server;
    private CheckBox silentAudio;
    private Button okButton;
    private TextView preview;
    private Button startTcp;
    private Button startUdp;
    private Button stopButton;
    private TextView status;

    private String codec;               // camera codec found by the last successful check
    private boolean checking;
    private boolean updatingCamera;     // camera field changed by the app, not by the operator
    private boolean cameraUnlocked;     // stays editable once a camera feed has been found

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        column.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("DhakshaKey");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(title);

        banner = new TextView(this);
        banner.setTextSize(16);
        banner.setTypeface(Typeface.DEFAULT_BOLD);
        banner.setPadding(dp(12), dp(12), dp(12), dp(12));
        column.addView(banner);

        LinearLayout step1 = row(column);
        addButton(step1, "Open DhakshaGroundControl", v -> openGroundControl());
        checkButton = addButton(step1, "Check camera feed", v -> checkFeed());

        camera = addField(column, "Camera RTSP address (unlocks when the feed is found)",
                KNOWN_CAMERAS[0], prefs.getString("camera", KNOWN_CAMERAS[0]));
        key = addField(column, "Stream key", "rtmp_xxxxxxxxxxxxxxxx", prefs.getString("key", ""));
        server = addField(column, "Server (stream key is added to the end)", StreamScripts.DEFAULT_SERVER,
                prefs.getString("server", StreamScripts.DEFAULT_SERVER));
        silentAudio = new CheckBox(this);
        silentAudio.setText("Add a silent audio track (needed by YouTube)");
        silentAudio.setChecked(prefs.getBoolean("silentAudio", false));
        column.addView(silentAudio);

        okButton = addButton(column, "OK: check everything and prepare the scripts", v -> prepareScripts());

        addLabel(column, "Script written to ~/stream.sh:");
        preview = new TextView(this);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextIsSelectable(true);
        preview.setPadding(dp(8), dp(8), dp(8), dp(8));
        preview.setBackgroundColor(0x22888888);
        column.addView(preview);

        LinearLayout run = row(column);
        startTcp = addButton(run, "Start (TCP)", v -> runInTermux("bash " + StreamScripts.STREAM_SCRIPT, false,
                "Streaming started in Termux. Watch the Termux window for ffmpeg output."));
        startUdp = addButton(run, "Start (UDP)", v -> runInTermux("bash " + StreamScripts.UDP_SCRIPT, false,
                "Streaming (UDP) started in Termux. Watch the Termux window for ffmpeg output."));
        stopButton = addButton(run, "Stop", v -> stop());

        status = new TextView(this);
        status.setPadding(0, dp(8), 0, dp(8));
        column.addView(status);

        addLabel(column, "First time only: run this once in Termux, then allow the permission this app asks for. "
                + "For start at boot also install the Termux:Boot app and open it once.");
        TextView setup = new TextView(this);
        setup.setTypeface(Typeface.MONOSPACE);
        setup.setTextIsSelectable(true);
        setup.setText(TERMUX_SETUP);
        column.addView(setup);
        addButton(column, "Copy Termux setup command", v -> copy(TERMUX_SETUP));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);

        camera.addTextChangedListener(new SimpleWatcher(() -> {
            if (!updatingCamera) {
                codec = null;
                showWaiting("Camera address changed. Press \"Check camera feed\" again.");
            }
        }));
        SimpleWatcher invalidate = new SimpleWatcher(this::invalidateScripts);
        key.addTextChangedListener(invalidate);
        server.addTextChangedListener(invalidate);
        silentAudio.setOnClickListener(v -> invalidateScripts());

        showWaiting(isInstalled(GCS_PACKAGE)
                ? "Step 1: Open DhakshaGroundControl, connect to the drone and wait until the camera picture "
                        + "shows. Then press \"Check camera feed\"."
                : "DhakshaGroundControl is not installed on this device. Install it, connect to the drone and "
                        + "get the camera picture, then press \"Check camera feed\".");

        if (needsPermission()) {
            requestPermissions(new String[] { TERMUX_PERMISSION }, 1);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("camera", text(camera))
                .putString("key", text(key))
                .putString("server", text(server))
                .putBoolean("silentAudio", silentAudio.isChecked())
                .apply();
    }

    private void openGroundControl() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(GCS_PACKAGE);
        if (launch == null) {
            setStatus("DhakshaGroundControl is not installed.", true);
            return;
        }
        startActivity(launch);
    }

    /** Looks for the camera at the entered address, then at the known camera addresses, off the UI thread. */
    private void checkFeed() {
        if (checking) {
            return;
        }
        checking = true;
        checkButton.setEnabled(false);
        final Set<String> candidates = new LinkedHashSet<>();
        if (!text(camera).isEmpty()) {
            candidates.add(text(camera));
        }
        for (String known : KNOWN_CAMERAS) {
            candidates.add(known);
        }
        showWaiting("Looking for the camera feed...");
        new Thread(() -> {
            RtspProbe.Result found = null;
            RtspProbe.Result first = null;
            for (String url : candidates) {
                RtspProbe.Result result = RtspProbe.probe(url, PROBE_TIMEOUT_MS);
                if (first == null) {
                    first = result;
                }
                if (result.hasFeed()) {
                    found = result;
                    break;
                }
            }
            final RtspProbe.Result outcome = found != null ? found : first;
            runOnUiThread(() -> feedChecked(outcome));
        }).start();
    }

    private void feedChecked(RtspProbe.Result result) {
        checking = false;
        checkButton.setEnabled(true);
        if (result == null || !result.hasFeed()) {
            codec = null;
            showWaiting("No camera feed: " + (result == null ? "no address to check" : result.problem) + ".\n"
                    + "Run DhakshaGroundControl, connect with the drone and get the camera feed, "
                    + "then press \"Check camera feed\" again.");
            return;
        }
        updatingCamera = true;
        camera.setText(result.url);
        updatingCamera = false;
        codec = result.codec;
        cameraUnlocked = true;
        camera.setEnabled(true);
        String what;
        if (RtspProbe.H264.equals(codec)) {
            what = "H.264: the video is sent as it is.";
        } else if (RtspProbe.H265.equals(codec)) {
            what = "H.265: the video is converted to H.264 on the MK15 (720p, 2 Mbit/s), because the server needs H.264.";
        } else {
            what = codec + ": this codec is not supported for streaming.";
        }
        showReady("Camera feed found at " + result.url + "\nCamera sends " + what
                + "\nNow check the stream key and press OK.");
        invalidateScripts();
    }

    private void prepareScripts() {
        String camera = text(this.camera);
        String key = text(this.key);
        String server = text(this.server);
        String problem = StreamScripts.validate(key, server, camera, codec);
        if (problem != null) {
            setStatus(problem, true);
            return;
        }
        String destination = StreamScripts.destination(server, key);
        boolean audio = silentAudio.isChecked();
        String tcp = StreamScripts.ffmpegCommand(camera, destination, codec, false, audio);
        String udp = StreamScripts.ffmpegCommand(camera, destination, codec, true, audio);
        String streamScript = StreamScripts.streamScript(tcp, codec, "TCP");
        String udpScript = StreamScripts.streamScript(udp, codec, "UDP");
        String bootScript = StreamScripts.bootScript(tcp, codec, StreamScripts.cameraHost(camera));

        if (runInTermux(StreamScripts.installCommand(streamScript, udpScript, bootScript), true,
                "Scripts saved in Termux: ~/stream.sh, ~/stream_udp.sh and ~/.termux/boot/autostream.sh. "
                        + "Press Start.")) {
            preview.setText(streamScript);
            setRunEnabled(true);
        }
    }

    private void stop() {
        String script = "[ -f " + StreamScripts.PID_FILE + " ] && kill $(cat " + StreamScripts.PID_FILE + ") 2>/dev/null; "
                + "rm -f " + StreamScripts.PID_FILE + "; pkill -x ffmpeg; termux-wake-unlock 2>/dev/null; true";
        runInTermux(script, true, "Stop sent to Termux.");
    }

    /** Key, server or audio changed: the saved scripts no longer match, so OK has to be pressed again. */
    private void invalidateScripts() {
        setRunEnabled(false);
        okButton.setEnabled(codec != null);
        preview.setText(codec == null ? "Check the camera feed first." : "Press OK to prepare the scripts.");
    }

    private boolean runInTermux(String script, boolean background, String doneMessage) {
        if (!isInstalled(TERMUX_PACKAGE)) {
            setStatus("Termux is not installed.", true);
            return false;
        }
        if (needsPermission()) {
            requestPermissions(new String[] { TERMUX_PERMISSION }, 1);
            setStatus("Allow \"Run commands in Termux environment\", then press the button again.", true);
            return false;
        }
        Intent intent = new Intent("com.termux.RUN_COMMAND");
        intent.setComponent(new ComponentName(TERMUX_PACKAGE, TERMUX_SERVICE));
        intent.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[] { "-c", script });
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME);
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background);
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");    // show the new session in Termux
        try {
            // DhakshaKey is in the foreground when a button is pressed, so a plain start is allowed
            startService(intent);
            setStatus(doneMessage, false);
            return true;
        } catch (SecurityException e) {
            setStatus("Termux refused the command. Run the first-time setup command below in Termux, "
                    + "then try again.\n(" + e.getMessage() + ")", true);
        } catch (RuntimeException e) {
            setStatus("Could not reach Termux: " + e.getMessage(), true);
        }
        return false;
    }

    private void showWaiting(String message) {
        banner.setText(message);
        banner.setBackgroundColor(Color.rgb(170, 40, 40));
        banner.setTextColor(Color.WHITE);
        AlphaAnimation flash = new AlphaAnimation(1f, 0.35f);
        flash.setDuration(700);
        flash.setRepeatMode(Animation.REVERSE);
        flash.setRepeatCount(Animation.INFINITE);
        banner.startAnimation(flash);
        camera.setEnabled(cameraUnlocked);
        invalidateScripts();
    }

    private void showReady(String message) {
        banner.clearAnimation();
        banner.setText(message);
        banner.setBackgroundColor(Color.rgb(30, 120, 60));
        banner.setTextColor(Color.WHITE);
    }

    private void setRunEnabled(boolean enabled) {
        startTcp.setEnabled(enabled);
        startUdp.setEnabled(enabled);
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
        setStatus("Copied.", false);
    }

    private void setStatus(String message, boolean error) {
        status.setText(message);
        status.setTextColor(error ? Color.rgb(220, 60, 60) : Color.rgb(60, 170, 90));
    }

    private static String text(EditText field) {
        return field.getText().toString().trim();
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(row);
        return row;
    }

    private EditText addField(LinearLayout parent, String label, String hint, String value) {
        addLabel(parent, label);
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        parent.addView(field);
        return field;
    }

    private void addLabel(LinearLayout parent, String label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setPadding(0, dp(12), 0, dp(4));
        parent.addView(view);
    }

    private Button addButton(LinearLayout parent, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        parent.addView(button);
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
}

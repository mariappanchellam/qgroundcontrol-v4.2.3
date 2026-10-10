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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * DhakshaKey: sends the drone camera to the Dhaksha video server with a few taps.
 *
 * The operator picks the camera (its IP is filled in and can be changed), the server IP, the drone number
 * and the video quality, then presses OK. DhakshaKey checks that the camera answers, writes the tested
 * streaming script with those values to ~/stream_<camera>.sh in Termux and runs it in a Termux window.
 * Optionally it also writes the Termux:Boot script that starts streaming when the MK15 turns on.
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
    private static final String OTHER_DEFAULT_RTSP = "rtsp://192.168.144.10:554/stream";

    static final String TERMUX_SETUP =
            "mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings";

    private SharedPreferences prefs;
    private TextView banner;
    private Spinner cameraSpinner;
    private LinearLayout ipBlock;
    private EditText cameraIp;
    private TextView cameraAddress;
    private LinearLayout otherBlock;
    private EditText otherRtsp;
    private EditText serverIp;
    private Spinner droneSpinner;
    private Spinner qualitySpinner;
    private CheckBox startAtBoot;
    private CheckBox showPosition;
    private LinearLayout advancedBlock;
    private EditText password;
    private Button okButton;
    private Button startAnyway;
    private TextView watchLink;
    private TextView status;

    private int shownCamera = -1;       // camera whose IP is in the IP field
    private boolean checking;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        column.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("DhakshaKey");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        column.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("Live drone video to the Dhaksha server");
        subtitle.setTextSize(15);
        column.addView(subtitle);

        banner = new TextView(this);
        banner.setTextSize(17);
        banner.setTypeface(Typeface.DEFAULT_BOLD);
        banner.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams bannerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bannerParams.topMargin = dp(12);
        column.addView(banner, bannerParams);

        // 1. Camera
        addHeading(column, "1. Camera");
        String[] cameraNames = new String[StreamScripts.CAMERAS.length];
        for (int i = 0; i < cameraNames.length; i++) {
            cameraNames[i] = StreamScripts.CAMERAS[i].name;
        }
        cameraSpinner = addSpinner(column, cameraNames, prefs.getInt("camera", 0));

        ipBlock = block(column);
        addLabel(ipBlock, "Camera IP (filled in for the chosen camera; change it if yours is different)");
        cameraIp = addField(ipBlock, "192.168.144.25", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        cameraAddress = new TextView(this);
        cameraAddress.setTextColor(Color.GRAY);
        ipBlock.addView(cameraAddress);

        otherBlock = block(column);
        addLabel(otherBlock, "Camera stream address (from the camera's manual)");
        otherRtsp = addField(otherBlock, OTHER_DEFAULT_RTSP, prefs.getString("otherRtsp", OTHER_DEFAULT_RTSP),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        // 2. Server
        addHeading(column, "2. Server IP");
        serverIp = addField(column, StreamScripts.DEFAULT_SERVER_IP,
                prefs.getString("server", StreamScripts.DEFAULT_SERVER_IP),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        // 3. Drone number
        addHeading(column, "3. Drone number (each drone needs its own)");
        String[] drones = new String[StreamScripts.MAX_DRONES];
        for (int i = 0; i < drones.length; i++) {
            drones[i] = "Drone " + (i + 1) + "   (" + StreamScripts.droneName(i + 1) + ")";
        }
        droneSpinner = addSpinner(column, drones, prefs.getInt("drone", 0));

        // 4. Quality
        addHeading(column, "4. Video quality");
        String[] qualities = new String[StreamScripts.QUALITIES.length];
        for (int i = 0; i < qualities.length; i++) {
            qualities[i] = StreamScripts.QUALITIES[i].label;
        }
        qualitySpinner = addSpinner(column, qualities, prefs.getInt("quality", StreamScripts.DEFAULT_QUALITY));

        startAtBoot = new CheckBox(this);
        startAtBoot.setText("Start streaming automatically when the MK15 turns on");
        startAtBoot.setTextSize(16);
        startAtBoot.setChecked(prefs.getBoolean("boot", false));
        column.addView(startAtBoot);

        showPosition = new CheckBox(this);
        showPosition.setText("Show the drone's position (lat, lon, altitude) on the video");
        showPosition.setTextSize(16);
        showPosition.setChecked(prefs.getBoolean("position", false));
        column.addView(showPosition);
        addLabel(column, "For the position: in DhakshaGroundControl tick Application Settings > MAVLink > "
                + "\"Enable MAVLink forwarding\" (host name localhost:" + StreamScripts.TELEMETRY_PORT
                + "), then restart DhakshaGroundControl.");

        CheckBox advanced = new CheckBox(this);
        advanced.setText("Show advanced settings");
        column.addView(advanced);
        advancedBlock = block(column);
        addLabel(advancedBlock, "Server password (the one given when the server was set up)");
        password = addField(advancedBlock, StreamScripts.DEFAULT_PASSWORD,
                prefs.getString("password", StreamScripts.DEFAULT_PASSWORD),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        addLabel(advancedBlock, "Video is sent to port " + StreamScripts.RTMP_PORT + " (RTMP) and watched on port "
                + StreamScripts.WATCH_PORT + ".");
        advancedBlock.setVisibility(View.GONE);
        advanced.setOnCheckedChangeListener((v, on) -> advancedBlock.setVisibility(on ? View.VISIBLE : View.GONE));

        okButton = addBigButton(column, "OK  –  Start streaming", Color.rgb(30, 130, 60), v -> start(false));
        startAnyway = addBigButton(column, "Start anyway (camera not answering yet)", Color.rgb(200, 120, 20),
                v -> start(true));
        startAnyway.setVisibility(View.GONE);
        addBigButton(column, "Stop streaming", Color.rgb(180, 40, 40), v -> stop());

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, dp(8), 0, dp(8));
        column.addView(status);

        addHeading(column, "Watch this drone at");
        watchLink = new TextView(this);
        watchLink.setTextSize(16);
        watchLink.setTypeface(Typeface.MONOSPACE);
        watchLink.setTextIsSelectable(true);
        column.addView(watchLink);
        addButton(column, "Copy watch link", v -> copy(watchLink.getText().toString()));

        addHeading(column, "First time on this MK15");
        addLabel(column, "1. Install Termux (and Termux:Boot for start at power-on) from F-Droid.\n"
                + "2. In Termux run this once:  pkg install ffmpeg python  and then the command below.\n"
                + "3. Allow \"Run commands in Termux environment\" when DhakshaKey asks.");
        TextView setup = new TextView(this);
        setup.setTypeface(Typeface.MONOSPACE);
        setup.setTextIsSelectable(true);
        setup.setText(TERMUX_SETUP);
        column.addView(setup);
        LinearLayout tools = row(column);
        addButton(tools, "Copy setup command", v -> copy(TERMUX_SETUP));
        addButton(tools, "Open DhakshaGroundControl", v -> openGroundControl());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);

        cameraSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                showCamera(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        showCamera(cameraSpinner.getSelectedItemPosition());
        SimpleWatcher refresh = new SimpleWatcher(this::refresh);
        cameraIp.addTextChangedListener(refresh);
        otherRtsp.addTextChangedListener(refresh);
        serverIp.addTextChangedListener(refresh);
        droneSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                refresh();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        showBanner("Choose your camera, check the server IP and drone number, then press OK.", false);
        if (needsPermission()) {
            requestPermissions(new String[] { TERMUX_PERMISSION }, 1);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    private void save() {
        SharedPreferences.Editor edit = prefs.edit()
                .putInt("camera", cameraSpinner.getSelectedItemPosition())
                .putString("otherRtsp", text(otherRtsp))
                .putString("server", text(serverIp))
                .putInt("drone", droneSpinner.getSelectedItemPosition())
                .putInt("quality", qualitySpinner.getSelectedItemPosition())
                .putBoolean("boot", startAtBoot.isChecked())
                .putBoolean("position", showPosition.isChecked())
                .putString("password", text(password));
        if (shownCamera >= 0) {
            edit.putString("ip_" + StreamScripts.CAMERAS[shownCamera].fileTag, text(cameraIp));
        }
        edit.apply();
    }

    /** Shows the chosen camera: its saved or default IP, or the full address field for "Other". */
    private void showCamera(int index) {
        if (shownCamera >= 0 && shownCamera != index) {
            prefs.edit().putString("ip_" + StreamScripts.CAMERAS[shownCamera].fileTag, text(cameraIp)).apply();
        }
        StreamScripts.Camera camera = StreamScripts.CAMERAS[index];
        shownCamera = index;
        cameraIp.setText(prefs.getString("ip_" + camera.fileTag, camera.defaultIp));
        ipBlock.setVisibility(camera.customAddress() ? View.GONE : View.VISIBLE);
        otherBlock.setVisibility(camera.customAddress() ? View.VISIBLE : View.GONE);
        refresh();
    }

    private StreamScripts.Camera camera() {
        return StreamScripts.CAMERAS[cameraSpinner.getSelectedItemPosition()];
    }

    private String cameraRtsp() {
        StreamScripts.Camera camera = camera();
        return camera.customAddress() ? text(otherRtsp) : camera.rtsp(text(cameraIp));
    }

    private int droneNumber() {
        return droneSpinner.getSelectedItemPosition() + 1;
    }

    private void refresh() {
        cameraAddress.setText("Stream address: " + cameraRtsp());
        watchLink.setText(StreamScripts.watchUrl(text(serverIp), droneNumber()));
        startAnyway.setVisibility(View.GONE);
    }

    /** OK: checks the inputs and the camera, then saves the script and runs it in Termux. */
    private void start(boolean skipCameraCheck) {
        if (checking) {
            return;
        }
        final String rtsp = cameraRtsp();
        String problem = StreamScripts.validate(rtsp, text(serverIp), droneNumber(), text(password));
        if (problem != null) {
            showBanner(problem, true);
            return;
        }
        save();
        if (skipCameraCheck) {
            saveAndRun(rtsp);
            return;
        }
        checking = true;
        okButton.setEnabled(false);
        showBanner("Checking the camera at " + rtsp + " ...", false);
        new Thread(() -> {
            final RtspProbe.Result result = RtspProbe.probe(rtsp, PROBE_TIMEOUT_MS);
            runOnUiThread(() -> {
                checking = false;
                okButton.setEnabled(true);
                if (result.hasFeed()) {
                    saveAndRun(rtsp);
                } else {
                    showBanner("The camera is not answering (" + result.problem + ").\n"
                            + "Check that the drone is powered on and the camera picture shows in "
                            + "DhakshaGroundControl, and that the camera type and IP are right. Then press OK again.",
                            true);
                    startAnyway.setVisibility(View.VISIBLE);
                }
            });
        }).start();
    }

    private void saveAndRun(String rtsp) {
        StreamScripts.Camera camera = camera();
        String server = text(serverIp);
        StreamScripts.Quality quality = StreamScripts.QUALITIES[qualitySpinner.getSelectedItemPosition()];
        boolean position = showPosition.isChecked();
        String stream = StreamScripts.streamScript(camera, rtsp, server, droneNumber(), text(password), quality,
                position);
        String boot = startAtBoot.isChecked() ? StreamScripts.bootScript(camera, rtsp, server) : null;
        String reader = null;
        if (position) {
            reader = readAsset("dhaksha_telemetry.py");
            if (reader == null) {
                showBanner("The position reader is missing from this app. Untick \"Show the drone's position\".", true);
                return;
            }
        }
        if (runInTermux(StreamScripts.saveAndStartCommand(camera, stream, boot, reader), false)) {
            startAnyway.setVisibility(View.GONE);
            showBanner("Streaming started with " + camera.scriptPath() + ". The Termux window shows its progress.\n"
                    + "Watch at " + StreamScripts.watchUrl(server, droneNumber()), false);
            setStatus(startAtBoot.isChecked()
                    ? "It will also start by itself when the MK15 turns on."
                    : "It will not start by itself at power-on (tick the box above for that).", false);
        }
    }

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

    private void stop() {
        if (runInTermux(StreamScripts.stopCommand(), true)) {
            showBanner("Streaming stopped.", false);
            setStatus("", false);
        }
    }

    private void openGroundControl() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(GCS_PACKAGE);
        if (launch == null) {
            setStatus("DhakshaGroundControl is not installed.", true);
            return;
        }
        startActivity(launch);
    }

    private boolean runInTermux(String script, boolean background) {
        if (!isInstalled(TERMUX_PACKAGE)) {
            showBanner("Termux is not installed. Install Termux from F-Droid first.", true);
            return false;
        }
        if (needsPermission()) {
            requestPermissions(new String[] { TERMUX_PERMISSION }, 1);
            showBanner("Allow \"Run commands in Termux environment\", then press the button again.", true);
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
            showBanner("Termux refused the command. Do the \"First time on this MK15\" steps below, "
                    + "then try again.", true);
            setStatus(e.getMessage(), true);
        } catch (RuntimeException e) {
            showBanner("Could not reach Termux: " + e.getMessage(), true);
        }
        return false;
    }

    private void showBanner(String message, boolean problem) {
        banner.setText(message);
        banner.setBackgroundColor(problem ? Color.rgb(170, 40, 40) : Color.rgb(30, 110, 60));
        banner.setTextColor(Color.WHITE);
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

    private LinearLayout block(LinearLayout parent) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        parent.addView(block);
        return block;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(row);
        return row;
    }

    private Spinner addSpinner(LinearLayout parent, String[] items, int selected) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected >= 0 && selected < items.length ? selected : 0);
        spinner.setMinimumHeight(dp(48));
        parent.addView(spinner);
        return spinner;
    }

    private EditText addField(LinearLayout parent, String hint, String value, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value);
        field.setTextSize(18);
        field.setSingleLine(true);
        field.setInputType(inputType);
        parent.addView(field);
        return field;
    }

    private void addHeading(LinearLayout parent, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(16), 0, dp(4));
        parent.addView(view);
    }

    private void addLabel(LinearLayout parent, String label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setPadding(0, dp(8), 0, dp(4));
        parent.addView(view);
    }

    private Button addButton(LinearLayout parent, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        parent.addView(button);
        return button;
    }

    private Button addBigButton(LinearLayout parent, String label, int color, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(18);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(color);
        button.setMinHeight(dp(56));
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
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
}

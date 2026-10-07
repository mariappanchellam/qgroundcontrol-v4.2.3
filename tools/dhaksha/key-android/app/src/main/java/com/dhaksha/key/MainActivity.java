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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * DhakshaKey: builds the drone streaming ffmpeg command from a stream key and runs it in Termux.
 *
 * Termux runs the command through its RUN_COMMAND service. That needs, once:
 *   - in Termux: allow-external-apps = true in ~/.termux/termux.properties
 *   - for this app: the "Run commands in Termux environment" permission
 */
public class MainActivity extends Activity {

    private static final String PREFS = "dhaksha_key";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String TERMUX_SERVICE = "com.termux.app.RunCommandService";
    private static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String PID_FILE = "$HOME/.dhakshakey.pid";

    static final String DEFAULT_SERVER = "rtmp://stream.livepush.io/live/";
    static final String DEFAULT_CAMERA = "rtsp://192.168.144.119:554/stream0";

    static final String TERMUX_SETUP =
            "mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings";

    private EditText key;
    private EditText server;
    private EditText camera;
    private CheckBox udp;
    private CheckBox reconnect;
    private CheckBox silentAudio;
    private TextView preview;
    private TextView status;

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

        key = addField(column, "Stream key", "rtmp_xxxxxxxxxxxxxxxx", prefs.getString("key", ""));
        server = addField(column, "Server (stream key is added to the end)", DEFAULT_SERVER,
                prefs.getString("server", DEFAULT_SERVER));
        camera = addField(column, "Camera RTSP address", DEFAULT_CAMERA,
                prefs.getString("camera", DEFAULT_CAMERA));

        reconnect = addCheck(column, "Reconnect automatically when the stream drops", prefs.getBoolean("reconnect", true));
        udp = addCheck(column, "Read the camera over UDP (default TCP)", prefs.getBoolean("udp", false));
        silentAudio = addCheck(column, "Add a silent audio track (needed by YouTube)", prefs.getBoolean("silentAudio", false));

        addLabel(column, "Command that will run in Termux:");
        preview = new TextView(this);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextIsSelectable(true);
        preview.setPadding(dp(8), dp(8), dp(8), dp(8));
        preview.setBackgroundColor(0x22888888);
        column.addView(preview);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        addButton(buttons, "Start in Termux", v -> start());
        addButton(buttons, "Stop", v -> stop());
        addButton(buttons, "Copy", v -> copy("ffmpeg command", buildScript()));
        column.addView(buttons);

        status = new TextView(this);
        status.setPadding(0, dp(8), 0, dp(8));
        column.addView(status);

        addLabel(column, "First time only: run this once in Termux, then allow the permission this app asks for.");
        TextView setup = new TextView(this);
        setup.setTypeface(Typeface.MONOSPACE);
        setup.setTextIsSelectable(true);
        setup.setText(TERMUX_SETUP);
        column.addView(setup);
        addButton(column, "Copy Termux setup command", v -> copy("Termux setup", TERMUX_SETUP));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(column);
        setContentView(scroll);

        TextWatcher refresh = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { updatePreview(); }
        };
        key.addTextChangedListener(refresh);
        server.addTextChangedListener(refresh);
        camera.addTextChangedListener(refresh);
        View.OnClickListener recheck = v -> updatePreview();
        reconnect.setOnClickListener(recheck);
        udp.setOnClickListener(recheck);
        silentAudio.setOnClickListener(recheck);
        updatePreview();

        if (needsPermission()) {
            requestPermissions(new String[] { TERMUX_PERMISSION }, 1);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("key", text(key))
                .putString("server", text(server))
                .putString("camera", text(camera))
                .putBoolean("reconnect", reconnect.isChecked())
                .putBoolean("udp", udp.isChecked())
                .putBoolean("silentAudio", silentAudio.isChecked())
                .apply();
    }

    private void updatePreview() {
        preview.setText(text(key).isEmpty() ? "Enter the stream key" : buildScript());
    }

    /** Full RTMP destination: the server with the key appended, or the key itself if it is already a URL. */
    static String destination(String server, String key) {
        if (key.contains("://")) {
            return key;
        }
        if (server.isEmpty()) {
            server = DEFAULT_SERVER;
        }
        return server.endsWith("/") ? server + key : server + "/" + key;
    }

    static String buildCommand(String camera, String destination, boolean udp, boolean silentAudio) {
        StringBuilder cmd = new StringBuilder("ffmpeg -rtsp_transport ").append(udp ? "udp" : "tcp")
                .append(" -i ").append(shellQuote(camera));
        if (silentAudio) {
            cmd.append(" -f lavfi -i anullsrc=r=44100:cl=stereo -map 0:v -map 1:a -c:v copy -c:a aac -b:a 128k -shortest");
        } else {
            cmd.append(" -c:v copy -an");
        }
        return cmd.append(" -f flv ").append(shellQuote(destination)).toString();
    }

    private String buildScript() {
        String camera = text(this.camera).isEmpty() ? DEFAULT_CAMERA : text(this.camera);
        String command = buildCommand(camera, destination(text(server), text(key)), udp.isChecked(), silentAudio.isChecked());
        if (!reconnect.isChecked()) {
            return command;
        }
        return "while true; do\n  " + command
                + "\n  echo \"Stream dropped. Reconnecting in 5 seconds...\"\n  sleep 5\ndone";
    }

    private void start() {
        if (text(key).isEmpty()) {
            setStatus("Enter the stream key first.", true);
            return;
        }
        // Records the script's process id so Stop can end the reconnect loop, then keeps the CPU awake
        String script = "echo $$ > " + PID_FILE + "\ntermux-wake-lock 2>/dev/null\n"
                + "echo 'DhakshaKey: streaming started. Press CTRL+C or use Stop in DhakshaKey to end.'\n"
                + buildScript();
        if (runInTermux(script, false)) {
            setStatus("Started in Termux. Check the Termux window for ffmpeg output.", false);
        }
    }

    private void stop() {
        String script = "[ -f " + PID_FILE + " ] && kill $(cat " + PID_FILE + ") 2>/dev/null; rm -f " + PID_FILE
                + "; pkill -x ffmpeg; termux-wake-unlock 2>/dev/null; true";
        if (runInTermux(script, true)) {
            setStatus("Stop sent to Termux.", false);
        }
    }

    private boolean runInTermux(String script, boolean background) {
        if (!isTermuxInstalled()) {
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
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");    // open the new session in Termux
        try {
            // DhakshaKey is in the foreground when a button is pressed, so a plain start is allowed
            startService(intent);
            return true;
        } catch (SecurityException e) {
            setStatus("Termux refused the command. Run the first-time setup command below in Termux, "
                    + "then try again.\n(" + e.getMessage() + ")", true);
        } catch (RuntimeException e) {
            setStatus("Could not reach Termux: " + e.getMessage(), true);
        }
        return false;
    }

    private boolean isTermuxInstalled() {
        try {
            getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean needsPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED;
    }

    /** Single-quotes a value for bash so keys and addresses can never break or extend the command. */
    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void copy(String label, String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
        setStatus("Copied.", false);
    }

    private void setStatus(String message, boolean error) {
        status.setText(message);
        status.setTextColor(error ? Color.rgb(220, 60, 60) : Color.rgb(60, 170, 90));
    }

    private static String text(EditText field) {
        return field.getText().toString().trim();
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

    private CheckBox addCheck(LinearLayout parent, String label, boolean checked) {
        CheckBox check = new CheckBox(this);
        check.setText(label);
        check.setChecked(checked);
        parent.addView(check);
        return check;
    }

    private void addLabel(LinearLayout parent, String label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setPadding(0, dp(12), 0, dp(4));
        parent.addView(view);
    }

    private void addButton(LinearLayout parent, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        parent.addView(button);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

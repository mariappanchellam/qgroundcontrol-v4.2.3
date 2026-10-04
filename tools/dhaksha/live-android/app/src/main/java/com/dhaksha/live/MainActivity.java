package com.dhaksha.live;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

/**
 * Dhaksha Live: plays the drone video that DhakshaGroundControl streams to the server.
 *
 * The stream address is entered once and remembered; afterwards the app plays it as soon as
 * it opens, and keeps retrying while the stream is not (yet) published.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "dhaksha_live";
    private static final String KEY_URL = "url";
    private static final String DEFAULT_PATH = "/live/drone1";
    private static final long RETRY_DELAY_MS = 3000;
    private static final long LIVE_OFFSET_MS = 1500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retry = this::play;

    private ExoPlayer player;
    private PlayerView playerView;
    private TextView status;
    private String url = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setUseController(false);
        root.addView(playerView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setBackgroundColor(0x99000000);
        status.setPadding(24, 12, 24, 12);
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        statusParams.setMargins(24, 24, 24, 24);
        root.addView(status, statusParams);

        Button address = new Button(this);
        address.setText("Stream address");
        address.setOnClickListener(v -> showAddressDialog());
        FrameLayout.LayoutParams addressParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        addressParams.setMargins(24, 24, 24, 24);
        root.addView(address, addressParams);

        setContentView(root);
        hideSystemBars();

        url = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "");
        if (url.isEmpty()) {
            showAddressDialog();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    status.setText("● LIVE  " + Uri.parse(url).getHost());
                } else if (state == Player.STATE_BUFFERING) {
                    status.setText("Connecting…");
                } else if (state == Player.STATE_ENDED) {
                    status.setText("Stream ended, waiting for it to restart…");
                    scheduleRetry();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                status.setText("Waiting for the stream (" + error.getErrorCodeName() + ")");
                scheduleRetry();
            }
        });
        playerView.setPlayer(player);
        play();
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(retry);
        playerView.setPlayer(null);
        player.release();
        player = null;
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    private void play() {
        handler.removeCallbacks(retry);
        if (player == null || url.isEmpty()) {
            if (url.isEmpty()) {
                status.setText("Tap \"Stream address\" to enter the server address");
            }
            return;
        }
        String scheme = Uri.parse(url).getScheme();
        MediaItem.Builder item = new MediaItem.Builder()
                .setUri(url)
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(LIVE_OFFSET_MS)
                        .build());
        if ("http".equals(scheme) || "https".equals(scheme)) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        status.setText("Connecting…");
        player.setMediaItem(item.build());
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void scheduleRetry() {
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, RETRY_DELAY_MS);
    }

    private void showAddressDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://xxxx.trycloudflare.com/live/drone1");
        input.setText(url);
        new AlertDialog.Builder(this)
                .setTitle("Stream address")
                .setMessage("Server address of the drone stream (https://..., http://...:8888/live/drone1 or rtsp://...)")
                .setView(input)
                .setPositiveButton("Play", (dialog, which) -> {
                    url = normalize(input.getText().toString());
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, url).apply();
                    play();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Accepts what people paste: the browser address of the stream (with or without https://),
     * a server address without a path, or a direct .m3u8 / rtsp:// URL.
     */
    static String normalize(String input) {
        String text = input.trim();
        if (text.isEmpty()) {
            return text;
        }
        if (!text.contains("://")) {
            text = "https://" + text;
        }
        Uri uri = Uri.parse(text);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return text;
        }
        String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/{2,}", "/");
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            path = DEFAULT_PATH;
        }
        if (!path.endsWith(".m3u8")) {
            path += "/index.m3u8";
        }
        return uri.buildUpon().scheme(scheme).path(path).build().toString();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }
}

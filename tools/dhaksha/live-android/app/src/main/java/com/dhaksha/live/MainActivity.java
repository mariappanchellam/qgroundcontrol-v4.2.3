package com.dhaksha.live;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

/**
 * Dhaksha Live: watches up to eight drone streams at once.
 *
 * The first page takes eight stream addresses (remembered between runs). OK opens the grid, where
 * every box with a live stream plays it and every other box shows the Dhaksha logo; boxes whose
 * stream is not (yet) live keep retrying, so a drone that starts streaming later appears by itself.
 * Back returns to the addresses; OK again restarts all players with the new addresses.
 */
public class MainActivity extends Activity {

    static final int STREAMS = 8;

    private static final String PREFS = "dhaksha_live";
    private static final String DEFAULT_PATH = "/live/drone1";
    private static final long RETRY_DELAY_MS = 3000;
    private static final long LIVE_OFFSET_MS = 1500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EditText[] addressFields = new EditText[STREAMS];
    private final Cell[] cells = new Cell[STREAMS];

    private View setupPage;
    private FrameLayout gridPage;
    private boolean showingGrid;
    private boolean started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String legacy = prefs.getString("url", "");     // single address saved by the first version of the app

        for (int i = 0; i < STREAMS; i++) {
            cells[i] = new Cell(i);
            cells[i].url = normalize(prefs.getString("url" + (i + 1), i == 0 ? legacy : ""));
        }

        setupPage = buildSetupPage();
        gridPage = new FrameLayout(this);
        gridPage.setBackgroundColor(Color.BLACK);
        layoutGrid();

        boolean anyAddress = false;
        for (Cell cell : cells) {
            anyAddress |= !cell.url.isEmpty();
        }
        show(anyAddress);
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (showingGrid) {
            startPlayers();
        }
    }

    @Override
    protected void onStop() {
        started = false;
        stopPlayers();
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (showingGrid) {
            show(false);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        layoutGrid();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && showingGrid) {
            hideSystemBars();
        }
    }

    // ---- Pages ----------------------------------------------------------------------------

    private View buildSetupPage() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        column.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Dhaksha Live");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        column.addView(title);

        TextView help = new TextView(this);
        help.setText("Enter up to eight stream addresses (https://..., http://...:8888/live/drone1 or rtsp://...). "
                + "Empty boxes show the Dhaksha logo. Press OK to watch; Back returns here.");
        help.setTextColor(Color.LTGRAY);
        help.setPadding(0, dp(4), 0, dp(12));
        column.addView(help);

        for (int i = 0; i < STREAMS; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView label = new TextView(this);
            label.setText("Drone " + (i + 1));
            label.setTextColor(Color.WHITE);
            label.setMinWidth(dp(72));
            row.addView(label);

            EditText field = new EditText(this);
            field.setSingleLine(true);
            field.setHint("https://xxxx.trycloudflare.com/live/drone" + (i + 1));
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            field.setText(cells[i].url);
            field.setTextColor(Color.WHITE);
            field.setHintTextColor(Color.GRAY);
            row.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button clear = new Button(this);
            clear.setText("Clear");
            clear.setOnClickListener(v -> field.setText(""));
            row.addView(clear);

            addressFields[i] = field;
            column.addView(row);
        }

        Button ok = new Button(this);
        ok.setText("OK: show all streams");
        ok.setOnClickListener(v -> applyAddresses());
        LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        okParams.topMargin = dp(12);
        column.addView(ok, okParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(18, 18, 18));
        scroll.addView(column);
        return scroll;
    }

    private void applyAddresses() {
        SharedPreferences.Editor prefs = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        for (int i = 0; i < STREAMS; i++) {
            String url = normalize(addressFields[i].getText().toString());
            addressFields[i].setText(url);
            cells[i].url = url;
            prefs.putString("url" + (i + 1), url);
        }
        prefs.remove("url").apply();

        InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (keyboard != null && getCurrentFocus() != null) {
            keyboard.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
        show(true);
    }

    private void show(boolean grid) {
        showingGrid = grid;
        stopPlayers();
        if (grid) {
            setContentView(gridPage);
            hideSystemBars();
            if (started) {
                startPlayers();
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            setContentView(setupPage);
        }
    }

    /** Arranges the eight boxes: 4 x 2 in landscape, 2 x 4 in portrait. */
    private void layoutGrid() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int columns = landscape ? 4 : 2;
        int rows = STREAMS / columns;

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < columns; c++) {
                View box = cells[r * columns + c].root;
                if (box.getParent() != null) {
                    ((ViewGroup) box.getParent()).removeView(box);
                }
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                params.setMargins(dp(1), dp(1), dp(1), dp(1));
                row.addView(box, params);
            }
            grid.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        gridPage.removeAllViews();
        gridPage.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void startPlayers() {
        for (Cell cell : cells) {
            cell.start();
        }
    }

    private void stopPlayers() {
        for (Cell cell : cells) {
            cell.stop();
        }
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---- One box of the grid ------------------------------------------------------------

    /** A player box: plays its stream when it is live, otherwise shows the Dhaksha logo and retries. */
    private final class Cell {
        final int index;
        final FrameLayout root;
        final PlayerView playerView;
        final View logo;
        final TextView label;
        final Runnable retry = this::play;
        String url = "";
        ExoPlayer player;

        Cell(int index) {
            this.index = index;
            root = new FrameLayout(MainActivity.this);
            root.setBackgroundColor(Color.BLACK);

            playerView = new PlayerView(MainActivity.this);
            playerView.setUseController(false);
            playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
            playerView.setVisibility(View.INVISIBLE);
            root.addView(playerView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            logo = buildLogo();
            root.addView(logo, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            label = new TextView(MainActivity.this);
            label.setTextColor(Color.WHITE);
            label.setTextSize(11);
            label.setBackgroundColor(0x99000000);
            label.setPadding(dp(6), dp(2), dp(6), dp(2));
            root.addView(label, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
            setState("");
        }

        private View buildLogo() {
            LinearLayout box = new LinearLayout(MainActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setBackgroundColor(Color.rgb(12, 24, 48));

            TextView name = new TextView(MainActivity.this);
            name.setText("DHAKSHA");
            name.setTextColor(Color.rgb(255, 160, 0));
            name.setTextSize(20);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setLetterSpacing(0.15f);
            box.addView(name);

            TextView sub = new TextView(MainActivity.this);
            sub.setText("Drone " + (index + 1));
            sub.setTextColor(Color.LTGRAY);
            sub.setTextSize(12);
            box.addView(sub);
            return box;
        }

        void start() {
            stop();
            if (url.isEmpty()) {
                return;
            }
            player = new ExoPlayer.Builder(MainActivity.this).build();
            player.setVolume(0f);
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        playerView.setVisibility(View.VISIBLE);
                        logo.setVisibility(View.INVISIBLE);
                        setState("LIVE");
                    } else if (state == Player.STATE_ENDED) {
                        showLogo("waiting");
                        scheduleRetry();
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    showLogo("waiting");
                    scheduleRetry();
                }
            });
            playerView.setPlayer(player);
            play();
        }

        void stop() {
            handler.removeCallbacks(retry);
            playerView.setPlayer(null);
            if (player != null) {
                player.release();
                player = null;
            }
            showLogo(url.isEmpty() ? "" : "waiting");
        }

        private void play() {
            handler.removeCallbacks(retry);
            if (player == null || url.isEmpty()) {
                return;
            }
            MediaItem.Builder item = new MediaItem.Builder()
                    .setUri(url)
                    .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                            .setTargetOffsetMs(LIVE_OFFSET_MS)
                            .build());
            String scheme = Uri.parse(url).getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) {
                item.setMimeType(MimeTypes.APPLICATION_M3U8);
            }
            setState("connecting");
            player.setMediaItem(item.build());
            player.prepare();
            player.setPlayWhenReady(true);
        }

        private void scheduleRetry() {
            handler.removeCallbacks(retry);
            handler.postDelayed(retry, RETRY_DELAY_MS);
        }

        private void showLogo(String state) {
            playerView.setVisibility(View.INVISIBLE);
            logo.setVisibility(View.VISIBLE);
            setState(state);
        }

        private void setState(String state) {
            StringBuilder text = new StringBuilder().append(index + 1);
            if (!state.isEmpty()) {
                text.append("  ").append(state);
            }
            if (state.equals("LIVE") && Uri.parse(url).getHost() != null) {
                text.append("  ").append(Uri.parse(url).getHost());
            }
            label.setText(text);
        }
    }

    // ---- Addresses ----------------------------------------------------------------------

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
}

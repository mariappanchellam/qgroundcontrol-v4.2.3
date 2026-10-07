package com.dhaksha.live;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Dhaksha Live: watches up to four drone streams from Livepush at once.
 *
 * The first page takes four Livepush player links (remembered between runs). OK opens a 2 x 2 grid
 * where every box with a Livepush link shows that player; empty boxes, and boxes whose address is not
 * a Livepush player link, show the Dhaksha logo. Back returns to the addresses; OK again reloads all
 * players with the new links.
 */
public class MainActivity extends Activity {

    static final int STREAMS = 4;

    private static final String PREFS = "dhaksha_live";
    private static final String LIVEPUSH_HOST = "player.livepush.io";

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
            cells[i].setAddress(prefs.getString("url" + (i + 1), i == 0 ? legacy : ""));
        }

        setupPage = buildSetupPage();
        gridPage = new FrameLayout(this);
        gridPage.setBackgroundColor(Color.BLACK);
        layoutGrid();

        boolean anyAddress = false;
        for (Cell cell : cells) {
            anyAddress |= !cell.text.isEmpty();
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
        help.setText("Paste up to four Livepush player links, like https://player.livepush.io/live/emYdk2WzYx3kNdaO. "
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
            field.setHint("https://player.livepush.io/live/...");
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            field.setText(cells[i].text);
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
            String typed = addressFields[i].getText().toString().trim();
            String url = livepushUrl(typed);
            String text = url == null ? typed : url;
            addressFields[i].setText(text);
            cells[i].setAddress(text);
            prefs.putString("url" + (i + 1), text);
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

    /** Arranges the four boxes 2 x 2. */
    private void layoutGrid() {
        int columns = 2;
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

    /** A player box: shows the Livepush player for its link, or the Dhaksha logo. */
    private final class Cell {
        final int index;
        final FrameLayout root;
        final View logo;
        final TextView note;
        final TextView label;
        String text = "";
        String url = "";
        WebView web;

        Cell(int index) {
            this.index = index;
            root = new FrameLayout(MainActivity.this);
            root.setBackgroundColor(Color.BLACK);

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
            note = new TextView(MainActivity.this);
            note.setTextColor(Color.rgb(255, 138, 128));
            note.setTextSize(12);
            box.addView(note);
            logo = box;
            root.addView(logo, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            label = new TextView(MainActivity.this);
            label.setText(String.valueOf(index + 1));
            label.setTextColor(Color.WHITE);
            label.setTextSize(11);
            label.setBackgroundColor(0x99000000);
            label.setPadding(dp(6), dp(2), dp(6), dp(2));
            root.addView(label, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        }

        void setAddress(String typed) {
            text = typed.trim();
            String link = livepushUrl(text);
            url = link == null ? "" : link;
            note.setText(link == null ? "Not a Livepush player link" : "");
        }

        void start() {
            stop();
            if (url.isEmpty()) {
                return;
            }
            web = new WebView(MainActivity.this);
            web.setBackgroundColor(Color.BLACK);
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            // Pages opened from the player stay inside its box
            web.setWebViewClient(new WebViewClient());
            root.addView(web, 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            web.loadUrl(url);
            logo.setVisibility(View.INVISIBLE);
        }

        void stop() {
            if (web != null) {
                root.removeView(web);
                web.loadUrl("about:blank");
                web.destroy();
                web = null;
            }
            logo.setVisibility(View.VISIBLE);
        }
    }

    // ---- Addresses ----------------------------------------------------------------------

    /**
     * The Livepush player link in a standard form (https:// added when missing), "" for an empty box,
     * or null when the text is not a Livepush player link.
     */
    static String livepushUrl(String input) {
        String text = input == null ? "" : input.trim();
        if (text.isEmpty()) {
            return "";
        }
        if (!text.contains("://")) {
            text = "https://" + text;
        }
        Uri uri = Uri.parse(text);
        String path = uri.getPath() == null ? "" : uri.getPath().replace("/", "");
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !LIVEPUSH_HOST.equalsIgnoreCase(uri.getHost()) || path.isEmpty()) {
            return null;
        }
        return uri.toString();
    }
}

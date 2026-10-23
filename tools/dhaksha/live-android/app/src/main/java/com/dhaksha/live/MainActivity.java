package com.dhaksha.live;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Dhaksha Live on the phone: the same page as the Windows / Linux app and the browser version
 * (tools/dhaksha/live-windows/src), shipped inside the APK by bundle_page.sh.
 *
 * 10 boxes from the Dhaksha video server: 1-5 Skydroid with the night stream as a picture-in-picture
 * (tap it to swap day and night), 6-10 ZR10. Tap a box to enlarge it, tap again to shrink it. The camera
 * window (server IP and port per box) is remembered between runs. The Back key acts like Esc on the
 * desktop: it shrinks an enlarged box, then returns to the camera window, then closes the app.
 */
public class MainActivity extends Activity {

    private static final String PAGE = "file:///android_asset/index.html";

    // Returns "exit" on the camera window; otherwise sends the page an Esc key press
    private static final String BACK_SCRIPT =
            "(function(){var g=document.getElementById('grid-page');"
            + "if(!g||g.classList.contains('hidden'))return 'exit';"
            + "document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape'}));return 'ok';})()";

    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#121212"));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);                    // the camera window's settings
        settings.setMediaPlaybackRequiresUserGesture(false);    // boxes start playing by themselves
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            // The page never leaves the app; video comes in through HLS / WebRTC requests, not navigation
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !request.getUrl().toString().startsWith("file:///android_asset/");
            }
        });
        setContentView(web);
        hideSystemBars();

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(PAGE);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript(BACK_SCRIPT, (result) -> {
            if ("\"exit\"".equals(result)) {
                finish();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}

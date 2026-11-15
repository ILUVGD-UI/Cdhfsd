package dev.lindroid.lindroid;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;

/**
 * Main screen: one-tap setup + start/stop of the Arch Linux session,
 * with an embedded noVNC WebView as the built-in desktop viewer.
 */
public class MainActivity extends Activity {

    private TextView status;
    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int retries = 0;
    private boolean desktopReady = false;

    private static final String DESKTOP_URL =
            "http://127.0.0.1:8765/vnc.html?autoconnect=true"
                    + "&host=127.0.0.1&port=6080&resize=scale&password=lindroid";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        webView = findViewById(R.id.webview);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        webView.setWebViewClient(new DesktopClient());

        Button btnSetup = findViewById(R.id.btn_setup);
        Button btnStart = findViewById(R.id.btn_start);
        Button btnStop  = findViewById(R.id.btn_stop);

        btnSetup.setOnClickListener(v -> runSetup());
        btnStart.setOnClickListener(v -> startSession());
        btnStop.setOnClickListener(v -> {
            startService(new Intent(this, SessionService.class)
                    .setAction(SessionService.ACTION_STOP));
            setStatus("Session stopping…");
        });

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }

        setStatus(SessionService.isRunning()
                ? "Session is running. Tap Start to view the desktop."
                : "Tap SETUP on first run, then START.");
    }

    private void runSetup() {
        setStatus("Setting up (downloads ~200 MB, may take a while)…");
        new Thread(() -> {
            try {
                Bootstrap.setup(this, msg -> runOnUiThread(() -> setStatus(msg)));
                runOnUiThread(() -> setStatus("Setup complete. Tap START."));
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("Setup failed: " + e.getMessage()));
            }
        }).start();
    }

    private void startSession() {
        setStatus("Starting Arch Linux session…");
        startService(new Intent(this, SessionService.class)
                .setAction(SessionService.ACTION_START));
        retries = 0;
        desktopReady = false;
        handler.postDelayed(() -> webView.loadUrl(DESKTOP_URL), 1500);
    }

    private void setStatus(String msg) {
        status.setText(msg);
    }

    /** Retries the desktop page until the VNC server inside the container is up. */
    private class DesktopClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false; // keep everything inside our WebView
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            retry();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (url != null && url.contains("vnc.html")) {
                desktopReady = true;
                setStatus(SessionService.isRunning()
                        ? "Desktop ready (password: lindroid). First start installs packages, so it can take 5–20 min."
                        : "Session not running. Tap START.");
            }
        }

        private void retry() {
            if (desktopReady || retries > 90) return; // ~4.5 min of retries
            retries++;
            handler.postDelayed(() -> webView.loadUrl(DESKTOP_URL), 3000);
        }
    }
}

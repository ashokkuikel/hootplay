package app.hootplay;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Full-screen WebView that loads YouTube's own TV interface and injects
 * assets/inject.js before the page's scripts run.
 */
public class MainActivity extends Activity {

    private static final String TAG = "Hootplay";
    private static final String HOME_URL = "https://www.youtube.com/tv";

    // YouTube only serves the TV interface to a TV-like user agent.
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12) Cobalt/22.2.3-gold (unlike Gecko) "
                    + "v8/8.8.278.8-jit gles Starboard/13, "
                    + "SystemIntegratorName_OTT_ChipsetModelNumber_2022/FirmwareVersion "
                    + "(BrandName, ModelName, Wired)";

    // Requests to these hosts or paths are answered with an empty response.
    private static final String[] BLOCKED_HOSTS = {
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.com",
            "imasdk.googleapis.com",
    };
    private static final String[] BLOCKED_PATHS = {
            "/pagead/",
            "/api/stats/ads",
            "/get_midroll_info",
            "/ptracking",
    };

    private WebView web;
    private String injectScript;
    private boolean injectAtPageStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The screen is kept awake only while a video is playing (see
        // Bridge.setPlaying), so the TV's screensaver and sleep timer still
        // work on menus and when playback is paused.

        // Debug builds can be inspected from chrome://inspect on a computer.
        boolean debuggable = (getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        WebView.setWebContentsDebuggingEnabled(debuggable);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        setContentView(web);
        hideSystemBars();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(USER_AGENT);
        // One CSS pixel per screen pixel, so the page sees the TV's real
        // resolution (1920x1080 or 3840x2160) and offers matching video quality.
        s.setUseWideViewPort(false);
        web.setInitialScale(100);
        s.setSupportZoom(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "HootplayHost");

        injectScript = "window.__hootplayCaps=" + deviceCaps() + ";\n" + readAsset("inject.js");
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Set<String> origins = new HashSet<>(Arrays.asList(
                    "https://youtube.com", "https://*.youtube.com"));
            WebViewCompat.addDocumentStartJavaScript(web, injectScript, origins);
        } else {
            // Older WebView: best effort, runs slightly later than document start.
            injectAtPageStart = true;
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (isBlocked(request)) {
                    return new WebResourceResponse("text/plain", "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (injectAtPageStart && url != null && url.contains("youtube.com")) {
                    view.evaluateJavascript(injectScript, null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false; // keep every navigation inside the app
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                // Without this the whole app is killed when the page's process dies.
                Log.e(TAG, "Page process gone (crashed=" + detail.didCrash() + "), restarting");
                recreate();
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onCloseWindow(WebView window) {
                finish();
            }

            @Override
            public Bitmap getDefaultVideoPoster() {
                // Replaces the WebView's large grey play icon shown while a video loads.
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }

            @Override
            public void onPermissionRequest(android.webkit.PermissionRequest request) {
                // DRM-protected videos (much label music, movies) need Widevine,
                // which the WebView only enables when this permission is granted.
                String host = request.getOrigin().getHost();
                boolean youtube = host != null
                        && (host.equals("youtube.com") || host.endsWith(".youtube.com"));
                if (youtube) {
                    for (String resource : request.getResources()) {
                        if (android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(resource)) {
                            request.grant(new String[]{resource});
                            return;
                        }
                    }
                }
                request.deny();
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.d(TAG, m.message());
                return true;
            }
        });

        if (savedInstanceState == null) {
            web.loadUrl(HOME_URL);
        } else {
            web.restoreState(savedInstanceState);
        }
        web.requestFocus();
    }

    /**
     * What this TV can actually show and decode, as JSON for inject.js: the
     * panel's largest display mode, and per codec the largest frame size and
     * frame rate its decoders accept.
     */
    private String deviceCaps() {
        int width = 0, height = 0;
        float fps = 0;
        try {
            for (android.view.Display.Mode mode : getWindowManager().getDefaultDisplay().getSupportedModes()) {
                int w = Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight());
                int h = Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight());
                if (w * h > width * height) { width = w; height = h; }
                fps = Math.max(fps, mode.getRefreshRate());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read display modes", e);
        }
        if (width == 0) { width = 1920; height = 1080; }
        if (fps < 24) fps = 60;

        StringBuilder codecs = new StringBuilder();
        String[][] types = {{"vp9", "video/x-vnd.on2.vp9"}, {"av1", "video/av01"},
                {"avc", "video/avc"}, {"hevc", "video/hevc"}};
        try {
            android.media.MediaCodecInfo[] infos = new android.media.MediaCodecList(
                    android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos();
            for (String[] type : types) {
                int cw = 0, ch = 0, cf = 0;
                for (android.media.MediaCodecInfo info : infos) {
                    if (info.isEncoder()) continue;
                    for (String supported : info.getSupportedTypes()) {
                        if (!supported.equalsIgnoreCase(type[1])) continue;
                        android.media.MediaCodecInfo.VideoCapabilities v =
                                info.getCapabilitiesForType(supported).getVideoCapabilities();
                        if (v == null) continue;
                        int w = v.getSupportedWidths().getUpper();
                        int h = v.getSupportedHeights().getUpper();
                        if (w * h > cw * ch) { cw = Math.max(w, h); ch = Math.min(w, h); }
                        cf = Math.max(cf, v.getSupportedFrameRates().getUpper());
                    }
                }
                if (cw > 0) {
                    if (codecs.length() > 0) codecs.append(',');
                    codecs.append('"').append(type[0]).append("\":{\"width\":").append(cw)
                            .append(",\"height\":").append(ch).append(",\"fps\":").append(cf).append('}');
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not read decoder limits", e);
        }
        String json = "{\"width\":" + width + ",\"height\":" + height + ",\"fps\":" + Math.round(fps)
                + ",\"codecs\":{" + codecs + "}}";
        Log.i(TAG, "Device limits " + json);
        return json;
    }

    private static boolean isBlocked(WebResourceRequest request) {
        String host = request.getUrl().getHost();
        String path = request.getUrl().getPath();
        if (host != null) {
            for (String h : BLOCKED_HOSTS) {
                if (host.equals(h) || host.endsWith("." + h)) return true;
            }
        }
        if (path != null) {
            for (String p : BLOCKED_PATHS) {
                if (path.startsWith(p)) return true;
            }
        }
        return false;
    }

    /** Maps remote buttons the page would not otherwise receive to the key codes YouTube's TV app listens for. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int jsKey = jsKeyFor(event.getKeyCode());
        if (jsKey == 0) return super.dispatchKeyEvent(event);

        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && !onTvPage()) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (web.canGoBack()) web.goBack(); else finish();
            }
            return true;
        }

        String type = event.getAction() == KeyEvent.ACTION_DOWN ? "keydown" : "keyup";
        web.evaluateJavascript(
                "window.__hootplayKey && window.__hootplayKey('" + type + "'," + jsKey + ");", null);
        return true;
    }

    private static int jsKeyFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK: return 27;               // Escape
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return 179;
            case KeyEvent.KEYCODE_MEDIA_PLAY: return 250;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: return 19;
            case KeyEvent.KEYCODE_MEDIA_STOP: return 178;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return 228;
            case KeyEvent.KEYCODE_MEDIA_REWIND: return 227;
            case KeyEvent.KEYCODE_MEDIA_NEXT: return 176;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return 177;
            default: return 0;
        }
    }

    private boolean onTvPage() {
        String url = web.getUrl();
        return url != null && url.contains("youtube.com/tv");
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.e(TAG, "Could not read asset " + name, e);
            return "";
        }
    }

    private class Bridge {
        @JavascriptInterface
        public void exit() {
            runOnUiThread(MainActivity.this::finish);
        }

        @JavascriptInterface
        public void setPlaying(boolean playing) {
            runOnUiThread(() -> {
                if (playing) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
                Log.i(TAG, "Keep screen on: " + playing);
            });
        }
    }
}

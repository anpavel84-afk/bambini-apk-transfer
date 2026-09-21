package ru.anpavel.bambiniliving;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BambiniDreamService extends DreamService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private WebView web;

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, false);

        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                // Screensaver is intentionally silent. The normal app keeps audio.
                String js = "(function(){const m=()=>document.querySelectorAll('video').forEach(v=>{v.muted=true;v.volume=0});" +
                        "m();new MutationObserver(m).observe(document.documentElement,{subtree:true,childList:true});})();";
                view.evaluateJavascript(js, null);
            }
        });
        setContentView(web);

        io.execute(() -> {
            try {
                String cookie = BambiniRemote.enroll();
                main.post(() -> {
                    if (web == null) return;
                    CookieManager mgr = CookieManager.getInstance();
                    mgr.setCookie(BambiniRemote.BASE, cookie + "; Path=/; Secure; SameSite=Strict");
                    mgr.flush();
                    web.loadUrl(BambiniRemote.BASE + "/living");
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (web != null) {
                        web.loadDataWithBaseURL(null,
                                "<html><body style='margin:0;background:#050505;color:#f6f0e6;display:flex;align-items:center;justify-content:center;height:100vh;font:120px serif'>B</body></html>",
                                "text/html", "UTF-8", null);
                    }
                });
            }
        });
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        if (web != null) web.onResume();
    }

    @Override public void onDreamingStopped() {
        if (web != null) web.onPause();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        io.shutdownNow();
        super.onDetachedFromWindow();
    }
}

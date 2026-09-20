package ru.anpavel.bambiniliving;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String APP_ORIGIN = "https://appassets.androidplatform.net";
    private static final String TEST_ORIGIN = "http://169.58.183.182:18080";
    private static final String[] WARM_IDS = {"18632", "18714"};
    private static final long TRANSIENT_TTL_MS = 10L * 60L * 1000L;
    private static final long TRANSIENT_MAX_BYTES = 250L * 1024L * 1024L;

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private File warmRoot;
    private File transientRoot;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        warmRoot = new File(getFilesDir(), "bambini-warm");
        transientRoot = new File(getCacheDir(), "bambini-stream");
        warmRoot.mkdirs();
        transientRoot.mkdirs();
        io.execute(this::cleanupTransient);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        web.addJavascriptInterface(new WarmBridge(), "Warm");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new LocalClient());
        setContentView(web);
        immersive();
        web.loadUrl(APP_ORIGIN + "/assets/player.html");
        io.execute(this::fillWarmPool);
    }

    private final class WarmBridge {
        @JavascriptInterface public String readyIds() {
            JSONArray a = new JSONArray();
            for (String id : WARM_IDS) if (isWarmReady(id)) a.put(id);
            return a.toString();
        }
    }

    private final class LocalClient extends WebViewClient {
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return intercept(request.getUrl());
        }
        @SuppressWarnings("deprecation")
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return intercept(Uri.parse(url));
        }
    }

    private WebResourceResponse intercept(Uri uri) {
        if (!"appassets.androidplatform.net".equals(uri.getHost())) return null;
        String path = uri.getPath();
        if (path == null || path.contains("..")) return null;
        try {
            if (path.startsWith("/assets/")) {
                String name = path.substring("/assets/".length());
                return response(name, getAssets().open(name));
            }
            if (path.startsWith("/warm/")) {
                String rel = path.substring("/warm/".length());
                File f = safeFile(warmRoot, rel);
                if (f != null && f.isFile()) return response(f.getName(), new FileInputStream(f));
                return notFound();
            }
            if (path.startsWith("/stream/")) {
                String rel = path.substring("/stream/".length());
                File f = transientFile(rel);
                if (f != null) return response(f.getName(), new FileInputStream(f));
                return notFound();
            }
        } catch (Exception ignored) {}
        return notFound();
    }

    private WebResourceResponse response(String name, InputStream in) {
        return new WebResourceResponse(mime(name), null, in);
    }
    private WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }
    private static String mime(String name) {
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".js")) return "text/javascript";
        if (name.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (name.endsWith(".ts")) return "video/mp2t";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private void fillWarmPool() {
        for (String id : WARM_IDS) {
            if (isWarmReady(id)) continue;
            try {
                File tmp = new File(warmRoot, "." + id + ".tmp");
                deleteTree(tmp);
                tmp.mkdirs();
                String playlist = fetchText(TEST_ORIGIN + "/hlsav/" + id + "/index.m3u8");
                writeBytes(new File(tmp, "index.m3u8"), playlist.getBytes(StandardCharsets.UTF_8));
                try { writeBytes(new File(tmp, "poster.jpg"), fetchBytes(TEST_ORIGIN + "/hlsav/" + id + "/poster.jpg")); }
                catch (Exception ignored) {}
                for (String line : playlist.split("\\r?\\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.contains("/") || line.contains("..")) continue;
                    writeBytes(new File(tmp, line), fetchBytes(TEST_ORIGIN + "/hlsav/" + id + "/" + line));
                }
                writeBytes(new File(tmp, ".complete"), Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
                File dest = new File(warmRoot, id);
                deleteTree(dest);
                if (!tmp.renameTo(dest)) throw new Exception("rename failed");
                main.post(() -> web.evaluateJavascript("window.onWarmUpdate&&window.onWarmUpdate()", null));
            } catch (Exception ignored) {}
        }
    }

    private boolean isWarmReady(String id) {
        File d = new File(warmRoot, id);
        return new File(d, ".complete").isFile() && new File(d, "index.m3u8").isFile();
    }

    private File transientFile(String rel) {
        try {
            String[] p = rel.split("/", 2);
            if (p.length != 2 || !p[0].matches("\\d+") || p[1].contains("..")) return null;
            File f = safeFile(transientRoot, rel);
            long now = System.currentTimeMillis();
            if (f != null && f.isFile() && now - f.lastModified() <= TRANSIENT_TTL_MS) {
                f.setLastModified(now);
                return f;
            }
            if (f == null) return null;
            byte[] data = fetchBytes(TEST_ORIGIN + "/hlsav/" + p[0] + "/" + p[1]);
            writeBytes(f, data);
            f.setLastModified(now);
            cleanupTransient();
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private void cleanupTransient() {
        long cutoff = System.currentTimeMillis() - TRANSIENT_TTL_MS;
        List<File> files = new ArrayList<>();
        collectFiles(transientRoot, files);
        long total = 0;
        for (File f : files) {
            if (f.lastModified() < cutoff) f.delete();
            else total += f.length();
        }
        if (total <= TRANSIENT_MAX_BYTES) return;
        files.clear();
        collectFiles(transientRoot, files);
        files.sort(Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (total <= TRANSIENT_MAX_BYTES) break;
            long n = f.length();
            if (f.delete()) total -= n;
        }
    }

    private static void collectFiles(File dir, List<File> out) {
        File[] a = dir.listFiles();
        if (a == null) return;
        for (File f : a) {
            if (f.isDirectory()) collectFiles(f, out);
            else out.add(f);
        }
    }

    private static File safeFile(File root, String rel) throws Exception {
        File f = new File(root, rel);
        String rp = root.getCanonicalPath() + File.separator;
        String fp = f.getCanonicalPath();
        return fp.startsWith(rp) ? f : null;
    }

    private static String fetchText(String url) throws Exception {
        return new String(fetchBytes(url), StandardCharsets.UTF_8);
    }

    private static byte[] fetchBytes(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(45000);
        c.setRequestProperty("User-Agent", "BambiniLiving/5.4");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) { c.disconnect(); throw new Exception("HTTP " + code); }
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[128 * 1024];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toByteArray();
        } finally { c.disconnect(); }
    }

    private static void writeBytes(File file, byte[] data) throws Exception {
        File p = file.getParentFile();
        if (p != null) p.mkdirs();
        File tmp = new File(file.getAbsolutePath() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(data); }
        if (file.exists()) file.delete();
        if (!tmp.renameTo(file)) throw new Exception("write rename failed");
    }

    private static void deleteTree(File f) {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            File[] a = f.listFiles();
            if (a != null) for (File x : a) deleteTree(x);
        }
        f.delete();
    }

    private void immersive() {
        web.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) immersive(); }
    @Override public void onBackPressed() { web.reload(); }
    @Override protected void onDestroy() {
        io.shutdownNow();
        if (web != null) { web.loadUrl("about:blank"); web.destroy(); }
        super.onDestroy();
    }
}

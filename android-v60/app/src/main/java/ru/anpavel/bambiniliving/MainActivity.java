package ru.anpavel.bambiniliving;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ConsoleMessage;
import android.webkit.WebResourceError;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String TAG = "BambiniQA";
    private static final String BUILD = "6.7-tv-exit-lifecycle";
    private static final String BASE = "https://bambini.anpavel.ru";
    private static final String APP_KEY = "_SEs08BNhi4G1ZRKuYI_" + "mimSSeEtOL8WiG1g0qe_" + "5qgoLJVxTEb7Z2_geKZl-Vxn";
    private static final String APP_ORIGIN = "https://appassets.androidplatform.net";
    private static final int WARM_TARGET = 10;
    private static final long TRANSIENT_TTL_MS = 10L * 60L * 1000L;
    private static final long TRANSIENT_MAX_BYTES = 300L * 1024L * 1024L;
    private static final long BACK_DOUBLE_MS = 2200L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService control = Executors.newSingleThreadExecutor();
    private final ExecutorService prefetch = Executors.newFixedThreadPool(3);

    private WebView web;
    private File warmRoot;
    private File transientRoot;
    private Api api;
    private long lastBackAt = 0L;
    private AlertDialog exitDialog;
    private boolean hostPaused = false;
    private boolean exiting = false;

    private volatile String warmStage = "START";
    private volatile String warmId = "";
    private volatile int warmFileDone = 0;
    private volatile int warmFileTotal = 1;
    private volatile String warmError = "";
    private final Set<String> failedVideos = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> prefetchQueued = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> leasedVideos = Collections.synchronizedSet(new HashSet<>());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        warmRoot = new File(getFilesDir(), "bambini-warm-v2");
        transientRoot = new File(getCacheDir(), "bambini-stream-v2");
        warmRoot.mkdirs();
        transientRoot.mkdirs();
        api = new Api();
        logEvent("APP_CREATE", "build=" + BUILD + " warm=" + warmRoot + " transient=" + transientRoot);

        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(16,17,15));
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        web.addJavascriptInterface(new AppBridge(), "App");
        WebView.setWebContentsDebuggingEnabled(true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage cm) {
                logEvent("WEB_CONSOLE", cm.messageLevel() + " " + cm.sourceId() + ":" + cm.lineNumber() + " " + cm.message());
                return true;
            }
        });
        web.setWebViewClient(new LocalClient());
        setContentView(web);
        immersive();
        logEvent("WEB_LOAD", APP_ORIGIN + "/assets/player.html");
        web.loadUrl(APP_ORIGIN + "/assets/player.html");

        control.execute(() -> {
            try {
                logEvent("CONTROL_START", "enroll");
                api.enroll();
                logEvent("CONTROL_ENROLLED", "ok");
                cleanupTransient();
                List<String> ready = readyWarmIds();
                if (ready.isEmpty()) {
                    setWarmProgress("NO_WARM", "", 0, 1, "");
                    logEvent("WARM_BOOTSTRAP", "no local warm media; start queue immediately and promote played videos");
                } else {
                    setWarmProgress("POOL_ACTIVE", ready.get(0), ready.size(), WARM_TARGET, "");
                    logEvent("WARM_BOOTSTRAP", "local_ready=" + ready.size());
                }
            } catch (Exception e) {
                logEvent("CONTROL_ERROR", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
                setWarmProgress("ERROR", "", 0, 1, e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
            }
        });
    }

    private final class AppBridge {
        @JavascriptInterface public void diag(String event, String detail) {
            logEvent("JS_" + safeLog(event), safeLog(detail));
        }

        @JavascriptInterface public String warmStatus() {
            JSONObject o = new JSONObject();
            try {
                JSONArray ready = new JSONArray();
                for (String id : readyWarmIds()) ready.put(id);
                int pct = warmFileTotal > 0 ? Math.min(100, warmFileDone * 100 / warmFileTotal) : 0;
                o.put("stage", warmStage).put("id", warmId).put("percent", pct).put("ready", ready).put("error", warmError);
            } catch (Exception ignored) {}
            return o.toString();
        }

        @JavascriptInterface public String warmIds() {
            JSONArray a = new JSONArray();
            for (String id : readyWarmIds()) a.put(id);
            return a.toString();
        }

        @JavascriptInterface public boolean isWarm(String id) { return isWarmReady(id); }

        @JavascriptInterface public String queue() {
            try {
                logEvent("QUEUE_REQUEST", "start");
                JSONObject q = api.queue();
                JSONArray scenes = q.optJSONArray("scenes");
                logEvent("QUEUE_OK", "scenes=" + (scenes == null ? 0 : scenes.length()));
                return q.toString();
            } catch (Exception e) {
                logEvent("QUEUE_ERROR", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
                return "{\"scenes\":[]}";
            }
        }

        @JavascriptInterface public void played(String jsonIds) {
            control.execute(() -> {
                try {
                    JSONArray a = new JSONArray(jsonIds);
                    api.played(a);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface public void prefetchVideo(String id) {
            if (id == null || !id.matches("\\d+")) return;
            if (isWarmReady(id) || isTransientReady(id) || failedVideos.contains(id)) return;
            if (!prefetchQueued.add(id)) return;
            logEvent("PREFETCH_QUEUE", "id=" + id);
            prefetch.execute(() -> {
                try {
                    if (!isWarmReady(id) && !isTransientReady(id)) downloadHlsTo(transientRoot, id, true);
                    failedVideos.remove(id);
                    logEvent("PREFETCH_OK", "id=" + id);
                } catch (Exception e) {
                    failedVideos.add(id);
                    logEvent("PREFETCH_ERROR", "id=" + id + " " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
                } finally {
                    prefetchQueued.remove(id);
                }
            });
        }

        @JavascriptInterface public void promoteWarm(String id) {
            if (id == null || !id.matches("\\d+")) return;
            control.execute(() -> promoteTransientToWarm(id));
        }

        @JavascriptInterface public void leaseVideo(String id) {
            if (id != null && id.matches("\\d+")) {
                leasedVideos.add(id);
                logEvent("CACHE_LEASE", "id=" + id);
            }
        }

        @JavascriptInterface public void releaseVideo(String id) {
            if (id != null) {
                leasedVideos.remove(id);
                logEvent("CACHE_RELEASE", "id=" + id);
            }
        }

        @JavascriptInterface public boolean isVideoReady(String id) {
            return isWarmReady(id) || isTransientReady(id);
        }

        @JavascriptInterface public String videoState(String id) {
            if (isWarmReady(id) || isTransientReady(id)) return "READY";
            if (failedVideos.contains(id)) return "FAILED";
            return "LOADING";
        }
    }

    private List<String> loadOrChooseWarmIds() throws Exception {
        List<String> existing = getWarmIds();
        if (existing.size() >= WARM_TARGET) return existing;

        JSONObject q = api.queue();
        JSONArray scenes = q.optJSONArray("scenes");
        Set<String> ids = new LinkedHashSet<>(existing);
        if (scenes != null) {
            for (int i=0; i<scenes.length() && ids.size()<WARM_TARGET; i++) {
                JSONArray items = scenes.getJSONObject(i).optJSONArray("items");
                if (items == null) continue;
                for (int j=0; j<items.length() && ids.size()<WARM_TARGET; j++) {
                    JSONObject item = items.getJSONObject(j);
                    if ("video".equals(item.optString("type"))) ids.add(Long.toString(item.optLong("id")));
                }
            }
        }

        // Queue can be shorter than 10 unique videos; request more smart-random batches.
        int attempts = 0;
        while (ids.size() < WARM_TARGET && attempts++ < 8) {
            JSONObject more = api.queue();
            JSONArray ss = more.optJSONArray("scenes");
            if (ss == null) continue;
            for (int i=0; i<ss.length() && ids.size()<WARM_TARGET; i++) {
                JSONArray items = ss.getJSONObject(i).optJSONArray("items");
                if (items == null) continue;
                for (int j=0; j<items.length() && ids.size()<WARM_TARGET; j++) {
                    JSONObject item = items.getJSONObject(j);
                    if ("video".equals(item.optString("type"))) ids.add(Long.toString(item.optLong("id")));
                }
            }
        }

        List<String> result = new ArrayList<>(ids);
        if (result.size() > WARM_TARGET) result = result.subList(0, WARM_TARGET);
        getPreferences(MODE_PRIVATE).edit().putString("warm_ids", new JSONArray(result).toString()).apply();
        return result;
    }

    private List<String> getWarmIds() {
        List<String> out = new ArrayList<>();
        String raw = getPreferences(MODE_PRIVATE).getString("warm_ids", "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i=0;i<a.length();i++) out.add(a.getString(i));
        } catch (Exception ignored) {}
        return out;
    }

    private void fillWarmPool(List<String> ids) {
        List<String> ready = readyWarmIds();
        if (!ready.isEmpty()) {
            setWarmProgress("POOL_ACTIVE", ready.get(0), ready.size(), WARM_TARGET, "");
            return;
        }
        int pos = 0;
        for (String id : ids) {
            pos++;
            try {
                setWarmProgress("DOWNLOADING", id, 0, 1, "");
                downloadHlsTo(warmRoot, id, true);
                setWarmProgress("READY", id, 1, WARM_TARGET, "");
                logEvent("WARM_STARTER_READY", "id=" + id + " background_fill=played_promotions");
                return;
            } catch (Exception e) {
                setWarmProgress("ERROR", id, pos, ids.size(), e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
            }
        }
        setWarmProgress("ERROR", "", 0, 1, "no warm starter could be prepared");
    }

    private void setWarmProgress(String stage, String id, int done, int total, String error) {
        warmStage = stage;
        warmId = id == null ? "" : id;
        warmFileDone = done;
        warmFileTotal = Math.max(1,total);
        warmError = error == null ? "" : error;
        logEvent("WARM_" + safeLog(stage), "id=" + warmId + " " + done + "/" + warmFileTotal + (warmError.isEmpty() ? "" : " error=" + warmError));
    }

    private void downloadHlsTo(File root, String id, boolean withPoster) throws Exception {
        File dest = new File(root, id);
        if (new File(dest, ".complete").isFile() && new File(dest, "index.m3u8").isFile()) {
            logEvent("HLS_LOCAL_HIT", "id=" + id + " pool=" + (root == warmRoot ? "warm" : "transient"));
            return;
        }

        File tmp = new File(root, "." + id + ".tmp");
        deleteTree(tmp);
        tmp.mkdirs();

        logEvent("HLS_PREPARE", "id=" + id);
        JSONObject desc = api.waitHls(id);
        String baseUrl = desc.getString("base_url");
        JSONArray files = desc.getJSONArray("files");
        int written = 0;
        for (int n=0; n<files.length(); n++) {
            JSONObject fdesc = files.getJSONObject(n);
            String name = fdesc.getString("name");
            if (!withPoster && "poster.jpg".equals(name)) continue;
            if (!name.matches("(?:index\\.m3u8|poster\\.jpg|seg_\\d{5}\\.ts)")) throw new Exception("unsafe hls file " + name);
            byte[] data = api.getBytes(baseUrl + name);
            long expected = fdesc.optLong("bytes", -1);
            String expectedSha = fdesc.optString("sha256", "");
            if (expected >= 0 && data.length != expected) throw new Exception("size mismatch " + name);
            if (!expectedSha.isEmpty() && !expectedSha.equals(sha256(data))) throw new Exception("sha mismatch " + name);
            writeBytes(new File(tmp, name), data);
            written++;
            if (root == warmRoot) setWarmProgress("DOWNLOADING", id, written, Math.max(1, files.length()), "");
        }
        if (!new File(tmp, "index.m3u8").isFile()) throw new Exception("missing playlist");
        writeBytes(new File(tmp, ".complete"), Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        deleteTree(dest);
        if (!tmp.renameTo(dest)) throw new Exception("rename failed");
        if (root == transientRoot) {
            touchTree(dest);
            cleanupTransient();
        }
        failedVideos.remove(id);
        logEvent("HLS_LOCAL_READY", "id=" + id + " pool=" + (root == warmRoot ? "warm" : "transient") + " bytes=" + treeSize(dest));
    }

    private List<String> readyWarmIds() {
        List<String> out = new ArrayList<>();
        File[] dirs = warmRoot.listFiles();
        if (dirs != null) {
            List<File> candidates = new ArrayList<>();
            for (File d : dirs) {
                if (d.isDirectory() && d.getName().matches("\\d+") &&
                        new File(d, ".complete").isFile() &&
                        new File(d, "index.m3u8").isFile() &&
                        new File(d, "poster.jpg").isFile()) candidates.add(d);
            }
            candidates.sort((a,b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File d : candidates) {
                out.add(d.getName());
                if (out.size() >= WARM_TARGET) break;
            }
        }
        return out;
    }

    private void promoteTransientToWarm(String id) {
        try {
            if (isWarmReady(id)) return;
            List<String> ready = readyWarmIds();
            if (ready.size() >= WARM_TARGET) return;
            if (!isTransientReady(id)) return;
            File src = new File(transientRoot, id);
            File poster = new File(src, "poster.jpg");
            if (!poster.isFile()) writeBytes(poster, api.getBytes("/poster/" + id));
            File dst = new File(warmRoot, id);
            deleteTree(dst);
            if (!src.renameTo(dst)) {
                logEvent("WARM_PROMOTE_ERROR", "id=" + id + " rename_failed");
                return;
            }
            dst.setLastModified(System.currentTimeMillis());
            LinkedHashSet<String> prefs = new LinkedHashSet<>(ready);
            prefs.add(id);
            List<String> saved = new ArrayList<>(prefs);
            if (saved.size() > WARM_TARGET) saved = saved.subList(0, WARM_TARGET);
            getPreferences(MODE_PRIVATE).edit().putString("warm_ids", new JSONArray(saved).toString()).apply();
            logEvent("WARM_PROMOTED", "id=" + id + " ready=" + readyWarmIds().size() + "/" + WARM_TARGET);
        } catch (Exception e) {
            logEvent("WARM_PROMOTE_ERROR", "id=" + id + " " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
        }
    }

    private boolean isWarmReady(String id) {
        File d = new File(warmRoot, id);
        return new File(d, ".complete").isFile() && new File(d, "index.m3u8").isFile();
    }

    private boolean isTransientReady(String id) {
        File d = new File(transientRoot, id);
        File c = new File(d, ".complete");
        if (!c.isFile() || !new File(d,"index.m3u8").isFile()) return false;
        if (System.currentTimeMillis() - c.lastModified() > TRANSIENT_TTL_MS) {
            deleteTree(d);
            return false;
        }
        touchTree(d);
        return true;
    }

    private final class LocalClient extends WebViewClient {
        @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            logEvent("WEB_PAGE_STARTED", url);
        }
        @Override public void onPageFinished(WebView view, String url) {
            logEvent("WEB_PAGE_FINISHED", url);
        }
        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            logEvent("WEB_ERROR", request.getUrl() + " code=" + error.getErrorCode() + " " + error.getDescription());
            super.onReceivedError(view, request, error);
        }
        @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            logEvent("WEB_HTTP_ERROR", request.getUrl() + " status=" + response.getStatusCode());
            super.onReceivedHttpError(view, request, response);
        }
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
        if (path == null || path.contains("..")) return notFound();
        try {
            if (path.startsWith("/assets/")) {
                String name = path.substring("/assets/".length());
                return response(name, getAssets().open(name));
            }
            if (path.startsWith("/warm/")) {
                File f = safeFile(warmRoot, path.substring(6));
                return f != null && f.isFile() ? response(f.getName(), new FileInputStream(f)) : notFound();
            }
            if (path.startsWith("/stream/")) {
                String rel = path.substring(8);
                String[] p = rel.split("/",2);
                if (p.length != 2 || !p[0].matches("\\d+")) return notFound();
                String id = p[0];
                File root = isWarmReady(id) ? warmRoot : transientRoot;
                File f = safeFile(root, rel);
                if (f != null && f.isFile()) return response(f.getName(), new FileInputStream(f));
                logEvent("LOCAL_STREAM_MISS", "id=" + id + " file=" + p[1]);
                return notFound();
            }
            if (path.startsWith("/media/")) {
                String upstream = path.replaceFirst("^/media", "");
                byte[] data = api.getBytes(upstream);
                logEvent("MEDIA_OK", upstream + " bytes=" + data.length);
                return new WebResourceResponse(
                        path.contains("/poster/") || path.contains("/thumb/") ? "image/jpeg" : "application/octet-stream",
                        null, new ByteArrayInputStream(data));
            }
        } catch (Exception e) {
            logEvent("INTERCEPT_ERROR", path + " " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
        }
        return notFound();
    }

    private WebResourceResponse response(String name, InputStream in) {
        return new WebResourceResponse(mime(name), null, in);
    }

    private WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                java.util.Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String n) {
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js")) return "text/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (n.endsWith(".ts")) return "video/mp2t";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private void cleanupTransient() {
        long cutoff = System.currentTimeMillis() - TRANSIENT_TTL_MS;
        File[] dirs = transientRoot.listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (d.isDirectory() && !leasedVideos.contains(d.getName()) && newestMtime(d) < cutoff) deleteTree(d);
            }
        }

        long total = treeSize(transientRoot);
        if (total <= TRANSIENT_MAX_BYTES) return;

        List<File> keep = new ArrayList<>();
        dirs = transientRoot.listFiles();
        if (dirs != null) for (File d : dirs) if (d.isDirectory() && !leasedVideos.contains(d.getName())) keep.add(d);
        keep.sort(Comparator.comparingLong(MainActivity::newestMtime));

        for (File d : keep) {
            if (total <= TRANSIENT_MAX_BYTES) break;
            long n = treeSize(d);
            deleteTree(d);
            total -= n;
        }
    }

    private final class Api {
        private volatile String cookie;

        synchronized void enroll() throws Exception {
            HttpURLConnection c = open(BASE + "/living/app", "GET");
            c.setRequestProperty("X-Bambini-App-Key", APP_KEY);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            String set = c.getHeaderField("Set-Cookie");
            if (set != null) cookie = set.split(";",2)[0];
            drain(c);
            c.disconnect();
            if ((code != 200 && code != 302 && code != 303) || cookie == null) throw new Exception("enroll " + code);
            logEvent("API_ENROLL", "status=" + code + " cookie=" + (cookie != null));
        }

        JSONObject queue() throws Exception {
            return post("/living/api/queue", new JSONObject());
        }

        JSONObject waitHls(String id) throws Exception {
            long deadline = System.currentTimeMillis() + 95000L;
            while (System.currentTimeMillis() < deadline) {
                JSONObject d = post("/living/api/hls/" + id, new JSONObject());
                String state = d.optString("state", "");
                logEvent("HLS_STATE", "id=" + id + " state=" + state + " code=" + d.optString("code", ""));
                if ("ready".equals(state)) return d;
                if ("failed".equals(state)) throw new Exception("hls failed " + d.optString("code", "unknown"));
                long wait = Math.max(500L, Math.min(4000L, d.optLong("retry_after", 1L) * 1000L));
                Thread.sleep(wait);
            }
            throw new Exception("hls prepare timeout");
        }

        byte[] getBytes(String path) throws Exception {
            if (path == null || !path.startsWith("/")) throw new Exception("unsafe path");
            if (cookie == null) enroll();
            HttpURLConnection c = open(BASE + path, "GET");
            c.setRequestProperty("Cookie", cookie);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code == 302 || code == 303 || code == 401) {
                drain(c); c.disconnect(); cookie = null; enroll(); return getBytes(path);
            }
            if (code < 200 || code >= 300) {
                drain(c); c.disconnect(); throw new Exception("HTTP " + code + " " + path);
            }
            try (InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] b=new byte[128*1024]; int n;
                while((n=in.read(b))!=-1) out.write(b,0,n);
                return out.toByteArray();
            } finally { c.disconnect(); }
        }

        void played(JSONArray ids) throws Exception {
            post("/living/api/played", new JSONObject().put("media_ids", ids));
        }

        synchronized JSONObject post(String path, JSONObject body) throws Exception {
            if (cookie == null) enroll();
            HttpURLConnection c = open(BASE + path, "POST");
            c.setRequestProperty("Cookie", cookie);
            c.setRequestProperty("X-Living-Frame", "1");
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(out); }
            int code = c.getResponseCode();
            if (code == 302 || code == 303 || code == 401) {
                c.disconnect();
                cookie = null;
                enroll();
                return post(path, body);
            }
            if (code == 204) { logEvent("API_POST", path + " status=204"); c.disconnect(); return new JSONObject(); }
            if (code < 200 || code >= 300) {
                drain(c); c.disconnect(); throw new Exception("HTTP " + code);
            }
            String text = readText(c.getInputStream());
            c.disconnect();
            logEvent("API_POST", path + " status=" + code + " bytes=" + text.length());
            return new JSONObject(text);
        }
    }

    private static HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(45000);
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "BambiniLiving/6.6");
        return c;
    }

    private static String readText(InputStream in) throws Exception {
        try (InputStream x=in; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[8192]; int n;
            while((n=x.read(b))!=-1) out.write(b,0,n);
            return out.toString("UTF-8");
        }
    }

    private static void drain(HttpURLConnection c) {
        try {
            InputStream in=c.getErrorStream();
            if(in==null) in=c.getInputStream();
            if(in!=null){byte[]b=new byte[4096];while(in.read(b)!=-1){}in.close();}
        } catch(Exception ignored){}
    }

    private static String sha256(byte[] data) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02x", x));
        return b.toString();
    }

    private static String safeLog(String s) {
        if (s == null) return "";
        s = s.replace('\n',' ').replace('\r',' ');
        return s.length() > 800 ? s.substring(0,800) : s;
    }

    private static void logEvent(String event, String detail) {
        Log.i(TAG, safeLog(event) + " | " + safeLog(detail));
    }

    private static void writeBytes(File file, byte[] data) throws Exception {
        File p=file.getParentFile(); if(p!=null)p.mkdirs();
        File tmp=new File(file.getAbsolutePath()+".part");
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(data);}
        if(file.exists())file.delete();
        if(!tmp.renameTo(file))throw new Exception("rename failed");
    }

    private static File safeFile(File root,String rel)throws Exception{
        File f=new File(root,rel);
        String rp=root.getCanonicalPath()+File.separator;
        String fp=f.getCanonicalPath();
        return fp.startsWith(rp)?f:null;
    }

    private static void touchTree(File f) {
        long now=System.currentTimeMillis();
        f.setLastModified(now);
        File[] a=f.listFiles();
        if(a!=null)for(File x:a)touchTree(x);
    }

    private static long newestMtime(File f) {
        long m=f.lastModified();
        File[] a=f.listFiles();
        if(a!=null)for(File x:a)m=Math.max(m,newestMtime(x));
        return m;
    }

    private static long treeSize(File f){
        if(!f.exists())return 0;
        if(f.isFile())return f.length();
        long n=0;File[]a=f.listFiles();if(a!=null)for(File x:a)n+=treeSize(x);return n;
    }

    private static void deleteTree(File f){
        if(!f.exists())return;
        if(f.isDirectory()){File[]a=f.listFiles();if(a!=null)for(File x:a)deleteTree(x);}
        f.delete();
    }

    private void immersive() {
        web.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void pauseHostPlayback(String reason) {
        if (web == null || hostPaused) return;
        hostPaused = true;
        logEvent("APP_BACKGROUND_PAUSE", reason);
        try {
            web.evaluateJavascript("window.bambiniHostPause&&window.bambiniHostPause()", null);
        } catch (Exception ignored) {}
        web.onPause();
    }

    private void resumeHostPlayback(String reason) {
        if (web == null || !hostPaused || isFinishing() || exiting) return;
        hostPaused = false;
        web.onResume();
        try {
            web.evaluateJavascript("window.bambiniHostResume&&window.bambiniHostResume()", null);
        } catch (Exception ignored) {}
        logEvent("APP_FOREGROUND_RESUME", reason);
        immersive();
    }

    private void handleBackExit() {
        long now = System.currentTimeMillis();
        if (exitDialog != null && exitDialog.isShowing()) return;
        if (now - lastBackAt > BACK_DOUBLE_MS) {
            lastBackAt = now;
            Toast.makeText(this, "Нажмите Назад ещё раз для выхода", Toast.LENGTH_SHORT).show();
            logEvent("BACK_ARMED", "waiting second press");
            return;
        }
        lastBackAt = 0L;
        pauseHostPlayback("exit-confirm");
        exitDialog = new AlertDialog.Builder(this)
                .setTitle("Закрыть Bambini?")
                .setMessage("Слайд-шоу и звук будут остановлены.")
                .setPositiveButton("Да", (dialog, which) -> {
                    logEvent("APP_EXIT_CONFIRMED", "yes");
                    exiting = true;
                    if (web != null) {
                        try { web.evaluateJavascript("window.bambiniHostStop&&window.bambiniHostStop()", null); } catch (Exception ignored) {}
                        web.stopLoading();
                    }
                    finishAndRemoveTask();
                })
                .setNegativeButton("Нет", (dialog, which) -> {
                    logEvent("APP_EXIT_CONFIRMED", "no");
                    resumeHostPlayback("exit-cancel");
                })
                .setOnCancelListener(dialog -> resumeHostPlayback("exit-cancel"))
                .setOnDismissListener(dialog -> {
                    exitDialog = null;
                    if (!isFinishing()) resumeHostPlayback("exit-dismiss");
                })
                .create();
        exitDialog.setOnShowListener(dialog -> exitDialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus());
        exitDialog.show();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) handleBackExit();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed(){ handleBackExit(); }

    @Override protected void onPause() {
        pauseHostPlayback("activity-pause");
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        main.postDelayed(() -> resumeHostPlayback("activity-resume"), 80);
    }

    @Override protected void onStop() {
        pauseHostPlayback("activity-stop");
        super.onStop();
    }

    @Override public void onWindowFocusChanged(boolean focus){
        super.onWindowFocusChanged(focus);
        if(focus) immersive();
    }

    @Override protected void onDestroy(){
        logEvent("APP_DESTROY", BUILD);
        control.shutdownNow();prefetch.shutdownNow();
        if(exitDialog!=null){try{exitDialog.dismiss();}catch(Exception ignored){}exitDialog=null;}
        if(web!=null){
            try{web.evaluateJavascript("window.bambiniHostStop&&window.bambiniHostStop()",null);}catch(Exception ignored){}
            web.loadUrl("about:blank");web.destroy();
        }
        super.onDestroy();
    }
}

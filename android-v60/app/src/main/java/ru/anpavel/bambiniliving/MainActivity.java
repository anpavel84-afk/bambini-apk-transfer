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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String BASE = "https://bambini.anpavel.ru";
    private static final String GATEWAY = "http://169.58.183.182:18080";
    private static final String APP_KEY = "_SEs08BNhi4G1ZRKuYI_" + "mimSSeEtOL8WiG1g0qe_" + "5qgoLJVxTEb7Z2_geKZl-Vxn";
    private static final String APP_ORIGIN = "https://appassets.androidplatform.net";
    private static final int WARM_TARGET = 10;
    private static final long TRANSIENT_TTL_MS = 10L * 60L * 1000L;
    private static final long TRANSIENT_MAX_BYTES = 300L * 1024L * 1024L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService control = Executors.newSingleThreadExecutor();
    private final ExecutorService prefetch = Executors.newSingleThreadExecutor();

    private WebView web;
    private File warmRoot;
    private File transientRoot;
    private Api api;

    private volatile String warmStage = "START";
    private volatile String warmId = "";
    private volatile int warmFileDone = 0;
    private volatile int warmFileTotal = 1;
    private volatile String warmError = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        warmRoot = new File(getFilesDir(), "bambini-warm-v2");
        transientRoot = new File(getCacheDir(), "bambini-stream-v2");
        warmRoot.mkdirs();
        transientRoot.mkdirs();
        api = new Api();

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
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new LocalClient());
        setContentView(web);
        immersive();
        web.loadUrl(APP_ORIGIN + "/assets/player.html");

        control.execute(() -> {
            try {
                api.enroll();
                cleanupTransient();
                List<String> ids = loadOrChooseWarmIds();
                fillWarmPool(ids);
            } catch (Exception e) {
                setWarmProgress("ERROR", "", 0, 1, e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
            }
        });
    }

    private final class AppBridge {
        @JavascriptInterface public String warmStatus() {
            JSONObject o = new JSONObject();
            try {
                JSONArray ready = new JSONArray();
                for (String id : getWarmIds()) if (isWarmReady(id)) ready.put(id);
                int pct = warmFileTotal > 0 ? Math.min(100, warmFileDone * 100 / warmFileTotal) : 0;
                o.put("stage", warmStage).put("id", warmId).put("percent", pct).put("ready", ready).put("error", warmError);
            } catch (Exception ignored) {}
            return o.toString();
        }

        @JavascriptInterface public String warmIds() {
            JSONArray a = new JSONArray();
            for (String id : getWarmIds()) if (isWarmReady(id)) a.put(id);
            return a.toString();
        }

        @JavascriptInterface public boolean isWarm(String id) { return isWarmReady(id); }

        @JavascriptInterface public String queue() {
            try {
                return api.queue().toString();
            } catch (Exception e) {
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
            if (isWarmReady(id) || isTransientReady(id)) return;
            prefetch.execute(() -> {
                try { downloadHlsTo(transientRoot, id, false); }
                catch (Exception ignored) {}
            });
        }

        @JavascriptInterface public boolean isVideoReady(String id) {
            return isWarmReady(id) || isTransientReady(id);
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
        int pos = 0;
        for (String id : ids) {
            pos++;
            if (isWarmReady(id)) {
                setWarmProgress("READY", id, pos, ids.size(), "");
                continue;
            }
            try {
                setWarmProgress("DOWNLOADING", id, 0, 1, "");
                downloadHlsTo(warmRoot, id, true);
                setWarmProgress("READY", id, pos, ids.size(), "");
            } catch (Exception e) {
                setWarmProgress("ERROR", id, pos, ids.size(), e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
            }
        }
        setWarmProgress("POOL_COMPLETE", "", ids.size(), Math.max(1, ids.size()), warmError);
    }

    private void setWarmProgress(String stage, String id, int done, int total, String error) {
        warmStage = stage;
        warmId = id == null ? "" : id;
        warmFileDone = done;
        warmFileTotal = Math.max(1,total);
        warmError = error == null ? "" : error;
    }

    private void downloadHlsTo(File root, String id, boolean withPoster) throws Exception {
        File dest = new File(root, id);
        if (new File(dest, ".complete").isFile() && new File(dest, "index.m3u8").isFile()) return;

        File tmp = new File(root, "." + id + ".tmp");
        deleteTree(tmp);
        tmp.mkdirs();

        String playlist = fetchText(GATEWAY + "/hlsdyn/" + id + "/index.m3u8");
        writeBytes(new File(tmp, "index.m3u8"), playlist.getBytes(StandardCharsets.UTF_8));

        if (withPoster) {
            try {
                writeBytes(new File(tmp, "poster.jpg"), fetchBytes(GATEWAY + "/media/poster/" + id));
            } catch (Exception ignored) {}
        }

        List<String> segments = new ArrayList<>();
        for (String raw : playlist.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.contains("/") || line.contains("..")) continue;
            segments.add(line);
        }

        int i = 0;
        for (String seg : segments) {
            i++;
            if (root == warmRoot) setWarmProgress("DOWNLOADING", id, i, Math.max(1,segments.size()), "");
            writeBytes(new File(tmp, seg), fetchBytes(GATEWAY + "/hlsdyn/" + id + "/" + seg));
        }

        writeBytes(new File(tmp, ".complete"), Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        deleteTree(dest);
        if (!tmp.renameTo(dest)) throw new Exception("rename failed");
        if (root == transientRoot) touchTree(dest);
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
                // Last-resort synchronous cache miss.
                byte[] data = fetchBytes(GATEWAY + "/hlsdyn/" + id + "/" + p[1]);
                f = safeFile(transientRoot, rel);
                writeBytes(f, data);
                return response(f.getName(), new FileInputStream(f));
            }
            if (path.startsWith("/media/")) {
                byte[] data = fetchBytes(GATEWAY + path);
                return new WebResourceResponse(
                        path.contains("/poster/") || path.contains("/thumb/") ? "image/jpeg" : "application/octet-stream",
                        null, new ByteArrayInputStream(data));
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
                if (d.isDirectory() && newestMtime(d) < cutoff) deleteTree(d);
            }
        }

        long total = treeSize(transientRoot);
        if (total <= TRANSIENT_MAX_BYTES) return;

        List<File> keep = new ArrayList<>();
        dirs = transientRoot.listFiles();
        if (dirs != null) for (File d : dirs) if (d.isDirectory()) keep.add(d);
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
        }

        JSONObject queue() throws Exception {
            return post("/living/api/queue", new JSONObject());
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
            if (code == 204) { c.disconnect(); return new JSONObject(); }
            if (code < 200 || code >= 300) {
                drain(c); c.disconnect(); throw new Exception("HTTP " + code);
            }
            String text = readText(c.getInputStream());
            c.disconnect();
            return new JSONObject(text);
        }
    }

    private static HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(45000);
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "BambiniLiving/6.0");
        return c;
    }

    private static byte[] fetchBytes(String url) throws Exception {
        HttpURLConnection c = open(url,"GET");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) { c.disconnect(); throw new Exception("HTTP " + code + " " + url); }
        try (InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[128*1024]; int n;
            while((n=in.read(b))!=-1) out.write(b,0,n);
            return out.toByteArray();
        } finally { c.disconnect(); }
    }

    private static String fetchText(String url) throws Exception {
        return new String(fetchBytes(url), StandardCharsets.UTF_8);
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

    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus)immersive();}
    @Override public void onBackPressed(){web.reload();}
    @Override protected void onDestroy(){
        control.shutdownNow();prefetch.shutdownNow();
        if(web!=null){web.loadUrl("about:blank");web.destroy();}
        super.onDestroy();
    }
}

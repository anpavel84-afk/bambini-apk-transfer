package ru.anpavel.bambiniliving;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class BambiniRemote {
    static final String BASE = "https://bambini.anpavel.ru";
    static final String APP_KEY = "_SEs08BNhi4G1ZRKuYI_" + "mimSSeEtOL8WiG1g0qe_" + "5qgoLJVxTEb7Z2_geKZl-Vxn";

    private BambiniRemote() {}

    static String enroll() throws Exception {
        HttpURLConnection c = open(BASE + "/living/app", "GET");
        c.setRequestProperty("X-Bambini-App-Key", APP_KEY);
        c.setInstanceFollowRedirects(false);
        int code = c.getResponseCode();
        String set = c.getHeaderField("Set-Cookie");
        drain(c);
        c.disconnect();
        if ((code != 200 && code != 302 && code != 303) || set == null) {
            throw new Exception("enroll " + code);
        }
        return set.split(";", 2)[0];
    }

    static JSONObject queue(String cookie) throws Exception {
        HttpURLConnection c = open(BASE + "/living/api/queue", "POST");
        c.setRequestProperty("Cookie", cookie);
        c.setRequestProperty("X-Living-Frame", "1");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write("{}".getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            drain(c);
            c.disconnect();
            throw new Exception("queue " + code);
        }
        String text = readText(c.getInputStream());
        c.disconnect();
        return new JSONObject(text);
    }

    static byte[] getBytes(String cookie, String path) throws Exception {
        if (path == null || !path.startsWith("/")) throw new Exception("unsafe path");
        HttpURLConnection c = open(BASE + path, "GET");
        c.setRequestProperty("Cookie", cookie);
        c.setInstanceFollowRedirects(false);
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            drain(c);
            c.disconnect();
            throw new Exception("GET " + path + " " + code);
        }
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(45000);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", "BambiniLiving/6.6");
        return c;
    }

    private static String readText(InputStream in) throws Exception {
        try (InputStream x = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = x.read(b)) != -1) out.write(b, 0, n);
            return out.toString("UTF-8");
        }
    }

    private static void drain(HttpURLConnection c) {
        try {
            InputStream in = c.getErrorStream();
            if (in == null) in = c.getInputStream();
            if (in != null) {
                byte[] b = new byte[4096];
                while (in.read(b) != -1) {}
                in.close();
            }
        } catch (Exception ignored) {}
    }
}

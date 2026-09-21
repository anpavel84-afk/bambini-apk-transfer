package ru.anpavel.bambiniliving;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BambiniWallpaperService extends WallpaperService {
    @Override public Engine onCreateEngine() {
        return new LivingEngine();
    }

    private final class LivingEngine extends Engine {
        private static final long HOLD_MS = 9000L;
        private static final long FADE_MS = 1500L;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService io = Executors.newSingleThreadExecutor();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint logo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private final Deque<String> pending = new ArrayDeque<>();
        private boolean visible;
        private boolean loading;
        private int width = 1, height = 1;
        private Bitmap current, next;
        private long sceneAt = System.currentTimeMillis();
        private String cookie;

        private final Runnable frame = new Runnable() {
            @Override public void run() {
                drawFrame();
                if (visible) main.postDelayed(this, 50);
            }
        };

        @Override public void onVisibilityChanged(boolean v) {
            visible = v;
            main.removeCallbacks(frame);
            if (visible) {
                ensureNext();
                main.post(frame);
            }
        }

        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            width = Math.max(1, w);
            height = Math.max(1, h);
            sceneAt = System.currentTimeMillis();
            if (visible) main.post(frame);
        }

        @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
            visible = false;
            main.removeCallbacks(frame);
            super.onSurfaceDestroyed(holder);
        }

        @Override public void onDestroy() {
            visible = false;
            main.removeCallbacksAndMessages(null);
            io.shutdownNow();
            recycle(current);
            recycle(next);
            current = next = null;
            super.onDestroy();
        }

        private void ensureNext() {
            if (loading || next != null) return;
            loading = true;
            io.execute(() -> {
                Bitmap decoded = null;
                try {
                    if (cookie == null) cookie = BambiniRemote.enroll();
                    if (pending.isEmpty()) refillQueue();
                    String path = pending.pollFirst();
                    if (path != null) {
                        byte[] bytes = BambiniRemote.getBytes(cookie, path);
                        decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    }
                } catch (Exception first) {
                    try {
                        cookie = BambiniRemote.enroll();
                        if (pending.isEmpty()) refillQueue();
                        String path = pending.pollFirst();
                        if (path != null) {
                            byte[] bytes = BambiniRemote.getBytes(cookie, path);
                            decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        }
                    } catch (Exception ignored) {}
                }
                Bitmap result = decoded;
                main.post(() -> {
                    loading = false;
                    if (result != null) {
                        if (current == null) {
                            current = result;
                            sceneAt = System.currentTimeMillis();
                        } else {
                            next = result;
                        }
                    }
                    if (visible && (current == null || next == null)) ensureNext();
                });
            });
        }

        private void refillQueue() throws Exception {
            JSONObject q = BambiniRemote.queue(cookie);
            JSONArray scenes = q.optJSONArray("scenes");
            if (scenes == null) return;
            for (int i = 0; i < scenes.length(); i++) {
                JSONArray items = scenes.getJSONObject(i).optJSONArray("items");
                if (items == null || items.length() == 0) continue;
                JSONObject item = items.getJSONObject(0);
                String p = item.optString("poster", "");
                if (p.startsWith("/poster/") || p.startsWith("/thumb/")) pending.addLast(p);
            }
        }

        private void drawFrame() {
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                c = holder.lockCanvas();
                if (c == null) return;
                c.drawColor(Color.rgb(5,5,5));
                long now = System.currentTimeMillis();
                float age = Math.max(0f, now - sceneAt);
                if (current == null) {
                    drawLogo(c);
                    ensureNext();
                    return;
                }
                float zoom = 1f + Math.min(0.025f, age / HOLD_MS * 0.025f);
                drawCover(c, current, zoom, 255);
                if (age >= HOLD_MS) {
                    ensureNext();
                    if (next != null) {
                        float f = Math.min(1f, (age - HOLD_MS) / FADE_MS);
                        drawCover(c, next, 1.025f - f * 0.025f, Math.round(255f * f));
                        if (f >= 1f) {
                            Bitmap old = current;
                            current = next;
                            next = null;
                            sceneAt = now;
                            recycle(old);
                            ensureNext();
                        }
                    }
                }
            } finally {
                if (c != null) holder.unlockCanvasAndPost(c);
            }
        }

        private void drawCover(Canvas c, Bitmap b, float zoom, int alpha) {
            if (b == null || b.isRecycled()) return;
            float base = Math.max(width / (float)b.getWidth(), height / (float)b.getHeight());
            float scale = base * zoom;
            float dx = (width - b.getWidth() * scale) * 0.5f;
            float dy = (height - b.getHeight() * scale) * 0.5f;
            matrix.reset();
            matrix.setScale(scale, scale);
            matrix.postTranslate(dx, dy);
            paint.setAlpha(alpha);
            c.drawBitmap(b, matrix, paint);
        }

        private void drawLogo(Canvas c) {
            logo.setColor(Color.rgb(246,240,230));
            logo.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            logo.setTextAlign(Paint.Align.CENTER);
            logo.setTextSize(Math.min(width, height) * 0.42f);
            Paint.FontMetrics fm = logo.getFontMetrics();
            float y = height * 0.5f - (fm.ascent + fm.descent) * 0.5f;
            c.drawText("B", width * 0.5f, y, logo);
        }

        private void recycle(Bitmap b) {
            if (b != null && !b.isRecycled()) b.recycle();
        }
    }
}

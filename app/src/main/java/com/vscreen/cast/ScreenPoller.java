package com.vscreen.cast;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 轮询截取指定屏幕的画面。
 *
 * 采集手段只有 screencap：screenrecord 的 --display-id 只认物理屏，
 * 对 scrcpy 这类虚拟屏会直接报 "Invalid physical display id"。
 * 单路 screencap 约 300~650ms（取决于设备），因此并发多路提交来换帧率。
 */
public class ScreenPoller {

    public interface Callback {
        void onFrame(Bitmap bmp);
    }

    private final Context appCtx;
    private final int displayId;
    private final Callback cb;
    private final int lanes;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread thread;

    public static volatile int fps = 0;
    public static volatile long frameCount = 0;
    public static volatile long failCount = 0;
    public static volatile int srcW = 0;
    public static volatile int srcH = 0;

    private long fpsBase = 0;
    private long fpsCount = 0;

    public ScreenPoller(Context ctx, int displayId, Callback cb, int lanes) {
        this.appCtx = ctx.getApplicationContext();
        this.displayId = displayId;
        this.cb = cb;
        this.lanes = Math.max(1, lanes);
    }

    public void start() {
        if (running.get()) return;
        running.set(true);
        frameCount = 0;
        failCount = 0;
        fpsBase = System.currentTimeMillis();
        fpsCount = 0;
        thread = new Thread(this::loop, "vcast-poll");
        thread.start();
        AppLog.i("POLL", "start display=" + displayId + " lanes=" + lanes);
    }

    public void stop() {
        running.set(false);
        Thread t = thread;
        if (t != null) t.interrupt();
        AppLog.i("POLL", "stop");
    }

    private void loop() {
        File dir = appCtx.getExternalFilesDir(null);
        if (dir == null) {
            AppLog.i("POLL", "no external dir");
            return;
        }
        boolean first = true;
        while (running.get()) {
            StringBuilder cmd = new StringBuilder();
            for (int i = 0; i < lanes; i++) {
                File f = new File(dir, "f" + i + ".png");
                cmd.append("screencap -d ").append(displayId)
                        .append(" -p '").append(f.getAbsolutePath()).append("' & ");
            }
            cmd.append("wait");
            ShizukuShell.execVoid(cmd.toString());

            for (int i = 0; i < lanes; i++) {
                if (!running.get()) break;
                File f = new File(dir, "f" + i + ".png");
                Bitmap b = null;
                try {
                    b = BitmapFactory.decodeFile(f.getAbsolutePath());
                } catch (Throwable t) {
                    AppLog.i("POLL", "decode err: " + t);
                }
                if (b == null) {
                    failCount++;
                    continue;
                }
                if (first) {
                    first = false;
                    srcW = b.getWidth();
                    srcH = b.getHeight();
                    AppLog.i("POLL", "first frame " + srcW + "x" + srcH);
                }
                frameCount++;
                fpsCount++;
                long now = System.currentTimeMillis();
                long dt = now - fpsBase;
                if (dt >= 1000) {
                    fps = (int) (fpsCount * 1000 / dt);
                    fpsBase = now;
                    fpsCount = 0;
                }
                try {
                    cb.onFrame(b);
                } catch (Throwable ignored) {
                }
            }
        }
    }
}

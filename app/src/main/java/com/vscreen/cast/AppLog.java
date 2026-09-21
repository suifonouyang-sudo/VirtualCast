package com.vscreen.cast;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 轻量日志：同时写 logcat 与文件（可用 run-as 直接 cat 出来核对） */
public final class AppLog {

    public interface Sink {
        void onLog(String line);
    }

    private static volatile Sink sink;
    private static volatile File file;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    public static void init(Context c) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir != null && !dir.exists()) dir.mkdirs();
            file = new File(dir, "vcast.log");
        } catch (Throwable ignored) {
        }
    }

    public static void setSink(Sink s) {
        sink = s;
    }

    public static void i(String tag, String msg) {
        String line = FMT.format(new Date()) + " [" + tag + "] " + msg;
        android.util.Log.i("VSCast", line);
        append(line);
        Sink s = sink;
        if (s != null) {
            try {
                s.onLog(line);
            } catch (Throwable ignored) {
            }
        }
    }

    private static synchronized void append(String line) {
        try {
            File f = file;
            if (f == null) return;
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write((line + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }
}

package com.vscreen.cast;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.File;
import java.io.FileInputStream;
import java.util.concurrent.locks.ReentrantLock;

import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

/** 通过 Shizuku 以 adb/shell 身份执行命令 */
public final class ShizukuShell {

    public static final class Res {
        public final int rc;
        public final String out;

        Res(int rc, String out) {
            this.rc = rc;
            this.out = out;
        }
    }

    private static final ReentrantLock LOCK = new ReentrantLock();

    private ShizukuShell() {
    }

    public static boolean binderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isReady() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int uid() {
        try {
            return Shizuku.getUid();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int version() {
        try {
            return Shizuku.getVersion();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static IShizukuService svc() throws Exception {
        return IShizukuService.Stub.asInterface(new ShizukuBinderWrapper(Shizuku.getBinder()));
    }

    /**
     * 需要输出的命令。输出先落到文件再回收 —— 部分命令（am/pm 等）会 fork 出持有写端的
     * 子进程，直接读流会永久阻塞在 EOF 等待上。
     */
    public static Res exec(Context ctx, String cmd) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir != null && !dir.exists()) dir.mkdirs();
        File f = new File(dir, "sh_" + Thread.currentThread().getId() + "_" + System.nanoTime() + ".out");
        String wrap = "{ " + cmd + " ; echo \"@@RC=$?\"; } > '" + f.getAbsolutePath() + "' 2>&1";
        int rc = run(wrap);
        String txt = read(f);
        int realRc = rc;
        int idx = txt.lastIndexOf("@@RC=");
        if (idx >= 0) {
            try {
                realRc = Integer.parseInt(txt.substring(idx + 5).trim());
            } catch (Exception ignored) {
            }
            txt = txt.substring(0, idx);
        }
        try {
            f.delete();
        } catch (Throwable ignored) {
        }
        return new Res(realRc, txt.trim());
    }

    /** 不需要输出的命令（input 等高频调用）：丢弃输出，只等退出码 */
    public static int execVoid(String cmd) {
        return run(cmd + " > /dev/null 2>&1");
    }

    private static int run(String cmd) {
        LOCK.lock();
        try {
            IShizukuService s = svc();
            // newProcess 实际返回 IRemoteProcess（ShizukuRemoteProcess 的父接口），用 var 避免版本差异
            var p = s.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            return p.waitFor();
        } catch (Throwable t) {
            AppLog.i("SH", "run failed: " + cmd + " -> " + t);
            return -1;
        } finally {
            LOCK.unlock();
        }
    }

    private static String read(File f) {
        try {
            FileInputStream fis = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = 0;
            while (n < buf.length) {
                int r = fis.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            fis.close();
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }
}

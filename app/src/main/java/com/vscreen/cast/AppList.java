package com.vscreen.cast;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 已安装应用清单，用于「在目标屏上打开任意应用」。
 *
 * 注意：Android 11+ 的包可见性限制 —— 不在 AndroidManifest 的 <queries> 里声明
 * MAIN/LAUNCHER intent，queryIntentActivities 只会返回自己，列表会是空的。
 */
public final class AppList {

    public static final class Item {
        public String label;
        public String pkg;
        public String act;
        public Drawable icon;

        @Override
        public String toString() {
            return label + " (" + pkg + ")";
        }
    }

    private AppList() {
    }

    public static List<Item> load(Context ctx) {
        List<Item> out = new ArrayList<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN, null);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
            for (ResolveInfo ri : ris) {
                if (ri.activityInfo == null) continue;
                Item it = new Item();
                it.pkg = ri.activityInfo.packageName;
                it.act = ri.activityInfo.name;
                try {
                    it.label = ri.loadLabel(pm).toString();
                } catch (Throwable t) {
                    it.label = it.pkg;
                }
                try {
                    it.icon = ri.loadIcon(pm);
                } catch (Throwable ignored) {
                }
                if (!it.pkg.equals(ctx.getPackageName())) {
                    out.add(it);   // 不把自己列进去，否则点了等于又开一个投屏
                }
            }
        } catch (Throwable t) {
            AppLog.i("APPS", "load err: " + t);
        }
        Collections.sort(out, (a, b) -> {
            String x = a.label == null ? "" : a.label;
            String y = b.label == null ? "" : b.label;
            return x.compareToIgnoreCase(y);
        });
        AppLog.i("APPS", "loaded " + out.size() + " launcher app(s)");
        return out;
    }

    /** 在指定屏幕上启动该应用 —— 关键就是 am start 的 --display 参数 */
    public static void launch(Context ctx, int displayId, Item it) {
        String cmd = "am start --display " + displayId
                + " --windowingMode 1"                       // 1=fullscreen，不加会以小窗(freeform)打开
                + " -n " + it.pkg + "/" + it.act
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER";
        ShizukuShell.Res r = ShizukuShell.exec(ctx, cmd);
        AppLog.i("APPS", "launch " + it.pkg + " on display " + displayId
                + " rc=" + r.rc + " out=" + r.out.replace('\n', ' ').trim());
        // -n 也偶有解析不到的情况，退回按包名再试一次
        if (r.out.contains("Error") && r.out.contains("unable to resolve")) {
            launchPkg(ctx, displayId, it.pkg);
        }
    }

    /** 按包名启动：先解析 launcher activity，再 -n 精确启动 */
    public static void launchPkg(Context ctx, int displayId, String pkg) {
        String act = resolveLauncher(ctx, pkg);
        String cmd;
        if (act != null) {
            String p = act.substring(0, act.indexOf('/'));
            String a = act.substring(act.indexOf('/') + 1);
            if (a.startsWith(".")) a = p + a;
            cmd = "am start --display " + displayId
                    + " --windowingMode 1 -n " + p + "/" + a;
        } else {
            cmd = "am start --display " + displayId
                    + " --windowingMode 1 -a android.intent.action.MAIN"
                    + " -c android.intent.category.LAUNCHER -p " + pkg;
        }
        ShizukuShell.Res r = ShizukuShell.exec(ctx, cmd);
        AppLog.i("APPS", "launchPkg " + pkg + " rc=" + r.rc
                + " out=" + r.out.replace('\n', ' ').trim());
    }

    /** 返回 "包名/Activity" 形式，解析不到返回 null */
    private static String resolveLauncher(Context ctx, String pkg) {
        ShizukuShell.Res q = ShizukuShell.exec(ctx,
                "cmd package resolve-activity --brief"
                        + " -a android.intent.action.MAIN"
                        + " -c android.intent.category.LAUNCHER -p " + pkg);
        String found = null;
        for (String line : q.out.split("\n")) {
            line = line.trim();
            if (line.contains("/") && !line.contains(" ")) {
                found = line;
            }
        }
        return found;
    }
}

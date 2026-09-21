package com.vscreen.cast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * 投屏期间的通知栏常驻入口。
 *
 * 为什么必须有它：投屏窗口为了让触摸能注入到目标屏，设了 FLAG_NOT_FOCUSABLE，
 * 代价是它拿不到输入焦点 —— 系统返回键不会传给它，onBackPressed() 永远不触发，
 * uiautomator 也 dump 不到它的控件。所以「退出」必须有一条不依赖该窗口的通道，
 * 通知栏是唯一在任何界面都点得到的地方。
 */
public final class CastNotif {

    /** 渠道一旦建好 importance 就改不动了，升级时换 id 才会生效 */
    public static final String CH = "vcast_ctrl";
    public static final String ACT_STOP = "com.vscreen.cast.STOP";
    public static final String ACT_PAUSE = "com.vscreen.cast.PAUSE";
    /** 打开应用列表（也可从 adb 触发） */
    public static final String ACT_APPS = "com.vscreen.cast.APPS";
    /** 直接在目标屏启动指定包：--es pkg com.tencent.mm */
    public static final String ACT_LAUNCH = "com.vscreen.cast.LAUNCH";

    /** 暂停控制：只看画面、不再把触摸注入到目标屏 */
    public static volatile boolean paused = false;

    private static final int ID = 8801;

    private CastNotif() {
    }

    static void show(Context ctx, int displayId) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (android.os.Build.VERSION.SDK_INT >= 26) {
                NotificationChannel c = nm.getNotificationChannel(CH);
                if (c == null) {
                    // 不能用 IMPORTANCE_LOW：低优先级通知会被系统折叠，
                    // 「退出投屏」按钮根本不显示 —— 那就白做了。
                    c = new NotificationChannel(CH, "投屏控制",
                            NotificationManager.IMPORTANCE_DEFAULT);
                    c.setDescription("投屏期间提供退出与暂停控制的入口");
                    nm.createNotificationChannel(c);
                }
            }

            String title = "正在投屏 display " + displayId;
            String text = paused ? "控制已暂停（仅显示画面）" : "点击/滑动可操控该屏";

            Notification.Builder b = new Notification.Builder(ctx, CH)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(openMain(ctx))
                    .setWhen(System.currentTimeMillis());

            b.addAction(android.R.drawable.ic_delete, "退出投屏", pi(ctx, ACT_STOP));
            b.addAction(android.R.drawable.ic_media_pause,
                    paused ? "恢复控制" : "暂停控制", pi(ctx, ACT_PAUSE));
            b.addAction(android.R.drawable.ic_menu_add, "打开应用", pi(ctx, ACT_APPS));

            nm.notify(ID, b.build());
            AppLog.i("NOTIF", "shown display=" + displayId + " paused=" + paused);
        } catch (Throwable t) {
            AppLog.i("NOTIF", "show err: " + t);
        }
    }

    /** 点通知主体 → 回到主界面（那里有「停止投屏」） */
    private static PendingIntent openMain(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(ctx, 1, i, flags);
    }

    private static PendingIntent pi(Context ctx, String action) {
        Intent i = new Intent(action).setPackage(ctx.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(ctx, action.hashCode(), i, flags);
    }

    static void cancel(Context ctx) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(ID);
        } catch (Throwable ignored) {
        }
        paused = false;
        AppLog.i("NOTIF", "cancelled");
    }
}

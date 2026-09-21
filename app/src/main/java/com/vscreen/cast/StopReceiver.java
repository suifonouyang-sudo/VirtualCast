package com.vscreen.cast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 退出 / 暂停控制的统一入口，三条路都能触发：
 *   1. 通知栏按钮
 *   2. 主界面的「停止投屏」
 *   3. 外部 adb：am broadcast -a com.vscreen.cast.STOP
 */
public class StopReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        AppLog.init(ctx);
        String a = intent == null ? null : intent.getAction();
        AppLog.i("STOPRX", "action=" + a);

        if (CastNotif.ACT_STOP.equals(a)) {
            CastNotif.cancel(ctx);
            ViewerActivity v = ViewerActivity.live;
            if (v != null) {
                v.finishFromRemote();
            } else {
                // 窗口已不在，兜底把进程里残留的采集停掉
                AppLog.i("STOPRX", "no live viewer");
            }
            return;
        }

        if (CastNotif.ACT_PAUSE.equals(a)) {
            CastNotif.paused = !CastNotif.paused;
            ViewerActivity v = ViewerActivity.live;
            if (v != null) {
                v.syncNotif();
            } else {
                CastNotif.cancel(ctx);
            }
            return;
        }

        // 打开应用列表
        if (CastNotif.ACT_APPS.equals(a)) {
            ViewerActivity v = ViewerActivity.live;
            if (v != null) v.showAppsRemote();
            return;
        }

        // 直接在目标屏启动指定包：am broadcast -a com.vscreen.cast.LAUNCH -p <pkg应用> --es pkg com.tencent.mm
        if (CastNotif.ACT_LAUNCH.equals(a)) {
            String target = intent.getStringExtra("pkg");
            ViewerActivity v = ViewerActivity.live;
            if (v != null && target != null && !target.isEmpty()) {
                v.launchPkgRemote(target);
            }
        }
    }
}

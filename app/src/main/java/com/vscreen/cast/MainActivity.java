package com.vscreen.cast;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/** 主界面：检测屏幕 → 选择一块 → 把它的画面投进来并反向控制它 */
public class MainActivity extends Activity {

    private TextView tvState;
    private TextView tvShizuku;
    private TextView tvDisplays;
    private TextView tvLog;
    private LinearLayout llDisplays;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile int physW = 0;
    private volatile int physH = 0;
    private volatile int physDpi = 320;
    private volatile boolean metaLoaded = false;

    private final StringBuilder logBuf = new StringBuilder();

    private final Shizuku.OnRequestPermissionResultListener permListener =
            (requestCode, result) -> ui.post(this::refresh);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.init(this);
        setContentView(R.layout.activity_main);

        tvState = findViewById(R.id.tvState);
        tvShizuku = findViewById(R.id.tvShizuku);
        tvDisplays = findViewById(R.id.tvDisplays);
        tvLog = findViewById(R.id.tvLog);
        llDisplays = findViewById(R.id.llDisplays);

        AppLog.setSink(line -> ui.post(() -> {
            logBuf.append(line).append('\n');
            if (logBuf.length() > 6000) logBuf.delete(0, logBuf.length() - 4000);
            tvLog.setText(logBuf.toString());
        }));

        try {
            Shizuku.addRequestPermissionResultListener(permListener);
        } catch (Throwable t) {
            AppLog.i("UI", "add perm listener failed: " + t);
        }

        bindButtons();
        requestNotifPermission();
        handleOpen(getIntent());
        refresh();
        ui.postDelayed(refreshTask, 1500);
        AppLog.i("UI", "main created");
    }

    /** App 已在后台时再次 am start 会走这里，不处理的话快捷入口第二次就失效 */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        AppLog.i("UI", "onNewIntent");
        handleOpen(intent);
    }

    /** 快捷入口（自动化/外部调起）：am start ... --ei open <displayId> */
    private void handleOpen(Intent src) {
        int open = -1;
        if (src != null) {
            try {
                open = src.getIntExtra("open", -1);
            } catch (Throwable ignored) {
            }
        }
        if (open < 0) return;
        final int targetId = open;
        if (ViewerActivity.live != null) {
            AppLog.i("UI", "viewer already live, restart on display " + targetId);
            sendBroadcast(new Intent(CastNotif.ACT_STOP).setPackage(getPackageName()));
            ui.postDelayed(() -> launchById(targetId), 500);
        } else {
            launchById(targetId);
        }
    }

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 1500);
        }
    };

    private void bindButtons() {
        Button btnShizuku = findViewById(R.id.btnShizuku);
        Button btnScan = findViewById(R.id.btnScan);

        btnShizuku.setOnClickListener(v -> {
            if (ShizukuShell.isReady()) {
                toast("Shizuku 已授权");
                return;
            }
            if (!ShizukuShell.binderAlive()) {
                toast("Shizuku 服务未运行，请先启动 Shizuku App");
                return;
            }
            try {
                Shizuku.requestPermission(0);
            } catch (Throwable t) {
                AppLog.i("UI", "requestPermission err: " + t);
                toast("请求失败: " + t.getMessage());
            }
        });

        btnScan.setOnClickListener(v -> runBg(this::scan));

        Button btnStop = findViewById(R.id.btnStop);
        btnStop.setOnClickListener(v -> {
            sendBroadcast(new Intent(CastNotif.ACT_STOP).setPackage(getPackageName()));
            toast("已发送停止指令");
        });
    }

    private void scan() {
        if (!ShizukuShell.isReady()) {
            toast("请先授权 Shizuku");
            return;
        }
        List<VDisplay.Disp> list = VDisplay.listAll(MainActivity.this);
        StringBuilder sb = new StringBuilder();
        for (VDisplay.Disp d : list) {
            sb.append(d.toString()).append('\n');
        }
        final String text = list.isEmpty() ? "未解析到显示屏（请查看日志）" : sb.toString().trim();
        AppLog.i("UI", "scan result:\n" + text);

        ui.post(() -> {
            tvDisplays.setText(text);
            llDisplays.removeAllViews();
            for (VDisplay.Disp d : list) {
                llDisplays.addView(buildDispButton(d));
            }
            if (list.isEmpty()) {
                toast("未检测到屏幕");
            } else {
                toast("检测到 " + list.size() + " 块屏，点一块即可投屏并控制");
            }
        });
    }

    private Button buildDispButton(VDisplay.Disp d) {
        Button b = new Button(this);
        b.setText("#" + d.id + "  " + (d.name.isEmpty() ? d.kind() : d.name)
                + "  " + (d.w > 0 ? d.w + "x" + d.h : "尺寸未知")
                + "\n" + d.kind() + (d.active ? " · 活跃" : " · 未激活")
                + "\n点击投屏并控制");
        b.setTextSize(12f);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 8;
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> openViewer(d));
        return b;
    }

    private void openViewer(VDisplay.Disp d) {
        launchById(d.id);
    }

    private void launchById(int id) {
        runBg(() -> {
            if (!ShizukuShell.isReady()) {
                toast("请先授权 Shizuku");
                return;
            }
            // 先探一下这块屏能不能截，避免打开一个永远黑屏的窗口
            String probe = "screencap -d " + id + " -p '"
                    + new java.io.File(getExternalFilesDir(null), "probe.png")
                            .getAbsolutePath() + "' >/dev/null 2>&1; echo rc=$?";
            ShizukuShell.Res r = ShizukuShell.exec(MainActivity.this, probe);
            AppLog.i("UI", "probe display " + id + " -> " + r.out.trim());
            if (!r.out.contains("rc=0")) {
                toast("display " + id + " 无法截屏（" + r.out.trim() + "）");
                return;
            }
            ui.post(() -> {
                Intent i = new Intent(MainActivity.this, ViewerActivity.class);
                i.putExtra(ViewerActivity.EXTRA_DISPLAY, id);
                startActivity(i);
                toast("正在投屏 display " + id + "\n退出：下拉通知栏点「退出投屏」");
            });
        });
    }

    private void refresh() {
        boolean ready = ShizukuShell.isReady();
        if (ready) {
            tvShizuku.setText("已授权（uid=" + ShizukuShell.uid()
                    + ", api v" + ShizukuShell.version() + "）");
            tvShizuku.setTextColor(getColor(R.color.ok));
        } else if (ShizukuShell.binderAlive()) {
            tvShizuku.setText("Shizuku 已运行，但本应用未授权");
            tvShizuku.setTextColor(getColor(R.color.warn));
        } else {
            tvShizuku.setText("Shizuku 未运行，请先启动 Shizuku App");
            tvShizuku.setTextColor(getColor(R.color.err));
        }

        if (ready && !metaLoaded) {
            metaLoaded = true;
            loadMeta();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("物理屏: ").append(physW > 0 ? physW + "x" + physH + " @" + physDpi : "读取中…");
        sb.append('\n');
        sb.append("投屏中: ");
        if (ScreenPoller.frameCount > 0) {
            sb.append(ScreenPoller.srcW).append("x").append(ScreenPoller.srcH)
                    .append(" · ").append(ScreenPoller.fps).append(" fps · ")
                    .append(ScreenPoller.frameCount).append(" 帧");
        } else {
            sb.append("无");
        }
        tvState.setText(sb.toString());
    }

    private void loadMeta() {
        runBg(() -> {
            int[] size = VDisplay.physicalSize(MainActivity.this);
            physW = size[0];
            physH = size[1];
            physDpi = VDisplay.physicalDensity(MainActivity.this);
            AppLog.i("UI", "physical " + physW + "x" + physH + "@" + physDpi);
        });
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1002);
        }
    }

    private void runBg(Runnable r) {
        exec.execute(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                AppLog.i("UI", "bg err: " + t);
            }
        });
    }

    private void toast(String msg) {
        ui.post(() -> {
            try {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
            AppLog.i("UI", "toast: " + msg);
        });
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) {
        }
        ui.removeCallbacks(refreshTask);
        AppLog.setSink(null);
        super.onDestroy();
    }
}

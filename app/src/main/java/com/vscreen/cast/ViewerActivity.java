package com.vscreen.cast;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 把指定屏幕的画面投进来显示，并把触摸/按键反向注入回「那块屏」。
 * 注入目标就是被投的那块屏，不是物理屏 —— 由 Intent 的 displayId 决定。
 */
public class ViewerActivity extends Activity {

    public static final String EXTRA_DISPLAY = "displayId";

    private static final int MOVE_SLOP = 14;

    /** 供通知栏 / 广播关闭本窗口（本窗口无焦点，返回键收不到，必须留这条通道） */
    public static volatile ViewerActivity live;

    private int displayId = -1;

    private SurfaceView surface;
    private SurfaceHolder holder;
    private Paint paint;
    private TextView tvInfo;

    private ScreenPoller poller;
    private LinearLayout layerApps;
    private ListView lvApps;
    private TextView tvAppsTitle;
    private AppAdapter appAdapter;
    private volatile Bitmap lastFrame;
    private volatile boolean render = true;
    private Thread renderThread;
    private final ExecutorService inputExec = Executors.newSingleThreadExecutor();

    private final Rect dst = new Rect();
    private volatile float drawScale = 1f;

    private float downX;
    private float downY;
    private long downTime;
    private boolean moved;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.init(this);

        displayId = getIntent().getIntExtra(EXTRA_DISPLAY, -1);
        if (displayId < 0) {
            AppLog.i("VIEW", "no displayId, abort");
            finish();
            return;
        }
        InputInjector.targetDisplay = displayId;
        live = this;
        CastNotif.paused = false;

        // 关键：本窗口不能抢焦点。系统全局只允许一个 display 持有 focused window，
        // 一旦我们（display 0）拿到焦点，目标屏就没有焦点窗口，
        // InputDispatcher 会把注入到目标屏的触摸事件整个丢弃：
        //   "Focused display #N does not have a focused window"
        // NOT_FOCUSABLE 的窗口仍然能正常接收触摸，只是不参与焦点竞争。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_viewer);

        paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        surface = findViewById(R.id.surface);
        tvInfo = findViewById(R.id.tvInfo);
        holder = surface.getHolder();

        surface.setOnTouchListener(this::onTouch);
        bindBar();

        startRender();
        startPoller();
        CastNotif.show(this, displayId);
        AppLog.i("VIEW", "created for display " + displayId);
    }

    /** 通知栏 / adb 广播调用的退出：可能在非 UI 线程 */
    public void finishFromRemote() {
        runOnUiThread(() -> {
            AppLog.i("VIEW", "finish from remote");
            finish();
        });
    }

    /** 暂停状态变化后刷新通知文案 */
    public void syncNotif() {
        CastNotif.show(this, displayId);
        runOnUiThread(this::updateInfo);
    }

    /** 广播触发：打开应用列表 */
    public void showAppsRemote() {
        runOnUiThread(this::showApps);
    }

    /** 广播触发：按包名在目标屏启动 */
    public void launchPkgRemote(String pkg) {
        inputExec.execute(() ->
                AppList.launchPkg(getApplicationContext(), displayId, pkg));
    }

    private void startPoller() {
        poller = new ScreenPoller(this, displayId, bmp -> lastFrame = bmp, 3);
        poller.start();
    }

    private void bindBar() {
        Button back = findViewById(R.id.btnBack);
        Button home = findViewById(R.id.btnHome);
        Button recents = findViewById(R.id.btnRecents);
        Button activate = findViewById(R.id.btnActivate);
        Button apps = findViewById(R.id.btnApps);
        Button pause = findViewById(R.id.btnPause);
        Button exit = findViewById(R.id.btnExit);

        layerApps = findViewById(R.id.layerApps);
        tvAppsTitle = findViewById(R.id.tvAppsTitle);
        lvApps = findViewById(R.id.lvApps);
        lvApps.setOnItemClickListener((parent, view, position, id) -> {
            if (appAdapter == null || position >= appAdapter.getCount()) return;
            AppList.Item item = appAdapter.getItem(position);
            layerApps.setVisibility(View.GONE);
            AppLog.i("VIEW", "open " + item.pkg + " on display " + displayId);
            inputExec.execute(() -> AppList.launch(getApplicationContext(), displayId, item));
        });
        findViewById(R.id.btnAppsClose).setOnClickListener(v -> layerApps.setVisibility(View.GONE));
        apps.setOnClickListener(v -> showApps());
        back.setOnClickListener(v -> inputExec.execute(InputInjector::back));
        home.setOnClickListener(v -> inputExec.execute(InputInjector::home));
        recents.setOnClickListener(v -> inputExec.execute(InputInjector::recents));
        // 唤起目标屏的桌面：让那块屏有内容并有机会拿到输入焦点
        activate.setOnClickListener(v -> inputExec.execute(() -> {
            ShizukuShell.execVoid("am start --display " + displayId
                    + " -a android.intent.action.MAIN -c android.intent.category.HOME");
            AppLog.i("VIEW", "activate display " + displayId);
        }));
        pause.setOnClickListener(v -> {
            CastNotif.paused = !CastNotif.paused;
            pause.setText(CastNotif.paused ? "继续" : "暂停");
            syncNotif();
        });
        exit.setOnClickListener(v -> finish());
        startFocusProbe();
    }

    /** 列出已安装应用，点一个就在被投的那块屏上打开它 */
    private void showApps() {
        layerApps.setVisibility(View.VISIBLE);
        if (appAdapter != null) return;
        tvAppsTitle.setText("在 display " + displayId + " 上打开应用 · 加载中…");
        new Thread(() -> {
            final List<AppList.Item> list = AppList.load(getApplicationContext());
            runOnUiThread(() -> {
                appAdapter = new AppAdapter(list);
                lvApps.setAdapter(appAdapter);
                tvAppsTitle.setText(list.isEmpty()
                        ? "没有可启动的应用"
                        : "在 display " + displayId + " 上打开应用 · " + list.size() + " 个");
            });
        }, "vcast-apps").start();
    }

    private class AppAdapter extends BaseAdapter {
        private final List<AppList.Item> data;

        AppAdapter(List<AppList.Item> data) {
            this.data = data == null ? new ArrayList<>() : data;
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public AppList.Item getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            if (convert == null) {
                convert = LayoutInflater.from(ViewerActivity.this)
                        .inflate(R.layout.app_item, parent, false);
            }
            AppList.Item it = data.get(position);
            ImageView iv = convert.findViewById(R.id.ivIcon);
            TextView label = convert.findViewById(R.id.tvLabel);
            TextView pkg = convert.findViewById(R.id.tvPkg);
            if (it.icon != null) {
                iv.setImageDrawable(it.icon);
            } else {
                iv.setImageDrawable(null);
            }
            label.setText(it.label);
            pkg.setText(it.pkg);
            return convert;
        }
    }

    private final android.os.Handler probeHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    /** 输入焦点在哪块屏决定是否控制得住：只有持有焦点的屏才会接收注入的触摸 */
    private void startFocusProbe() {
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                probeExec.execute(() -> {
                    ShizukuShell.Res res = ShizukuShell.exec(getApplicationContext(),
                            "dumpsys input 2>/dev/null | grep -m1 FocusedDisplayId");
                    String out = res.out;
                    int fid = -99;
                    java.util.regex.Matcher m = FOCUS.matcher(out);
                    if (m.find()) {
                        try {
                            fid = Integer.parseInt(m.group(1));
                        } catch (Exception ignored) {
                        }
                    }
                    focusedDisplay = fid;
                });
                probeHandler.postDelayed(this, 3000);
            }
        };
        probeHandler.postDelayed(r, 1500);
        probeHandlerWeak = r;
    }

    private Runnable probeHandlerWeak;
    private final ExecutorService probeExec = Executors.newSingleThreadExecutor();
    private static final java.util.regex.Pattern FOCUS =
            java.util.regex.Pattern.compile("FocusedDisplayId:\\s*(-?\\d+)");
    private volatile int focusedDisplay = -99;

    private void startRender() {
        renderThread = new Thread(() -> {
            while (render) {
                try {
                    drawFrame(lastFrame);
                } catch (Throwable t) {
                    AppLog.i("VIEW", "draw err: " + t);
                }
                SystemClock.sleep(16);
            }
        }, "vcast-render");
        renderThread.start();
    }

    private void drawFrame(Bitmap bmp) {
        SurfaceHolder h = holder;
        if (h == null) return;
        Canvas c = null;
        try {
            c = h.lockCanvas();
            if (c == null) return;
            int vw = c.getWidth();
            int vh = c.getHeight();
            c.drawColor(Color.BLACK);

            if (bmp != null) {
                int bw = bmp.getWidth();
                int bh = bmp.getHeight();
                float s = Math.min(vw / (float) bw, vh / (float) bh);
                int dw = (int) (bw * s);
                int dh = (int) (bh * s);
                int ox = (vw - dw) / 2;
                int oy = (vh - dh) / 2;
                dst.set(ox, oy, ox + dw, oy + dh);
                drawScale = s;
                c.drawBitmap(bmp, null, dst, paint);
            } else {
                paint.setColor(Color.WHITE);
                paint.setTextSize(22f);
                c.drawText("正在获取 display " + displayId + " 的画面…", 24, vh / 2f, paint);
            }
            paint.setColor(Color.WHITE);
        } finally {
            if (c != null) {
                try {
                    h.unlockCanvasAndPost(c);
                } catch (Throwable ignored) {
                }
            }
        }
        updateInfo();
    }

    private void updateInfo() {
        runOnUiThread(() -> {
            String head = "display " + displayId + " · "
                    + ScreenPoller.srcW + "x" + ScreenPoller.srcH + " · "
                    + ScreenPoller.fps + " fps · " + ScreenPoller.frameCount + " 帧"
                    + (ScreenPoller.failCount > 0 ? " · 失败 " + ScreenPoller.failCount : "");
            String focus;
            if (CastNotif.paused) {
                focus = "\n已暂停控制（仅看画面）";
            } else if (focusedDisplay == displayId) {
                focus = "\n焦点: display " + focusedDisplay + " 可控制";
            } else {
                focus = "\n焦点: display " + focusedDisplay
                        + " 不在本屏 → 点「激活」或先在目标屏触摸一次";
            }
            tvInfo.setText(head + focus + "\n▼ 下拉通知栏 → 退出投屏");
        });
    }

    /** 显示区域坐标 → 被投屏幕坐标（截图分辨率即该屏分辨率，线性映射即可） */
    private float[] mapToSource(float x, float y) {
        float s = drawScale;
        if (s <= 0) s = 1f;
        return new float[]{(x - dst.left) / s, (y - dst.top) / s};
    }

    private boolean onTouch(android.view.View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downTime = System.currentTimeMillis();
                moved = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - downX) > MOVE_SLOP
                        || Math.abs(e.getY() - downY) > MOVE_SLOP) {
                    moved = true;
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                final float ux = e.getX();
                final float uy = e.getY();
                final float sx = downX;
                final float sy = downY;
                final boolean isMove = moved && e.getActionMasked() == MotionEvent.ACTION_UP;
                final long dur = System.currentTimeMillis() - downTime;
                if (CastNotif.paused) {
                    AppLog.i("IN", "paused, skip inject");
                    return true;
                }
                inputExec.execute(() -> {
                    try {
                        if (!ShizukuShell.isReady()) {
                            AppLog.i("IN", "skip: shizuku not ready");
                            return;
                        }
                        float[] p1 = mapToSource(sx, sy);
                        if (isMove) {
                            float[] p2 = mapToSource(ux, uy);
                            int d = (int) Math.min(Math.max(dur, 120), 1200);
                            InputInjector.swipe((int) p1[0], (int) p1[1],
                                    (int) p2[0], (int) p2[1], d);
                        } else {
                            InputInjector.tap((int) p1[0], (int) p1[1]);
                        }
                    } catch (Throwable t) {
                        AppLog.i("IN", "inject err: " + t);
                    }
                });
                return true;
            }
            default:
                return true;
        }
    }

    @Override
    protected void onDestroy() {
        render = false;
        live = null;
        CastNotif.cancel(this);
        if (poller != null) poller.stop();
        if (probeHandlerWeak != null) probeHandler.removeCallbacks(probeHandlerWeak);
        try {
            probeExec.shutdownNow();
        } catch (Throwable ignored) {
        }
        try {
            inputExec.shutdownNow();
        } catch (Throwable ignored) {
        }
        AppLog.i("VIEW", "destroyed");
        super.onDestroy();
    }

    @Override
    public void finish() {
        render = false;
        super.finish();
    }
}

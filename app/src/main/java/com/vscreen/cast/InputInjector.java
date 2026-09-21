package com.vscreen.cast;

/** 把虚拟屏上的操作反向注入回真实屏幕（走 Shizuku 的 input 命令） */
public final class InputInjector {

    /** 注入目标屏：默认物理屏 0 */
    public static volatile int targetDisplay = 0;

    private InputInjector() {
    }

    public static void tap(int x, int y) {
        AppLog.i("IN", "tap " + x + "," + y + " -> display " + targetDisplay);
        ShizukuShell.execVoid("input -d " + targetDisplay + " tap " + x + " " + y);
    }

    public static void swipe(int x1, int y1, int x2, int y2, int durationMs) {
        AppLog.i("IN", "swipe " + x1 + "," + y1 + " -> " + x2 + "," + y2 + " (" + durationMs + "ms)");
        ShizukuShell.execVoid("input -d " + targetDisplay + " swipe "
                + x1 + " " + y1 + " " + x2 + " " + y2 + " " + durationMs);
    }

    public static void swipe(int x1, int y1, int x2, int y2) {
        swipe(x1, y1, x2, y2, 300);
    }

    /** 按键也要带 -d，否则默认走 focused app，会打到虚拟屏自己的窗口上 */
    public static void key(int keyCode) {
        AppLog.i("IN", "key " + keyCode + " -> display " + targetDisplay);
        ShizukuShell.execVoid("input -d " + targetDisplay + " keyevent " + keyCode);
    }

    public static void back() {
        key(4); // KEYCODE_BACK
    }

    public static void home() {
        key(3); // KEYCODE_HOME
    }

    public static void recents() {
        key(187); // KEYCODE_APP_SWITCH
    }
}

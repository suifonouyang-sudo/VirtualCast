package com.vscreen.cast;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 虚拟屏（第二块屏幕）的创建、探测与销毁 */
public final class VDisplay {

    /** 必须紧邻匹配：dumpsys display 里同一行可能有多个 uniqueId，贪婪写法会把屏认错 */
    private static final Pattern DISP =
            Pattern.compile("displayId=(\\d+), uniqueId='(overlay|virtual):");

    /** mViewports 里每个 viewport 是一段 {...}，内部只含 () 不含 {}，可用 [^}]* 切分 */
    private static final Pattern VIEWPORT =
            Pattern.compile("DisplayViewport\\{([^}]*)\\}");

    private static final Pattern V_TYPE = Pattern.compile("type=(\\w+)");
    private static final Pattern V_ACTIVE = Pattern.compile("isActive=(\\w+)");
    private static final Pattern V_ID = Pattern.compile("displayId=(\\d+)");
    private static final Pattern V_UID = Pattern.compile("uniqueId='([^']*)'");
    private static final Pattern V_FRAME =
            Pattern.compile("logicalFrame=Rect\\((\\d+), (\\d+) - (\\d+), (\\d+)\\)");

    /** DisplayDeviceInfo{"屏幕名": ..., uniqueId="xxx", ...} */
    private static final Pattern DEV_NAME =
            Pattern.compile("DisplayDeviceInfo\\{\"([^\"]*)\"[^\\n]{0,240}?uniqueId=\"([^\"]*)\"");

    public static final class Disp {
        public int id;
        public String uniqueId = "";
        public String type = "";
        public boolean active;
        public int w;
        public int h;
        public String name = "";

        public String kind() {
            if (uniqueId.startsWith("local:")) return "物理屏";
            if (uniqueId.startsWith("overlay:")) return "系统虚拟屏(overlay)";
            if (uniqueId.startsWith("virtual:")) return "第三方虚拟屏";
            return "其他";
        }

        @Override
        public String toString() {
            String label = name.isEmpty() ? kind() : name;
            return "#" + id + "  " + label
                    + "\n    uniqueId=" + uniqueId
                    + " | " + kind()
                    + " | " + (w > 0 ? w + "x" + h : "尺寸未知")
                    + " | " + (active ? "活跃" : "未激活")
                    + " | type=" + type;
        }
    }

    private VDisplay() {
    }

    /** 列出设备上所有显示屏（物理屏 / 系统虚拟屏 / 第三方虚拟屏），按 id 升序 */
    public static List<Disp> listAll(Context ctx) {
        String out = ShizukuShell.exec(ctx, "dumpsys display").out;

        Map<String, String> names = new HashMap<>();
        Matcher nm = DEV_NAME.matcher(out);
        while (nm.find()) {
            names.put(nm.group(2), nm.group(1));
        }

        List<Disp> list = new ArrayList<>();
        Matcher m = VIEWPORT.matcher(out);
        while (m.find()) {
            String seg = m.group(1);
            Matcher t = V_ID.matcher(seg);
            if (!t.find()) continue;
            Disp d = new Disp();
            try {
                d.id = Integer.parseInt(t.group(1));
            } catch (Exception e) {
                continue;
            }
            Matcher u = V_UID.matcher(seg);
            if (u.find()) d.uniqueId = u.group(1);
            Matcher ty = V_TYPE.matcher(seg);
            if (ty.find()) d.type = ty.group(1);
            Matcher ac = V_ACTIVE.matcher(seg);
            if (ac.find()) d.active = Boolean.parseBoolean(ac.group(1));
            Matcher fr = V_FRAME.matcher(seg);
            if (fr.find()) {
                try {
                    d.w = Integer.parseInt(fr.group(3)) - Integer.parseInt(fr.group(1));
                    d.h = Integer.parseInt(fr.group(4)) - Integer.parseInt(fr.group(2));
                } catch (Exception ignored) {
                }
            }
            String n = names.get(d.uniqueId);
            if (n != null) d.name = n;
            list.add(d);
        }
        Collections.sort(list, (a, b) -> Integer.compare(a.id, b.id));
        AppLog.i("VD", "scan -> " + list.size() + " display(s)");
        return list;
    }

    /** 优先 overlay（overlay 型虚拟屏），兜底 virtual（scrcpy 之类第三方建的） */
    public static int find(Context ctx) {
        String out = ShizukuShell.exec(ctx, "dumpsys display").out;
        int overlay = -1;
        int virt = -1;
        Matcher m = DISP.matcher(out);
        while (m.find()) {
            int id;
            try {
                id = Integer.parseInt(m.group(1));
            } catch (Exception e) {
                continue;
            }
            if ("overlay".equals(m.group(2))) {
                if (id > overlay) overlay = id;
            } else if (id > virt) {
                virt = id;
            }
        }
        return overlay >= 0 ? overlay : virt;
    }

    public static int[] physicalSize(Context ctx) {
        String out = ShizukuShell.exec(ctx, "wm size").out;
        Matcher m = Pattern.compile("(\\d+)x(\\d+)").matcher(out);
        if (m.find()) {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        }
        return new int[]{1080, 2340};
    }

    public static int physicalDensity(Context ctx) {
        String out = ShizukuShell.exec(ctx, "wm density").out;
        Matcher m = Pattern.compile("(\\d+)").matcher(out);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return 320;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}

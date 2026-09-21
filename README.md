# VirtualCast · 虚拟投屏

在 Android 设备上**列出所有屏幕（含虚拟屏 / 副屏）→ 抓取指定屏幕的画面 → 在窗口里实时显示 → 反向操控那块屏**。

配合 [Shizuku](https://shizuku.rikka.app/) 使用，**无需 root**。

> 典型场景：手机上同时挂着 scrcpy 之类的虚拟屏、或用系统「强制启用副屏」开出的第二块屏，本工具能把那块屏的画面抓进来看，并在上面启动应用、发返回/Home 等按键、force-stop 应用。

---

## 功能

| 功能 | 说明 |
|---|---|
| 屏幕检测 | 列出设备上所有 display，标注 `displayId`、类型（physical / overlay / virtual）、分辨率、是否活跃 |
| 实时投屏 | 抓取选中屏幕的画面并渲染（`screencap` 并发多路轮询，实测约 3 fps，视设备而定） |
| 触摸注入 | 在投屏窗口上点击/滑动 → 映射坐标 → `input -d <id>` 注入到目标屏 |
| 按键注入 | 返回 / Home / 最近任务 |
| 应用启动器 | 列出设备上所有可启动应用，点一下就在**目标屏**全屏启动（自动解析 launcher activity，避开 `-p` 解析失败） |
| 焦点诊断 | 实时显示 `FocusedDisplayId`，一眼看出"为什么点不动" |
| 外部接口 | 广播控制：退出 / 暂停 / 打开应用列表 / 指定包名启动 |
| 通知栏控制 | 常驻通知提供「退出投屏」「暂停控制」「打开应用」 |

## 环境要求

- Android 8.0+（`minSdk 26`，`targetSdk 34`）
- 已安装并启动 [Shizuku](https://shizuku.rikka.app/)（或用 SUI / root 方案），且已授权本应用
- 目标屏幕必须**已经存在**——本工具不创建虚拟屏，只检测并使用设备上已有的屏幕

## 安装

从 [Releases](../../releases) 下载 `app-debug.apk` 安装：

```bash
adb install -r app-debug.apk
```

## 使用

1. 打开 App → 点 **授权 Shizuku** → 在系统弹窗选「始终允许」
2. 点 **检测所有屏幕** → 列表出现，每块屏一个按钮（如 `投屏并控制 #4 (virtual 800x1280) 活跃`）
3. 点某一块 → 进入投屏窗口：画面实时刷新，点击/滑动即操控那块屏
4. 底部工具条：`返回` `Home` `任务` `应用` `激活` `暂停` `退出投屏`

## 外部控制接口（adb / 自动化）

```bash
# 退出投屏
adb shell am broadcast -a com.vscreen.cast.STOP -p com.vscreen.cast

# 暂停 / 恢复触摸注入
adb shell am broadcast -a com.vscreen.cast.PAUSE -p com.vscreen.cast

# 打开应用列表
adb shell am broadcast -a com.vscreen.cast.APPS -p com.vscreen.cast

# 在目标屏全屏启动指定包名
adb shell am broadcast -a com.vscreen.cast.LAUNCH -p com.vscreen.cast --es pkg com.tencent.mm

# 直接打开某块屏的投屏窗口（App 未运行时）
adb shell am start --display 0 -n com.vscreen.cast/.MainActivity --ei open 4
```

停止应用：`adb shell am force-stop <包名>`

## 构建

需要 JDK 17+ 与 Android SDK（`compileSdk 34` / `build-tools 34.0.0`）。

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 项目结构

```
app/src/main/java/com/vscreen/cast/
├── MainActivity.java     主界面：Shizuku 授权、屏幕检测、进入投屏
├── ViewerActivity.java   投屏窗口：渲染、触摸映射、工具条、应用列表、广播处理
├── ScreenPoller.java     并发 screencap 轮询取帧（多路并发换帧率）
├── VDisplay.java         枚举 display、解析 dumpsys display、读物理屏参数
├── ShizukuShell.java     Shizuku 侧 shell 命令封装（newProcess + 读输出）
├── InputInjector.java    触摸/按键注入（input -d <id> tap/swipe/keyevent）
├── AppList.java          枚举可启动应用、解析 launcher activity、在指定屏启动
├── CastNotif.java        常驻通知（退出 / 暂停 / 打开应用）
├── StopReceiver.java     广播入口
└── AppLog.java           落盘日志（/sdcard/Android/data/com.vscreen.cast/files/vcast.log）
tools/uitap.py            真机自动化调试脚本（按 resource-id 定位并点击）
```

## 技术要点

- **抓副屏只能用 `screencap -d <id>`。** `screenrecord --display-id` 只认物理屏，对虚拟屏直接报 `Invalid physical display id`。
- **`screencap` 单帧约 300~650ms**（视设备与分辨率），所以用**并发 3 路**提交换帧率——实测 3 路约 900ms 出 3 帧，比串行快 2.2 倍。并发数在 `ViewerActivity` 中构造 `ScreenPoller` 时指定。
- **投屏窗口必须设成 `NOT_FOCUSABLE`**（`FLAG_NOT_FOCUSABLE`），否则它会抢走全局输入焦点，注入到目标屏的触摸会被系统全部丢弃。
- **在目标屏启动应用**：`am start --display <id> --windowingMode 1 -n 包名/Activity`。不加 `--windowingMode 1` 会以 freeform 小窗打开；用 `-p <包名>` 对部分应用会 `unable to resolve Intent`，所以先 `cmd package resolve-activity --brief` 解析出 Activity 再精确启动。
- **Android 11+ 包可见性**：`<queries>` 里必须声明 `MAIN`/`LAUNCHER`，否则应用列表为空。
- **`dumpsys display` 解析要非贪婪**：同一行可能出现多个 `uniqueId`，贪婪匹配会认错屏。

## 已知限制

**触摸控制是否生效，取决于目标屏是否持有输入焦点。** 这是 Android 的输入分发规则——全局只有一块屏能持有焦点。

- 若目标屏本身就是活跃的（例如正连着 scrcpy、或是你正在用的外接屏），点击/滑动可正常注入
- 若目标屏空闲，注入的触摸会被系统丢弃，日志会看到 `Focused display #N does not have a focused window`

没有任何 shell 命令能强制切换输入焦点（`am -h`、`cmd activity -h`、`cmd window -h` 里都没有）。投屏窗口里的「激活」按钮和不抢焦点的设计都是为缓解这一点。

> **不受此限制的功能**（都是非触摸方式，稳定可用）：在目标屏**启动任意应用**、`force-stop` 停止应用、返回/Home/最近任务按键。

另外本工具**不创建虚拟屏**——只检测并使用设备上已有的屏幕。

## License

MIT

"""真机 UI 驱动：按 resource-id / text 定位控件并点击中心点。
用法: python uitap.py <serial> <rid|text:xxx> [--no-compressed]
"""
import re
import subprocess
import sys
import time

ADB = r"C:\Android\Sdk\platform-tools\adb.exe"


def sh(serial, cmd):
    return subprocess.run([ADB, "-s", serial, "shell", cmd],
                          capture_output=True).stdout.decode("utf-8", "ignore")


def dump(serial, compressed=True):
    flag = "--compressed " if compressed else ""
    for _ in range(3):
        subprocess.run([ADB, "-s", serial, "shell",
                        f"uiautomator dump {flag}/sdcard/ui.xml"], capture_output=True)
        if "ui.xml" in sh(serial, "ls /sdcard/ui.xml 2>&1"):
            break
    return sh(serial, "cat /sdcard/ui.xml")


def screen_size(serial):
    out = sh(serial, "wm size")
    m = re.search(r"(\d+)x(\d+)", out)
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 1920)


def scroll_up(serial):
    """上滑一屏，把下方内容带进可视区（dump 只反映可见节点）"""
    w, h = screen_size(serial)
    x = w // 2
    sh(serial, f"input swipe {x} {int(h * 0.8)} {x} {int(h * 0.35)} 500")


def parse_bounds(node):
    m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(xml, target):
    nodes = re.findall(r"<node\b[^>]*?/>", xml)
    for n in nodes:
        if target.startswith("text:"):
            ok = f'text="{target[5:]}"' in n
        else:
            ok = f'resource-id="{target}"' in n
        if ok:
            b = parse_bounds(n)
            if b:
                return b, n
    return None, None


def main():
    serial = sys.argv[1]
    target = sys.argv[2]
    compressed = "--no-compressed" not in sys.argv
    xml = dump(serial, compressed)
    pt, node = find(xml, target)
    tries = 0
    while not pt and tries < 3:
        scroll_up(serial)
        time.sleep(1.2)
        xml = dump(serial, compressed)
        pt, node = find(xml, target)
        tries += 1
    if not pt:
        print("NOT_FOUND", target)
        # 打印所有可见候选，便于排查
        for n in re.findall(r"<node\b[^>]*?/>", xml):
            rid = re.search(r'resource-id="([^"]*)"', n)
            tx = re.search(r'text="([^"]*)"', n)
            if tx and tx.group(1):
                print("  cand:", rid.group(1) if rid else "", "|", tx.group(1))
        sys.exit(2)
    print("HIT", pt, (node[:160] if node else ""))
    print(sh(serial, f"input tap {pt[0]} {pt[1]}"))


if __name__ == "__main__":
    main()

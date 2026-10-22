"""
End-to-end checks for XMB Reborn, run against an emulator (never a personal phone: it presses
keys, flips settings and rotates the screen).

    python -I tools/e2e/e2e.py [--serial emulator-5554] [--apk path] [--only name1,name2]

The bar is drawn in GL; these read it through the accessibility tree XmbAccessibility exposes
(one node per visible category icon and row, `selected` on the focused ones) via uiautomator.
Screenshots of failures land in tools/e2e/out/.
"""
import argparse
import os
import re
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET

PKG = "dev.xmbreborn"
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(HERE, "out")
APK = os.path.join(REPO, "app", "build", "outputs", "apk", "lab", "app-lab.apk")


class Fail(Exception):
    pass


def check(cond, msg):
    if not cond:
        raise Fail(msg)


class Node:
    def __init__(self, el):
        self.text = el.get("text", "")
        self.selected = el.get("selected") == "true"
        self.cls = el.get("class", "")
        x1, y1, x2, y2 = map(int, re.findall(r"-?\d+", el.get("bounds", "[0,0][0,0]")))
        self.bounds = (x1, y1, x2, y2)

    @property
    def center(self):
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2

    @property
    def category(self):
        return self.cls == "android.widget.Tab"


class Device:
    def __init__(self, serial, adb):
        self.serial, self.adb = serial, adb

    def run(self, *args, timeout=90, binary=False):
        r = subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True, timeout=timeout)
        return r.stdout if binary else r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")

    def sh(self, cmd, timeout=90):
        return self.run("shell", cmd, timeout=timeout)

    def keys(self, *names, pause=0.0):
        # One shell call per batch: each separate adb call costs ~100 ms.
        self.sh("; ".join(f"input keyevent {n}" for n in names))
        if pause:
            time.sleep(pause)

    def nodes(self):
        for _ in range(3):
            out = self.sh("uiautomator dump /sdcard/xmb_e2e.xml >/dev/null && cat /sdcard/xmb_e2e.xml")
            start = out.find("<?xml")
            if start >= 0:
                root = ET.fromstring(out[start:out.rfind(">") + 1])
                return [Node(el) for el in root.iter("node") if el.get("package") == PKG and el.get("text")]
            time.sleep(0.5)
        raise Fail("uiautomator dump failed")

    def selected(self):
        """(focused category title, focused row title or None)."""
        ns = self.nodes()
        cat = next((n.text for n in ns if n.category and n.selected), None)
        row = next((n.text for n in ns if not n.category and n.selected and n.text != "+"), None)
        return cat, row

    def screenshot(self, name):
        os.makedirs(OUT, exist_ok=True)
        with open(os.path.join(OUT, name + ".png"), "wb") as f:
            f.write(self.run("exec-out", "screencap -p", binary=True))

    def foreground(self):
        out = self.sh("dumpsys activity activities | grep -m1 topResumedActivity")
        return PKG in out

    def launch(self):
        self.sh(f"am start -W -n {PKG}/.MainActivity >/dev/null")
        time.sleep(2.5)

    def home_intent(self):
        """What pressing Home does when XMB is the home app, without changing the HOME role."""
        self.sh(f"am start -a android.intent.action.MAIN -c android.intent.category.HOME -n {PKG}/.MainActivity >/dev/null")
        time.sleep(1.5)

    def go_column(self, title):
        """Walks the bar with the d-pad until [title] is focused."""
        for direction in ("DPAD_LEFT", "DPAD_RIGHT"):
            for _ in range(12):
                if self.selected()[0] == title:
                    return
                self.keys(direction, pause=0.5)
        raise Fail(f"couldn't reach column {title!r}")

    def to_top(self):
        self.keys(*["DPAD_UP"] * 40, pause=1.0)


# --------------------------------------------------------------------------------- tests

def test_tree(d):
    """The bar is visible to accessibility: categories, and rows under the focused one."""
    ns = d.nodes()
    cats = [n.text for n in ns if n.category]
    # Only icons on screen are exposed; in portrait that can be just two.
    check(len(cats) >= 2, f"expected category nodes, got {cats}")
    check(sum(n.selected for n in ns if n.category) == 1, "exactly one category should be selected")
    rows = [n for n in ns if not n.category and n.text != "+"]
    check(rows, "no row nodes")
    check(sum(n.selected for n in rows) == 1, "exactly one row should be selected")


def test_dpad(d):
    """Left/right change column; up/down move the row."""
    d.go_column("Apps")
    d.keys("DPAD_LEFT", pause=0.6)
    check(d.selected()[0] != "Apps", "DPAD_LEFT didn't leave Apps")
    d.keys("DPAD_RIGHT", pause=0.6)
    check(d.selected()[0] == "Apps", "DPAD_RIGHT didn't come back to Apps")
    d.to_top()
    first = d.selected()[1]
    d.keys("DPAD_DOWN", "DPAD_DOWN", pause=0.6)
    second = d.selected()[1]
    check(first != second, "DPAD_DOWN didn't move the row")
    rows = [n.text for n in d.nodes() if not n.category and n.text != "+"]
    check(rows.index(second) - rows.index(first) == 2, f"expected two rows down: {first} -> {second}")


def test_fling(d):
    """A quick flick carries on past where the finger stopped; a slow drag of the same length doesn't."""
    d.go_column("Apps")
    rows = [n.text for n in d.nodes() if not n.category and n.text != "+"]
    check(len(rows) >= 12, "Apps needs a dozen rows for this test")
    d.to_top()
    d.sh("input swipe 600 1800 600 1600 700")
    time.sleep(1.5)
    slow = rows.index(d.selected()[1])
    d.to_top()
    d.sh("input swipe 600 1800 600 1600 60")
    time.sleep(3)
    fast = rows.index(d.selected()[1]) if d.selected()[1] in rows else len(rows)
    check(fast > slow + 2, f"flick went {fast} rows, slow drag {slow}")


def test_fling_catch(d):
    """Touching the screen during a fling stops it without opening anything."""
    d.go_column("Apps")
    rows = [n.text for n in d.nodes() if not n.category and n.text != "+"]
    d.to_top()
    d.sh("input swipe 600 1800 600 1400 40; sleep 0.2; input tap 600 1000")
    time.sleep(2.5)
    check(d.foreground(), "the catch tap opened something")
    row = d.selected()[1]
    check(row in rows and rows.index(row) < len(rows) - 1, f"fling wasn't caught (ended on {row})")


def test_a11y_click(d):
    """Tapping a row's node area focuses it (the same path accessibility clicks use)."""
    d.go_column("Apps")
    d.to_top()
    target = [n for n in d.nodes() if not n.category and n.text != "+" and not n.selected][2]
    d.sh(f"input tap {target.bounds[0] + 40} {target.center[1]}")
    time.sleep(0.8)
    check(d.selected()[1] == target.text, f"tap didn't focus {target.text}")
    check(d.foreground(), "first tap on a row should focus it, not open it")


def test_home(d):
    """Home while home glides back to the top of Apps."""
    d.go_column("Settings")
    d.home_intent()
    cat, row = d.selected()
    rows = [n.text for n in d.nodes() if not n.category and n.text != "+"]
    check(cat == "Apps", f"Home went to {cat}")
    check(row == rows[0], f"Home left the row on {row}, not {rows[0]}")


def setting_value(d, name):
    n = next((n for n in d.nodes() if n.text.startswith(name + ", ")), None)
    check(n is not None, f"setting {name} not found")
    return n.text.split(", ", 1)[1]


def choose_setting(d, name, value):
    """Opens setting [name] with the d-pad and picks [value] from its options."""
    d.go_column("Settings")
    d.to_top()
    for _ in range(30):
        if d.selected()[1].startswith(name):
            break
        d.keys("DPAD_DOWN", pause=0.4)
    else:
        raise Fail(f"no setting {name}")
    d.keys("ENTER", pause=1.2)
    option = next((n for n in d.nodes() if n.text == value), None)
    check(option is not None, f"no option {value!r} for {name}")
    d.sh(f"input tap {option.center[0]} {option.center[1]}")
    time.sleep(1.2)


def test_options_tree(d):
    """A long-press opens options; the tree then shows only its rows, and Back brings the bar back."""
    d.go_column("Apps")
    d.to_top()
    row = next(n for n in d.nodes() if not n.category and n.selected)
    d.sh(f"input motionevent DOWN {row.center[0]} {row.center[1]}; sleep 1; input motionevent UP {row.center[0]} {row.center[1]}")
    time.sleep(1.2)
    labels = [n.text for n in d.nodes() if n.text != "+"]
    check("Open" in labels and "App Info" in labels, f"options not in tree: {labels}")
    check(not any(n.category for n in d.nodes()), "bar still exposed under the panel")
    check(sum(n.selected for n in d.nodes() if n.text != "+") == 1, "one option should be selected")
    d.keys("DPAD_DOWN", pause=0.6)
    check(next(n.text for n in d.nodes() if n.selected and n.text != "+") != "Open", "option focus didn't follow the d-pad")
    d.keys("BACK", pause=1.2)
    check(d.foreground(), "Back left the launcher")
    check(any(n.category for n in d.nodes()), "bar didn't come back after closing")


def test_widget_button_setting(d):
    """Settings > Widget Button Off hides the floating +, and On brings it back."""
    try:
        choose_setting(d, "Widget Button", "Off")
        check(setting_value(d, "Widget Button") == "Off", "setting didn't change to Off")
        check(not any(n.text == "+" for n in d.nodes()), "+ still showing with Widget Button Off")
    finally:
        choose_setting(d, "Widget Button", "On")
    check(any(n.text == "+" for n in d.nodes()), "+ didn't come back")


def test_landscape(d):
    """Rotating keeps the bar working and accessible."""
    before = d.sh("settings get system accelerometer_rotation").strip(), d.sh("settings get system user_rotation").strip()
    d.go_column("Apps")  # the last column, so left always has somewhere to go
    try:
        d.sh("settings put system accelerometer_rotation 0; settings put system user_rotation 1")
        time.sleep(3)
        check(d.foreground(), "rotation lost the launcher")
        cat, row = d.selected()
        check(cat is not None and row is not None, "nothing focused after rotating")
        d.keys("DPAD_LEFT", pause=0.6)
        check(d.selected()[0] != cat, "d-pad dead in landscape")
    finally:
        d.sh(f"settings put system accelerometer_rotation {before[0]}; settings put system user_rotation {before[1]}")
        time.sleep(2.5)


def open_jump_panel(d):
    d.go_column("Apps")
    row = next(n for n in d.nodes() if not n.category and n.selected)
    d.sh(f"input motionevent DOWN {row.center[0]} {row.center[1]}; sleep 1; input motionevent UP {row.center[0]} {row.center[1]}")
    time.sleep(1.2)
    entry = next((n for n in d.nodes() if n.text == "Jump to Letter"), None)
    check(entry is not None, "no Jump to Letter in an app's options")
    d.sh(f"input tap {entry.center[0]} {entry.center[1]}")
    time.sleep(1.4)
    letters = [n for n in d.nodes() if re.fullmatch(r"[A-Z#À-ɏ]", n.text)]
    check(len(letters) >= 2, f"letter panel didn't open: {[n.text for n in d.nodes()]}")
    return letters


def test_jump_to_letter(d):
    """Apps options > Jump to Letter lists the starting letters; choosing one focuses its first app."""
    d.go_column("Apps")
    d.to_top()
    letters = open_jump_panel(d)
    target = letters[len(letters) // 2]
    d.sh(f"input tap {target.center[0]} {target.center[1]}")
    time.sleep(1.2)
    row = d.selected()[1]
    check(row and row.upper().startswith(target.text), f"chose {target.text}, landed on {row}")


def test_letter_keys(d):
    """With a keyboard, typing a letter jumps to it; Page Down skips ten rows."""
    d.go_column("Apps")
    d.to_top()
    rows = [n.text for n in d.nodes() if not n.category and n.text != "+"]
    want = next(t for t in reversed(rows) if t[:1].isalpha())
    d.keys("KEYCODE_" + want[0].upper(), pause=1.0)
    row = d.selected()[1]
    check(row and row[0].upper() == want[0].upper(), f"typed {want[0]}, landed on {row}")
    d.to_top()
    first = d.selected()[1]
    d.keys("PAGE_DOWN", pause=1.0)
    landed = d.selected()[1]
    check(landed != first, "Page Down didn't move")


def test_long_panel_scrolls(d):
    """In landscape the letter panel is too tall to fit; the focused letter stays on screen."""
    before = d.sh("settings get system accelerometer_rotation").strip(), d.sh("settings get system user_rotation").strip()
    try:
        d.sh("settings put system accelerometer_rotation 0; settings put system user_rotation 1")
        time.sleep(3)
        d.to_top()
        letters = open_jump_panel(d)
        height = int(re.search(r"(\d+)x(\d+)", d.sh("wm size")).group(1))  # natural portrait width = landscape height
        d.keys(*["DPAD_DOWN"] * 30, pause=1.5)
        focused = next((n for n in d.nodes() if n.selected and n.text != "+"), None)
        check(focused is not None, "no focused letter")
        check(focused.bounds[3] <= height + 4 and focused.bounds[1] >= 0, f"focused letter off screen: {focused.bounds} (height {height})")
        d.keys("BACK", pause=1.0)
    finally:
        d.sh(f"settings put system accelerometer_rotation {before[0]}; settings put system user_rotation {before[1]}")
        time.sleep(2.5)


BACKUP_NAME = "xmbreborn-backup.json"


def backup_files(d):
    """The picker opens wherever it was last used, so look everywhere it might have saved."""
    return d.sh(f"find /sdcard/ -name '{BACKUP_NAME}' 2>/dev/null").split()


def remove_backups(d):
    d.sh(f"find /sdcard/ -name '{BACKUP_NAME}' -delete 2>/dev/null")


def picker_tap(d, text, seconds=8):
    """Taps the system file picker's node with [text] (DocumentsUI isn't our package)."""
    end = time.time() + seconds
    while time.time() < end:
        out = d.sh("uiautomator dump /sdcard/xmb_e2e.xml >/dev/null && cat /sdcard/xmb_e2e.xml")
        m = re.search(r'text="' + re.escape(text) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', out)
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            d.sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
            return
        time.sleep(0.7)
    raise Fail(f"file picker has no {text!r}")


def choose_backup(d, entry):
    d.go_column("Settings")
    d.to_top()
    for _ in range(30):
        if (d.selected()[1] or "").startswith("Backup"):
            break
        d.keys("DPAD_DOWN", pause=0.4)
    d.keys("ENTER", pause=1.2)
    n = next((n for n in d.nodes() if n.text == entry), None)
    check(n is not None, f"no {entry} in Backup")
    d.sh(f"input tap {n.center[0]} {n.center[1]}")
    time.sleep(2.5)


def test_backup_round_trip(d):
    """Save Backup writes a file; after changing a setting, Restore Backup puts it back."""
    remove_backups(d)
    try:
        choose_setting(d, "Widget Button", "On")
        choose_backup(d, "Save Backup")
        picker_tap(d, "SAVE")
        time.sleep(2)
        files = backup_files(d)
        check(len(files) == 1, f"expected one saved backup, found {files}")
        saved = d.sh(f"cat '{files[0]}'")
        check('"app": "XMB Reborn"' in saved and '"widgetButton": true' in saved, f"backup file wrong: {saved[:300]}")
        choose_setting(d, "Widget Button", "Off")
        check(setting_value(d, "Widget Button") == "Off", "couldn't change the setting")
        choose_backup(d, "Restore Backup")
        picker_tap(d, BACKUP_NAME)
        time.sleep(2.5)
        check(d.foreground(), "didn't come back from the picker")
        check(setting_value(d, "Widget Button") == "On", "restore didn't bring the setting back")
        check(any(n.text == "+" for n in d.nodes()), "restored setting didn't take effect")
    finally:
        remove_backups(d)
        if not d.foreground():
            d.launch()
        if setting_value(d, "Widget Button") != "On":
            choose_setting(d, "Widget Button", "On")


def clock_patch(d):
    """Mean brightness where the PS3 date bar sits (top right, under the status bar)."""
    raw = d.run("exec-out", "screencap", binary=True)
    w, h = int.from_bytes(raw[0:4], "little"), int.from_bytes(raw[4:8], "little")
    head = len(raw) - w * h * 4
    total = count = 0
    for y in range(int(h * 0.035), int(h * 0.05)):
        row = head + y * w * 4
        for x in range(int(w * 0.6), int(w * 0.95), 4):
            i = row + x * 4
            total += raw[i] + raw[i + 1] + raw[i + 2]
            count += 3
    return total / count


def test_clock_setting(d):
    """The date-and-time bar is off by default; Settings > Date and Time brings it back."""
    d.go_column("Settings")
    check(setting_value(d, "Date and Time") == "Off", "Date and Time should default to Off")
    off = clock_patch(d)
    try:
        choose_setting(d, "Date and Time", "On")
        time.sleep(1)
        on = clock_patch(d)
        check(on > off + 8, f"no clock bar after turning it on (brightness {off:.1f} -> {on:.1f})")
    finally:
        choose_setting(d, "Date and Time", "Off")


# Any small launchable APK works; TSF Reborn's test app is the one on hand.
FIXTURE_APK = os.environ.get("XMB_FIXTURE_APK", os.path.join(os.path.dirname(REPO), "TSFReborn", "testkit",
                             "build", "outputs", "apk", "debug", "testkit-debug.apk"))
FIXTURE_PKG, FIXTURE_TITLE = "dev.tsfreborn.testkit", "TSF Testkit"


def apps_rows_near_end(d):
    d.go_column("Apps")
    d.keys(*["DPAD_DOWN"] * 60, pause=1.2)
    return [n.text for n in d.nodes() if not n.category and n.text != "+"]


def wait_for(d, cond, seconds=8):
    end = time.time() + seconds
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.7)
    return False


def test_apps_live(d):
    """Uninstalling an app takes it out of Apps while home is showing; installing brings it back."""
    if not os.path.exists(FIXTURE_APK):
        print("  (skipped: no fixture APK; set XMB_FIXTURE_APK)")
        return
    if FIXTURE_PKG not in d.sh("pm list packages " + FIXTURE_PKG):
        d.run("install", "-r", FIXTURE_APK, timeout=300)
    try:
        check(wait_for(d, lambda: FIXTURE_TITLE in apps_rows_near_end(d)), f"{FIXTURE_TITLE} not listed to start with")
        d.sh("pm uninstall " + FIXTURE_PKG)
        check(wait_for(d, lambda: FIXTURE_TITLE not in apps_rows_near_end(d)), "uninstalled app still listed")
    finally:
        d.run("install", "-r", FIXTURE_APK, timeout=300)
    check(wait_for(d, lambda: FIXTURE_TITLE in apps_rows_near_end(d)), "reinstalled app didn't come back")


def test_no_crash(d):
    """Nothing in the app crashed during the run."""
    log = d.sh(f"logcat -d -b crash")
    check(PKG not in log, "crash buffer mentions the app:\n" + log[-1500:])


TESTS = [test_tree, test_options_tree, test_dpad, test_fling, test_fling_catch, test_a11y_click, test_home,
         test_widget_button_setting, test_jump_to_letter, test_letter_keys, test_long_panel_scrolls, test_backup_round_trip, test_clock_setting, test_apps_live, test_landscape, test_no_crash]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--apk", default=APK)
    ap.add_argument("--only")
    ap.add_argument("--no-install", action="store_true")
    a = ap.parse_args()
    if not a.serial.startswith("emulator-"):
        sys.exit("refusing to run on a non-emulator device")
    adb = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
    if not os.path.exists(adb):
        adb = "adb"
    d = Device(a.serial, adb)
    if not a.no_install:
        print(d.run("install", "-r", a.apk, timeout=300).strip().splitlines()[-1])
    d.sh("logcat -b crash -c")
    d.launch()
    tests = [t for t in TESTS if not a.only or t.__name__.removeprefix("test_") in a.only.split(",")]
    failed = 0
    for t in tests:
        name = t.__name__.removeprefix("test_")
        start = time.time()
        try:
            if not d.foreground():
                d.launch()
            t(d)
            print(f"PASS {name} ({time.time() - start:.1f}s)")
        except Exception as e:
            failed += 1
            d.screenshot("fail_" + name)
            print(f"FAIL {name}: {e}")
            if not isinstance(e, Fail):
                traceback.print_exc()
    print(f"{len(tests) - failed}/{len(tests)} passed")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

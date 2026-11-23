#!/usr/bin/env python3
"""Check Android System UI screenshot capture against a signed mockRelease on an owned emulator."""
import argparse
from io import BytesIO
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET

from PIL import Image, ImageStat

PACKAGE = "ru.finassist.pf.mock"
SCREENSHOTS = "/sdcard/Pictures/Screenshots"
SCREEN_TITLE = "Операции"
FIXTURE_ROW = "Barsik 29"


def capture(adb, avd_name, output):
    if output.exists():
        raise ValueError("Choose a new output path for this capture.")
    if adb("emu", "avd", "name").decode().partition("\n")[0].strip() != avd_name:
        raise ValueError("The device is not the requested test AVD.")
    package = adb("shell", "dumpsys", "package", PACKAGE).decode()
    if not re.search(r"^\s*versionName=\S+-mock\s*$", package, re.MULTILINE):
        raise ValueError("Install the locally signed mockRelease before running this check.")
    flags = re.findall(r"^\s*(?:flags|pkgFlags)=\[([^]]*)\]", package, re.MULTILINE)
    if not flags or any("DEBUGGABLE" in entry.split() for entry in flags):
        raise ValueError("The check requires a non-debuggable mock APK.")

    hierarchy = f"/sdcard/pf-release-{uuid.uuid4().hex}.xml"
    try:
        adb("shell", "uiautomator", "dump", hierarchy)
        root = ET.fromstring(adb("exec-out", "cat", hierarchy))
    finally:
        adb("shell", "rm", "-f", hierarchy)
    labels = {
        node.get("text"): node.get("bounds", "") for node in root.iter("node")
        if node.get("package") == PACKAGE
    }
    if SCREEN_TITLE not in labels or FIXTURE_ROW not in labels:
        raise ValueError(f"Open «{SCREEN_TITLE}» with the visible mock operation «{FIXTURE_ROW}».")
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", labels[FIXTURE_ROW])
    if bounds is None:
        raise ValueError("Could not locate the fixture operation in the app window.")

    def screenshots():
        return set(adb("shell", f"if [ -d {SCREENSHOTS} ]; then ls -1 {SCREENSHOTS}; fi").decode().splitlines())

    previous = screenshots()
    adb("shell", "input", "keyevent", "KEYCODE_SYSRQ")
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        new = screenshots() - previous
        if new:
            break
        time.sleep(1)
    else:
        raise ValueError("Android System UI did not save a new screenshot.")

    if len(new) != 1:
        raise ValueError("Expected one new system screenshot on the test emulator.")
    name = new.pop()
    if not re.fullmatch(r"Screenshot_[A-Za-z0-9_.-]+\.png", name):
        raise ValueError("Unexpected system screenshot filename.")
    data = adb("exec-out", "cat", f"{SCREENSHOTS}/{name}")
    with Image.open(BytesIO(data)) as shot:
        x1, y1, x2, y2 = map(int, bounds.groups())
        if shot.format != "PNG" or not (x1 < x2 <= shot.width and y1 < y2 <= shot.height):
            raise ValueError("The screenshot must contain the app viewport.")
        text_region = shot.convert("RGB").crop((x1, y1, x2, y2))
        # A blank or obscured app surface does not contain the contrast of the operation title.
        if max(ImageStat.Stat(text_region).stddev) < 10:
            raise ValueError("The saved screenshot does not show the fixture operation.")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="ADB serial of the owned emulator")
    parser.add_argument("--avd", required=True, help="Expected AVD name")
    parser.add_argument("output", type=Path, help="New path for the System UI PNG")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("--serial must identify an Android emulator")

    deadline = time.monotonic() + 60

    def adb(*command):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("Release screenshot check exceeded its 60 s deadline.")
        return subprocess.run(
            ["adb", "-s", args.serial, *command],
            capture_output=True, check=True, timeout=min(remaining, 20),
        ).stdout

    try:
        capture(adb, args.avd, args.output)
    except (OSError, ValueError, ET.ParseError, subprocess.SubprocessError) as error:
        print(f"Release screenshot check failed: {error}", file=sys.stderr)
        return 1
    print(f"System screenshot shows the fixture operation: {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

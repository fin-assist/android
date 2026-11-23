import importlib.util
from io import BytesIO
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

script = Path(__file__).with_name("check-release-screenshot.py")
spec = importlib.util.spec_from_file_location("check_release_screenshot", script)
check_release_screenshot = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_release_screenshot)

AVD = "Plainfin_Test"
SCREENSHOT = "Screenshot_test.png"


def emulator(*, avd=AVD, flags="HAS_CODE", row=True, shots=(SCREENSHOT,), png=b""):
    """ADB substitute. System UI adds the supplied files after the screenshot key."""
    taken = False

    def adb(*command):
        nonlocal taken
        if command == ("emu", "avd", "name"):
            return f"{avd}\nOK\n".encode()
        if command == ("shell", "dumpsys", "package", check_release_screenshot.PACKAGE):
            return f"  flags=[ {flags} ]\n  pkgFlags=[ HAS_CODE ]\n  versionName=1.1.0-mock\n".encode()
        if command[:3] in (("shell", "uiautomator", "dump"), ("shell", "rm", "-f")):
            return b""
        if command[:2] == ("exec-out", "cat"):
            if command[2].endswith(".xml"):
                operation = '<node package="ru.finassist.pf.mock" text="Barsik 29" bounds="[4,4][24,20]"/>' if row else ""
                return ('<hierarchy><node package="ru.finassist.pf.mock" text="Операции"/>' +
                        operation + '</hierarchy>').encode()
            return png
        if command == ("shell", "input", "keyevent", "KEYCODE_SYSRQ"):
            taken = True
            return b""
        if command[0] == "shell" and command[1].startswith("if [ -d "):
            names = ("Screenshot_previous.png",) + (shots if taken else ())
            return "\n".join(names).encode()
        raise AssertionError(f"Unexpected adb command: {command}")

    return adb


class ReleaseScreenshotTest(unittest.TestCase):
    def setUp(self):
        directory = TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.output = Path(directory.name) / "capture.png"

    def refuses(self, message, **emulator_options):
        with self.assertRaisesRegex(ValueError, message):
            check_release_screenshot.capture(emulator(**emulator_options), AVD, self.output)
        self.assertFalse(self.output.exists())

    def test_refuses_other_avd(self):
        self.refuses("not the requested test AVD", avd="Foreign_Emulator")

    def test_refuses_debuggable_apk(self):
        self.refuses("requires a non-debuggable mock APK", flags="DEBUGGABLE HAS_CODE")

    def test_requires_visible_fixture_row(self):
        self.refuses("visible mock operation", row=False)

    def test_requires_exactly_one_new_screenshot(self):
        self.refuses("Expected one new system screenshot", shots=(SCREENSHOT, "Screenshot_unrelated.png"))

    @unittest.skipUnless(importlib.util.find_spec("PIL"), "Pillow is required for PNG validation")
    def test_pixel_oracle_rejects_uniform_png_and_accepts_the_visible_row(self):
        from PIL import Image, ImageDraw

        image = Image.new("RGB", (32, 32), "white")

        def encode_png():
            output = BytesIO()
            image.save(output, format="PNG")
            return output.getvalue()

        self.refuses("does not show the fixture operation", png=encode_png())
        ImageDraw.Draw(image).rectangle((6, 6, 14, 14), fill="black")
        png = encode_png()
        check_release_screenshot.capture(emulator(png=png), AVD, self.output)
        self.assertEqual(self.output.read_bytes(), png)


if __name__ == "__main__":
    unittest.main()

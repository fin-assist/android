"""Regression tests for the checks run before publishing the store artifact."""

import base64
from datetime import date, timedelta
import importlib.util
from pathlib import Path
import os
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SCRIPTS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("check_store_release", SCRIPTS / "check-store-release.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)

PIN1 = base64.b64encode(b"a" * 32).decode()
PIN2 = base64.b64encode(b"b" * 32).decode()
EXPIRATION = (date.today() + timedelta(days=30)).isoformat()
BADGING = """package: name='ru.finassist.pf' versionCode='2' versionName='1.0.1' platformBuildVersionName='16'
sdkVersion:'26'
targetSdkVersion:'36'
"""
RESOURCES = """  resource 0x7f100001 xml/network_security_config
    () (file) res/k9.xml type=XML
"""
MANIFEST = """E: manifest (line=2)
  E: application (line=17)
    A: http://schemas.android.com/apk/res/android:usesCleartextTraffic(0x010104ec)=false
    A: http://schemas.android.com/apk/res/android:networkSecurityConfig(0x01010527)=@0x7f100001
"""
CONFIG = f"""E: network-security-config (line=3)
  E: base-config (line=4)
    A: cleartextTrafficPermitted=false
  E: domain-config (line=5)
    A: cleartextTrafficPermitted=false
    E: domain (line=6)
      A: includeSubdomains=false
      T: 'api.plainfin.ru'
    E: pin-set (line=7)
      A: expiration="{EXPIRATION}" (Raw: "{EXPIRATION}")
      E: pin (line=8)
        A: digest="SHA-256" (Raw: "SHA-256")
        T: '{PIN1}'
      E: pin (line=9)
        A: digest="SHA-256" (Raw: "SHA-256")
        T: '{PIN2}'
"""


class PackageVerificationTest(unittest.TestCase):
    def check_release(self, *, badging=BADGING, manifest=MANIFEST, config=CONFIG, expiration=EXPIRATION):
        dumps = {
            ("badging", "sample.apk"): badging,
            ("resources", "sample.apk"): RESOURCES,
            ("xmltree", "sample.apk", "--file", "AndroidManifest.xml"): manifest,
            ("xmltree", "sample.apk", "--file", "res/k9.xml"): config,
        }
        env = {
            "PF_VERSION_NAME": "1.0.1",
            "PF_VERSION_CODE": "2",
            "PF_API_PINS": f"sha256/{PIN1},sha256/{PIN2}",
            "PF_API_PINS_EXPIRATION": expiration,
        }
        with patch.dict(os.environ, env), patch.object(
            checker.subprocess, "check_output", side_effect=lambda cmd, **kwargs: dumps[tuple(cmd[2:])]
        ):
            checker.check("aapt2", "sample.apk")

    def test_accepts_pinned_production_package_with_shortened_resource_path(self):
        self.check_release()

    def test_rejects_unexpected_packaged_host_pins_or_expiration(self):
        for actual in (
            CONFIG.replace("api.plainfin.ru", "stage.plainfin.ru"),
            CONFIG[:CONFIG.index("      E: pin (line=9)")],
            CONFIG + f"      E: pin (line=10)\n        A: digest=\"SHA-256\"\n        T: '{PIN1}'\n",
            CONFIG.replace(PIN2, PIN1),
            CONFIG.replace(EXPIRATION, "2030-01-01"),
        ):
            with self.subTest(actual=actual), self.assertRaisesRegex(ValueError, "Packaged host|Wrong pin count"):
                self.check_release(config=actual)

    def test_rejects_expired_expected_configuration(self):
        yesterday = (date.today() - timedelta(days=1)).isoformat()
        with self.assertRaisesRegex(ValueError, "unexpired FIN-121 pins"):
            self.check_release(config=CONFIG.replace(EXPIRATION, yesterday), expiration=yesterday)

    def test_rejects_weakened_network_policy(self):
        for actual, message in (
            (CONFIG.replace("cleartextTrafficPermitted=false", "cleartextTrafficPermitted=true", 1), "forbid cleartext"),
            (CONFIG.replace("includeSubdomains=false", "includeSubdomains=true"), "pin only the API host"),
            (CONFIG + "  E: debug-overrides (line=10)\n", "Unexpected trust overrides"),
            (CONFIG + "  E: trust-anchors (line=10)\n", "Unexpected trust overrides"),
        ):
            with self.subTest(message=message), self.assertRaisesRegex(ValueError, message):
                self.check_release(config=actual)

    def test_rejects_wrong_package_version_privileged_manifest_and_sdk(self):
        for options, message in (
            ({"badging": BADGING.replace("ru.finassist.pf", "ru.finassist.pf.mock")}, "Package or version"),
            ({"badging": BADGING.replace("versionCode='2'", "versionCode='3'")}, "Package or version"),
            ({"badging": BADGING.replace("targetSdkVersion:'36'", "targetSdkVersion:'35'")}, "SDK levels"),
            ({"badging": BADGING + "application-debuggable\n"}, "debuggable"),
            ({"manifest": MANIFEST.replace("@0x7f100001", "@0x7f100011")}, "does not reference"),
            ({"manifest": MANIFEST + '    A: name="ru.finassist.pf.e2e.E2eControlActivity"\n'}, "Test hooks"),
        ):
            with self.subTest(options=options), self.assertRaisesRegex(ValueError, message):
                self.check_release(**options)


# Stub only the external signing commands. The actual shell script evaluates both certificate
# digests and the test observes that it stops before bundletool or creation of upload files.
FAKE_SIGNER = r"""#!/usr/bin/env bash
set -euo pipefail
case "${0##*/}:$1" in
  zipalign:*) ;;
  apksigner:sign) ;;
  apksigner:verify)
    echo 'Verified using v2 scheme (APK Signature Scheme v2): true'
    printf 'Signer #1 certificate SHA-256 digest: %s\n' "$TEST_APK_SIGNER"
    ;;
  jarsigner:-keystore) ;;
  jarsigner:-J-Duser.language=en) echo 'jar verified.' ;;
  keytool:*) printf 'SHA256: %s\n' "$TEST_AAB_SIGNER" ;;
  java:*) echo 'Verification continued after a wrong signer' >&2; exit 99 ;;
  *) exit 98 ;;
esac
"""


class SigningVerificationTest(unittest.TestCase):
    def test_stops_before_upload_when_either_certificate_differs(self):
        correct = "a" * 64
        wrong = "b" * 64
        for apk_signer, aab_signer in ((wrong, correct), (correct, wrong)):
            with self.subTest(apk_signer=apk_signer), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                stub = root / "stub"
                stub.write_text(FAKE_SIGNER)
                stub.chmod(0o700)
                for command in ("apksigner", "zipalign", "jarsigner", "keytool", "java"):
                    (root / command).symlink_to(stub)
                output = root / "upload"
                env = os.environ | {
                    "PATH": f"{root}{os.pathsep}{os.environ['PATH']}",
                    "BUILD_TOOLS": str(root),
                    "PF_STORE_KEYSTORE_BASE64": base64.b64encode(b"fake keystore").decode(),
                    "PF_STORE_KEYSTORE_PASSWORD": "fake",
                    "PF_STORE_SIGNER_SHA256": correct,
                    "PF_VERSION_NAME": "1.0.1",
                    "PF_VERSION_CODE": "2",
                    "TEST_APK_SIGNER": apk_signer,
                    "TEST_AAB_SIGNER": aab_signer,
                }
                result = subprocess.run(
                    ["bash", str(SCRIPTS / "sign-store-release.sh"), str(root), "fake.jar", str(output)],
                    env=env, text=True, capture_output=True, timeout=10,
                )
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertIn("must each be signed with the expected FIN-121 certificate", result.stderr)
                self.assertFalse(output.exists())

#!/usr/bin/env python3
"""Check the packaged identity, SDK levels and FIN-121 pin-set of both store builds."""

from datetime import date
import os
from pathlib import Path
import re
import subprocess
import sys
import tomllib


def require(condition, message: str) -> None:
    if not condition:
        raise ValueError(message)


def check(aapt2: str, apk: str) -> None:
    def dump(*args: str) -> str:
        return subprocess.check_output([aapt2, "dump", *args], text=True, timeout=30)

    version = os.environ["PF_VERSION_NAME"]
    code = os.environ["PF_VERSION_CODE"]
    expiration = os.environ["PF_API_PINS_EXPIRATION"].strip()
    pins = re.split(r"[,;\s]+", os.environ["PF_API_PINS"].strip())
    require(len(pins) >= 2 and len(set(pins)) == len(pins) and date.fromisoformat(expiration) > date.today(),
            "Expected distinct, unexpired FIN-121 pins")
    sdk = tomllib.loads(Path("gradle/libs.versions.toml").read_text())["versions"]
    badging = dump("badging", apk)
    require(f"package: name='ru.finassist.pf' versionCode='{code}' versionName='{version}'" in badging,
            "Package or version differs from the requested release")
    require(re.search(rf"(?m)^(?:minSdkVersion|sdkVersion):'{sdk['minSdk']}'$", badging)
            and f"targetSdkVersion:'{sdk['targetSdk']}'" in badging, "SDK levels differ from the version catalog")
    require("application-debuggable" not in badging, "Release is debuggable")

    resources = dump("resources", apk)
    resource = re.search(r"(?m)^\s*resource (0x[0-9a-f]+) xml/network_security_config\s*\n"
                         r"\s*\(\) \(file\) (\S+) type=XML", resources)
    require(resource, "Missing network security config resource")
    resource_id, resource_path = resource.groups()
    manifest = dump("xmltree", apk, "--file", "AndroidManifest.xml")
    require(re.search(r"networkSecurityConfig\(0x[0-9a-f]+\)=@" + resource_id + r"\b", manifest),
            "Manifest does not reference the pinning resource")
    require(re.search(r"usesCleartextTraffic\(0x[0-9a-f]+\)=false\b", manifest), "Cleartext is not disabled")
    require("E2eControlActivity" not in manifest and ":testOnly(" not in manifest, "Test hooks in release manifest")

    xml = dump("xmltree", apk, "--file", resource_path)
    require(re.findall(r"T: '([^']+)'", xml) == ["api.plainfin.ru", *(pin.removeprefix("sha256/") for pin in pins)],
            "Packaged host or pins differ from FIN-121 release configuration")
    require(xml.count("E: pin (") == len(pins) and xml.count('digest="SHA-256"') == len(pins)
            and f'expiration="{expiration}"' in xml, "Wrong pin count, digest algorithm or expiration")
    require(re.findall(r"cleartextTrafficPermitted=(\w+)", xml) == ["false", "false"]
            and re.findall(r"includeSubdomains=(\w+)", xml) == ["false"],
            "Network security config must forbid cleartext and pin only the API host")
    require("debug-overrides" not in xml and "trust-anchors" not in xml, "Unexpected trust overrides")
    print(f"{Path(apk).name}: ru.finassist.pf {version}+{code}, minSdk {sdk['minSdk']}, "
          f"targetSdk {sdk['targetSdk']}, {len(pins)} FIN-121 pins until {expiration}")


if __name__ == "__main__":
    aapt2, *apks = sys.argv[1:]
    for apk_path in apks:
        try:
            check(aapt2, apk_path)
        except (ValueError, KeyError, OSError, subprocess.SubprocessError) as exc:
            sys.exit(f"{Path(apk_path).name}: {exc}")

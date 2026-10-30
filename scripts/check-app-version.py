#!/usr/bin/env python3
"""Check release branch/version agreement and, optionally, generated Android versions (FIN-377)."""
import argparse
import os
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"


def app_version(source: str) -> str:
    values = re.findall(r'^val appVersionName = "(' + VERSION + r')"\s*$', source, re.MULTILINE)
    if len(values) != 1:
        raise ValueError("expected exactly one literal appVersionName in app/build.gradle.kts")
    return values[0]


def check_branches(version: str, branches: list[str]) -> None:
    # PR target, PR source (release → main), and push ref are all checked. No Git/network access is needed.
    for branch in branches:
        if branch.startswith("release/"):
            expected = branch.removeprefix("release/")
            if not re.fullmatch(VERSION, expected):
                raise ValueError(f"invalid release branch: {branch!r}; expected release/X.Y.Z")
            if version != expected:
                raise ValueError(f"{branch} requires appVersionName {expected}, found {version}")


def check_artifacts(version: str, suffix: str, manifest: str, build_config: str) -> None:
    expected = version + suffix
    actual = ET.fromstring(manifest).get(ANDROID + "versionName")
    if actual != expected:
        raise ValueError(f"manifest versionName: expected {expected!r}, found {actual!r}")
    for field, value in (("VERSION_NAME", expected), ("CLIENT_VERSION_NAME", version)):
        values = re.findall(r'public static final String ' + field + r' = "([^"]*)";', build_config)
        if values != [value]:
            raise ValueError(f"BuildConfig.{field}: expected {value!r}, found {values!r}")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path("app/build.gradle.kts"))
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--build-config", type=Path)
    parser.add_argument("--suffix", default="")
    args = parser.parse_args(argv)
    if bool(args.manifest) != bool(args.build_config):
        parser.error("--manifest and --build-config must be supplied together")
    try:
        version = app_version(args.source.read_text(encoding="utf-8"))
        check_branches(version, [os.environ.get(key, "") for key in
                                 ("GITHUB_BASE_REF", "GITHUB_HEAD_REF", "GITHUB_REF_NAME")])
        if args.manifest:
            check_artifacts(version, args.suffix, args.manifest.read_text(encoding="utf-8"),
                            args.build_config.read_text(encoding="utf-8"))
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"App version check failed: {error}", file=sys.stderr)
        return 1
    print(f"App version {version}: release context and supplied artifacts agree")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

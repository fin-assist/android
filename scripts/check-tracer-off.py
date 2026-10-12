#!/usr/bin/env python3
"""Checks that a build without a Tracer app token cannot start the SDK.

Tracer starts from components it merges into the app manifest (today one content provider). Without
`pf.tracerAppToken` they must all be disabled (`android:enabled="false"`), otherwise the SDK would start
without a token — collecting data or failing with «Tracer is not initialized». A Tracer update that adds or
renames a component makes this fail in CI instead of shipping silently (README, «Крэши и ANR: Tracer»).

Usage: scripts/check-tracer-off.py <merged AndroidManifest.xml>...
"""
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
COMPONENTS = ("provider", "service", "receiver", "activity", "activity-alias")
PACKAGE = "ru.ok.tracer."


def enabled_tracer_components(manifest_xml: str) -> list[str]:
    """Names of Tracer components the manifest leaves enabled."""
    root = ET.fromstring(manifest_xml)
    application = root.find("application")
    if application is None:
        return []
    found = []
    for tag in COMPONENTS:
        for element in application.findall(tag):
            name = element.get(ANDROID + "name", "")
            if name.startswith(PACKAGE) and element.get(ANDROID + "enabled", "true") != "false":
                found.append(f"<{tag}> {name}")
    return found


def main(paths: list[str]) -> int:
    if not paths:
        print(__doc__)
        return 2
    failed = False
    for path in paths:
        with open(path, encoding="utf-8") as f:
            enabled = enabled_tracer_components(f.read())
        for component in enabled:
            print(f"{path}: Tracer component enabled without a token: {component}")
        failed = failed or bool(enabled)
    if not failed:
        print(f"Tracer is off in {len(paths)} manifest(s)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

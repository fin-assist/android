#!/usr/bin/env python3
"""Checks AppMetrica isolation in merged Android manifests.

Without an API key the service must be disabled. Any newly enabled AppMetrica
component in a keyless build makes this check fail.

Usage: scripts/check-appmetrica-off.py [--absent] <merged AndroidManifest.xml>...
"""
import argparse
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
COMPONENTS = ("provider", "service", "receiver", "activity", "activity-alias")
PACKAGE = "io.appmetrica.analytics."


def appmetrica_components(manifest_xml: str, *, include_disabled: bool = False) -> list[str]:
    """Names of enabled AppMetrica components, or all components when checking absence."""
    root = ET.fromstring(manifest_xml)
    application = root.find("application")
    if application is None:
        return []
    found = []
    for tag in COMPONENTS:
        for element in application.findall(tag):
            name = element.get(ANDROID + "name", "")
            enabled = element.get(ANDROID + "enabled", "true") != "false"
            if name.startswith(PACKAGE) and (enabled or include_disabled):
                found.append(f"<{tag}> {name}")
    return found


def main(paths: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--absent", action="store_true", help="also reject disabled AppMetrica components")
    parser.add_argument("manifests", nargs="+")
    args = parser.parse_args(paths)

    failed = False
    for path in args.manifests:
        with open(path, encoding="utf-8") as f:
            components = appmetrica_components(f.read(), include_disabled=args.absent)
        for component in components:
            print(f"{path}: unexpected AppMetrica component: {component}")
        failed = failed or bool(components)
    if not failed:
        print(f"AppMetrica isolation verified in {len(args.manifests)} manifest(s)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

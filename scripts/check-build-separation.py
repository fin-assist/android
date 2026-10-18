#!/usr/bin/env python3
"""Checks that test hooks and cleartext HTTP stay where they belong (FIN-120, AGENTS.md «Security»).

`release` — merged manifests of builds that may reach users (prodRelease, localRelease): no e2e or mock hooks
(`ru.finassist.pf.e2e.*`, the `pfe2e` control link, the document picker override), not debuggable, cleartext
explicitly off and no network security config, so no exception to it either.

`local-debug` — the merged manifest of localDebug and its network security config: no test hooks; the config is the
app's own, forbids cleartext by default and allows it to a single host, without subdomains, user CAs or debug
overrides.

Usage:
  scripts/check-build-separation.py release <merged AndroidManifest.xml>...
  scripts/check-build-separation.py local-debug <merged AndroidManifest.xml> <network_security_config.xml>
"""
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
# Markers of the mock e2e build (app/src/mockE2e) and of the fake backend, in any attribute of any element.
HOOK_MARKERS = ("ru.finassist.pf.e2e", "ru.finassist.pf.mock", "pfe2e", "DOCUMENT_PICKER_OVERRIDE")
NSC_REF = "@xml/network_security_config"


def test_hooks(root: ET.Element) -> list[str]:
    """Attributes of the manifest that point at e2e or mock code."""
    found = []
    for element in root.iter():
        for name, value in element.attrib.items():
            if any(marker in value for marker in HOOK_MARKERS):
                found.append(f"<{element.tag}> {name.replace(ANDROID, 'android:')}={value}")
    return found


def release_problems(manifest_xml: str) -> list[str]:
    root = ET.fromstring(manifest_xml)
    problems = [f"test hook: {hook}" for hook in test_hooks(root)]
    application = root.find("application")
    if application is None:
        return problems + ["no <application>"]
    if application.get(ANDROID + "debuggable") == "true":
        problems.append("android:debuggable is true")
    if application.get(ANDROID + "usesCleartextTraffic") != "false":
        problems.append("android:usesCleartextTraffic is not explicitly false")
    if application.get(ANDROID + "networkSecurityConfig") is not None:
        problems.append("a network security config (it may allow cleartext) in a release build")
    return problems


def local_debug_problems(manifest_xml: str, nsc_xml: str) -> list[str]:
    root = ET.fromstring(manifest_xml)
    problems = [f"test hook: {hook}" for hook in test_hooks(root)]
    application = root.find("application")
    if application is None or application.get(ANDROID + "networkSecurityConfig") != NSC_REF:
        problems.append(f"the manifest does not use {NSC_REF}")
    config = ET.fromstring(nsc_xml)
    base = config.find("base-config")
    if base is None or base.get("cleartextTrafficPermitted") != "false":
        problems.append("base-config does not forbid cleartext")
    if config.find("debug-overrides") is not None or config.find(".//trust-anchors") is not None:
        problems.append("custom trust anchors or debug overrides")
    hosts = []
    for domain_config in config.iter("domain-config"):
        if domain_config.get("cleartextTrafficPermitted") != "true":
            continue
        if domain_config.find("domain-config") is not None:
            problems.append("nested domain-config")
        for domain in domain_config.findall("domain"):
            if domain.get("includeSubdomains") == "true":
                problems.append(f"cleartext to subdomains of {domain.text}")
            hosts.append((domain.text or "").strip())
    if len(hosts) > 1:
        problems.append(f"cleartext to more than one host: {', '.join(hosts)}")
    return problems


def read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def report(path: str, problems: list[str]) -> bool:
    for problem in problems:
        print(f"{path}: {problem}")
    return bool(problems)


def main(args: list[str]) -> int:
    if len(args) >= 2 and args[0] == "release":
        failed = False
        for path in args[1:]:
            failed = report(path, release_problems(read(path))) or failed
        if not failed:
            print(f"No test hooks or cleartext in {len(args) - 1} release manifest(s)")
        return 1 if failed else 0
    if len(args) == 3 and args[0] == "local-debug":
        failed = report(args[1], local_debug_problems(read(args[1]), read(args[2])))
        if not failed:
            print("localDebug: no test hooks, cleartext to one host only")
        return 1 if failed else 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

#!/usr/bin/env python3
"""Checks that test hooks and cleartext HTTP stay where they belong (FIN-120, AGENTS.md «Security»).

`release` — merged manifests of builds that may reach users (prodRelease, localRelease): no e2e or mock hooks
(`ru.finassist.pf.e2e.*`, the `pfe2e` control link, the document picker override), not debuggable, cleartext
explicitly off and no network security config, so no exception to it either.

`local-debug` — the merged manifest of localDebug, its network security config and its BuildConfig.java: no test
hooks; the config is the app's own and forbids cleartext by default. An http backend (the compiled `API_BASE_URL`,
i.e. pf.apiBaseUrl) gets exactly one exception, for its own host, without subdomains; an https backend gets none.
No user CAs or debug overrides.

`pinned-release` — a release build with API pins (FIN-121, `pf.apiPins`): the release rules, except that the manifest
must use the app's own network security config; that config forbids cleartext everywhere, has no trust anchors or
debug overrides (user CAs stay untrusted) and pins exactly the host of the compiled `API_BASE_URL`, without subdomains:
at least two distinct SHA-256 pins and an expiration date that has not passed.

Usage:
  scripts/check-build-separation.py release <merged AndroidManifest.xml>...
  scripts/check-build-separation.py local-debug <merged AndroidManifest.xml> <network_security_config.xml> <BuildConfig.java>
  scripts/check-build-separation.py pinned-release <merged AndroidManifest.xml> <network_security_config.xml> <BuildConfig.java>
  scripts/check-build-separation.py stage-default <merged AndroidManifest.xml> <BuildConfig.java>
"""
import base64
import binascii
import datetime
import re
import sys
import urllib.parse
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


def release_problems(manifest_xml: str, allow_config: bool = False) -> list[str]:
    """Release rules; [allow_config] — a pinned build, which must use the app's own config instead of having none."""
    root = ET.fromstring(manifest_xml)
    problems = [f"test hook: {hook}" for hook in test_hooks(root)]
    application = root.find("application")
    if application is None:
        return problems + ["no <application>"]
    if application.get(ANDROID + "debuggable") == "true":
        problems.append("android:debuggable is true")
    if application.get(ANDROID + "usesCleartextTraffic") != "false":
        problems.append("android:usesCleartextTraffic is not explicitly false")
    config = application.get(ANDROID + "networkSecurityConfig")
    if allow_config:
        if config != NSC_REF:
            problems.append(f"the manifest does not use {NSC_REF}")
    elif config is not None:
        problems.append("a network security config (it may allow cleartext) in a release build")
    return problems


MIN_PINS = 2


def sha256_digest(value: str) -> bytes | None:
    """The 32 bytes of a base64 SHA-256 pin, or None. Two spellings of one digest decode to the same bytes."""
    try:
        decoded = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError):
        return None
    return decoded if len(value) == 44 and len(decoded) == 32 else None


def pin_problems(nsc_xml: str, base_url: str, today: datetime.date) -> list[str]:
    """Problems of the pinned network security config of a release build (FIN-121)."""
    problems = []
    config = ET.fromstring(nsc_xml)
    base = config.find("base-config")
    if base is None or base.get("cleartextTrafficPermitted") != "false":
        problems.append("base-config does not forbid cleartext")
    if config.find("debug-overrides") is not None or config.find(".//trust-anchors") is not None:
        problems.append("custom trust anchors or debug overrides")
    url = urllib.parse.urlsplit(base_url)
    if url.scheme != "https" or not url.hostname:
        return problems + [f"API_BASE_URL {base_url!r} is not an https URL: nothing to pin"]
    domain_configs = list(config.iter("domain-config"))
    if len(domain_configs) != 1:
        return problems + [f"expected one domain-config for {url.hostname}, found {len(domain_configs)}"]
    domain_config = domain_configs[0]
    if domain_config.get("cleartextTrafficPermitted") == "true":
        problems.append("cleartext allowed in the pinned domain-config")
    domains = domain_config.findall("domain")
    hosts = [(d.text or "").strip().lower() for d in domains]
    if hosts != [url.hostname]:
        problems.append(f"pinned hosts {', '.join(hosts) or 'none'}, expected only the backend host {url.hostname}")
    if any(d.get("includeSubdomains") == "true" for d in domains):
        problems.append("pins cover subdomains")
    pin_set = domain_config.find("pin-set")
    if pin_set is None:
        return problems + ["no pin-set"]
    expiration = pin_set.get("expiration")
    if expiration is None:
        problems.append("pin-set has no expiration: a stale pin would lock the app out for good")
    else:
        try:
            if datetime.date.fromisoformat(expiration) <= today:
                problems.append(f"pin-set expired on {expiration}: the pins are ignored")
        except ValueError:
            problems.append(f"pin-set expiration {expiration!r} is not a date")
    # Distinct by decoded bytes, not by text: base64 with different unused bits before `=` is the same key.
    digests = set()
    for pin in pin_set.findall("pin"):
        value = (pin.text or "").strip()
        digest = sha256_digest(value)
        if pin.get("digest") != "SHA-256":
            problems.append(f"pin {value!r} is not SHA-256")
        elif digest is None:
            problems.append(f"pin {value!r} is not a base64 SHA-256 digest")
        else:
            digests.add(digest)
    if len(digests) < MIN_PINS:
        problems.append(f"{len(digests)} distinct pin(s), at least {MIN_PINS} required (the current key and a backup)")
    return problems


def pinned_release_problems(manifest_xml: str, nsc_xml: str, base_url: str, today: datetime.date) -> list[str]:
    return release_problems(manifest_xml, allow_config=True) + pin_problems(nsc_xml, base_url, today)


def api_base_url(build_config_java: str) -> str:
    """`API_BASE_URL` of a generated BuildConfig.java."""
    match = re.search(r'\bString\s+API_BASE_URL\s*=\s*"([^"]*)"', build_config_java)
    if match is None:
        raise ValueError("no API_BASE_URL in BuildConfig")
    return match.group(1)


def stage_default_problems(manifest_xml: str, build_config_java: str) -> list[str]:
    """Default stage build: fixed HTTPS endpoint, separate identity, no inherited SDK credentials or test hooks."""
    root = ET.fromstring(manifest_xml)
    problems = [f"test hook: {hook}" for hook in test_hooks(root)]
    application = root.find("application")
    if application is None:
        return problems + ["no <application>"]
    if root.get("package") != "ru.finassist.pf.stage":
        problems.append("stage package must be ru.finassist.pf.stage")
    if application.get(ANDROID + "usesCleartextTraffic") != "false":
        problems.append("stage cleartext is not explicitly false")
    if application.get(ANDROID + "networkSecurityConfig") is not None:
        problems.append("default stage inherited a network security config")
    if api_base_url(build_config_java) != "https://stage.plainfin.ru/":
        problems.append("stage API_BASE_URL must be https://stage.plainfin.ru/")
    expected = {
        "APPLICATION_ID": '"ru.finassist.pf.stage"',
        "RUSTORE_REMOTE_CONFIG_APP_ID": '""',
        "MYTRACKER_SDK_KEY": '""',
        "CRASH_REPORTING_ENABLED": "false",
        "E2E_NOW": '""',
    }
    for name, value in expected.items():
        values = re.findall(r'public static final (?:String|boolean) ' + name + r' = ([^;]*);', build_config_java)
        if values != [value]:
            # Never include actual SDK values in logs.
            problems.append(f"unexpected default stage BuildConfig.{name}")
    return problems


def local_debug_problems(manifest_xml: str, nsc_xml: str, base_url: str) -> list[str]:
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
    url = urllib.parse.urlsplit(base_url)
    if url.scheme == "https":
        if hosts:
            problems.append(f"cleartext to {', '.join(hosts)} although the backend {base_url} is https")
    elif url.scheme == "http" and url.hostname:
        others = [h for h in hosts if h.lower() != url.hostname]
        if not hosts:
            problems.append(f"no cleartext exception for the http backend host {url.hostname}: the build cannot reach it")
        if others:
            problems.append(f"cleartext to {', '.join(others)}, not the backend host {url.hostname}")
    else:
        problems.append(f"API_BASE_URL {base_url!r} is not an http(s) URL")
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
    if len(args) == 3 and args[0] == "stage-default":
        failed = report(args[1], stage_default_problems(read(args[1]), read(args[2])))
        if not failed:
            print("stage: separate identity, HTTPS, no production SDK keys or test hooks")
        return 1 if failed else 0
    if len(args) >= 2 and args[0] == "release":
        failed = False
        for path in args[1:]:
            failed = report(path, release_problems(read(path))) or failed
        if not failed:
            print(f"No test hooks or cleartext in {len(args) - 1} release manifest(s)")
        return 1 if failed else 0
    if len(args) == 4 and args[0] == "local-debug":
        failed = report(args[1], local_debug_problems(read(args[1]), read(args[2]), api_base_url(read(args[3]))))
        if not failed:
            print("localDebug: no test hooks, cleartext only to the backend host")
        return 1 if failed else 0
    if len(args) == 4 and args[0] == "pinned-release":
        problems = pinned_release_problems(
            read(args[1]), read(args[2]), api_base_url(read(args[3])), datetime.date.today()
        )
        failed = report(args[1], problems)
        if not failed:
            print("pinned release: no test hooks or cleartext, the API host pinned with a backup and an expiration")
        return 1 if failed else 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

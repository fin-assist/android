#!/usr/bin/env python3
"""Measure two SDK package transfers without starting Android or building an APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

SCHEMA = "sdk-prep-v1"
IMAGE = "system-images;android-34;google_apis;x86_64"
PACKAGES = {"emulator": "emulator", "image": "system-images/android-34/google_apis/x86_64"}
RESULT = Path("build/sdk-preparation-benchmark/result.json")


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n")


def output(name, value):
    with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
        stream.write(f"{name}={value}\n")


def hosted():
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted":
        raise ValueError("SDK benchmark requires a disposable GitHub-hosted runner")


def sdk_root():
    root = Path(os.environ["ANDROID_HOME"])
    if not root.is_absolute() or root == Path("/"):
        raise ValueError("ANDROID_HOME must be an absolute SDK directory")
    return root


def identity():
    names = ("RUNNER_OS", "RUNNER_ARCH", "ImageOS", "ImageVersion")
    result = {name: os.environ[name] for name in names}
    if any(not value.strip() for value in result.values()):
        raise ValueError("Missing runner image identity")
    return result


def metadata(path):
    data = path.read_bytes()
    revisions = re.findall(rb"^Pkg.Revision\s*=\s*([^\r\n]+)", data, re.MULTILINE)
    if len(revisions) != 1 or not re.fullmatch(rb"[0-9]+(?:\.[0-9]+)*(?:\s+(?:rc|beta|alpha)[0-9]+)?", revisions[0].strip()):
        raise ValueError(f"Invalid package revision in {path.name}")
    return {"revision": revisions[0].decode().strip(), "source_properties_sha256": hashlib.sha256(data).hexdigest()}


def manifest(root):
    paths = {**PACKAGES, "platform-tools": "platform-tools", "cmdline-tools": "cmdline-tools/latest"}
    return {"schema": SCHEMA, "runner": identity(),
            "packages": {name: metadata(root / relative / "source.properties") for name, relative in paths.items()}}


def cache_key(name, installed, run_id):
    if name not in PACKAGES or not re.fullmatch(r"[0-9]+", run_id):
        raise ValueError("Invalid package or run namespace")
    payload = {"runner": installed["runner"], "package": PACKAGES[name], "metadata": installed["packages"][name]}
    digest = hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()
    return f"{SCHEMA}-{run_id}-{name}-{digest}"


def check_expected(installed, expected):
    if installed != expected:
        raise ValueError("SDK revisions/metadata or hosted runner ImageVersion differ from the seed; comparison invalid")


def start(mode, shard):
    hosted()
    root = sdk_root()
    identity()
    if not (root / "cmdline-tools/latest/bin/sdkmanager").is_file():
        raise ValueError("SDK manager is missing; refusing package deletion")
    free_before = shutil.disk_usage(root).free
    before = {}
    # Remove only the two measured SDK packages, identically in both arms. Never touch AVDs or host keys.
    for name, relative in PACKAGES.items():
        directory = root / relative
        if directory.is_symlink() or not directory.resolve().is_relative_to(root.resolve()):
            raise ValueError("Measured SDK package directory must not be a symlink")
        properties = directory / "source.properties"
        before[name] = metadata(properties) if properties.is_file() else None
        if directory.exists():
            shutil.rmtree(directory)
        output(f"{name}-path", str(directory))
    write_json(RESULT, {"mode": mode, "shard": shard, "started": time.time(), "cold_package_baseline": True,
                        "hosted_preinstalled_packages": before, "free_bytes_before_cleanup": free_before,
                        "free_bytes_after_cleanup": shutil.disk_usage(root).free})


def prepare(mode, expected_path):
    hosted()
    state = json.loads(RESULT.read_text())
    if state["mode"] != mode:
        raise ValueError("Benchmark mode changed")
    state["install_started"] = time.time()
    write_json(RESULT, state)
    manager = str(sdk_root() / "cmdline-tools/latest/bin/sdkmanager")
    subprocess.run([manager, "--licenses"], input="y\n" * 100, text=True, stdout=subprocess.DEVNULL,
                   check=True, timeout=120)
    subprocess.run([manager, "--install", "platform-tools", "emulator", IMAGE, "--channel=0"], check=True, timeout=600)
    installed = manifest(sdk_root())
    state["install_finished"] = time.time()
    state["manifest"] = installed
    state["free_bytes_after_install"] = shutil.disk_usage(sdk_root()).free
    state["package_bytes"] = {name: sum(p.stat().st_size for p in (sdk_root() / relative).rglob("*") if p.is_file())
                              for name, relative in PACKAGES.items()}
    write_json(RESULT, state)
    write_json(RESULT.parent / "manifest.json", installed)
    if mode != "seed":
        try:
            check_expected(installed, json.loads(Path(expected_path).read_text()))
        except (ValueError, OSError):
            state["comparison_valid"] = False
            state["error"] = "Seed metadata comparison failed; actual manifest retained"
            write_json(RESULT, state)
            raise
    state["comparison_valid"] = True
    write_json(RESULT, state)
    if mode == "seed":
        for name in PACKAGES:
            output(f"{name}-key", cache_key(name, installed, os.environ["GITHUB_RUN_ID"]))


def finish():
    state = json.loads(RESULT.read_text())
    if not state.get("comparison_valid"):
        raise ValueError("Incomplete or incompatible measurement cannot be reported as successful")
    state["finished"] = time.time()
    state["seconds"] = {
        "restore_and_setup": state["install_started"] - state["started"],
        "sdkmanager_and_metadata": state["install_finished"] - state["install_started"],
        "save_and_step_overhead": state["finished"] - state["install_finished"],
        "total": state["finished"] - state["started"],
    }
    write_json(RESULT, state)
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as stream:
        stream.write(f"### SDK preparation: {state['mode']} / {state['shard']}\n\n")
        stream.write("No emulator, APK or UI test was started. Baseline removes the two measured packages.\n\n")
        stream.write("| Phase | Seconds |\n|---|---:|\n")
        for name, value in state["seconds"].items():
            stream.write(f"| {name} | {value:.2f} |\n")
        stream.write(f"\nLogical package bytes: `{json.dumps(state['package_bytes'], sort_keys=True)}`. "
                     "Compressed transfer bytes and individual cache step durations are in the Actions logs/cache API.\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("start", "prepare", "finish"))
    parser.add_argument("--mode", choices=("seed", "baseline", "warm"), default="seed")
    parser.add_argument("--shard", type=int, choices=range(1, 5), default=1)
    parser.add_argument("--expected", default="seed/manifest.json")
    args = parser.parse_args()
    if args.command == "start":
        start(args.mode, args.shard)
    elif args.command == "prepare":
        prepare(args.mode, args.expected)
    else:
        finish()


if __name__ == "__main__":
    main()

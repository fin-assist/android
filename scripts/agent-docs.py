#!/usr/bin/env python3
"""Keeps AGENTS.md (read by Claude Code via CLAUDE.md and by Codex) in step with the code.

Two jobs:
  1. Regenerates the blocks between `<!-- generated:<name>:start -->` and `<!-- generated:<name>:end -->`
     in AGENTS.md from the sources of truth: version catalog, settings.gradle.kts, module build files, Flag.kt.
  2. Checks the hand-written registries that cannot be generated (they carry descriptions):
     every module is described in docs/modules.md, :app wires the same features as settings.gradle.kts,
     docs/flags.md lists exactly the flags of Flag.kt, the mock flags asset uses only known keys.

Usage:
  python3 scripts/agent-docs.py --check   # CI: exit 1 on any drift, print what to fix
  python3 scripts/agent-docs.py --write   # rewrite the generated blocks; registry problems are still reported

Standard library only (Python 3.11+ for tomllib), so CI runs it without Gradle or pip.
"""

from __future__ import annotations

import argparse
import difflib
import json
import re
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
AGENTS = ROOT / "AGENTS.md"
SETTINGS = ROOT / "settings.gradle.kts"
CATALOG = ROOT / "gradle/libs.versions.toml"
WRAPPER = ROOT / "gradle/wrapper/gradle-wrapper.properties"
FLAG_KT = ROOT / "core/toggles/src/main/kotlin/ru/finassist/pf/core/toggles/Flag.kt"
FLAGS_MD = ROOT / "docs/flags.md"
MODULES_MD = ROOT / "docs/modules.md"
MOCK_FLAGS = ROOT / "app/src/mock/assets/flags.json"
APP_BUILD = ROOT / "app/build.gradle.kts"

# Catalog versions worth knowing before touching the build; the rest is one `cat` away.
STACK_VERSIONS = ["agp", "kotlin", "ksp", "hilt", "composeBom", "navigationCompose", "compileSdk", "minSdk", "targetSdk"]


# --- sources ------------------------------------------------------------------------------------------------

def read_modules() -> list[str]:
    """Gradle paths from settings.gradle.kts, including `listOf(...).forEach { x -> include(":a:$x:b") }`."""
    text = SETTINGS.read_text(encoding="utf-8")
    modules: list[str] = []
    loop = re.compile(r'listOf\(([^)]*)\)\.forEach\s*\{\s*(\w+)\s*->(.*?)\n\s*\}', re.S)
    for names, var, body in loop.findall(text):
        for name in re.findall(r'"([^"]+)"', names):
            for path in re.findall(r'include\("(:[^"]+)"\)', body):
                modules.append(path.replace(f"${var}", name).replace(f"${{{var}}}", name))
    text = loop.sub("", text)
    modules += re.findall(r'^\s*include\("(:[^"]+)"\)', text, re.M)
    return sorted(modules, key=module_sort_key)


def module_sort_key(path: str) -> tuple[int, str]:
    order = {"app": 0, "core": 1, "providers": 2, "mock": 3, "feature": 4}
    return order.get(path.split(":")[1], 9), path


def module_dir(path: str) -> Path:
    return ROOT / path.strip(":").replace(":", "/")


def module_kind(path: str) -> str:
    build = module_dir(path) / "build.gradle.kts"
    if not build.exists():
        return "(no build file)"
    plugins = re.findall(r'id\("(pf\.[a-z.]+)"\)', build.read_text(encoding="utf-8"))
    return ", ".join(f"`{p}`" for p in plugins) or "-"


def module_tests(path: str) -> str:
    src = module_dir(path) / "src"
    kinds = []
    unit = [f for f in src.glob("test*/**/*.kt")]
    if any(not f.name.endswith("DesignCheckTest.kt") for f in unit):
        kinds.append("unit")
    if any(f.name.endswith("DesignCheckTest.kt") for f in unit):
        kinds.append("design-check")
    if any(src.glob("androidTest*/**/*.kt")):
        kinds.append("instrumented")
    return ", ".join(kinds) or "-"


def read_flags() -> list[tuple[str, str, bool]]:
    """(constant, key, default) from the Flag enum; the default is `true` unless given explicitly."""
    text = FLAG_KT.read_text(encoding="utf-8")
    entries = re.findall(r'^\s*([A-Z][A-Z0-9_]*)\("([^"]+)"(?:\s*,\s*(?:defaultValue\s*=\s*)?(true|false))?\)', text, re.M)
    return [(const, key, default != "false") for const, key, default in entries]


# --- generated blocks ---------------------------------------------------------------------------------------

def block_stack() -> str:
    catalog = tomllib.loads(CATALOG.read_text(encoding="utf-8"))["versions"]
    gradle = re.search(r"gradle-([\d.]+)-", WRAPPER.read_text(encoding="utf-8"))
    rows = [f"| Gradle | {gradle.group(1) if gradle else '?'} |"]
    rows += [f"| {name} | {catalog[name]} |" for name in STACK_VERSIONS if name in catalog]
    return "| Component | Version |\n|---|---|\n" + "\n".join(rows)


def block_modules(modules: list[str]) -> str:
    rows = [f"| `{m}` | {module_kind(m)} | {module_tests(m)} |" for m in modules]
    return "| Module | Convention plugins | Tests |\n|---|---|---|\n" + "\n".join(rows)


def block_flags(flags: list[tuple[str, str, bool]]) -> str:
    rows = [f"| `{key}` | `Flag.{const}` | `{str(default).lower()}` |" for const, key, default in flags]
    return "| Key | Constant | Code default |\n|---|---|---|\n" + "\n".join(rows)


def render(text: str, blocks: dict[str, str]) -> tuple[str, list[str]]:
    problems = []
    for name, body in blocks.items():
        pattern = re.compile(
            rf"(<!-- generated:{name}:start[^>]*-->\n).*?(\n<!-- generated:{name}:end -->)", re.S)
        if not pattern.search(text):
            problems.append(f"AGENTS.md: markers for the `{name}` block are missing")
            continue
        text = pattern.sub(lambda m: m.group(1) + body + m.group(2), text)
    return text, problems


# --- registry checks ----------------------------------------------------------------------------------------

def check_registries(modules: list[str], flags: list[tuple[str, str, bool]]) -> list[str]:
    problems = []

    modules_md = MODULES_MD.read_text(encoding="utf-8")
    for m in modules:
        parts = m.strip(":").split(":")
        if parts[0] == "feature":
            # Features are listed by name ("Features: `auth`, ..."); api/impl share one description.
            if f"`{parts[1]}`" not in modules_md:
                problems.append(f"docs/modules.md: feature `{parts[1]}` is not listed")
        elif "/".join(parts) not in modules_md:
            problems.append(f"docs/modules.md: module `{m}` is not described")

    # :app wires features by its own list; a feature missing there is built but never reaches the Hilt graph.
    features = {m.split(":")[2] for m in modules if m.startswith(":feature:")}
    app_lists = re.findall(r'listOf\(([^)]*)\)\.forEach', APP_BUILD.read_text(encoding="utf-8"))
    app_features = {name for names in app_lists for name in re.findall(r'"([^"]+)"', names)}
    for name in sorted(features - app_features):
        problems.append(f"app/build.gradle.kts: feature `{name}` is not in the features list")
    for name in sorted(app_features - features):
        problems.append(f"app/build.gradle.kts: feature `{name}` is not included in settings.gradle.kts")

    code_keys = {key for _, key, _ in flags}
    doc_keys = set(re.findall(r"^\|\s*`([a-z0-9_.]+)`\s*\|", FLAGS_MD.read_text(encoding="utf-8"), re.M))
    for key in sorted(code_keys - doc_keys):
        problems.append(f"docs/flags.md: flag `{key}` from Flag.kt has no row")
    for key in sorted(doc_keys - code_keys):
        problems.append(f"docs/flags.md: row `{key}` is not in Flag.kt")

    if MOCK_FLAGS.exists():
        for key in sorted(set(json.loads(MOCK_FLAGS.read_text(encoding="utf-8"))) - code_keys):
            problems.append(f"{MOCK_FLAGS.relative_to(ROOT)}: unknown flag `{key}`")

    return problems


# --- entry point --------------------------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="fail on any drift")
    mode.add_argument("--write", action="store_true", help="rewrite generated blocks in AGENTS.md")
    args = parser.parse_args()

    modules = read_modules()
    flags = read_flags()
    if not modules or not flags:
        print("agent-docs: parsed no modules or no flags; the parser is out of date with the sources", file=sys.stderr)
        return 1

    current = AGENTS.read_text(encoding="utf-8")
    updated, problems = render(current, {
        "stack": block_stack(),
        "modules": block_modules(modules),
        "flags": block_flags(flags),
    })
    problems += check_registries(modules, flags)

    if updated != current:
        if args.write:
            AGENTS.write_text(updated, encoding="utf-8")
            print("agent-docs: AGENTS.md generated blocks updated")
        else:
            sys.stdout.writelines(difflib.unified_diff(
                current.splitlines(keepends=True), updated.splitlines(keepends=True), "AGENTS.md", "AGENTS.md (expected)"))
            problems.append("AGENTS.md: generated blocks are stale, run `python3 scripts/agent-docs.py --write`")

    problems = list(dict.fromkeys(problems))  # a feature's :api and :impl report the same missing name
    for p in problems:
        print(f"agent-docs: {p}", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())

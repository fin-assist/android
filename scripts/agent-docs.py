#!/usr/bin/env python3
"""Keeps AGENTS.md (read by Claude Code via CLAUDE.md and by Codex) in step with the code.

Two jobs:
  1. Regenerates the blocks between `<!-- generated:<name>:start -->` and `<!-- generated:<name>:end -->`
     in AGENTS.md from the sources of truth: version catalog, settings.gradle.kts, module build files, Flag.kt.
  2. Checks the hand-written registries that cannot be generated (they carry descriptions):
     every module is described in docs/modules.md, :app wires every feature of settings.gradle.kts,
     docs/flags.md lists exactly the flags of Flag.kt with the same defaults, the mock flags asset uses
     only known keys.
A source in a shape the parser does not recognise fails the check rather than being skipped.

Usage:
  python3 scripts/agent-docs.py --check   # CI: exit 1 on any drift, print what to fix
  python3 scripts/agent-docs.py --write   # rewrite the generated blocks; registry problems are still reported
  python3 -m unittest discover -s scripts -p 'test_*.py'   # parser tests

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


# --- parsing ------------------------------------------------------------------------------------------------
# Pure functions over file text (covered by scripts/test_agent_docs.py). Anything they recognise only
# partially raises ParseError: a silently skipped module or flag would let --check pass on real drift.

class ParseError(Exception):
    pass


# Strings are matched first so that `//` inside "https://..." is not taken for a comment.
_TOKENS = re.compile(r'"(?:\\.|[^"\\])*"|//[^\n]*|/\*.*?\*/', re.S)
_STRINGS = re.compile(r'"([^"]*)"')
# `listOf("a", "b").forEach { x -> ... }`; the body has no braces once `${x}` is normalised to `$x`.
_LOOP = re.compile(r'listOf\(([^)]*)\)\s*\.forEach\s*\{\s*(\w+)\s*->([^{}]*)\}', re.S)


def kotlin_code(text: str) -> str:
    """Drops comments and normalises `${x}` to `$x`, keeping string literals intact."""
    text = _TOKENS.sub(lambda m: m.group(0) if m.group(0).startswith('"') else "", text)
    return re.sub(r'\$\{(\w+)\}', r'$\1', text)


def parse_settings(text: str) -> list[str]:
    """Gradle paths from settings.gradle.kts: `include(":a", ":b")` and includes inside `listOf(...).forEach`."""
    code = kotlin_code(text)
    paths: list[str] = []
    for names, var, body in _LOOP.findall(code):
        for args in re.findall(r'include\(([^)]*)\)', body):
            for name in _STRINGS.findall(names):
                paths += [p.replace(f"${var}", name) for p in _STRINGS.findall(args)]
    for args in re.findall(r'include\(([^)]*)\)', _LOOP.sub("", code)):
        paths += _STRINGS.findall(args)
    unresolved = [p for p in paths if "$" in p or not p.startswith(":")]
    if unresolved:
        raise ParseError(f"settings.gradle.kts: cannot resolve include {unresolved}")
    if not paths:
        raise ParseError("settings.gradle.kts: no include(...) found")
    return sorted(dict.fromkeys(paths), key=module_sort_key)


def parse_flags(text: str) -> list[tuple[str, str, bool]]:
    """(constant, key, default) of the Flag enum; an entry without its own value takes the constructor's default.

    Entries may span lines and end with a trailing comma. Every `NAME(` in the enum body must parse, so an
    entry in an unexpected shape fails the check instead of disappearing from the registry."""
    enum = re.search(r'enum\s+class\s+Flag\b([^{]*)\{(.*?);', kotlin_code(text), re.S)
    if not enum:
        raise ParseError("Flag.kt: `enum class Flag { ...; }` not found")
    ctor, body = enum.groups()
    param = re.search(r'\bdefaultValue\s*:\s*Boolean\s*(?:=\s*(true|false))?', ctor)
    if not param:
        raise ParseError("Flag.kt: constructor parameter `defaultValue: Boolean` not found")
    ctor_default = param.group(1)  # None: every entry must pass its own value
    entry = re.compile(
        r'\b([A-Z][A-Z0-9_]*)\s*\(\s*"([^"]+)"\s*(?:,\s*(?:defaultValue\s*=\s*)?(true|false)\s*)?,?\s*\)', re.S)
    flags, missing = [], []
    for const, key, own in entry.findall(body):
        value = own or ctor_default
        if value is None:
            missing.append(const)
        flags.append((const, key, value == "true"))
    if missing:
        raise ParseError(f"Flag.kt: no defaultValue for {missing} and none in the constructor")
    seen = re.findall(r'\b([A-Z][A-Z0-9_]*)\s*\(', body)
    parsed = [const for const, _, _ in flags]
    if seen != parsed or not flags:
        raise ParseError(f"Flag.kt: cannot parse entries {sorted(set(seen) - set(parsed)) or seen}")
    return flags


def parse_app_features(text: str) -> set[str]:
    """Features whose `:impl` is wired in app/build.gradle.kts.

    Counts a `listOf(...).forEach` only if its body adds both `:feature:$x:api` and `:feature:$x:impl`
    (other loops in the file are ignored), plus explicit `project(":feature:x:impl")` / `projects.feature.x.impl`."""
    code = kotlin_code(text)
    wired: set[str] = set()
    for names, var, body in _LOOP.findall(code):
        if all(re.search(rf'project\(\s*":feature:\${var}:{part}"\s*\)', body) for part in ("api", "impl")):
            wired.update(_STRINGS.findall(names))
    wired.update(re.findall(r'project\(\s*":feature:([\w-]+):impl"\s*\)', code))
    wired.update(re.findall(r'\bprojects\.feature\.(\w+)\.impl\b', code))
    return wired


def parse_flag_docs(text: str) -> dict[str, bool | None]:
    """Rows of the docs/flags.md table: key → documented default (`None` if the column is not `true`/`false`)."""
    rows = re.findall(r'^\|\s*`([a-z0-9_.]+)`\s*\|\s*([^|]*?)\s*\|', text, re.M)
    return {key: {"`true`": True, "`false`": False}.get(default) for key, default in rows}


def parse_listed_features(text: str) -> set[str] | None:
    """Names on the `Features: `a`, `b`.` line of docs/modules.md; `None` if the line is missing."""
    line = re.search(r'^Features:(.*)$', text, re.M)
    return set(re.findall(r'`([\w-]+)`', line.group(1))) if line else None


def mentions_path(text: str, path: str) -> bool:
    """`core/api` as a whole path: not a prefix of `core/api-foo` or a part of `x/core/api`."""
    return re.search(rf'(?<![\w/-]){re.escape(path)}(?![\w/-])', text) is not None


# --- sources ------------------------------------------------------------------------------------------------

def read_modules() -> list[str]:
    return parse_settings(SETTINGS.read_text(encoding="utf-8"))


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
    return parse_flags(FLAG_KT.read_text(encoding="utf-8"))


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

    features = {m.split(":")[2] for m in modules if m.startswith(":feature:")}

    # Features are listed by name on the "Features:" line (api/impl share one description); other modules
    # by their directory path.
    modules_md = MODULES_MD.read_text(encoding="utf-8")
    listed = parse_listed_features(modules_md)
    if listed is None:
        problems.append("docs/modules.md: the `Features: ...` line is missing")
    else:
        for name in sorted(features - listed):
            problems.append(f"docs/modules.md: feature `{name}` is not on the Features line")
        for name in sorted(listed - features):
            problems.append(f"docs/modules.md: feature `{name}` on the Features line is not in settings.gradle.kts")
    for m in modules:
        if not m.startswith(":feature:") and not mentions_path(modules_md, m.strip(":").replace(":", "/")):
            problems.append(f"docs/modules.md: module `{m}` is not described")

    # A feature included in settings but not wired in :app is built and never reaches the Hilt graph.
    for name in sorted(features - parse_app_features(APP_BUILD.read_text(encoding="utf-8"))):
        problems.append(f"app/build.gradle.kts: feature `{name}` (:api + :impl) is not wired")

    code = {key: default for _, key, default in flags}
    docs = parse_flag_docs(FLAGS_MD.read_text(encoding="utf-8"))
    for key in sorted(code.keys() - docs.keys()):
        problems.append(f"docs/flags.md: flag `{key}` from Flag.kt has no row")
    for key in sorted(docs.keys() - code.keys()):
        problems.append(f"docs/flags.md: row `{key}` is not in Flag.kt")
    for key in sorted(code.keys() & docs.keys()):
        if docs[key] is None:
            problems.append(f"docs/flags.md: row `{key}`: Default column must be `true` or `false`")
        elif docs[key] != code[key]:
            problems.append(f"docs/flags.md: row `{key}`: Default `{str(docs[key]).lower()}`, "
                            f"Flag.kt `{str(code[key]).lower()}`")
    code_keys = code.keys()

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

    try:
        modules = read_modules()
        flags = read_flags()
    except ParseError as e:
        print(f"agent-docs: {e}; update the parser in scripts/agent-docs.py or the source shape", file=sys.stderr)
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

    for p in problems:
        print(f"agent-docs: {p}", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())

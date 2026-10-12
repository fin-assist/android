#!/usr/bin/env python3
"""Coverage by UI tests: scenarios and code (FIN-48, docs/testing.md).

Two numbers, kept apart from unit test coverage (Kover):

- Scenarios, static: which screens and test tags the Maestro flows refer to, against every tag the app declares.
  Needs no device: tags are read from the `…Tags` objects of the sources, ids from `.maestro/**/*.yaml`.
- Code: lines and branches the flows execute in the mock e2e build, from the JaCoCo report of
  `./gradlew :e2eCoverageReport` (composables included — UI tests are what checks them).

Usage:
  python3 scripts/ui-coverage.py summary                          # scenario coverage, Markdown to stdout
  python3 scripts/ui-coverage.py summary --jacoco build/reports/e2e-coverage/report.xml
  python3 scripts/ui-coverage.py summary … --summary "$GITHUB_STEP_SUMMARY"
  python3 scripts/ui-coverage.py collect build/e2e/coverage/device build/e2e/coverage/ec

`collect` picks, for every app process of the run, the newest execution data file that is complete: the e2e build
saves the data every few seconds and a process killed mid-write leaves a truncated file (`E2eCoverage.kt`).

Standard library only, so CI runs it without pip.
"""

from __future__ import annotations

import argparse
import re
import shutil
import struct
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Where test tags are declared: `…Tags` objects next to the screens and `PfTestTags` of the design system.
TAG_SOURCES = ("feature", "core", "app/src/main")
# Test hooks of the e2e build and Maestro's «sleep» id: not screens of the app.
IGNORED_PREFIXES = ("e2e.",)

TAGS_OBJECT = re.compile(r"\bobject\s+\w*Tags\s*\{")
CONST_TAG = re.compile(r'\bconst\s+val\s+\w+\s*=\s*"([^"$]+)"')
# fun chip(screen: String) = "assistant.answer.chip.$screen"  →  prefix «assistant.answer.chip.»
FUN_TAG = re.compile(r'\bfun\s+\w+\([^)]*\)\s*=\s*"([^"$]*)\$')
LITERAL_TAG = re.compile(r'\.testTag\("([a-z][a-z0-9_.]*)"\)')
FLOW_ID = re.compile(r'^\s*(?:-\s*)?id:\s*"([^"]+)"', re.MULTILINE)


# ---------- scenario coverage ----------

@dataclass
class Tags:
    exact: set[str] = field(default_factory=set)
    #: Tags built at run time (`ds.tab.<id>`): a flow id that starts with the prefix refers to them.
    prefixes: set[str] = field(default_factory=set)


def parse_tags(text: str) -> Tags:
    """Tags declared in one Kotlin file: constants and builder functions of `…Tags` objects, literal `testTag("…")`."""
    tags = Tags()
    for match in TAGS_OBJECT.finditer(text):
        body = _block(text, match.end() - 1)
        tags.exact.update(CONST_TAG.findall(body))
        tags.prefixes.update(p for p in FUN_TAG.findall(body) if p)
    tags.exact.update(LITERAL_TAG.findall(text))
    return tags


def _block(text: str, open_brace: int) -> str:
    """The `{ … }` block that starts at [open_brace]."""
    depth = 0
    for i in range(open_brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[open_brace + 1:i]
    return text[open_brace + 1:]


def declared_tags(root: Path) -> Tags:
    tags = Tags()
    for source in TAG_SOURCES:
        for path in sorted((root / source).rglob("*.kt")):
            parts = path.relative_to(root).parts
            if "build" in parts or "src" not in parts or parts[parts.index("src") + 1] != "main":
                continue
            found = parse_tags(path.read_text(encoding="utf-8"))
            tags.exact |= found.exact
            tags.prefixes |= found.prefixes
    tags.exact = {t for t in tags.exact if not t.startswith(IGNORED_PREFIXES)}
    return tags


def flow_ids(root: Path) -> set[str]:
    ids: set[str] = set()
    for path in sorted((root / ".maestro").rglob("*.yaml")):
        ids.update(FLOW_ID.findall(path.read_text(encoding="utf-8")))
    return {i for i in ids if not i.startswith(IGNORED_PREFIXES)}


def screen_of(tag: str) -> str:
    """`operations.search.chip.period` → `operations.search`; shared components (`ds.*`) are one group per kind."""
    parts = tag.split(".")
    return ".".join(parts[:2]) if len(parts) > 1 else tag


@dataclass
class ScenarioCoverage:
    #: feature → (reached, all) for screens and tags.
    screens: dict[str, tuple[set[str], set[str]]]
    tags: dict[str, tuple[set[str], set[str]]]
    #: Ids the flows use that no source declares: a renamed tag left behind in a flow.
    unknown_ids: set[str]


def scenario_coverage(tags: Tags, ids: set[str]) -> ScenarioCoverage:
    declared = set(tags.exact) | {p + "*" for p in tags.prefixes}

    def reached(tag: str) -> bool:
        return any(i.startswith(tag[:-1]) for i in ids) if tag.endswith("*") else tag in ids

    screens: dict[str, tuple[set[str], set[str]]] = {}
    by_feature: dict[str, tuple[set[str], set[str]]] = {}
    for tag in declared:
        feature = tag.split(".")[0]
        screen = screen_of(tag.rstrip("*").rstrip("."))
        s_reached, s_all = screens.setdefault(feature, (set(), set()))
        t_reached, t_all = by_feature.setdefault(feature, (set(), set()))
        s_all.add(screen)
        t_all.add(tag)
        if reached(tag):
            s_reached.add(screen)
            t_reached.add(tag)
    unknown = {i for i in ids if i not in tags.exact and not any(i.startswith(p) for p in tags.prefixes)}
    return ScenarioCoverage(screens, by_feature, unknown)


def percent(covered: int, total: int) -> str:
    return "—" if total == 0 else f"{covered * 100 / total:.1f} %"


def scenario_markdown(c: ScenarioCoverage) -> str:
    def total(groups: dict[str, tuple[set[str], set[str]]]) -> tuple[int, int]:
        return sum(len(r) for r, _ in groups.values()), sum(len(a) for _, a in groups.values())

    s_reached, s_all = total(c.screens)
    t_reached, t_all = total(c.tags)
    lines = [
        "#### Scenarios (flows against the app's test tags)",
        "",
        f"Screens: **{percent(s_reached, s_all)}** ({s_reached}/{s_all}), test tags: **{percent(t_reached, t_all)}** "
        f"({t_reached}/{t_all}).",
        "",
        "| Feature | Screens | Tags | Not referenced by any flow |",
        "|---|---|---|---|",
    ]
    for feature in sorted(c.tags):
        sr, sa = c.screens[feature]
        tr, ta = c.tags[feature]
        missing = ", ".join(f"`{t}`" for t in sorted(ta - tr)) or "—"
        lines.append(f"| `{feature}` | {len(sr)}/{len(sa)} | {len(tr)}/{len(ta)} | {missing} |")
    if c.unknown_ids:
        lines += ["", "Ids in flows that no source declares: " + ", ".join(f"`{i}`" for i in sorted(c.unknown_ids)) + "."]
    return "\n".join(lines)


# ---------- code coverage (JaCoCo XML) ----------

@dataclass
class Counter:
    covered: int = 0
    missed: int = 0

    @property
    def total(self) -> int:
        return self.covered + self.missed

    def add(self, other: "Counter") -> None:
        self.covered += other.covered
        self.missed += other.missed


def _counter(node: ET.Element, kind: str) -> Counter:
    for c in node.findall("counter"):
        if c.get("type") == kind:
            return Counter(int(c.get("covered", "0")), int(c.get("missed", "0")))
    return Counter()


def module_of(package: str) -> str:
    """`ru/finassist/pf/feature/auth/impl/ui` → `feature/auth`; `…/pf/core/designsystem/…` → `core/designsystem`."""
    parts = package.split("/")
    rest = parts[3:] if parts[:3] == ["ru", "finassist", "pf"] else parts
    if not rest:
        return "app"
    if rest[0] in ("feature", "core", "providers") and len(rest) > 1:
        return f"{rest[0]}/{rest[1]}"
    if rest[0] == "mock":
        return "mock/backend"
    return "app"


@dataclass
class CodeCoverage:
    lines: Counter
    branches: Counter
    modules: dict[str, Counter]
    #: Source files of screens (`…Screen….kt`, `…Screens.kt`) in which the flows executed no line.
    untouched_screens: list[str]


def code_coverage(report: Path) -> CodeCoverage:
    root = ET.parse(report).getroot()
    modules: dict[str, Counter] = {}
    untouched: list[str] = []
    for package in root.iter("package"):
        name = package.get("name", "")
        modules.setdefault(module_of(name), Counter()).add(_counter(package, "LINE"))
        for source in package.findall("sourcefile"):
            file_name = source.get("name", "")
            lines = _counter(source, "LINE")
            if "Screen" in file_name and lines.total > 0 and lines.covered == 0:
                untouched.append(f"{module_of(name)}: {file_name}")
    return CodeCoverage(_counter(root, "LINE"), _counter(root, "BRANCH"), modules, sorted(untouched))


def code_markdown(c: CodeCoverage) -> str:
    lines = [
        "#### Code (JaCoCo, mock e2e build under the Maestro flows)",
        "",
        f"Lines: **{percent(c.lines.covered, c.lines.total)}** ({c.lines.covered}/{c.lines.total}), "
        f"branches: **{percent(c.branches.covered, c.branches.total)}** ({c.branches.covered}/{c.branches.total}).",
        "",
        "| Module | Lines | Covered |",
        "|---|---|---|",
    ]
    for module in sorted(c.modules, key=lambda m: (-c.modules[m].total, m)):
        counter = c.modules[module]
        if counter.total:
            lines.append(f"| `{module}` | {percent(counter.covered, counter.total)} | {counter.covered}/{counter.total} |")
    if c.untouched_screens:
        lines += ["", "Screen files in which no line ran: " + ", ".join(f"`{s}`" for s in c.untouched_screens) + "."]
    return "\n".join(lines)


# ---------- execution data files ----------

def exec_file_complete(data: bytes) -> bool:
    """Whether [data] is a whole JaCoCo execution data file (a process killed mid-write leaves a truncated one).

    Format (`org.jacoco.core.data.ExecutionDataWriter`): blocks, each starting with its type byte — header 0x01
    (magic 0xC0C0, version char), session info 0x10 (UTF id, two longs), execution data 0x11 (long class id, UTF
    name, probes as a var-int count and that many bits).
    """
    pos = 0
    seen_header = False
    try:
        while pos < len(data):
            block = data[pos]
            pos += 1
            if block == 0x01:
                magic, _version = struct.unpack_from(">HH", data, pos)
                if magic != 0xC0C0:
                    return False
                pos += 4
                seen_header = True
            elif block == 0x10:
                pos = _skip_utf(data, pos) + 16
            elif block == 0x11:
                pos = _skip_utf(data, pos + 8)
                count, pos = _var_int(data, pos)
                pos += (count + 7) // 8
            else:
                return False
            if pos > len(data):
                return False
    except (struct.error, IndexError):
        return False
    return seen_header


def _skip_utf(data: bytes, pos: int) -> int:
    (length,) = struct.unpack_from(">H", data, pos)
    return pos + 2 + length


def _var_int(data: bytes, pos: int) -> tuple[int, int]:
    value = shift = 0
    while True:
        byte = data[pos]
        pos += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, pos
        shift += 7


EXEC_NAME = re.compile(r"^pf-(\d+-\d+)-(\d+)(?: \(\d+\))?\.ec$")


def collect(source: Path, target: Path) -> tuple[int, int]:
    """Copies the newest complete file of every process from [source] to [target]. Returns (processes, files kept)."""
    by_process: dict[str, list[tuple[int, Path]]] = {}
    for path in source.rglob("*.ec"):
        match = EXEC_NAME.match(path.name)
        if match:
            by_process.setdefault(match.group(1), []).append((int(match.group(2)), path))
    target.mkdir(parents=True, exist_ok=True)
    kept = 0
    for process, files in sorted(by_process.items()):
        for _sequence, path in sorted(files, reverse=True):
            if exec_file_complete(path.read_bytes()):
                shutil.copyfile(path, target / f"{process}.ec")
                kept += 1
                break
    return len(by_process), kept


# ---------- command line ----------

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    summary = commands.add_parser("summary", help="scenario coverage and, with --jacoco, code coverage")
    summary.add_argument("--jacoco", type=Path, help="JaCoCo XML report of :e2eCoverageReport")
    summary.add_argument("--summary", type=Path, help="append the Markdown to this file as well")
    summary.add_argument("--root", type=Path, default=ROOT)

    collector = commands.add_parser("collect", help="pick the complete execution data files pulled from the device")
    collector.add_argument("source", type=Path)
    collector.add_argument("target", type=Path)

    args = parser.parse_args(argv)
    if args.command == "collect":
        processes, kept = collect(args.source, args.target)
        print(f"execution data: {kept} file(s) kept for {processes} app process(es)")
        return 0

    parts = ["### UI test coverage", ""]
    if args.jacoco is not None and args.jacoco.is_file():
        parts += [code_markdown(code_coverage(args.jacoco)), ""]
    elif args.jacoco is not None:
        parts += [f"No code coverage: `{args.jacoco}` was not produced (no execution data from the device).", ""]
    parts.append(scenario_markdown(scenario_coverage(declared_tags(args.root), flow_ids(args.root))))
    text = "\n".join(parts) + "\n"
    sys.stdout.write(text)
    if args.summary is not None:
        with args.summary.open("a", encoding="utf-8") as out:
            out.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Unit test coverage of the lines a branch changes (docs/testing.md).

Reads the aggregated Kover report (`build/reports/kover/reportUnit.xml`, JaCoCo XML format) and the diff of
the branch against its base, and reports how many of the changed executable lines of production code the unit
tests run. A changed line counts only if the report has it: comments, declarations, test code, generated code
and composables (excluded from the report, see build.gradle.kts) do not.

Usage:
  ./gradlew koverXmlReportUnit
  python3 scripts/coverage-diff.py --base origin/main                 # Markdown to stdout
  python3 scripts/coverage-diff.py --base origin/main --min 80        # exit 1 below 80 %
  python3 scripts/coverage-diff.py --base … --summary "$GITHUB_STEP_SUMMARY"

Standard library only, so CI runs it without pip.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPORT = ROOT / "build/reports/kover/reportUnit.xml"

# Production source sets of any module: src/main, src/mock, src/prod, src/debug, … — not src/test*, src/androidTest*.
_SOURCE = re.compile(r'(?:^|/)src/(?!test|androidTest)[A-Za-z0-9]+/(?:kotlin|java)/(.+\.(?:kt|java))$')
_HUNK = re.compile(r'^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@')


# --- parsing ------------------------------------------------------------------------------------------------
# Pure functions over text (covered by scripts/test_coverage_diff.py).

def source_key(path: str) -> str | None:
    """`feature/x/impl/src/main/kotlin/ru/a/B.kt` → `ru/a/B.kt` (package path + file, as in the report);
    `None` for test sources, resources and anything that is not Kotlin or Java."""
    m = _SOURCE.search(path)
    return m.group(1) if m else None


def parse_diff(text: str) -> dict[str, set[int]]:
    """Added or modified lines per new file path from `git diff -U0` output (deleted files are skipped)."""
    changed: dict[str, set[int]] = {}
    path = None
    for line in text.splitlines():
        if line.startswith("+++ "):
            target = line[4:].strip()
            path = None if target == "/dev/null" else re.sub(r'^b/', '', target)
        elif path and (m := _HUNK.match(line)):
            start, count = int(m.group(1)), int(m.group(2) or "1")
            if count:
                changed.setdefault(path, set()).update(range(start, start + count))
    return changed


def parse_report(text: str) -> dict[str, dict[int, bool]]:
    """`package/File.kt` → {line number: covered} from a JaCoCo/Kover XML report.

    A line with any covered instruction counts as covered (partially covered branches included)."""
    root = ET.fromstring(text)
    lines: dict[str, dict[int, bool]] = {}
    for package in root.iter("package"):
        for source in package.findall("sourcefile"):
            key = f"{package.get('name')}/{source.get('name')}".lstrip("/")
            per_file = lines.setdefault(key, {})
            for line in source.findall("line"):
                nr = int(line.get("nr"))
                per_file[nr] = per_file.get(nr, False) or int(line.get("ci", "0")) > 0
    return lines


def report_totals(text: str) -> dict[str, tuple[int, int]]:
    """Report-level counters: type → (covered, missed)."""
    root = ET.fromstring(text)
    return {c.get("type"): (int(c.get("covered")), int(c.get("missed"))) for c in root.findall("counter")}


# --- computation --------------------------------------------------------------------------------------------

@dataclass
class FileCoverage:
    path: str
    covered: list[int] = field(default_factory=list)
    missed: list[int] = field(default_factory=list)


def diff_coverage(changed: dict[str, set[int]], report: dict[str, dict[int, bool]]) -> list[FileCoverage]:
    """Changed executable lines per file, in path order. Files with no executable changed line are left out.

    Two production files with the same package and name in different modules would share a report key; the
    report merges them the same way, so the result is still what the tests cover."""
    result = []
    for path in sorted(changed):
        key = source_key(path)
        if key is None or key not in report:
            continue
        lines = report[key]
        fc = FileCoverage(path)
        for nr in sorted(changed[path]):
            if nr in lines:
                (fc.covered if lines[nr] else fc.missed).append(nr)
        if fc.covered or fc.missed:
            result.append(fc)
    return result


def ranges(numbers: list[int]) -> str:
    """[3, 4, 5, 9] → `3–5, 9`."""
    out, start, prev = [], None, None
    for n in numbers:
        if start is None:
            start = prev = n
        elif n == prev + 1:
            prev = n
        else:
            out.append(f"{start}–{prev}" if prev != start else f"{start}")
            start = prev = n
    if start is not None:
        out.append(f"{start}–{prev}" if prev != start else f"{start}")
    return ", ".join(out)


def percent(covered: int, total: int) -> str:
    return f"{100 * covered / total:.1f} %" if total else "—"


def render(files: list[FileCoverage], totals: dict[str, tuple[int, int]], base: str) -> str:
    covered = sum(len(f.covered) for f in files)
    total = covered + sum(len(f.missed) for f in files)
    out = ["### Unit test coverage", ""]
    line_c, line_m = totals.get("LINE", (0, 0))
    branch_c, branch_m = totals.get("BRANCH", (0, 0))
    out.append(f"Project: lines {percent(line_c, line_c + line_m)} ({line_c}/{line_c + line_m}), "
               f"branches {percent(branch_c, branch_c + branch_m)}.")
    if not total:
        out.append(f"Changed lines vs `{base}`: no executable production lines changed.")
        return "\n".join(out) + "\n"
    out.append(f"Changed lines vs `{base}`: **{percent(covered, total)}** ({covered}/{total}).")
    out += ["", "| File | Covered | Not covered |", "|---|---|---|"]
    for f in files:
        out.append(f"| `{f.path}` | {len(f.covered)}/{len(f.covered) + len(f.missed)} | {ranges(f.missed) or '—'} |")
    return "\n".join(out) + "\n"


# --- entry point --------------------------------------------------------------------------------------------

def git_diff(base: str) -> str:
    return subprocess.run(
        ["git", "diff", "-U0", "--no-color", "--no-ext-diff", f"{base}...HEAD", "--", "*.kt", "*.java"],
        cwd=ROOT, check=True, capture_output=True, text=True,
    ).stdout


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--base", required=True, help="ref the branch is compared with, e.g. origin/main")
    parser.add_argument("--report", type=Path, default=REPORT, help=f"Kover XML report (default {REPORT.relative_to(ROOT)})")
    parser.add_argument("--min", type=float, help="fail when changed-line coverage is below this percentage")
    parser.add_argument("--summary", type=Path, help="also append the Markdown to this file ($GITHUB_STEP_SUMMARY)")
    args = parser.parse_args()

    if not args.report.exists():
        print(f"coverage-diff: {args.report} not found; run ./gradlew koverXmlReportUnit first", file=sys.stderr)
        return 1
    xml = args.report.read_text(encoding="utf-8")
    files = diff_coverage(parse_diff(git_diff(args.base)), parse_report(xml))
    text = render(files, report_totals(xml), args.base)
    print(text)
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as f:
            f.write(text)

    covered = sum(len(f.covered) for f in files)
    total = covered + sum(len(f.missed) for f in files)
    if args.min is not None and total and 100 * covered / total < args.min:
        print(f"coverage-diff: changed lines {percent(covered, total)} < {args.min} %", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

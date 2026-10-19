#!/usr/bin/env python3
"""Splits the Maestro flows between emulators and puts the shards' results back together (FIN-180, docs/e2e.md).

The `UI tests` workflow runs the flows on several emulators at once, one job per shard. A shard is a fixed list of
flow files: the flows are spread so that the shards take about the same time, by the durations measured on CI
(`scripts/e2e-durations.json`; a flow not measured yet counts as the median). The split is deterministic — the same
flows and durations give the same shards on every job and every machine — and every flow lands in exactly one
shard. The native instrumented tests run in shard 1, so it gets fewer flows.

Usage:
  python3 scripts/e2e-shards.py plan --shards 4            # the split, as a table
  python3 scripts/e2e-shards.py list --shard 2/4           # flow files of shard 2 (what scripts/e2e.sh --shard runs)
  python3 scripts/e2e-shards.py gather --shards 4 --dir build/e2e-shards --out build/e2e
  python3 scripts/e2e-shards.py durations build/e2e/maestro-report.xml   # refresh e2e-durations.json

`gather` reads the reports each shard job uploaded (`<dir>/e2e-shard-<i>/`, the shard's `build/e2e/`) and writes:
- `<out>/maestro-report.xml` — one JUnit report of all shards. The flows of a shard that left no report, and flows
  missing from a shard's report, are added as failures: a shard that broke must not read as fewer flows;
- `<out>/coverage/ec/` — the JaCoCo execution data of every shard, file names prefixed with the shard;
- the native tests' results and execution data (shard 1) back where Gradle left them, under `app/build/outputs/`,
  so `:e2eCoverageReport` and `scripts/qase-report.py` read them as after a local run.
It prints a Markdown status of the shards (also appended to `--summary`).

Standard library only, so CI runs it without pip.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import statistics
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DURATIONS_FILE = ROOT / "scripts" / "e2e-durations.json"
FLOWS_DIR = ".maestro/flows"
# The native tests in shard 1: Gradle's configuration plus the tests themselves on the CI emulator (~25 s + ~45 s).
NATIVE_SECONDS = 70
# Artifact name of a shard in the workflow: `e2e-shard-<i>`; `gather` looks for these folders.
SHARD_DIR = "e2e-shard-{index}"
FLOW_NAME = re.compile(r"^name:\s*(.+?)\s*$", re.MULTILINE)


@dataclass
class Shard:
    index: int
    flows: list[str] = field(default_factory=list)
    seconds: int = 0
    native: bool = False


def discover_flows(root: Path) -> list[str]:
    """Flow files, repository-relative and sorted: what `maestro test .maestro` runs (`flows/**`, subflows aside)."""
    return sorted(p.relative_to(root).as_posix() for p in (root / FLOWS_DIR).rglob("*.yaml"))


def load_durations(path: Path) -> dict[str, int]:
    if not path.is_file():
        return {}
    return {k: int(v) for k, v in json.loads(path.read_text(encoding="utf-8")).get("flows", {}).items()}


def parse_shard(value: str) -> tuple[int, int]:
    """`2/4` → (2, 4); 1-based."""
    match = re.fullmatch(r"(\d+)/(\d+)", value.strip())
    if not match:
        raise ValueError(f"shard must look like 2/4, got {value!r}")
    index, count = int(match.group(1)), int(match.group(2))
    if count < 1 or not 1 <= index <= count:
        raise ValueError(f"shard {index}/{count} is out of range")
    return index, count


def plan(flows: list[str], durations: dict[str, int], count: int, native_seconds: int | None = None) -> list[Shard]:
    """
    Longest flow first, each to the shard that has the least so far (ties: the lower index). Shard 1 starts with the
    native tests' time. Flows inside a shard keep the path order, so a shard's run reads like the full run.
    """
    if count < 1:
        raise ValueError("at least one shard")
    known = [durations[f] for f in flows if f in durations]
    default = int(statistics.median(known)) if known else 60
    shards = [Shard(index=i + 1) for i in range(count)]
    shards[0].native = True
    shards[0].seconds = NATIVE_SECONDS if native_seconds is None else native_seconds
    for flow in sorted(flows, key=lambda f: (-durations.get(f, default), f)):
        target = min(shards, key=lambda s: (s.seconds, s.index))
        target.flows.append(flow)
        target.seconds += durations.get(flow, default)
    for shard in shards:
        shard.flows.sort()
    return shards


def flow_name(root: Path, flow: str) -> str:
    """The flow's `name:` (what Maestro reports), or its file name."""
    try:
        match = FLOW_NAME.search((root / flow).read_text(encoding="utf-8"))
    except OSError:
        match = None
    return match.group(1).strip("\"'") if match else Path(flow).stem


def flow_of(case: ET.Element) -> str | None:
    """Repository-relative flow path of a Maestro test case (its `file` attribute is absolute)."""
    file = (case.get("file") or "").replace("\\", "/")
    marker = "/.maestro/"
    if marker in file:
        return ".maestro/" + file.split(marker, 1)[1]
    return None


def _failed(case: ET.Element) -> bool:
    if case.find("failure") is not None or case.find("error") is not None:
        return True
    return (case.get("status") or "SUCCESS").upper() not in ("SUCCESS", "PASSED")


def _missing_case(root: Path, flow: str, message: str) -> ET.Element:
    name = flow_name(root, flow)
    case = ET.Element("testcase", {
        "id": name, "name": name, "classname": name, "file": str(root / flow), "time": "0", "status": "ERROR",
    })
    ET.SubElement(case, "failure", {"message": message}).text = message
    return case


@dataclass
class ShardOutcome:
    index: int
    planned: int
    reported: bool
    passed: int = 0
    failed: int = 0
    missing: int = 0
    seconds: float = 0.0


def merge_reports(root: Path, shards: list[Shard], reports: dict[int, Path | None]) -> tuple[ET.Element, list[ShardOutcome]]:
    """One `<testsuites>` with a suite per shard; see the module docstring for what counts as a failure."""
    merged = ET.Element("testsuites")
    outcomes = []
    count = len(shards)
    for shard in shards:
        suite = ET.SubElement(merged, "testsuite", {"name": f"Shard {shard.index}/{count}"})
        report = reports.get(shard.index)
        outcome = ShardOutcome(shard.index, len(shard.flows), reported=report is not None and report.is_file())
        seen: set[str] = set()
        if outcome.reported:
            try:
                parsed = ET.parse(report).getroot()
            except ET.ParseError:
                outcome.reported = False
            else:
                for case in parsed.iter("testcase"):
                    suite.append(case)
                    flow = flow_of(case)
                    if flow:
                        seen.add(flow)
                    if _failed(case):
                        outcome.failed += 1
                    else:
                        outcome.passed += 1
                    outcome.seconds += float(case.get("time") or 0)
        reason = (
            f"not in the report of shard {shard.index}/{count} (the run stopped before this flow)"
            if outcome.reported else
            f"shard {shard.index}/{count} left no Maestro report (the job failed or was stopped before the flows ended)"
        )
        for flow in shard.flows:
            if flow not in seen:
                suite.append(_missing_case(root, flow, reason))
                outcome.missing += 1
        cases = list(suite.iter("testcase"))
        suite.set("tests", str(len(cases)))
        suite.set("failures", str(sum(1 for c in cases if _failed(c))))
        suite.set("time", f"{sum(float(c.get('time') or 0) for c in cases):.1f}")
        outcomes.append(outcome)
    return merged, outcomes


def _copy_tree(source: Path, target: Path) -> bool:
    if not source.is_dir():
        return False
    shutil.copytree(source, target, dirs_exist_ok=True)
    return True


def gather(root: Path, shards: list[Shard], shards_dir: Path, out: Path) -> list[ShardOutcome]:
    """Writes the merged report and puts the coverage and native results in place (see the module docstring)."""
    dirs = {s.index: shards_dir / SHARD_DIR.format(index=s.index) for s in shards}
    merged, outcomes = merge_reports(root, shards, {i: d / "maestro-report.xml" for i, d in dirs.items()})
    out.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(merged).write(out / "maestro-report.xml", encoding="utf-8", xml_declaration=True)
    ec = out / "coverage" / "ec"
    ec.mkdir(parents=True, exist_ok=True)
    for index, directory in dirs.items():
        # One file per app process of the shard; processes of different emulators may share a name.
        for data in sorted((directory / "coverage" / "ec").glob("*.ec")):
            shutil.copyfile(data, ec / f"shard{index}-{data.name}")
        # scripts/e2e.sh keeps the native tests' outputs next to the flows' (native/, native-coverage/).
        _copy_tree(directory / "native", root / "app" / "build" / "outputs" / "androidTest-results")
        _copy_tree(directory / "native-coverage", root / "app" / "build" / "outputs" / "code_coverage")
    return outcomes


def outcomes_markdown(outcomes: list[ShardOutcome]) -> str:
    rows = ["| Shard | Flows | Passed | Failed | Not run | Flow time |", "|---|---|---|---|---|---|"]
    for o in outcomes:
        state = "" if o.reported else " (no report)"
        rows.append(
            f"| {o.index}/{len(outcomes)}{state} | {o.planned} | {o.passed} | {o.failed} | {o.missing} | "
            f"{o.seconds / 60:.1f} min |"
        )
    return "\n".join(rows)


def plan_markdown(shards: list[Shard]) -> str:
    rows = ["| Shard | Flows | Estimate | |", "|---|---|---|---|"]
    for s in shards:
        rows.append(f"| {s.index}/{len(shards)} | {len(s.flows)} | {s.seconds / 60:.1f} min | {'native tests' if s.native else ''} |")
    return "\n".join(rows)


def updated_durations(current: dict[str, int], reports: list[Path]) -> dict[str, int]:
    """Durations of the passed flows of the reports replace the stored ones; failed flows keep theirs."""
    result = dict(current)
    for report in reports:
        for case in ET.parse(report).getroot().iter("testcase"):
            flow = flow_of(case)
            if flow and not _failed(case):
                result[flow] = max(1, round(float(case.get("time") or 0)))
    return dict(sorted(result.items()))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--durations", type=Path, default=DURATIONS_FILE)
    commands = parser.add_subparsers(dest="command", required=True)

    commands.add_parser("plan", help="print the split as a table").add_argument("--shards", type=int, required=True)
    commands.add_parser("list", help="flow files of one shard").add_argument("--shard", required=True, help="i/n, e.g. 2/4")
    gatherer = commands.add_parser("gather", help="merge the shards' reports and put coverage data in place")
    gatherer.add_argument("--shards", type=int, required=True)
    gatherer.add_argument("--dir", type=Path, required=True, help="folder with e2e-shard-<i>/ of every shard")
    gatherer.add_argument("--out", type=Path, required=True)
    gatherer.add_argument("--summary", type=Path, help="append the Markdown status to this file as well")
    refresher = commands.add_parser("durations", help="update the durations file from JUnit reports")
    refresher.add_argument("reports", nargs="+", type=Path)

    args = parser.parse_args(argv)
    root = args.root.resolve()
    flows = discover_flows(root)
    durations = load_durations(args.durations)

    if args.command == "list":
        try:
            index, count = parse_shard(args.shard)
        except ValueError as e:
            parser.error(str(e))
        print("\n".join(plan(flows, durations, count)[index - 1].flows))
        return 0
    if args.command == "plan":
        print(plan_markdown(plan(flows, durations, args.shards)))
        return 0
    if args.command == "gather":
        outcomes = gather(root, plan(flows, durations, args.shards), args.dir, args.out)
        text = "#### Shards\n\n" + outcomes_markdown(outcomes) + "\n"
        sys.stdout.write(text)
        if args.summary is not None:
            with args.summary.open("a", encoding="utf-8") as summary:
                summary.write(text + "\n")
        return 0
    stored = json.loads(args.durations.read_text(encoding="utf-8")) if args.durations.is_file() else {}
    stored["flows"] = updated_durations(durations, args.reports)
    args.durations.write_text(json.dumps(stored, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"durations: {len(stored['flows'])} flow(s) in {args.durations}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

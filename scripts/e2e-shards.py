#!/usr/bin/env python3
"""Splits the Maestro flows between emulators and puts the shards' results back together (FIN-180, docs/e2e.md).

The `UI tests` workflow runs the flows on several emulators at once, one job per shard. A shard is a fixed list of
flow files: the flows are spread so that the shards take about the same time, by the durations measured on CI
(`scripts/e2e-durations.json`; a flow not measured yet counts as the median). The split is deterministic — the same
flows and durations give the same shards on every job and every machine — and every flow lands in exactly one
shard. Native instrumented tests have a separate job and budget (FIN-473).

Usage:
  python3 scripts/e2e-shards.py plan --shards 5            # the split, as a table
  python3 scripts/e2e-shards.py list --shard 2/5           # flow files of shard 2 (what scripts/e2e.sh --shard runs)
  python3 scripts/e2e-shards.py gather --shards 5 --dir build/e2e-shards --out build/e2e \
      --native-dir build/e2e-native --jvm-dir build/e2e-components
  python3 scripts/e2e-shards.py durations build/e2e/maestro-report.xml   # refresh e2e-durations.json

`gather` reads the reports each shard job uploaded (`<dir>/e2e-shard-<i>/`, the shard's `build/e2e/`) and writes:
- `<out>/maestro-report.xml` — one JUnit report of all shards. The flows of a shard that left no report, and flows
  missing from a shard's report, are added as NOT RUN skips; the strict verdict remains incomplete;
- `<out>/coverage/ec/` — the JaCoCo execution data of every shard, file names prefixed with the shard;
- canonical native results and execution data from `--native-dir` back under `app/build/outputs/`,
  so `:e2eCoverageReport` and `scripts/qase-report.py` read them as after a local run.
It prints a Markdown status of the shards (also appended to `--summary`).

Standard library only, so CI runs it without pip.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
import statistics
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DURATIONS_FILE = ROOT / "scripts" / "e2e-durations.json"
FLOWS_DIR = ".maestro/flows"
# Native tests have their own CI job and process budget (FIN-473).
NATIVE_SECONDS = 0
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
    Longest flow first, each to the shard that has the least so far (ties: the lower index). Native execution has
    no default reserve. An explicit historical reserve is supported for old reports. Paths stay sorted.
    """
    if count < 1:
        raise ValueError("at least one shard")
    known = [durations[f] for f in flows if f in durations]
    default = int(statistics.median(known)) if known else 60
    shards = [Shard(index=i + 1) for i in range(count)]
    shards[0].seconds = NATIVE_SECONDS if native_seconds is None else native_seconds
    shards[0].native = shards[0].seconds > 0
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


def flow_of(case: ET.Element, by_name: dict[str, str] | None = None) -> str | None:
    """
    Repository-relative flow path of a Maestro test case: from its `file` attribute (absolute), else — a Maestro
    version that writes no such path — from its name, the flow's `name:` ([by_name]; names are unique per flow).
    """
    file = (case.get("file") or "").replace("\\", "/")
    marker = "/.maestro/"
    if marker in file:
        return ".maestro/" + file.split(marker, 1)[1]
    if file.startswith(".maestro/"):
        return file
    return (by_name or {}).get(case.get("name") or "") if not file else None


def flows_by_name(root: Path, flows: list[str]) -> dict[str, str]:
    """`name:` of each flow → its path: the fallback of [flow_of]."""
    return {flow_name(root, flow): flow for flow in flows}


# Maestro 2.11's terminal lines, not its nested per-command status messages (which are indented).
TERMINAL_FLOW = re.compile(r"\[(Passed|Failed)\] (.+?) \((?:[0-9]+[hms] )*[0-9]+[hms]\)(?: \(.*\))?")
PROGRESS_IDENTITY = ("GITHUB_SHA", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT")
PROGRESS_FILE = "maestro-progress.json"


def watch_progress(root: Path, flows: list[str], path: Path, command: list[str]) -> int:
    """Persist terminal flow observations while preserving the one Maestro process and its exit status."""
    names: dict[str, list[str]] = {}
    for flow in flows:
        names.setdefault(flow_name(root, flow), []).append(flow)
    identity = {key: os.environ[key] for key in PROGRESS_IDENTITY if key in os.environ}
    observed: dict[str, str] = {}
    recording = True
    with subprocess.Popen(command, stdout=subprocess.PIPE) as process:
        assert process.stdout is not None
        for line in process.stdout:
            match = TERMINAL_FLOW.fullmatch(line.decode("utf-8", errors="replace").rstrip("\r\n"))
            if recording and match and line.endswith(b"\n"):
                candidates = names.get(match[2], [])
                if len(candidates) == 1 and candidates[0] not in observed:
                    observed[candidates[0]] = match[1].lower()
                    try:
                        temporary = path.with_suffix(".tmp")
                        temporary.write_text(json.dumps({"schema": 1, "identity": identity, "flows": observed}) + "\n",
                                             encoding="utf-8")
                        temporary.replace(path)
                    except OSError:
                        # Logging cannot turn a valid Maestro JUnit result into a test failure.
                        print("Warning: could not checkpoint Maestro progress", file=sys.stderr, flush=True)
                        recording = False
            sys.stdout.buffer.write(line)
            sys.stdout.buffer.flush()
        code = process.wait()
    return code if code >= 0 else 128 - code


def read_progress(directory: Path, outcome: ShardOutcome, expected: list[str]) -> dict[str, str]:
    """Console evidence is tied to the matching receipt and never promoted to a verified result."""
    if not all(key in outcome.identity for key in PROGRESS_IDENTITY):
        return {}
    path = directory / PROGRESS_FILE
    try:
        if path.is_symlink() or path.stat().st_size > 64 * 1024:
            return {}
        data = json.loads(path.read_text(encoding="utf-8"))
        if data["schema"] != 1 or data["identity"] != {key: outcome.identity[key] for key in PROGRESS_IDENTITY}:
            return {}
        return {flow: status for flow, status in data["flows"].items()
                if flow in expected and status in ("passed", "failed")}
    except (OSError, KeyError, ValueError, TypeError, AttributeError):
        return {}


def _failed(case: ET.Element) -> bool:
    return _status(case) != "passed"


def _status(case: ET.Element) -> str:
    if case.find("failure") is not None or case.find("error") is not None:
        return "failed"
    if case.find("skipped") is not None or (case.get("status") or "").upper() in ("SKIPPED", "IGNORED"):
        return "skipped"
    return "passed" if (case.get("status") or "SUCCESS").upper() in ("SUCCESS", "PASSED") else "failed"


def _missing_case(root: Path, key: str, message: str) -> ET.Element:
    if key.startswith(".maestro/"):
        name = flow_name(root, key)
        attributes = {"id": name, "name": name, "classname": name, "file": str(root / key)}
    else:
        cls, name = key.removeprefix("jvm:").split("#", 1)
        attributes = {"name": name, "classname": cls}
    case = ET.Element("testcase", {**attributes, "time": "0", "status": "SKIPPED", "pf-execution": "not-run"})
    ET.SubElement(case, "skipped", {"message": message}).text = message
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
    skipped: int = 0
    invalid: list[str] = field(default_factory=list)
    kind: str = "maestro"
    phase: str = "reporting"
    category: str | None = None
    result: str | None = None
    exit_code: int | None = None
    receipt_required: bool = True
    identity: dict[str, str] = field(default_factory=dict)
    apks: dict[str, str] = field(default_factory=dict)
    budget_expired: bool = False
    unverified: int = 0
    unverified_results: dict[str, int] = field(default_factory=dict)


def _receipt(directory: Path, outcome: ShardOutcome, required: bool = True) -> None:
    outcome.receipt_required = required
    path = directory / "execution.json"
    if not path.is_file():
        if required and outcome.planned:
            outcome.invalid.append("missing execution receipt")
        return
    try:
        receipt = json.loads(path.read_text(encoding="utf-8"))
        phase, category = receipt.get("phase"), receipt.get("category")
        result, code = receipt.get("result"), receipt.get("exit_code")
        budget_expired = receipt.get("budget_expired", False)
        if type(budget_expired) is not bool or budget_expired and result != "failed":
            raise ValueError("invalid budget evidence")
        if receipt.get("schema") != 1 or isinstance(receipt.get("schema"), bool):
            raise ValueError("invalid execution schema")
        if phase not in ("setup", "device", "maestro", "native", "components", "reporting", "complete"):
            raise ValueError("invalid execution phase")
        if category not in (None, "setup", "device", "test", "budget", "reporting"):
            raise ValueError("invalid execution category")
        if result not in ("running", "passed", "failed", "not_run"):
            raise ValueError("invalid execution result")
        if code is not None and (not isinstance(code, int) or isinstance(code, bool)):
            raise ValueError("invalid execution exit code")
        if (result == "passed" and (phase != "complete" or code != 0 or category is not None)
                or result == "failed" and (code is None or code == 0)
                or result == "running" and code is not None):
            raise ValueError("inconsistent execution result")
        identity = receipt.get("identity", {})
        apks = receipt.get("apks", {})
        if not isinstance(identity, dict) or not isinstance(apks, dict):
            raise ValueError("invalid execution identity")
        for name in ("GITHUB_SHA", "GITHUB_RUN_ID"):
            if name in os.environ and identity.get(name) != os.environ[name]:
                raise ValueError("execution source or run mismatch")
        if "GITHUB_RUN_ATTEMPT" in os.environ:
            attempt = str(identity.get("GITHUB_RUN_ATTEMPT", ""))
            if not attempt.isdigit() or not 1 <= int(attempt) <= int(os.environ["GITHUB_RUN_ATTEMPT"]):
                raise ValueError("execution attempt mismatch")
        safe_identity = ("GITHUB_SHA", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "RUNNER_OS", "RUNNER_ARCH",
                         "ImageOS", "ImageVersion", "MAESTRO_VERSION", "MAESTRO_SHA256")
        outcome.identity = {key: identity[key] for key in safe_identity if isinstance(identity.get(key), str)}
        outcome.apks = {name: value for name, value in apks.items()
                        if name in ("app-mock-e2e.apk", "app-mock-e2e-androidTest.apk")
                        and isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value)}
        outcome.result, outcome.exit_code = result, code
        outcome.phase, outcome.category = phase, category
        outcome.budget_expired = budget_expired
        if result in ("failed", "not_run") and category is None:
            outcome.category = "reporting"
    except (ValueError, OSError, AttributeError, TypeError):
        outcome.invalid.append("invalid execution receipt")


def _merge_suite(
    root: Path, suite: ET.Element, expected: list[str], reports: list[Path], outcome: ShardOutcome,
    by_name: dict[str, str] | None = None,
    progress: dict[str, str] | None = None,
) -> None:
    seen: dict[str, ET.Element] = {}
    for report in reports:
        try:
            parsed = ET.parse(report).getroot()
            if parsed.tag not in ("testsuite", "testsuites"):
                raise ValueError("not JUnit")
        except (ET.ParseError, OSError, ValueError):
            outcome.invalid.append("unreadable or malformed JUnit report")
            continue
        outcome.reported = True
        for case in parsed.iter("testcase"):
            key = flow_of(case, by_name) if outcome.kind == "maestro" else f"{case.get('classname', '')}#{case.get('name', '')}"
            if outcome.kind == "jvm":
                key = "jvm:" + key
            if key not in expected:
                outcome.invalid.append("unknown or wrong-suite test result")
                continue
            if key in seen:
                outcome.invalid.append("duplicate test result")
                # The verdict is invalid regardless; keep any real failure visible in canonical/Qase results.
                previous = seen[key]
                severity = {"passed": 0, "skipped": 1, "failed": 2}
                current_status, previous_status = _status(case), _status(previous)
                if severity[current_status] > severity[previous_status]:
                    setattr(outcome, previous_status, getattr(outcome, previous_status) - 1)
                    setattr(outcome, current_status, getattr(outcome, current_status) + 1)
                    previous.set("status", "ERROR" if current_status == "failed" else "SKIPPED")
                    skipped = previous.find("skipped")
                    if skipped is not None:
                        previous.remove(skipped)
                    problem = case.find("failure")
                    if problem is None:
                        problem = case.find("error")
                    if problem is None:
                        problem = case.find("skipped")
                    if problem is not None:
                        previous.append(problem)
                continue
            seen[key] = case
            if outcome.kind == "maestro":
                case.set("file", str(root / key))
            try:
                seconds = float(case.get("time") or 0)
                if not math.isfinite(seconds) or seconds < 0:
                    raise ValueError("invalid duration")
            except ValueError:
                outcome.invalid.append("invalid test duration")
                seconds = 0
                case.set("time", "0")
            suite.append(case)
            status = _status(case)
            setattr(outcome, status, getattr(outcome, status) + 1)
            outcome.seconds += seconds
    if (outcome.receipt_required and outcome.result is not None and outcome.kind in ("maestro", "native")
            and seen):
        expected_apks = {"app-mock-e2e.apk", "app-mock-e2e-androidTest.apk"}
        if not expected_apks <= set(outcome.apks):
            outcome.invalid.append("missing emulator APK identity")
    provenance_errors = {"missing execution receipt", "invalid execution receipt", "missing emulator APK identity"}
    unverified_reasons = sorted(provenance_errors.intersection(outcome.invalid))
    if unverified_reasons:
        # Keep raw reports in their artifact; unverified observations cannot attest to the current run in Qase.
        for key, case in seen.items():
            raw_status = _status(case)
            outcome.unverified += 1
            outcome.unverified_results[raw_status] = outcome.unverified_results.get(raw_status, 0) + 1
            setattr(outcome, raw_status, getattr(outcome, raw_status) - 1)
            outcome.skipped += 1
            message = f"UNVERIFIED: {outcome.kind}; raw result {raw_status}; " + "; ".join(unverified_reasons)
            canonical = _missing_case(root, key, message)
            canonical.set("pf-execution", "unverified")
            canonical.set("pf-raw-status", raw_status)
            ET.SubElement(canonical, "system-out").text = "Raw observation (not current-run evidence):\n" + ET.tostring(case, encoding="unicode")[:8000]
            suite.remove(case)
            suite.append(canonical)
    label = f"{outcome.kind} suite" if outcome.kind != "maestro" else f"shard {outcome.index}"
    for key in expected:
        if key not in seen:
            cause = "budget" if outcome.budget_expired else outcome.category or "reporting"
            message = f"NOT RUN: {label}; phase={outcome.phase}; cause={cause}; no test result received"
            if outcome.budget_expired:
                message += "; deadline_expired=true"
            observed = (progress or {}).get(key)
            if observed:
                message = f"UNVERIFIED: {label}; console observed {observed}; no JUnit result received"
                case = _missing_case(root, key, message)
                case.set("pf-execution", "unverified")
                case.set("pf-raw-status", observed)
                suite.append(case)
                outcome.unverified += 1
                outcome.unverified_results[observed] = outcome.unverified_results.get(observed, 0) + 1
                outcome.skipped += 1
            else:
                suite.append(_missing_case(root, key, message))
                outcome.missing += 1
    suite.set("tests", str(outcome.planned))
    suite.set("failures", str(outcome.failed))
    suite.set("skipped", str(outcome.skipped + outcome.missing))
    suite.set("time", f"{outcome.seconds:.3f}")
    if outcome.invalid:
        outcome.category = "reporting"
    elif outcome.failed and outcome.category is None:
        outcome.category = "test"
    elif (outcome.missing or outcome.skipped) and outcome.category is None:
        outcome.category = "reporting"


def merge_reports(
    root: Path, shards: list[Shard], reports: dict[int, Path | None], receipts_required: bool = True,
) -> tuple[ET.Element, list[ShardOutcome]]:
    """Merge only exact expected observations; preserve missing execution separately from assertion failures."""
    merged = ET.Element("testsuites")
    outcomes = []
    by_name = flows_by_name(root, [f for s in shards for f in s.flows])
    for shard in shards:
        suite = ET.SubElement(merged, "testsuite", {"name": f"Shard {shard.index}/{len(shards)}"})
        report = reports.get(shard.index)
        outcome = ShardOutcome(shard.index, len(shard.flows), reported=False)
        if report is not None:
            _receipt(report.parent, outcome, receipts_required)
        has_report = report is not None and report.is_file()
        progress = read_progress(report.parent, outcome, shard.flows) if report is not None and not has_report else {}
        _merge_suite(root, suite, shard.flows, [report] if has_report else [], outcome, by_name, progress)
        outcomes.append(outcome)
    return merged, outcomes


def native_keys(root: Path) -> set[str]:
    """Discover actual native @Test methods; the case map remains the sole case-ID registry."""
    found = set()
    for path in sorted((root / "app" / "src").glob("androidTest*/kotlin/**/*.kt")):
        text = path.read_text(encoding="utf-8")
        package = re.search(r"^package\s+([\w.]+)", text, re.MULTILINE)
        methods = re.findall(r"@Test\b(?:(?!\bfun\b).)*\bfun\s+(\w+)\s*\(", text, re.DOTALL)
        if methods and package:
            found.update(f"{package.group(1)}.{path.stem}#{method}" for method in methods)
    return found


def manifest(root: Path, shards: list[Shard]) -> tuple[dict, list[str]]:
    """Derive the expected test set from source discovery and the existing Qase map, never another registry."""
    maestro = {flow for shard in shards for flow in shard.flows}
    native = native_keys(root)
    path = root / "scripts" / "qase-cases.json"
    issues = []
    jvm = set()
    if path.is_file():
        try:
            cases = json.loads(path.read_text(encoding="utf-8"))["cases"]
            if not isinstance(cases, dict) or any(
                not isinstance(key, str) or not isinstance(value, int) or isinstance(value, bool) or value < 1
                or (not key.startswith(".maestro/") and re.fullmatch(r"(?:jvm:)?[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+#\w+", key) is None)
                for key, value in cases.items()
            ):
                raise ValueError("invalid component mapping")
            mapped = set(cases)
            jvm = {key for key in mapped if key.startswith("jvm:")}
            if mapped - jvm != maestro | native:
                issues.append("source discovery and Qase component mapping differ")
            native |= {key for key in mapped if not key.startswith((".maestro/", "jvm:"))}
            for cls in sorted({key[4:].split("#", 1)[0] for key in jvm}):
                sources = list(root.glob(f"**/src/test*/kotlin/{cls.replace('.', '/')}.kt"))
                methods = set()
                for source in sources:
                    text = source.read_text(encoding="utf-8")
                    methods.update(re.findall(r"@Test\b(?:(?!\bfun\b).)*\bfun\s+(\w+)\s*\(", text, re.DOTALL))
                if not sources or {f"jvm:{cls}#{method}" for method in methods} != {key for key in jvm if key.startswith(f"jvm:{cls}#")}:
                    issues.append("JVM component source and Qase mapping differ")
        except (ValueError, KeyError, TypeError):
            issues.append("invalid Qase component mapping")
    elif native or maestro:
        issues.append("missing Qase component mapping")
    if len(flows_by_name(root, sorted(maestro))) != len(maestro):
        issues.append("duplicate Maestro flow names")
    return {"schema": 1, "maestro": {str(s.index): s.flows for s in shards}, "native": sorted(native), "jvm": sorted(jvm)}, issues


def _copy_tree(source: Path, target: Path) -> bool:
    if not source.is_dir():
        return False
    shutil.copytree(source, target, dirs_exist_ok=True)
    return True


def gather(
    root: Path, shards: list[Shard], shards_dir: Path, out: Path, native_dir: Path | None = None, jvm_dir: Path | None = None, receipts_required: bool = True,
) -> list[ShardOutcome]:
    """Write canonical results, a derived manifest and the strict verdict before coverage/Qase publication."""
    dirs = {s.index: shards_dir / SHARD_DIR.format(index=s.index) for s in shards}
    merged, outcomes = merge_reports(root, shards, {i: d / "maestro-report.xml" for i, d in dirs.items()}, receipts_required)
    out.mkdir(parents=True, exist_ok=True)
    expected, issues = manifest(root, shards)
    if issues:
        outcomes[0].invalid.extend(issues)
        outcomes[0].category = "reporting"
    ET.ElementTree(merged).write(out / "maestro-report.xml", encoding="utf-8", xml_declaration=True)
    (out / "expected-tests.json").write_text(json.dumps(expected, indent=2) + "\n", encoding="utf-8")
    ec = out / "coverage" / "ec"
    ec.mkdir(parents=True, exist_ok=True)
    for index, directory in dirs.items():
        for data in sorted((directory / "coverage" / "ec").glob("*.ec")):
            shutil.copyfile(data, ec / f"shard{index}-{data.name}")
    if native_dir is not None or expected["native"]:
        directory = native_dir if native_dir is not None else root / "build" / "e2e-native"
        native = ET.Element("testsuite", {"name": "Native"})
        outcome = ShardOutcome(0, len(expected["native"]), False, kind="native")
        _receipt(directory, outcome, receipts_required)
        _merge_suite(root, native, expected["native"], sorted((directory / "native").rglob("TEST-*.xml")), outcome)
        outcomes.append(outcome)
        ET.ElementTree(native).write(out / "native-report.xml", encoding="utf-8", xml_declaration=True)
        # Qase reads this canonical file only: copying raw files here would duplicate every native observation.
        target = root / "app" / "build" / "outputs" / "androidTest-results" / "connected" / "fin473" / "TEST-integrity.xml"
        result_dir = root / "app" / "build" / "outputs" / "androidTest-results"
        if result_dir.is_dir():
            shutil.rmtree(result_dir)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(out / "native-report.xml", target)
        _copy_tree(directory / "native-coverage", root / "app" / "build" / "outputs" / "code_coverage")
    else:
        # Compatibility for gathering historical artifacts that predate the separate native job.
        for directory in dirs.values():
            _copy_tree(directory / "native", root / "app" / "build" / "outputs" / "androidTest-results")
            _copy_tree(directory / "native-coverage", root / "app" / "build" / "outputs" / "code_coverage")
    if jvm_dir is not None or expected["jvm"]:
        directory = jvm_dir if jvm_dir is not None else root / "build" / "e2e-components"
        suite = ET.Element("testsuite", {"name": "JVM components"})
        outcome = ShardOutcome(0, len(expected["jvm"]), False, kind="jvm")
        _receipt(directory, outcome, receipts_required)
        _merge_suite(root, suite, expected["jvm"], sorted(directory.rglob("TEST-*.xml")), outcome)
        outcomes.append(outcome)
        ET.ElementTree(suite).write(out / "jvm-report.xml", encoding="utf-8", xml_declaration=True)
    (out / "execution-status.json").write_text(json.dumps({
        "schema": 1, "passed": not problems(outcomes), "suites": [asdict(o) for o in outcomes],
        "problems": problems(outcomes),
    }, indent=2) + "\n", encoding="utf-8")
    return outcomes


def problems(outcomes: list[ShardOutcome]) -> list[str]:
    """The expected test set must be complete and successful independently of job exit codes."""
    count = sum(o.kind == "maestro" for o in outcomes)
    found = []
    for o in outcomes:
        label = f"{o.kind} suite" if o.kind != "maestro" else f"shard {o.index}/{count}"
        unit = "test(s)" if o.kind != "maestro" else "flow(s)"
        if not o.reported and o.planned:
            kind = o.kind if o.kind != "maestro" else "Maestro"
            observed = f", {o.unverified} observed without JUnit" if o.unverified else ""
            found.append(f"{label}: no {kind} report received ({o.missing} {unit} not run{observed})")
        elif o.missing:
            found.append(f"{label}: {o.missing} {unit} missing from its report")
        if o.failed:
            found.append(f"{label}: {o.failed} {unit} failed")
        if o.skipped:
            found.append(f"{label}: {o.skipped} {unit} skipped")
        if o.result in ("failed", "not_run") and not (o.failed or o.missing or o.skipped or o.invalid):
            found.append(f"{label}: execution failed during {o.phase}")
        if o.result == "running" and not (o.failed or o.missing or o.skipped or o.invalid):
            found.append(f"{label}: execution did not finish")
        if o.invalid:
            found.append(f"{label}: {len(o.invalid)} report integrity error(s)")
    return found


def outcomes_markdown(outcomes: list[ShardOutcome]) -> str:
    rows = ["| Suite | Expected | Passed | Failed | Not run | Skipped | Integrity errors | Time | Cause / phase |",
            "|---|---|---|---|---|---|---|---|---|"]
    count = sum(o.kind == "maestro" for o in outcomes)
    for o in outcomes:
        label = o.kind.capitalize() if o.kind != "maestro" else f"{o.index}/{count}"
        state = "" if o.reported else " (no report)"
        rows.append(f"| {label}{state} | {o.planned} | {o.passed} | {o.failed} | {o.missing} | "
                    f"{o.skipped} | {len(o.invalid)} | {o.seconds / 60:.1f} min | {o.category or 'none'} / {o.phase}"
                    + ("; deadline_expired=true" if o.budget_expired else "")
                    + (f"; UNVERIFIED={o.unverified}" if o.unverified else "") + " |")
    return "\n".join(rows)


def plan_markdown(shards: list[Shard]) -> str:
    rows = ["| Shard | Flows | Estimate | |", "|---|---|---|---|"]
    for s in shards:
        rows.append(f"| {s.index}/{len(shards)} | {len(s.flows)} | {s.seconds / 60:.1f} min | {'native tests' if s.native else ''} |")
    return "\n".join(rows)


def updated_durations(
    current: dict[str, int], reports: list[Path], by_name: dict[str, str] | None = None,
) -> dict[str, int]:
    """Keep each flow's largest positive successful sample; failed/partial samples do not lower its weight."""
    result = dict(current)
    for report in reports:
        for case in ET.parse(report).getroot().iter("testcase"):
            flow = flow_of(case, by_name)
            if flow and not _failed(case) and case.find("skipped") is None:
                seconds = float(case.get("time") or 0)
                if math.isfinite(seconds) and seconds > 0:
                    result[flow] = max(result.get(flow, 1), round(seconds))
    return dict(sorted(result.items()))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--durations", type=Path, default=DURATIONS_FILE)
    commands = parser.add_subparsers(dest="command", required=True)

    commands.add_parser("plan", help="print the split as a table").add_argument("--shards", type=int, required=True)
    commands.add_parser("list", help="flow files of one shard").add_argument("--shard", required=True, help="i/n, e.g. 2/4")
    watcher = commands.add_parser("watch", help="checkpoint the terminal progress of one Maestro shard")
    watcher.add_argument("--shard", required=True)
    watcher.add_argument("--progress", type=Path, required=True)
    watcher.add_argument("maestro", nargs=argparse.REMAINDER)
    gatherer = commands.add_parser("gather", help="merge the shards' reports and put coverage data in place")
    gatherer.add_argument("--shards", type=int, required=True)
    gatherer.add_argument("--dir", type=Path, required=True, help="folder with e2e-shard-<i>/ of every shard")
    gatherer.add_argument("--out", type=Path, required=True)
    gatherer.add_argument("--native-dir", type=Path, help="separate native artifact with native/ and native-coverage/")
    gatherer.add_argument("--jvm-dir", type=Path, help="targeted JVM component JUnit artifact")
    gatherer.add_argument("--summary", type=Path, help="append the Markdown status to this file as well")
    gatherer.add_argument(
        "--github-output", type=Path,
        help="append `incomplete=true|false` and `problems=<text>` (GitHub Actions step outputs) to this file",
    )
    refresher = commands.add_parser("durations", help="update the durations file from JUnit reports")
    refresher.add_argument("reports", nargs="+", type=Path)

    args = parser.parse_args(argv)
    root = args.root.resolve()
    flows = discover_flows(root)
    durations = load_durations(args.durations)

    if args.command in ("list", "watch"):
        try:
            index, count = parse_shard(args.shard)
        except ValueError as e:
            parser.error(str(e))
        selected = plan(flows, durations, count)[index - 1].flows
        if args.command == "watch":
            command = args.maestro[1:] if args.maestro[:1] == ["--"] else args.maestro
            if not command:
                parser.error("watch requires a Maestro command")
            return watch_progress(root, selected, args.progress, command)
        print("\n".join(selected))
        return 0
    if args.command == "plan":
        print(plan_markdown(plan(flows, durations, args.shards)))
        return 0
    if args.command == "gather":
        outcomes = gather(root, plan(flows, durations, args.shards), args.dir, args.out, args.native_dir, args.jvm_dir)
        found = problems(outcomes)
        text = "#### Shards\n\n" + outcomes_markdown(outcomes) + "\n"
        if found:
            text += "\n" + "\n".join(f"- {p}" for p in found) + "\n"
        sys.stdout.write(text)
        if args.summary is not None:
            with args.summary.open("a", encoding="utf-8") as summary:
                summary.write(text + "\n")
        if args.github_output is not None:
            # The workflow's last step fails on it: a shard whose report never arrived must not leave the run green.
            with args.github_output.open("a", encoding="utf-8") as output:
                output.write(f"incomplete={'true' if found else 'false'}\n")
                output.write(f"problems={'; '.join(found)}\n")
                interrupted = any(o.budget_expired or o.category == "budget" for o in outcomes)
                output.write(f"interrupted={'true' if interrupted else 'false'}\n")
        return 0
    stored = json.loads(args.durations.read_text(encoding="utf-8")) if args.durations.is_file() else {}
    stored["flows"] = updated_durations(durations, args.reports, flows_by_name(root, flows))
    args.durations.write_text(json.dumps(stored, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"durations: {len(stored['flows'])} flow(s) in {args.durations}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
